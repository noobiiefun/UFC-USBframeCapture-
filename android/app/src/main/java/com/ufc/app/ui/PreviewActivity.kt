package com.ufc.app.ui

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
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
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import kotlinx.coroutines.launch

/**
 * Activity khusus untuk Preview Mode - berfungsi sebagai monitor/layar tambahan.
 * Menampilkan video & audio langsung dari capture card tanpa encoding/streaming.
 * Modul terpisah: saat preview aktif, fungsi lain off (kecuali hide notifikasi).
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        private const val AUTO_HIDE_DELAY_MS = 3000L
    }

    private lateinit var config: StreamConfig
    private var previewView: AspectRatioTextureView? = null
    private var multiCameraClient: MultiCameraClient? = null
    private var cameraClient: MultiCameraClient.ICamera? = null
    
    // UI Components
    private var controlsView: View? = null
    private var textStatus: TextView? = null
    private var btnDetectUsb: Button? = null
    private var btnStop: Button? = null
    
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
                                Log.i(TAG, "Camera opened - starting preview")
                                StatusRepository.update { it.copy(connected = true) }
                                
                                // Mulai preview
                                cameraClient?.captureStreamStart()
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
