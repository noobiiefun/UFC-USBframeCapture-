package com.ufc.app.stream

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP menggunakan RtmpClient dasar (RootEncoder).
 *
 * Audio diambil dari capture card via UAC / SOURCE_DEV_MIC (lihat
 * UfcCameraFragment.getCameraRequest(); fallback ke mic internal HP jika
 * switch "Audio dari Capture Card" di Settings dimatikan), diteruskan dari
 * callback AAC ke onDeviceAudioData().
 *
 * === FIX #1 (sudah ada) -- race condition penyebab "Broken pipe" ===
 * Semua akses ke `rtmpClient` (baca isStreaming, sendVideo, sendAudio,
 * disconnect, penggantian objek saat reconnect) dibungkus SATU lock yang
 * sama (`clientLock`), supaya thread video/audio tidak pernah menulis ke
 * objek yang sedang di tengah proses disconnect/penggantian dari thread lain.
 *
 * === FIX #2 (baru) -- "generation guard" untuk callback client lama (stale) ===
 * Setiap kali RtmpClient baru dibuat (reconnect), dia dibungkus dengan
 * ConnectChecker yang ditandai nomor generasi saat itu (`currentGeneration`).
 * Kalau client LAMA (generasi sebelumnya) masih sempat memanggil
 * onDisconnect()/onConnectionFailed() -- misalnya karena coroutine
 * internalnya baru selesai di-cancel setelah client baru sudah dibuat --
 * callback itu SEKARANG DIABAIKAN kalau generasinya sudah tidak aktif lagi.
 *
 * Tanpa ini: 1 kejadian "Broken pipe" nyata bisa memicu beberapa
 * RtmpClient lama melapor onDisconnect() secara beruntun dalam
 * hitungan milidetik (gema dari proses disconnect/cancel), dan semuanya
 * ikut menambah `consecutiveFailures` -- sehingga kuota
 * MAX_CONSECUTIVE_FAILURES habis dalam < 1 detik, JAUH sebelum percobaan
 * reconnect yang sesungguhnya (butuh waktu untuk handshake TCP/RTMP) sempat
 * selesai. Gejalanya: log menunjukkan "RTMP Disconnected (2/5)" sampai
 * "(5/5)" dalam waktu berdekatan, padahal cuma ada 1 broken pipe beneran.
 */
class RtmpPusher {

