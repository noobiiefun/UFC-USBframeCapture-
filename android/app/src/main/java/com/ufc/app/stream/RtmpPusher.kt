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
 * Audio diambil LANGSUNG dari capture card (HDMI-in via UAC/USB Audio Class),
 * diteruskan dari UfcCameraFragment.onDeviceAudioData().
 *
 * === PERBAIKAN PENTING (fix "Broken pipe") ===
 * Versi sebelumnya membaca/menulis `rtmpClient` dari BEBERAPA thread berbeda
 * tanpa lock bersama:
 *  - thread encode video (AUSBC) -> onVideoData() -> sendVideo()
 *  - thread encode audio (AUSBC) -> onDeviceAudioData() -> sendAudio()
 *  - thread reconnect (resetClientForReconnect) -> disconnect() + bikin objek baru
 *
 * Kalau thread reconnect memanggil disconnect() / mengganti objek rtmpClient
 * TEPAT saat thread video/audio sedang menulis ke objek RtmpClient yang sama,
 * hasilnya adalah menulis ke socket yang baru saja ditutup -> "Broken pipe"
 * (EPIPE). Ini murni race condition, bukan masalah jaringan.
 *
 * Solusinya: SEMUA akses ke `rtmpClient` (baca isStreaming, sendVideo,
 * sendAudio, disconnect, dan penggantian objek saat reconnect) sekarang
 * dibungkus satu lock yang sama (`clientLock`). Operasi jaringan yang lambat
 * (connect() -- handshake TCP/RTMP) TETAP dijalankan di luar lock supaya
 * tidak memblokir thread video/audio berkepanjangan; yang dikunci hanya
 * bagian yang menyentuh objek rtmpClient itu sendiri.
 *
 * Tambahan: sendVideo()/sendAudio() sekarang dibungkus try/catch. Kalau
 * gagal (mis. Broken pipe beneran karena koneksi putus mendadak), kita
 * langsung panggil onConnectionFailed() sendiri alih-alih menunggu callback
 * dari library yang kadang telat -- supaya reconnect terpicu lebih cepat.
 */
class RtmpPusher : ConnectChecker {

