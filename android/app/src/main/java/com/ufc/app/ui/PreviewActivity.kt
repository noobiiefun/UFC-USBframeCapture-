package com.ufc.app.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.jiangdg.ausbc.UVCCameraHelper
import com.jiangdg.ausbc.widget.SizePickerDialog

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PreviewActivity : Activity(), UVCCameraHelper.OnMyViewConnectListener, UVCCameraHelper.OnMyCameraDataListener {

    private var mHelper: UVCCameraHelper? = null
    private var rootView: View? = null
    private var btnDetect: Button? = null
    private var btnRotate: Button? = null
    private var btnExit: Button? = null
    private var container: FrameLayout? = null
    
    // Audio variables
    private var audioThread: Thread? = null
    private var isAudioPlaying = false
    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen & Hide Notifikasi
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        setContentView(R.layout.activity_preview) // Pastikan layout ini ada

        rootView = findViewById(android.R.id.content)
        container = findViewById(R.id.preview_container) // Pastikan ID ini ada di layout XML
        btnDetect = findViewById(R.id.btn_detect_usb)
        btnRotate = findViewById(R.id.btn_rotate)
        btnExit = findViewById(R.id.btn_exit)

        mHelper = UVCCameraHelper.getInstance()
        mHelper?.setOnMyViewConnectListener(this)
        mHelper?.setOnCameraDataListener(this)

        // Setup Click Listeners
        btnDetect?.setOnClickListener {
            checkUsbPermissionAndConnect()
        }

        btnRotate?.setOnClickListener {
            rotatePreview()
        }

        btnExit?.setOnClickListener {
            stopAudio()
            mHelper?.unregisterUSB()
            mHelper?.closeCamera()
            finish()
        }

        // Tap to toggle controls
        rootView?.setOnClickListener {
            toggleControls()
        }
    }

    private fun checkUsbPermissionAndConnect() {
        if (mHelper == null) return
        
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        var device: UsbDevice? = null

        for (key in deviceList.keys) {
            val dev = deviceList[key]
            if (dev.vendorId == 0x05a3 && dev.productId == 0x9230) { // Generic UVC
                device = dev
                break
            }
            // Fallback: ambil device pertama jika tidak terdeteksi spesifik
            if (device == null) device = dev
        }

        if (device == null) {
            Toast.makeText(this, "Capture card tidak terdeteksi. Coba cabut-pasang.", Toast.LENGTH_LONG).show()
            return
        }

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent("ACTION_USB_PERMISSION"),
                PendingIntent.FLAG_IMMUTABLE
            )
            val filter = IntentFilter("ACTION_USB_PERMISSION")
            registerReceiver(mUsbReceiver, filter)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            startPreview(device)
        }
    }

    private val mUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ("ACTION_USB_PERMISSION" == action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { startPreview(it) }
                    } else {
                        Toast.makeText(context, "Izin USB ditolak", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startPreview(device: UsbDevice) {
        mHelper?.initUSBHost(device, rootView, container)
        mHelper?.setPreviewSize(1280, 720) // Default HD
        mHelper?.openCamera()
        
        // Delay sedikit agar video jalan dulu, baru audio nyala
        handler.postDelayed({
            startAudioPassthrough()
        }, 1000)
    }

    private fun rotatePreview() {
        container?.let { view ->
            val currentRotation = view.rotation
            val newRotation = (currentRotation + 90) % 360
            view.rotation = newRotation
            
            // Opsional: Adjust scale jika perlu agar full screen saat rotasi
            // Ini sederhana hanya memutar view
            Toast.makeText(this, "Rotasi: ${newRotation.toInt()}°", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleControls() {
        val isVisible = btnDetect?.visibility == View.VISIBLE
        if (isVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun hideControls() {
        btnDetect?.visibility = View.GONE
        btnRotate?.visibility = View.GONE
        btnExit?.visibility = View.GONE
    }

    private fun showControls() {
        btnDetect?.visibility = View.VISIBLE
        btnRotate?.visibility = View.VISIBLE
        btnExit?.visibility = View.VISIBLE
        
        // Reset timer auto-hide
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5000) // Hide setelah 5 detik
    }

    // --- AUDIO PASSTHROUGH LOGIC ---
    private fun startAudioPassthrough() {
        if (isAudioPlaying) return

        isAudioPlaying = true
        audioThread = Thread {
            var audioRecord: AudioRecord? = null
            var audioTrack: AudioTrack? = null
            try {
                val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val minPlayBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = maxOf(minRecBuf, minPlayBuf) * 2

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT, // Atau MIC jika DEFAULT gagal
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

                audioTrack = AudioTrack(
                    audioAttrs,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    0
                )

                if (audioRecord.state == AudioRecord.STATE_INITIALIZED && 
                    audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    
                    audioTrack.play()
                    audioRecord.startRecording()

                    val buffer = ByteArray(bufferSize)
                    while (isAudioPlaying) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            audioTrack.write(buffer, 0, read)
                        }
                    }
                } else {
                    Log.e("Audio", "Gagal inisialisasi AudioRecord atau AudioTrack")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("Audio", "Error audio thread: ${e.message}")
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioTrack?.stop()
                audioTrack?.release()
            }
        }
        audioThread?.start()
    }

    private fun stopAudio() {
        isAudioPlaying = false
        audioThread?.join(1000)
        audioThread = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        unregisterReceiver(mUsbReceiver)
        mHelper?.unregisterUSB()
        mHelper?.closeCamera()
        mHelper?.release()
    }

    // --- Interface Callbacks (Wajib ada meski kosong) ---
    override fun onAttachDev(isSuccess: Boolean) {}
    override fun onDettachDev() {}
    override fun onConnectDev(isSuccess: Boolean) {}
    override fun onDisconnectDev() {}
    
    override fun onFrameData(data: ByteBuffer?, width: Int, height: Int, format: Int) {
        // Data frame diterima oleh library secara otomatis ke SurfaceView
    }
}