package com.ufc.app.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.widget.Toast
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.rtmp.rtmp.RtmpClient
import com.ufc.app.StatusRepository
import java.nio.ByteBuffer

/**
 * Membungkus komponen push RTMP menggunakan RtmpClient dasar (RootEncoder).
 * Menggunakan Mikrofon HP secara internal untuk suara guna menghindari Bad File Descriptor.
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
    private var rtmpClient: RtmpClient = RtmpClient(this)
    private var audioEncoder: AudioEncoder? = null
    private var microphoneManager: MicrophoneManager? = null
    
    private val videoInfo = MediaCodec.BufferInfo()
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
            // Reset RTMP Client
            rtmpClient = RtmpClient(this)
            rtmpClient.setVideoCodec(VideoCodec.H264)
            rtmpClient.setAudioCodec(AudioCodec.AAC)
            rtmpClient.setVideoResolution(config.width, config.height)
            rtmpClient.setFps(config.fps)
            rtmpClient.setAudioInfo(44100, true) // Stereo 44.1kHz

            // Inisialisasi Audio HP (Mic)
            audioEncoder = AudioEncoder(object : GetAudioData {
                override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    if (rtmpClient.isStreaming) {
                        rtmpClient.sendAudio(audioBuffer, info)
                    }
                }
                override fun onAudioFormat(mediaFormat: MediaFormat) {
                    // Muxer tidak digunakan di sini
                }
            })
            
            // Set bitrate audio (128kbps default)
            audioEncoder?.prepareAudioEncoder(128 * 1024, 44100, true)
            
            microphoneManager = MicrophoneManager(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    audioEncoder?.inputPCMData(frame)
                }
            })
            
            microphoneManager?.createMicrophone(44100, true, false, false)
            microphoneManager?.start()
            audioEncoder?.start()

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

    fun stop() {
        isPushing = false
        isConnecting = false
        isMetadataReady = false
        
        try {
            microphoneManager?.stop()
            audioEncoder?.stop()
            if (rtmpClient.isStreaming) {
                rtmpClient.disconnect()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        
        microphoneManager = null
        audioEncoder = null
        markConnected(false)
        Log.i(TAG, "Stream stopped")
    }

    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing) return

        // Connect SETELAH metadata (SPS/PPS) masuk
        if (isMetadataReady && !rtmpClient.isStreaming && !isConnecting) {
            Log.i(TAG, "Metadata ready, connecting to server...")
            isConnecting = true
            rtmpClient.connect(config.rtmpUrl)
            return
        }

        if (rtmpClient.isStreaming) {
            isConnecting = false
            videoInfo.set(offset, size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            rtmpClient.sendVideo(buffer, videoInfo)
        }
    }

    fun setVideoMetadata(sps: ByteBuffer, pps: ByteBuffer) {
        if (!isPushing) return
        Log.i(TAG, "SPS/PPS received")
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

    // --- ConnectChecker Implementation ---
    override fun onConnectionSuccess() {
        Log.i(TAG, "RTMP Success")
        isConnecting = false
        markConnected(true)
    }

    override fun onConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Timeout/Handshake Failure" }
        Log.e(TAG, "RTMP Failed: $cleanReason")
        isConnecting = false
        isPushing = false
        markConnected(false)
        appContext?.let {
            android.os.Handler(it.mainLooper).post {
                Toast.makeText(it, "Koneksi Gagal: $cleanReason", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        isPushing = false
        markConnected(false)
    }
    override fun onAuthError() {
        onConnectionFailed("Auth Error")
    }
    override fun onAuthSuccess() {}
}
