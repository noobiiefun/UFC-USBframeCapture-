package com.ufc.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import com.ufc.app.server.DiscoveryBroadcaster
import com.ufc.app.server.StatusServer
import com.ufc.app.stream.RtmpPusher
import com.ufc.app.stream.StreamService
import kotlinx.coroutines.launch

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = StreamConfig(this)
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

        startStreamButton.setOnClickListener {
            val streamUrl = config.fullUrl
            if (streamUrl.length < 10) {
                // Toast atau arahkan ke setting
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }

            rtmpPusher.configure(
                RtmpPusher.Config(
                    width = config.resolutionWidth,
                    height = config.resolutionHeight,
                    fps = config.fps,
                    videoBitrateKbps = config.bitrateKbps,
                    rtmpUrl = streamUrl
                )
            )
            rtmpPusher.start(this)
            startService(Intent(this, StreamService::class.java))
        }

        stopStreamButton.setOnClickListener {
            rtmpPusher.stop()
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

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
    }
}
