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
 * 
 * Perbaikan Masalah:
 * 1. Broken Pipe: Menggunakan jam tunggal (System.nanoTime) untuk sinkronisasi Video & Audio.
 * 2. Suara: Mengambil suara dari Mikrofon HP (Internal) untuk stabilitas USB.
 * 3. Connection Lock: Mencegah spam connect() saat start.
 */
class RtmpPusher : ConnectChecker {

    companion object {
        private const val TAG = "RtmpPusher"
        private const val SAMPLE_RATE = 44100
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
    private var startTimestampUs: Long = -1
    
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
        startTimestampUs = -1
        
        try {
            // 1. Reset RTMP Client
            rtmpClient = RtmpClient(this)
            rtmpClient.setVideoCodec(VideoCodec.H264)
            rtmpClient.setAudioCodec(AudioCodec.AAC)
            rtmpClient.setVideoResolution(config.width, config.height)
            rtmpClient.setFps(config.fps)
            rtmpClient.setAudioInfo(SAMPLE_RATE, true)

            // 2. Inisialisasi Audio HP (Mic)
            audioEncoder = AudioEncoder(object : GetAudioData {
                override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    if (rtmpClient.isStreaming) {
                        // Gunakan jam tunggal untuk sinkronisasi
                        info.presentationTimeUs = getUnifiedTimestampUs()
                        rtmpClient.sendAudio(audioBuffer, info)
                    }
                }
                override fun onAudioFormat(mediaFormat: MediaFormat) {}
            })
            
            audioEncoder?.prepareAudioEncoder(config.audioBitrateKbps * 1024, SAMPLE_RATE, true)
            
            microphoneManager = MicrophoneManager(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    audioEncoder?.inputPCMData(frame)
                }
            })
            
            microphoneManager?.createMicrophone(SAMPLE_RATE, true, false, false)
            microphoneManager?.start()
            audioEncoder?.start()

            Toast.makeText(context, "Menghubungkan ke YouTube...", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal inisialisasi pusher: ${e.message}")
            stop()
            Toast.makeText(context, "Streaming Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

    /**
     * Menghasilkan timestamp mikrodetik yang sinkron untuk Video & Audio.
     */
    private fun getUnifiedTimestampUs(): Long {
        if (startTimestampUs == -1L) {
            startTimestampUs = System.nanoTime() / 1000
        }
        return (System.nanoTime() / 1000) - startTimestampUs
    }

    fun onVideoData(buffer: ByteBuffer, offset: Int, size: Int, timestampUs: Long, isKeyFrame: Boolean) {
        if (!isPushing) return

        // Hubungkan SETELAH metadata (SPS/PPS) siap
        if (isMetadataReady && !rtmpClient.isStreaming && !isConnecting) {
            Log.i(TAG, "Metadata ready, connecting to server...")
            isConnecting = true
            Thread {
                try {
                    rtmpClient.connect(config.rtmpUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Connect failed: ${e.message}")
                    isConnecting = false
                }
            }.start()
            return
        }

        if (rtmpClient.isStreaming) {
            isConnecting = false
            // Gunakan jam tunggal yang sama dengan audio (System.nanoTime)
            // Ini kunci utama untuk mencegah "Broken Pipe" di YouTube.
            videoInfo.set(offset, size, getUnifiedTimestampUs(), if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
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
        Log.i(TAG, "RTMP Connection Success")
        isConnecting = false
        markConnected(true)
    }

    override fun onConnectionFailed(reason: String) {
        val cleanReason = reason.ifBlank { "Network Timeout" }
        Log.e(TAG, "RTMP Connection Failed: $cleanReason")
        isConnecting = false
        isPushing = false
        markConnected(false)
        appContext?.let {
            android.os.Handler(it.mainLooper).post {
                Toast.makeText(it, "Live Gagal: $cleanReason", Toast.LENGTH_LONG).show()
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
