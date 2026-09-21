package com.ufc.app.ui

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity khusus untuk Preview Mode - berfungsi sebagai monitor/layar tambahan.
 * Menampilkan video & audio langsung dari capture card tanpa encoding/streaming.
 * Modul terpisah: saat preview aktif, fungsi lain off (kecuali hide notifikasi).
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        private const val AUTO_HIDE_DELAY_MS = 3000L
        
        // Audio passthrough constants
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private lateinit var config: StreamConfig
    private var previewView: AspectRatioTextureView? = null
    private var multiCameraClient: MultiCameraClient? = null
    private var cameraClient: MultiCameraClient.ICamera? = null
    
    // Audio passthrough
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioThread: ExecutorService? = null
    private var isAudioPlaying = false
    
    // UI Components
    private var controlsView: View? = null
    private var textStatus: TextView? = null
    private var btnDetectUsb: Button? = null
    private var btnRotate: Button? = null
    private var btnStop: Button? = null
    
    // Rotation state
    private var currentRotationIndex = 0
    private val rotationAngles = listOf(
        RotateType.ANGLE_0,
        RotateType.ANGLE_90,
        RotateType.ANGLE_180,
        RotateType.ANGLE_270
    )
    
    // Auto-hide handler
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        hideControls()
    }

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
        
        setContentView(R.layout.activity_preview)
        
        previewView = findViewById(R.id.previewTextureView)
        controlsView = findViewById(R.id.controlsOverlay)
        textStatus = findViewById(R.id.textPreviewStatus)
        btnDetectUsb = findViewById(R.id.btnDetectUsb)
        btnRotate = findViewById(R.id.btnRotate)
        btnStop = findViewById(R.id.btnStopPreview)
        
        // Setup tombol detect USB - untuk mendeteksi dan memulai preview
        btnDetectUsb?.setOnClickListener {
            if (multiCameraClient == null) {
                startPreview()
            } else {
                // Re-connect jika sudah terhubung
                stopPreview()
                startPreview()
            }
        }
        
        // Setup tombol rotasi - untuk mengubah orientasi tampilan
        btnRotate?.setOnClickListener {
            rotatePreview()
        }
        
        // Setup tombol keluar
        btnStop?.setOnClickListener {
            stopPreview()
            finish()
        }
        
        // Tap pada preview untuk toggle controls dengan auto-hide
        var controlsVisible = true
        previewView?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                controlsVisible = !controlsVisible
                if (controlsVisible) {
                    showControls()
                    // Auto-hide setelah 3 detik
                    autoHideHandler.removeCallbacks(autoHideRunnable)
                    autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
                } else {
                    hideControls()
                }
            }
            true
        }
        
        // Update status UI
        lifecycleScope.launch {
            StatusRepository.status.collect { status ->
                textStatus?.text = buildString {
                    append("PREVIEW MODE\n")
                    append("UVC: ${if (status.connected) "ON" else "OFF"} | ")
                    append("${status.resolution} @ ${status.fps}fps")
                }
                
                // Auto-start preview jika USB terdeteksi connected
                if (status.connected && multiCameraClient == null) {
                    Log.i(TAG, "USB connected detected, starting preview...")
                }
            }
        }
        
        // Langsung mulai preview saat activity dibuka
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
    
    private fun showControls() {
        controlsView?.visibility = View.VISIBLE
        hideSystemUI()
    }
    
    private fun hideControls() {
        controlsView?.visibility = View.GONE
        hideSystemUI()
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
        
        // Pastikan resolusi minimal 720p untuk landscape
        if (!config.isPortrait) {
            // Landscape mode: width harus >= 1280, height >= 720
            if (width < 1280 || height < 720) {
                width = 1280
                height = 720
            }
        } else {
            // Portrait mode: height harus >= 1280, width >= 720
            if (width < 720 || height < 1280) {
                width = 720
                height = 1280
            }
        }
        
        // Simpan konfigurasi yang sudah disesuaikan
        config.resolutionWidth = width
        config.resolutionHeight = height
        
        Log.i(TAG, "Initializing camera with resolution: ${width}x${height}, rotation: ${rotationAngles[currentRotationIndex]}")
        
        val cameraRequest = CameraRequest.Builder()
            .setPreviewWidth(width)
            .setPreviewHeight(height)
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setDefaultRotateType(rotationAngles[currentRotationIndex])
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
                                Log.i(TAG, "Camera opened - starting preview and audio")
                                StatusRepository.update { it.copy(connected = true) }
                                
                                // Mulai preview video
                                cameraClient?.captureStreamStart()
                                
                                // Mulai audio passthrough setelah delay singkat
                                Handler(Looper.getMainLooper()).postDelayed({
                                    startAudioPassthrough()
                                }, 500)
                            }
                            ICameraStateCallBack.State.CLOSED -> {
                                Log.i(TAG, "Camera closed")
                                StatusRepository.update { it.copy(connected = false) }
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
    
    private fun stopPreview() {
        Log.i(TAG, "Stopping preview...")
        
        // Stop audio passthrough terlebih dahulu
        stopAudioPassthrough()
        
        cameraClient?.captureStreamStop()
        multiCameraClient?.unRegister()
        multiCameraClient?.destroy()
        multiCameraClient = null
        cameraClient = null
        
        StatusRepository.update { it.copy(connected = false) }
    }
    
    /**
     * Mulai audio passthrough dari capture card ke speaker HP
     * Menggunakan AudioRecord untuk merekam dari USB audio device
     * dan AudioTrack untuk memutar suara secara real-time
     */
    @SuppressLint("MissingPermission")
    private fun startAudioPassthrough() {
        if (isAudioPlaying) {
            Log.w(TAG, "Audio already playing")
            return
        }
        
        try {
            // Dapatkan buffer size minimum
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size for audio record")
                return
            }
            
            val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
            
            // Setup AudioRecord - merekam dari sumber audio eksternal (USB capture card)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return
            }
            
            // Setup AudioTrack untuk playback
            val trackMinBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, 
                AudioFormat.CHANNEL_OUT_STEREO, AUDIO_FORMAT)
            if (trackMinBufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size for audio track")
                return
            }
            
            val trackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            val trackFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            
            audioTrack = AudioTrack(
                trackAttributes,
                trackFormat,
                trackMinBufferSize * BUFFER_SIZE_FACTOR,
                AudioTrack.MODE_STREAM,
                0
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                audioTrack?.release()
                audioTrack = null
                return
            }
            
            // Mulai thread untuk streaming audio
            audioThread = Executors.newSingleThreadExecutor()
            audioThread?.execute {
                try {
                    audioRecord?.startRecording()
                    audioTrack?.play()
                    isAudioPlaying = true
                    
                    Log.i(TAG, "Audio passthrough started - ${SAMPLE_RATE}Hz stereo")
                    
                    val buffer = ByteArray(bufferSize)
                    while (isAudioPlaying) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0) {
                            audioTrack?.write(buffer, 0, bytesRead)
                        } else if (bytesRead < 0) {
                            Log.e(TAG, "Error reading audio: $bytesRead")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio passthrough error: ${e.message}")
                    e.printStackTrace()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio passthrough: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Stop audio passthrough dan release resources
     */
    private fun stopAudioPassthrough() {
        try {
            isAudioPlaying = false
            
            audioThread?.let {
                it.shutdown()
                if (!it.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    it.shutdownNow()
                }
                audioThread = null
            }
            
            audioRecord?.let {
                try {
                    if (it.state == AudioRecord.STATE_INITIALIZED) {
                        it.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                }
                it.release()
                audioRecord = null
            }
            
            audioTrack?.let {
                try {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.stop()
                        it.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
                }
                it.release()
                audioTrack = null
            }
            
            Log.i(TAG, "Audio passthrough stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun rotatePreview() {
        // Increment rotation index (0 -> 1 -> 2 -> 3 -> 0)
        currentRotationIndex = (currentRotationIndex + 1) % rotationAngles.size
        
        Log.i(TAG, "Rotating preview to angle index: $currentRotationIndex")
        
        // Restart preview dengan rotasi baru - HARUS stop dulu sepenuhnya
        if (multiCameraClient != null) {
            // Stop audio terlebih dahulu
            stopAudioPassthrough()
            
            // Stop capture stream
            cameraClient?.captureStreamStop()
            
            // Close camera untuk reset sepenuhnya
            cameraClient?.closeCamera()
            cameraClient = null
            
            // Destroy multiCameraClient
            multiCameraClient?.unRegister()
            multiCameraClient?.destroy()
            multiCameraClient = null
            
            // Delay singkat untuk memastikan camera benar-benar tertutup
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "Re-initializing camera with new rotation...")
                startPreview()
            }, 300)
        }
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
        stopAudioPassthrough()
        stopPreview()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val allGranted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.i(TAG, "All permissions granted")
            } else {
                Toast.makeText(this, "Permission RECORD_AUDIO is required for audio passthrough", Toast.LENGTH_LONG).show()
            }
        }
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
