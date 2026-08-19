package com.ufc.app.stream

import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP dari library (README resmi menyebutnya
 * "IPusher dan AusbcPusher", modul :libpush).
 *
 * PENTING: berbeda dari getCameraRequest()/onCameraState() di
 * UfcCameraFragment (yang API publiknya terdokumentasi jelas di README),
 * detail method IPusher/AusbcPusher TIDAK dijelaskan lengkap di README.
 * Jadi bagian di bawah ini masih PSEUDO-CODE terstruktur — begitu Gradle
 * selesai sync, buka class AusbcPusher lewat "Go to Declaration" di Android
 * Studio (atau lihat demo push activity di repo aslinya) untuk menyamakan
 * nama method persisnya, lalu isi TODO di sini.
 *
 * Kalau ternyata AusbcPusher tidak cocok/terlalu terbatas, alternatif:
 * pakai IPusher sebagai interface custom dan implementasikan sendiri pakai
 * MediaMuxer + library RTMP murni seperti pedroSG94/RootEncoder
 * (io.github.pedrosg94:rtmp-rtsp-stream-client-java), yang API publiknya
 * lebih terdokumentasi: prepareVideo()/prepareAudio()/startStream(url)/stopStream().
 */
class RtmpPusher {

    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val videoBitrateKbps: Int = 2200,
        val audioBitrateKbps: Int = 128,
        val rtmpUrl: String = "" // rtmp://a.rtmp.youtube.com/live2/<STREAM_KEY>
    )

    private var config: Config = Config()
    private var isPushing = false

    // TODO: ganti dengan instance AusbcPusher/IPusher asli, contoh (pseudo):
    // private var pusher: AusbcPusher? = null

    fun configure(config: Config) {
        this.config = config
    }

    fun start() {
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi — cek Stream Key YouTube" }

        // TODO (verifikasi nama method asli di AusbcPusher):
        // pusher = AusbcPusher(context).apply {
        //     setPushUrl(config.rtmpUrl)
        //     setVideoParams(config.width, config.height, config.fps, config.videoBitrateKbps * 1000)
        //     setAudioParams(sampleRate = 44100, bitrate = config.audioBitrateKbps * 1000, isStereo = true)
        //     setOnPushStateListener(object : OnPushStateListener {
        //         override fun onConnected() = markConnected(true)
        //         override fun onDisconnected() = markConnected(false)
        //         override fun onError(msg: String) = markConnected(false)
        //         override fun onStatistics(bitrateKbps: Int, fps: Int, dropped: Long) {
        //             StatusRepository.update {
        //                 it.copy(bitrateKbps = bitrateKbps, fps = fps, droppedFrames = dropped)
        //             }
        //         }
        //     })
        //     startPush()
        // }

        isPushing = true
        StatusRepository.update {
            it.copy(resolution = "${config.width}x${config.height}", fps = config.fps)
        }
    }

    fun stop() {
        isPushing = false
        markConnected(false)
        // TODO: pusher?.stopPush(); pusher = null
    }

    /**
     * Dipanggil UfcCameraFragment setiap ada frame H.264/AAC baru dari
     * addEncodeDataCallBack milik library.
     */
    fun onEncodedFrame(buffer: ByteBuffer, isVideo: Boolean) {
        if (!isPushing) return
        // TODO: pusher?.pushData(buffer, isVideo)
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
