package com.ufc.app.stream

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP menggunakan library RootEncoder (pedroSG94).
 */
class RtmpPusher : ConnectChecker {

    companion object {
        private const val TAG = "RtmpPusher"
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
    private var rtmpStream: RtmpStream? = null
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
        
        try {
            // Gunakan RtmpStream (high level) agar manajemen Mic dan Video lebih stabil
            rtmpStream = RtmpStream(context, this)
            rtmpStream?.setAudioInfo(128 * 1024, 44100, true)
            rtmpStream?.setVideoInfo(config.width, config.height, config.videoBitrateKbps * 1000, config.fps)
            
            Toast.makeText(context, "Menunggu data dari kamera...", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            e.printStackTrace()
            markConnected(false)
            isPushing = false
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

    fun stop() {
        isPushing = false
        isConnecting = false
        isMetadataReady = false
        try {
            rtmpStream?.stopStream()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        markConnected(false)
        Log.i(TAG, "Stream stopped manual")
    }

    /**
     * Dipanggil UfcCameraFragment setiap ada frame H.264/AAC baru.
     */
    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing) return

        // Kita baru bisa connect SETELAH metadata (SPS/PPS) masuk
        if (isMetadataReady && rtmpStream?.isStreaming == false && !isConnecting) {
            Log.i(TAG, "Metadata ready, connecting to server...")
            isConnecting = true
            rtmpStream?.startStream(config.rtmpUrl)
            return
        }

        if (rtmpStream?.isStreaming == true) {
            isConnecting = false // Reset flag jika sudah streaming
            videoInfo.set(offset, size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            rtmpStream?.rtmpClient?.sendVideo(buffer, videoInfo)
        }
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
        if (!isPushing) return
        Log.i(TAG, "SPS/PPS received, size: ${sps.remaining()} / ${pps.remaining()}")
        rtmpStream?.rtmpClient?.setVideoInfo(sps, pps, null)
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

    // --- ConnectChecker Implementation ---

    override fun onConnectionSuccess() {
        Log.i(TAG, "RTMP Connection Success")
        isConnecting = false
        markConnected(true)
        appContext?.let {
            // Toast di UI Thread
            android.os.Handler(it.mainLooper).post {
                Toast.makeText(it, "Live ke YouTube Aktif!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Unknown Error (Cek Internet/Stream Key)" }
        Log.e(TAG, "RTMP Connection Failed: $cleanReason")
        isConnecting = false
        isPushing = false
        markConnected(false)
        appContext?.let {
            android.os.Handler(it.mainLooper).post {
                Toast.makeText(it, "Koneksi Gagal: $cleanReason", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "RTMP Connection Started: $url")
    }

    override fun onNewBitrate(bitrate: Long) {
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTMP Disconnected")
        isPushing = false
        markConnected(false)
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP Auth Error")
        isPushing = false
        markConnected(false)
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTMP Auth Success")
    }
}