    companion object {
        private const val TAG = "RtmpPusher"

        // Sample rate audio mic internal HP. Kalau nanti audio balik
        // dipindah ke capture card (UAC), cek dulu apakah sample rate-nya
        // beda (banyak dongle capture di 48kHz, sebagian di 44.1kHz).
        private const val AUDIO_SAMPLE_RATE = 48000

        // Jeda sebelum coba reconnect otomatis setelah koneksi putus/gagal.
        // Mencegah spam percobaan connect() tiap frame video baru.
        private const val RECONNECT_DELAY_MS = 3000L

        // Batas percobaan reconnect beruntun. Dengan generation guard,
        // angka ini sekarang benar-benar mencerminkan 5 kegagalan NYATA
        // (bukan gema dari client lama), jadi lebih masuk akal untuk
        // menyerah setelah 5 kali gagal beneran.
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }

    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val videoBitrateKbps: Int = 2500,
        val audioBitrateKbps: Int = 128,
        val rtmpUrl: String = ""
    )

    private var config: Config = Config()

    @Volatile private var isPushing = false
    @Volatile private var isConnecting = false
    @Volatile private var isMetadataReady = false
    @Volatile private var lastConnectAttemptMs = 0L
    @Volatile private var hasShownFailureToast = false
    @Volatile private var consecutiveFailures = 0

    // === LOCK TUNGGAL untuk semua akses ke rtmpClient (lihat catatan FIX #1) ===
    private val clientLock = Any()
    private var rtmpClient: RtmpClient = RtmpClient(GenChecker(0))

    // === GENERATION GUARD (FIX #2) ===
    // Dinaikkan setiap kali objek RtmpClient baru dibuat. HARUS dibaca/ditulis
    // di dalam clientLock supaya konsisten dengan rtmpClient itu sendiri.
    private var currentGeneration = 0

    private var cachedSps: ByteBuffer? = null
    private var cachedPps: ByteBuffer? = null

    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()
    private var appContext: Context? = null

    /**
     * ConnectChecker yang tahu generasinya sendiri. Semua callback dari
     * library RootEncoder masuk sini dulu, lalu cuma diteruskan ke logika
     * asli (handleXxx di bawah) KALAU generasi ini masih yang aktif. Kalau
     * client ini sudah "pensiun" (ada client generasi baru), callback-nya
     * dibuang -- tidak ikut menambah consecutiveFailures atau memicu
     * reconnect baru.
     */
    private inner class GenChecker(private val generation: Int) : ConnectChecker {
        private fun isStale(): Boolean = generation != currentGeneration

        override fun onConnectionSuccess() {
            if (isStale()) { logStale("onConnectionSuccess"); return }
            handleConnectionSuccess()
        }

        override fun onConnectionFailed(reason: String) {
            if (isStale()) { logStale("onConnectionFailed: $reason"); return }
            handleConnectionFailed(reason)
        }

        override fun onConnectionStarted(url: String) {}

        override fun onNewBitrate(bitrate: Long) {}

        override fun onDisconnect() {
            if (isStale()) { logStale("onDisconnect"); return }
            handleDisconnect()
        }

        override fun onAuthError() {
            if (isStale()) { logStale("onAuthError"); return }
            handleConnectionFailed("Auth Error")
        }

        override fun onAuthSuccess() {}

        private fun logStale(what: String) {
            Log.d(TAG, "Abaikan callback $what dari RtmpClient generasi lama ($generation, aktif sekarang: $currentGeneration)")
        }
    }

    fun configure(config: Config) {
        this.config = config
    }

    /**
     * Sinkronkan sample rate audio dengan yang terdeteksi library saat
     * encoding dimulai (capture card UAC umumnya 48kHz, sebagian 44.1kHz).
     * Dipanggil dari UI thread setelah camera open + captureStreamStart().
     */
    fun setAudioSampleRate(sampleRate: Int) {
        if (sampleRate <= 0) return
        synchronized(clientLock) {
            try {
                rtmpClient.setAudioInfo(sampleRate, true) // tetap stereo
                Log.i(TAG, "Audio sample rate diset ke ${sampleRate}Hz")
            } catch (e: Throwable) {
                Log.w(TAG, "Gagal set audio info: ${e.message}")
            }
        }
    }

    fun start(context: Context) {
        this.appContext = context.applicationContext

        synchronized(clientLock) {
            if (isPushing) return
            require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi" }

            Log.i(TAG, "Starting stream to: ${config.rtmpUrl}")
            isPushing = true
            isConnecting = false
            isMetadataReady = false
            lastConnectAttemptMs = 0L
            hasShownFailureToast = false
            cachedSps = null
            cachedPps = null
            consecutiveFailures = 0

            try {
                createFreshClientLocked()
            } catch (e: Throwable) {
                Log.e(TAG, "Gagal inisialisasi pusher: ${e.message}")
                e.printStackTrace()
                isPushing = false
                appContext?.let { ctx ->
                    Handler(ctx.mainLooper).post {
                        Toast.makeText(ctx, "Config Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
        }

        appContext?.let { ctx ->
            Handler(ctx.mainLooper).post {
                Toast.makeText(ctx, "Menghubungkan ke YouTube...", Toast.LENGTH_SHORT).show()
            }
        }

        StatusRepository.update {
            it.copy(
                resolution = "${config.width}x${config.height}",
                fps = config.fps,
                bitrateKbps = config.videoBitrateKbps
            )
        }
    }

    /**
     * Bikin instance RtmpClient baru + naikkan generasi, lalu pasang ulang
     * konfigurasi + SPS/PPS (kalau sudah pernah ada). HARUS selalu dipanggil
     * dari dalam synchronized(clientLock).
     */
    private fun createFreshClientLocked() {
        currentGeneration++
        val gen = currentGeneration
        Log.d(TAG, "Membuat RtmpClient generasi baru: $gen")

        rtmpClient = RtmpClient(GenChecker(gen))
        rtmpClient.setVideoCodec(VideoCodec.H264)
        rtmpClient.setAudioCodec(AudioCodec.AAC)
        rtmpClient.setVideoResolution(config.width, config.height)
        rtmpClient.setFps(config.fps)
        rtmpClient.setAudioInfo(AUDIO_SAMPLE_RATE, true) // Stereo

        val sps = cachedSps
        val pps = cachedPps
        if (sps != null && pps != null) {
            rtmpClient.setVideoInfo(sps.duplicate(), pps.duplicate(), null)
        }
    }

    fun stop() {
        synchronized(clientLock) {
            isPushing = false
            isConnecting = false
            isMetadataReady = false
            // Naikkan generasi supaya callback yang mungkin masih nyangkut
            // dari proses disconnect di bawah ini otomatis diabaikan.
            currentGeneration++
            try {
                if (rtmpClient.isStreaming) {
                    rtmpClient.disconnect()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        markConnected(false)
        Log.i(TAG, "Stream stopped")
    }

    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing) return

        var clientToConnect: RtmpClient? = null
        var sendException: Throwable? = null

        synchronized(clientLock) {
            if (!isPushing) return

            val now = System.currentTimeMillis()
            val shouldConnect = isMetadataReady && !rtmpClient.isStreaming && !isConnecting &&
                consecutiveFailures < MAX_CONSECUTIVE_FAILURES &&
                (now - lastConnectAttemptMs) > RECONNECT_DELAY_MS

            if (shouldConnect) {
                isConnecting = true
                lastConnectAttemptMs = now
                clientToConnect = rtmpClient
            } else if (rtmpClient.isStreaming) {
                isConnecting = false
                videoInfo.set(offset, size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                try {
                    rtmpClient.sendVideo(buffer, videoInfo)
                } catch (e: Throwable) {
                    sendException = e
                }
            }
        }

        clientToConnect?.let { client ->
            Log.i(TAG, "Metadata ready, connecting to server... (percobaan ke-${consecutiveFailures + 1})")
            Thread {
                try {
                    client.connect(config.rtmpUrl)
                } catch (e: Throwable) {
                    Log.e(TAG, "Gagal connect RTMP: ${e.message}")
                    synchronized(clientLock) { isConnecting = false }
                }
            }.start()
        }

        sendException?.let { e ->
            Log.e(TAG, "sendVideo gagal (kemungkinan Broken pipe): ${e.message}")
            handleConnectionFailed("Send error: ${e.message}")
        }
    }

    fun onDeviceAudioData(buffer: ByteBuffer, size: Int, timestampUs: Long) {
        if (!isPushing) return

        var sendException: Throwable? = null
        synchronized(clientLock) {
            if (!isPushing || !rtmpClient.isStreaming) return
            audioInfo.set(0, size, timestampUs, 0)
            try {
                rtmpClient.sendAudio(buffer, audioInfo)
            } catch (e: Throwable) {
                sendException = e
            }
        }

        sendException?.let { e ->
            Log.e(TAG, "sendAudio gagal (kemungkinan Broken pipe): ${e.message}")
            handleConnectionFailed("Send error: ${e.message}")
        }
    }

    fun setVideoMetadata(sps: ByteBuffer, pps: ByteBuffer) {
        if (!isPushing) return
        Log.i(TAG, "SPS/PPS received")

        val spsCopy = ByteBuffer.allocateDirect(sps.remaining())
        spsCopy.put(sps.duplicate())
        spsCopy.flip()
        val ppsCopy = ByteBuffer.allocateDirect(pps.remaining())
        ppsCopy.put(pps.duplicate())
        ppsCopy.flip()

        synchronized(clientLock) {
            cachedSps = spsCopy
            cachedPps = ppsCopy
            rtmpClient.setVideoInfo(sps, pps, null)
            isMetadataReady = true
        }
    }

    private fun markConnected(connected: Boolean) {
        StatusRepository.update {
            it.copy(
                youtubeConnected = connected,
                uptimeSec = if (connected) StatusRepository.currentUptimeSec() else it.uptimeSec
            )
        }
    }

    /**
     * Bersihkan client yang bermasalah dan siapkan yang baru untuk reconnect.
     * disconnect() lama + pembuatan client baru (yang menaikkan generasi)
     * dilakukan di dalam synchronized(clientLock) yang SAMA dengan yang
     * dipakai onVideoData()/onDeviceAudioData() -- lihat FIX #1.
     *
     * Tetap dijalankan di Thread terpisah supaya kalau disconnect() ternyata
     * butuh waktu, thread callback internal library tidak ikut terblokir.
     */
    private fun resetClientForReconnect() {
        isConnecting = true
        Thread {
            synchronized(clientLock) {
                try {
                    rtmpClient.disconnect()
                } catch (e: Throwable) {
                    Log.e(TAG, "Gagal disconnect client lama: ${e.message}")
                }
                if (isPushing && consecutiveFailures < MAX_CONSECUTIVE_FAILURES &&
                    cachedSps != null && cachedPps != null) {
                    try {
                        createFreshClientLocked()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Gagal bikin client baru untuk reconnect: ${e.message}")
                    }
                }
                isConnecting = false
            }
        }.start()
    }

    private fun giveUpAfterTooManyFailures() {
        Log.e(TAG, "Sudah $MAX_CONSECUTIVE_FAILURES kali gagal beruntun, berhenti mencoba.")
        synchronized(clientLock) {
            isPushing = false
        }
        appContext?.let {
            Handler(it.mainLooper).post {
                Toast.makeText(
                    it,
                    "Gagal live setelah $MAX_CONSECUTIVE_FAILURES kali percobaan. Cek internet/URL/stream key, lalu Start Live lagi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --- Logika asli, sekarang dipanggil lewat GenChecker (bukan implements ConnectChecker langsung) ---

    private fun handleConnectionSuccess() {
        Log.i(TAG, "RTMP Success")
        synchronized(clientLock) {
            isConnecting = false
        }
        hasShownFailureToast = false
        consecutiveFailures = 0
        markConnected(true)
    }

    private fun handleConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Timeout/Handshake Failure" }
        consecutiveFailures++
        Log.e(TAG, "RTMP Failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): $cleanReason")
        markConnected(false)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            giveUpAfterTooManyFailures()
            return
        }
        resetClientForReconnect()
        if (!hasShownFailureToast) {
            hasShownFailureToast = true
            appContext?.let {
                Handler(it.mainLooper).post {
                    Toast.makeText(it, "Koneksi Gagal: $cleanReason (mencoba reconnect...)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleDisconnect() {
        consecutiveFailures++
        Log.i(TAG, "RTMP Disconnected ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES) -- akan coba reconnect otomatis jika masih live")
        markConnected(false)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            giveUpAfterTooManyFailures()
            return
        }
        resetClientForReconnect()
    }
}
