package com.ufc.app.stream

import android.content.Context
import com.jiangdg.ausbc.pusher.AusbcPusher
import com.jiangdg.ausbc.pusher.IPusher
import com.jiangdg.ausbc.pusher.callback.IStateCallback
import com.jiangdg.ausbc.pusher.config.AusbcConfig
import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP dari library AndroidUSBCamera (modul :libpush).
 */
class RtmpPusher {

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
    private var pusher: IPusher? = null

    fun configure(config: Config) {
        this.config = config
    }

    fun start(context: Context) {
        if (isPushing) return
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi" }

        val ausbcConfig = AusbcConfig().apply {
            setVideoWidth(config.width)
            setVideoHeight(config.height)
        }

        try {
            AusbcPusher.init(context, ausbcConfig, object : IStateCallback {
                override fun onPushState(code: Int, msg: String?) {
                    // Biasanya code > 0 menandakan sukses/aktif
                    if (code > 0) {
                        markConnected(true)
                    } else {
                        markConnected(false)
                    }
                }
            })
            AusbcPusher.start(config.rtmpUrl)
        } catch (e: Exception) {
            // Tangani NotImplementedError atau exception lain dari library skeleton
            e.printStackTrace()
            markConnected(false)
            isPushing = false
        }

        isPushing = true
        StatusRepository.update {
            it.copy(
                resolution = "${config.width}x${config.height}", 
                fps = config.fps,
                bitrateKbps = config.videoBitrateKbps
            )
        }
    }

    fun stop() {
        isPushing = false
        AusbcPusher.stop()
        markConnected(false)
    }

    /**
     * Dipanggil UfcCameraFragment setiap ada frame H.264/AAC baru.
     * type: 0 untuk audio, 1 untuk video (berdasarkan AusbcPusher 3.6.0)
     */
    fun onEncodedData(type: Int, data: ByteArray, size: Int, pts: Long) {
        if (!isPushing) return
        AusbcPusher.pushStream(type, data, size, pts)
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
