package com.ufc.app.stream

import com.jiangdg.ausbc.camera.bean.CameraEncodeData
import com.jiangdg.ausbc.push.AusbcPusher
import com.jiangdg.ausbc.push.IPusher
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

    fun start() {
        if (isPushing) return
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi" }

        pusher = AusbcPusher().apply {
            setPusherCallBack(object : IPusher.OnPusherCallBack {
                override fun onPusherStatus(status: IPusher.PusherStatus) {
                    when (status) {
                        IPusher.PusherStatus.CONNECTED -> markConnected(true)
                        IPusher.PusherStatus.DISCONNECTED, 
                        IPusher.PusherStatus.ERROR -> markConnected(false)
                        else -> {}
                    }
                }
            })
            startPusher(config.rtmpUrl)
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
        pusher?.stopPusher()
        pusher = null
        markConnected(false)
    }

    /**
     * Dipanggil UfcCameraFragment setiap ada frame H.264/AAC baru.
     */
    fun onEncodedData(data: CameraEncodeData) {
        if (!isPushing) return
        pusher?.pushData(data.data, data.type)
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
