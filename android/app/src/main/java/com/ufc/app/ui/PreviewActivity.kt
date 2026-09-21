package com.ufc.app.ui

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Activity khusus untuk Preview Mode - berfungsi sebagai monitor/layar tambahan.
 * Menampilkan video & audio langsung dari capture card tanpa encoding/streaming.
 * Modul terpisah: saat preview aktif, fungsi lain off (kecuali hide notifikasi).
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        // Audio passthrough - capture card biasanya mengirim PCM 48kHz stereo
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private lateinit var config: StreamConfig
    private var previewView: AspectRatioTextureView? = null
    private var multiCameraClient: MultiCameraClient? = null
    private var cameraClient: MultiCameraClient.ICamera? = null
    
    // Audio passthrough dari HDMI capture card
    private var audioTrack: AudioTrack? = null
    private var audioBufferSize: Int = 0
    private var isAudioPlaying = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Fullscreen mode - tidak ada status bar atau navigation bar
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        // Hide system UI completely
        hideSystemUI()
        
        config = StreamConfig(this)
        
        // PreviewActivity hanya untuk preview-only mode (tidak ada streaming)
        
        setContentView(R.layout.activity_preview)
        
        previewView = findViewById(R.id.previewTextureView)
        
        val btnStop = findViewById<Button>(R.id.btnStopPreview)
        val textStatus = findViewById<TextView>(R.id.textPreviewStatus)
        
        // Setup tombol toggle controls (tap sekali untuk show/hide controls)
        var controlsVisible = true
        val controlsView = findViewById<View>(R.id.controlsOverlay)
        
        previewView?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                controlsVisible = !controlsVisible
                controlsView.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                if (!controlsVisible) {
                    hideSystemUI()
                }
            }
            true
        }
        
        btnStop.setOnClickListener {
            stopPreview()
            finish()
        }
        
        lifecycleScope.launch {
            StatusRepository.status.collect { status ->
                textStatus.text = buildString {
                    append("PREVIEW MODE\n")
                    append("UVC: ${if (status.connected) "ON" else "OFF"} | ")
                    append("${status.resolution} @ ${status.fps}fps")
                }
            }
        }
        
        startPreview()
    }
    
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
    
    private fun startPreview() {
        if (checkPermissions()) {
            initCamera()
        } else {
            requestPermissions()
        }
    }
    
    private fun initCamera() {
        var width = config.resolutionWidth
        var height = config.resolutionHeight
        
        if (config.isPortrait) {
            if (width > height) {
                val temp = width
                width = height
                height = temp
            }
        } else {
            if (height > width) {
                val temp = width
                width = height
                height = temp
            }
        }
        
        val cameraRequest = CameraRequest.Builder()
            .setPreviewWidth(width)
            .setPreviewHeight(height)
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_DEV_MIC) // Audio dari capture card
            .setPreviewFormat(if (config.useMjpeg) CameraRequest.PreviewFormat.FORMAT_MJPEG else CameraRequest.PreviewFormat.FORMAT_YUYV)
            .setAspectRatioShow(false) // Tidak perlu aspect ratio indicator di preview mode
            .create()
        
        multiCameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                multiCameraClient?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                cameraClient?.closeCamera()
                cameraClient = null
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                val camera = CameraUVC(this@PreviewActivity, device!!)
                camera.setUsbControlBlock(ctrlBlock)
                cameraClient = camera
                
                cameraClient?.setCameraStateCallBack(object : ICameraStateCallBack {
                    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                        when (code) {
                            ICameraStateCallBack.State.OPENED -> {
                                Log.i(TAG, "Camera opened - starting preview & audio passthrough")
                                StatusRepository.update { it.copy(connected = true) }
                                
                                // Preview only mode - setup audio passthrough callback
                                setupAudioPassthroughCallback()
                                
                                // Mulai audio passthrough dari HDMI
                                startAudioPassthrough()
                                
                                // Mulai preview
                                cameraClient?.captureStreamStart()
                            }
                            ICameraStateCallBack.State.CLOSED -> {
                                Log.i(TAG, "Camera closed")
                                StatusRepository.update { it.copy(connected = false) }
                                stopAudioPassthrough()
                            }
                            ICameraStateCallBack.State.ERROR -> {
                                Log.e(TAG, "Camera error: $msg")
                                StatusRepository.update { it.copy(connected = false) }
                                Toast.makeText(this@PreviewActivity, "Camera Error: $msg", Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                })
                
                cameraClient?.openCamera(previewView, cameraRequest)
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                cameraClient?.closeCamera()
            }

            override fun onCancelDev(device: UsbDevice?) {
            }
        })
        
        multiCameraClient?.register()
    }
    
    private fun setupAudioPassthroughCallback() {
        // Setup callback khusus untuk audio passthrough di preview only mode
        cameraClient?.setEncodeDataCallBack(object : IEncodeDataCallBack {
            override fun onEncodeData(
                type: IEncodeDataCallBack.DataType,
                buffer: ByteBuffer,
                offset: Int,
                size: Int,
                timestamp: Long
            ) {
                if (type == IEncodeDataCallBack.DataType.AAC && isAudioPlaying) {
                    playAudioData(buffer, offset, size)
                }
            }
        })
        Log.i(TAG, "Audio passthrough callback setup for preview-only mode")
    }
    
    private fun extractSpsPps(buffer: ByteBuffer, offset: Int, size: Int) {
        // Fungsi ini tidak dipakai di preview-only mode
        // Dibiarkan untuk kompatibilitas kode
    }
    
    private fun startAudioPassthrough() {
        // Hitung buffer size untuk audio passthrough dengan latency rendah
        audioBufferSize = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        if (audioBufferSize <= 0) {
            Log.e(TAG, "Invalid audio buffer size")
            return
        }
        
        // Gunakan AudioTrack dengan latency rendah
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // Musik untuk kualitas lebih baik
            .build()
        
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AUDIO_CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack = AudioTrack(
                    audioAttributes,
                    audioFormat,
                    audioBufferSize * 2, // Buffer lebih besar untuk stabilitas
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }
            
            audioTrack?.playbackRate = AUDIO_SAMPLE_RATE
            audioTrack?.play()
            isAudioPlaying = true
            
            Log.i(TAG, "Audio passthrough started: ${AUDIO_SAMPLE_RATE}Hz, stereo, 16-bit, buffer=${audioBufferSize * 2}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio passthrough: ${e.message}")
            Toast.makeText(this, "Gagal memulai audio: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun playAudioData(buffer: ByteBuffer, offset: Int, size: Int) {
        if (!isAudioPlaying || audioTrack == null) return
        
        try {
            val audioData = ByteArray(size)
            val originalPos = buffer.position()
            buffer.position(offset)
            buffer.get(audioData)
            buffer.position(originalPos)
            
            audioTrack?.write(audioData, 0, size)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
        }
    }
    
    private fun stopAudioPassthrough() {
        try {
            isAudioPlaying = false
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.i(TAG, "Audio passthrough stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
    }
    
    private fun stopPreview() {
        Log.i(TAG, "Stopping preview...")
        
        stopAudioPassthrough()
        
        cameraClient?.captureStreamStop()
        multiCameraClient?.unRegister()
        multiCameraClient?.destroy()
        multiCameraClient = null
        cameraClient = null
        
        StatusRepository.update { it.copy(connected = false) }
    }
    
    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }
    
    override fun onPause() {
        super.onPause()
        // Jangan stop preview saat pause, biarkan tetap jalan di background
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            101
        )
    }
}
