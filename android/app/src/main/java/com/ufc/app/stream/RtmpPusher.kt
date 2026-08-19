package com.ufc.app.stream

import com.ufc.app.StatusRepository

/**
 * Konfigurasi encode + push RTMP. Membungkus modul `libpush` dari
 * AndroidUSBCamera (atau library RTMP lain kalau diganti — lihat
 * docs/SETUP.md 1.2).
 *
 * Nilai default di bawah dipilih untuk device kelas entry-level
 * (Helio G36 / RAM 3-4GB) — lihat README.md bagian "Catatan Performa".
 */
class RtmpPusher {

    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val videoBitrateKbps: Int = 2200,
        val audioBitrateKbps: Int = 128,
        val rtmpUrl: String = "" // contoh: rtmp://a.rtmp.youtube.com/live2/<STREAM_KEY>
    )

    private var config: Config = Config()
    private var isPushing = false
    private var droppedFrames = 0L

    fun configure(config: Config) {
        this.config = config
    }

    /**
     * Mulai push. TODO: ganti isi fungsi ini dengan pemanggilan API
     * library push yang dipakai, contoh (pseudo, cek nama method asli
     * di demo library):
     *
     * pusher.setVideoParams(config.width, config.height, config.fps, config.videoBitrateKbps * 1000)
     * pusher.setAudioParams(config.audioBitrateKbps * 1000)
     * pusher.setPushUrl(config.rtmpUrl)
     * pusher.setOnPushListener(object : OnPushListener {
     *     override fun onConnected() { markConnected(true) }
     *     override fun onDisconnected() { markConnected(false) }
     *     override fun onStatistics(bitrateKbps: Int, fps: Int, dropped: Long) {
     *         droppedFrames = dropped
     *         StatusRepository.update {
     *             it.copy(bitrateKbps = bitrateKbps, fps = fps, droppedFrames = dropped)
     *         }
     *     }
     * })
     * pusher.startPush()
     */
    fun start() {
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi — cek Stream Key YouTube" }
        isPushing = true
        StatusRepository.update {
            it.copy(
                resolution = "${config.width}x${config.height}",
                fps = config.fps,
                bitrateKbps = config.videoBitrateKbps
            )
        }
        // TODO: panggil start API library push
    }

    fun stop() {
        isPushing = false
        markConnected(false)
        // TODO: panggil stop/release API library push
    }

    /** Dipanggil UvcCaptureManager tiap ada frame mentah baru dari capture card. */
    fun onRawFrame(frame: ByteArray) {
        if (!isPushing) return
        // TODO: kirim frame ke encoder/pusher library
    }

    private fun markConnected(connected: Boolean) {
        StatusRepository.update {
            it.copy(
                youtubeConnected = connected,
                uptimeSec = if (connected) StatusRepository.currentUptimeSec() else it.uptimeSec
            )
        }
    }
}
