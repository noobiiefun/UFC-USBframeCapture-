package com.ufc.app.stream

import android.content.Context
import android.media.MediaCodec
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
 * diteruskan dari UfcCameraFragment.onDeviceAudioData(). Ini menggantikan
 * pendekatan lama yang memakai mic internal HP sebagai workaround Bad File
 * Descriptor -- sekarang bug itu diatasi dengan deep-copy buffer di
 * UfcCameraFragment, jadi audio asli PC bisa dipakai langsung.
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
    private var isPushing = false
    private var isConnecting = false
    private var isMetadataReady = false
    private var rtmpClient: RtmpClient = RtmpClient(this)
    private var lastConnectAttemptMs = 0L
    private var hasShownFailureToast = false

    // Cache SPS/PPS supaya bisa dipasang ulang ke RtmpClient BARU saat
    // reconnect -- MediaCodec cuma mengirim SPS/PPS sekali di awal, bukan
    // tiap kali kita bikin ulang koneksi, jadi kalau tidak di-cache, client
    // baru hasil reconnect tidak akan pernah punya info video yang valid.
    private var cachedSps: ByteBuffer? = null
    private var cachedPps: ByteBuffer? = null

    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()
    private var appContext: Context? = null

    fun configure(config: Config) {
        this.config = config
    }

    fun start(context: Context) {
        if (isPushing) return
        this.appContext = context.applicationContext
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi" }

        Log.i(TAG, "Starting stream to: ${config.rtmpUrl}")
        isPushing = true
        isConnecting = false
        isMetadataReady = false
        lastConnectAttemptMs = 0L
        hasShownFailureToast = false
        cachedSps = null
        cachedPps = null

        try {
            createFreshClient()
            Toast.makeText(context, "Menghubungkan ke YouTube...", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal inisialisasi pusher: ${e.message}")
            e.printStackTrace()
            stop()
            Toast.makeText(context, "Config Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
     * (kalau sudah pernah ada). Dipanggil saat start() awal dan saat
     * reconnect otomatis setelah koneksi putus.
     */
    private fun createFreshClient() {
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

        markConnected(false)
        Log.i(TAG, "Stream stopped")
    }

    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing) return

        // Connect SETELAH metadata (SPS/PPS) masuk.
        // PENTING: connect() ini blocking (handshake TCP/RTMP ke YouTube).
        // Kalau dipanggil langsung di sini, thread encode video (dari library
        // AUSBC) akan freeze total selama proses connect -- itu penyebab
        // "macet" saat Start Live. Jalankan di thread terpisah.
        //
        // Juga berfungsi sebagai AUTO-RECONNECT: kalau koneksi putus di
        // tengah jalan (mis. "Broken pipe"), kondisi ini otomatis kepenuhi
        // lagi di frame video berikutnya dan coba connect ulang ke
        // rtmpClient yang SUDAH DI-REFRESH oleh onConnectionFailed/onDisconnect
        // -- itu sebabnya isConnecting dijaga true sampai client baru siap.
        val now = System.currentTimeMillis()
        if (isMetadataReady && !rtmpClient.isStreaming && !isConnecting &&
            (now - lastConnectAttemptMs) > RECONNECT_DELAY_MS) {
            Log.i(TAG, "Metadata ready, connecting to server...")
            isConnecting = true
            lastConnectAttemptMs = now
            Thread {
                try {
                    rtmpClient.connect(config.rtmpUrl)
                } catch (e: Throwable) {
                    Log.e(TAG, "Gagal connect RTMP: ${e.message}")
                    isConnecting = false
                }
            }.start()
            return
        }

        if (rtmpClient.isStreaming) {
            isConnecting = false
            videoInfo.set(offset, size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            rtmpClient.sendVideo(buffer, videoInfo)
        }
    }

    /**
     * Audio mentah (raw AAC, tanpa ADTS) dari capture card, diteruskan
     * langsung ke RtmpClient tanpa lewat encoder tambahan -- capture card
     * (via library AUSBC) sudah meng-encode ke AAC duluan.
     */
    fun onDeviceAudioData(buffer: ByteBuffer, size: Int, timestampUs: Long) {
        if (!isPushing) return
        if (!rtmpClient.isStreaming) return

        audioInfo.set(0, size, timestampUs, 0)
        rtmpClient.sendAudio(buffer, audioInfo)
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
        cachedSps = spsCopy
        cachedPps = ppsCopy

        rtmpClient.setVideoInfo(sps, pps, null)
        isMetadataReady = true
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
     * Bersihkan client yang bermasalah dan siapkan yang baru untuk
     * reconnect. isConnecting sengaja TETAP true sampai proses ini selesai,
     * supaya onVideoData tidak menembak connect() ke rtmpClient lama yang
     * sedang di tengah proses pembersihan/pergantian.
     */
    private fun resetClientForReconnect() {
        isConnecting = true
        Thread {
            try {
                rtmpClient.disconnect()
            } catch (e: Throwable) {
                Log.e(TAG, "Gagal disconnect client lama: ${e.message}")
            }
            if (isPushing && cachedSps != null && cachedPps != null) {
                try {
                    createFreshClient()
                } catch (e: Throwable) {
                    Log.e(TAG, "Gagal bikin client baru untuk reconnect: ${e.message}")
                }
            }
            isConnecting = false
        }.start()
    }

    // --- ConnectChecker Implementation ---
    override fun onConnectionSuccess() {
        Log.i(TAG, "RTMP Success")
        isConnecting = false
        hasShownFailureToast = false
        markConnected(true)
    }

    override fun onConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Timeout/Handshake Failure" }
        Log.e(TAG, "RTMP Failed: $cleanReason -- akan coba reconnect otomatis")
        // TIDAK set isPushing = false di sini -- biar pipeline tetap hidup
        // dan auto-reconnect (via resetClientForReconnect) yang bekerja.
        markConnected(false)
        resetClientForReconnect()
        if (!hasShownFailureToast) {
            hasShownFailureToast = true
            appContext?.let {
                android.os.Handler(it.mainLooper).post {
                    Toast.makeText(it, "Koneksi Gagal: $cleanReason (mencoba reconnect...)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        Log.i(TAG, "RTMP Disconnected -- akan coba reconnect otomatis jika masih live")
        markConnected(false)
        resetClientForReconnect()
    }
    override fun onAuthError() {
        onConnectionFailed("Auth Error")
    }
    override fun onAuthSuccess() {}
}