    companion object {
        private const val TAG = "RtmpPusher"

        // Sample rate audio capture card. Kebanyakan dongle capture HDMI (mis.
        // chip MS2130/MS2109) mengirim audio di 48kHz stereo. Kalau suara di
        // stream terdengar terlalu cepat/lambat/pitch berubah, coba ganti ke
        // 44100 -- itu tandanya capture card kamu sebenarnya di 44.1kHz.
        private const val AUDIO_SAMPLE_RATE = 48000

        // Jeda sebelum coba reconnect otomatis kalau koneksi putus/gagal
        // (mis. "Broken pipe" karena hiccup jaringan sesaat). Ini mencegah
        // spam percobaan connect() tiap frame video baru.
        private const val RECONNECT_DELAY_MS = 3000L

        // Batas percobaan reconnect beruntun. Tanpa batas ini, kalau server
        // terus-menerus menolak (mis. key invalid/network down total), app akan
        // spawn Thread baru tanpa henti -- boros CPU & bisa memicu masalah lain
        // (mis. resource/file descriptor) di device yang sudah pas-pasan ini.
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

    // === LOCK TUNGGAL ===
    // Semua baca/tulis terhadap `rtmpClient` (termasu isStreaming, sendVideo,
    // sendAudio, disconnect, dan penggantian objek via createFreshClientLocked)
    // WAJIB lewat lock ini. Ini yang mencegah race condition penyebab
    // "Broken pipe" yang dijelaskan di komentar kelas di atas.
    private val clientLock = Any()
    private var rtmpClient: RtmpClient = RtmpClient(this)

    // Cache SPS/PPS supaya bisa dipasang ulang ke RtmpClient BARU saat
    // reconnect -- MediaCodec/capture card cuma mengirim SPS/PPS sekali di
    // awal, bukan tiap kali kita bikin ulang koneksi, jadi kalau tidak
    // di-cache, client baru hasil reconnect tidak akan pernah punya info
    // video yang valid.
    private var cachedSps: ByteBuffer? = null
    private var cachedPps: ByteBuffer? = null

    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()
    private var appContext: Context? = null

    fun configure(config: Config) {
        this.config = config
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
     * Bikin instance RtmpClient baru dan pasang ulang konfigurasi + SPS/PPS
     * (kalau sudah pernah ada). HARUS selalu dipanggil dari dalam
     * synchronized(clientLock) -- tidak melakukan locking sendiri.
     */
    private fun createFreshClientLocked() {
        rtmpClient = RtmpClient(this)
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

        // Diisi DI DALAM lock, dipakai DI LUAR lock -- supaya connect() (yang
        // blocking, handshake TCP/RTMP) tidak menahan lock lama-lama dan tidak
        // memblokir thread video/audio lain yang butuh clientLock juga.
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
            // Jangan tunggu callback onDisconnect/onConnectionFailed dari library
            // yang bisa saja telat -- begitu tulis ke socket gagal, langsung
            // anggap koneksi putus dan picu reconnect dari sini juga.
            onConnectionFailed("Send error: ${e.message}")
        }
    }

    /**
     * Audio mentah (raw AAC, tanpa ADTS) dari capture card, diteruskan
     * langsung ke RtmpClient tanpa lewat encoder tambahan -- capture card
     * (via library AUSBC) sudah meng-encode ke AAC duluan.
     */
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
            onConnectionFailed("Send error: ${e.message}")
        }
    }

    fun setVideoMetadata(sps: ByteBuffer, pps: ByteBuffer) {
        if (!isPushing) return
        Log.i(TAG, "SPS/PPS received")

        // Simpan salinan independen untuk dipakai ulang saat reconnect
        // (buffer asli dari fragment akan dipakai ulang/ditimpa nanti).
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
     *
     * PENTING (fix race condition): disconnect() lama + pembuatan client baru
     * sekarang dilakukan DI DALAM synchronized(clientLock) yang SAMA dengan
     * yang dipakai onVideoData()/onDeviceAudioData(). Ini memastikan thread
     * video/audio tidak akan pernah menulis ke objek RtmpClient yang sedang
     * di tengah proses disconnect/penggantian -- kalaupun mereka datang
     * bersamaan, mereka akan menunggu sebentar (blocked di lock) sampai
     * proses reconnect ini selesai, baru lanjut dengan client yang baru.
     *
     * Tetap dijalankan di Thread terpisah (bukan langsung di thread callback
     * onConnectionFailed/onDisconnect milik library) supaya kalau disconnect()
     * ternyata butuh waktu, thread callback internal library tidak ikut
     * terblokir/berpotensi deadlock.
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

    // --- ConnectChecker Implementation ---
    override fun onConnectionSuccess() {
        Log.i(TAG, "RTMP Success")
        synchronized(clientLock) {
            isConnecting = false
        }
        hasShownFailureToast = false
        consecutiveFailures = 0
        markConnected(true)
    }

    override fun onConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Timeout/Handshake Failure" }
        consecutiveFailures++
        Log.e(TAG, "RTMP Failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): $cleanReason")
        markConnected(false)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            giveUpAfterTooManyFailures()
            return
        }
        // TIDAK set isPushing = false di sini -- biar pipeline tetap hidup
        // dan auto-reconnect (via resetClientForReconnect) yang bekerja.
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

    override fun onConnectionStarted(url: String) {}

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        consecutiveFailures++
        Log.i(TAG, "RTMP Disconnected ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES) -- akan coba reconnect otomatis jika masih live")
        markConnected(false)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            giveUpAfterTooManyFailures()
            return
        }
        resetClientForReconnect()
    }

    override fun onAuthError() {
        onConnectionFailed("Auth Error")
    }

    override fun onAuthSuccess() {}
}
