package com.ufc.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.server.DiscoveryBroadcaster
import com.ufc.app.server.StatusServer
import com.ufc.app.stream.RtmpPusher
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val rtmpPusher = RtmpPusher()
    private var statusServer: StatusServer? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null
    private lateinit var cameraFragment: UfcCameraFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraFragment = UfcCameraFragment().apply { rtmpPusher = this@MainActivity.rtmpPusher }
        supportFragmentManager.beginTransaction()
            .replace(R.id.cameraFragmentContainer, cameraFragment)
            .commit()

        val statusText = findViewById<TextView>(R.id.statusText)
        val startStreamButton = findViewById<Button>(R.id.btnStartStream)
        val stopStreamButton = findViewById<Button>(R.id.btnStopStream)

        startStreamButton.setOnClickListener {
            // TODO: ambil rtmpUrl dari input user / SharedPreferences, jangan hardcode di produksi
            rtmpPusher.configure(
                RtmpPusher.Config(
                    rtmpUrl = "rtmp://a.rtmp.youtube.com/live2/PASTE_STREAM_KEY_DI_SINI"
                )
            )
            rtmpPusher.start()
        }

        stopStreamButton.setOnClickListener {
            rtmpPusher.stop()
        }

        lifecycleScope.launch {
            StatusRepository.status.collect { status ->
                statusText.text = buildString {
                    append("UVC: ${if (status.connected) "connected" else "-"}\n")
                    append("YouTube: ${if (status.youtubeConnected) "connected" else "-"}\n")
                    append("Res: ${status.resolution} @ ${status.fps}fps\n")
                    append("Bitrate: ${status.bitrateKbps} kbps\n")
                    append("Dropped: ${status.droppedFrames}\n")
                    append("Uptime: ${status.uptimeSec}s")
                }
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
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
    }
}
