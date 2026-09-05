package com.ufc.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import com.ufc.app.server.DiscoveryBroadcaster
import com.ufc.app.server.StatusServer
import com.ufc.app.stream.RtmpPusher
import com.ufc.app.stream.StreamService
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private val rtmpPusher = RtmpPusher()
    private var statusServer: StatusServer? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null
    private lateinit var cameraFragment: UfcCameraFragment
    private lateinit var config: StreamConfig

    private var dX = 0f
    private var dY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Jaga layar tetap menyala
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Crash Handler Pintar
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("UfcCrash", "Fatal crash in thread ${thread.name}: ${throwable.message}")
            throwable.printStackTrace()
            // Tunjukkan Toast lewat handler UI jika memungkinkan
            android.os.Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Crash Fatal: ${throwable.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        config = StreamConfig(this)
        setContentView(R.layout.activity_main)

        // Terapkan orientasi awal tanpa memicu recreate loop jika memungkinkan
        val targetOrient = if (config.isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (requestedOrientation != targetOrient) {
            requestedOrientation = targetOrient
        }

        cameraFragment = UfcCameraFragment().apply { rtmpPusher = this@MainActivity.rtmpPusher }
        supportFragmentManager.beginTransaction()
            .replace(R.id.cameraFragmentContainer, cameraFragment)
            .commit()

        val statusText = findViewById<TextView>(R.id.statusText)
        val overlayText = findViewById<TextView>(R.id.overlayText)
        val draggableOverlay = findViewById<View>(R.id.draggableOverlay)
        val startStreamButton = findViewById<Button>(R.id.btnStartStream)
        val stopStreamButton = findViewById<Button>(R.id.btnStopStream)
        val settingsButton = findViewById<Button>(R.id.btnSettings)
        val rotateButton = findViewById<Button>(R.id.btnRotate)
        val usbButton = findViewById<Button>(R.id.btnUsb)

        // Drag logic for overlay
        draggableOverlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                }
            }
            true
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        rotateButton.setOnClickListener {
            config.isPortrait = !config.isPortrait
            requestedOrientation = if (config.isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        usbButton.setOnClickListener {
            if (checkPermissions()) {
                cameraFragment.showDeviceListDialog()
            } else {
                requestPermissions()
            }
        }

        startStreamButton.setOnClickListener {
            val status = checkPermissionsDetail()
            if (!status.allGranted) {
                Toast.makeText(this, "Izin kurang: ${status.missing}", Toast.LENGTH_LONG).show()
                requestPermissions()
                return@setOnClickListener
            }

            val streamUrl = config.fullUrl
            if (streamUrl.length < 10) {
                // Toast atau arahkan ke setting
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }

            var finalWidth = config.resolutionWidth
            var finalHeight = config.resolutionHeight
            if (config.isPortrait) {
                // Swap jika portrait
                if (finalWidth > finalHeight) {
                    val temp = finalWidth
                    finalWidth = finalHeight
                    finalHeight = temp
                }
            } else {
                // Pastikan landscape
                if (finalHeight > finalWidth) {
                    val temp = finalWidth
                    finalWidth = finalHeight
                    finalHeight = temp
                }
            }

            rtmpPusher.configure(
                RtmpPusher.Config(
                    width = finalWidth,
                    height = finalHeight,
                    fps = config.fps,
                    videoBitrateKbps = config.bitrateKbps,
                    rtmpUrl = streamUrl
                )
            )
            rtmpPusher.start(this)
            cameraFragment.startEncoding()
            startService(Intent(this, StreamService::class.java))
            Toast.makeText(this, "Menghubungkan ke YouTube...", Toast.LENGTH_SHORT).show()
        }

        stopStreamButton.setOnClickListener {
            rtmpPusher.stop()
            cameraFragment.stopEncoding()
            stopService(Intent(this, StreamService::class.java))
        }

        lifecycleScope.launch {
            StatusRepository.status.collect { status ->
                val info = buildString {
                    append("UVC: ${if (status.connected) "ON" else "OFF"} | ")
                    append("YT: ${if (status.youtubeConnected) "LIVE" else "OFF"}\n")
                    append("${status.resolution} @ ${status.fps}fps | ${status.bitrateKbps}kbps\n")
                    append("UP: ${status.uploadSpeedKbps} kbps | DOWN: ${status.downloadSpeedKbps} kbps\n")
                    append("Uptime: ${status.uptimeSec}s")
                }
                statusText.text = info
                overlayText.text = info

                // Tombol Start berubah jadi Stop begitu berhasil live,
                // dan balik lagi begitu stream berhenti/putus
                startStreamButton.visibility = if (status.youtubeConnected) View.GONE else View.VISIBLE
                stopStreamButton.visibility = if (status.youtubeConnected) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        statusServer = StatusServer().apply { start(SOCKET_TIMEOUT_MS, false) }
        discoveryBroadcaster = DiscoveryBroadcaster(httpPort = StatusServer.DEFAULT_PORT)
            .also { it.start(lifecycleScope) }
    }

    override fun onStop() {
        super.onStop()
        discoveryBroadcaster?.stop()
        statusServer?.stop()
        statusServer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        rtmpPusher.stop()
        stopService(Intent(this, StreamService::class.java))
    }

    data class PermissionStatus(val allGranted: Boolean, val missing: String)

    private fun checkPermissionsDetail(): PermissionStatus {
        val missing = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add("Kamera")
        }
        
        // Hanya minta Mic jika audio dinyalakan
        if (config.monitorAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add("Microphone")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add("Notifikasi")
            }
        }
        
        return PermissionStatus(missing.isEmpty(), missing.joinToString(", "))
    }

    private fun checkPermissions(): Boolean = checkPermissionsDetail().allGranted

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERM_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions Denied! App may not work correctly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val PERM_CODE = 101
    }
}
