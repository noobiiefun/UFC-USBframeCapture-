package com.ufc.app.stream

import android.content.Context
import android.media.MediaCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.common.ConnectChecker
import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP menggunakan library RootEncoder (pedroSG94).
 */
class RtmpPusher : ConnectChecker {

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
    private var rtmpClient: RtmpClient = RtmpClient(this)
    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()

    fun configure(config: Config) {
        this.config = config
    }

    fun start(context: Context) {
        if (isPushing) return
        require(config.rtmpUrl.isNotBlank()) { "RTMP URL belum diisi" }

        try {
            // Inisialisasi info dasar (akan diupdate saat SPS/PPS datang)
            rtmpClient.setAudioInfo(44100, true)
            rtmpClient.connect(config.rtmpUrl)
            isPushing = true
        } catch (e: Throwable) {
            e.printStackTrace()
            markConnected(false)
            isPushing = false
            android.widget.Toast.makeText(context, "Streaming Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
        }

        if (isPushing) {
            StatusRepository.update {
                it.copy(
                    resolution = "${config.width}x${config.height}",
                    fps = config.fps,
                    bitrateKbps = config.videoBitrateKbps
                )
            }
        }
    }

    fun stop() {
        isPushing = false
        try {
            rtmpClient.disconnect()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        markConnected(false)
    }

    /**
     * Dipanggil UfcCameraFragment setiap ada frame H.264/AAC baru.
     * Menggunakan ByteBuffer langsung dari library AUSBC untuk efisiensi.
     */
    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing || !rtmpClient.isStreaming) return

        videoInfo.set(offset, size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        rtmpClient.sendVideo(buffer, videoInfo)
    }

    fun onAudioData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long) {
        if (!isPushing || !rtmpClient.isStreaming) return

        audioInfo.set(offset, size, timestampUs, 0)
        rtmpClient.sendAudio(buffer, audioInfo)
    }

    /**
     * Set metadata video (SPS/PPS) yang didapat dari encoder.
     */
    fun setVideoMetadata(sps: ByteBuffer, pps: ByteBuffer) {
        rtmpClient.setVideoInfo(sps, pps, null)
    }

    private fun markConnected(connected: Boolean) {
        StatusRepository.update {
            it.copy(
                youtubeConnected = connected,
                uptimeSec = if (connected) StatusRepository.currentUptimeSec() else it.uptimeSec
            )
        }
    }

    // --- ConnectChecker Implementation ---

    override fun onConnectionSuccess() {
        markConnected(true)
    }

    override fun onConnectionFailed(reason: String) {
        isPushing = false
        markConnected(false)
    }

    override fun onConnectionStarted(url: String) {
    }

    override fun onNewBitrate(bitrate: Long) {
    }

    override fun onDisconnect() {
        isPushing = false
        markConnected(false)
    }

    override fun onAuthError() {
        isPushing = false
        markConnected(false)
    }

    override fun onAuthSuccess() {
    }
}
