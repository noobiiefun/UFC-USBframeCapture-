package com.ufc.app.ui

import android.os.Bundle
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ufc.app.R
import com.ufc.app.StatusRepository
import com.ufc.app.server.DiscoveryBroadcaster
import com.ufc.app.server.StatusServer
import com.ufc.app.stream.RtmpPusher
import com.ufc.app.usb.UvcCaptureManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var captureManager: UvcCaptureManager
    private lateinit var rtmpPusher: RtmpPusher
    private var statusServer: StatusServer? = null
    private var discoveryBroadcaster: DiscoveryBroadcaster? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        captureManager = UvcCaptureManager(this)
        rtmpPusher = RtmpPusher()

        val previewView = findViewById<TextureView>(R.id.previewView)
        val statusText = findViewById<TextView>(R.id.statusText)
        val connectButton = findViewById<Button>(R.id.btnConnect)
        val startStreamButton = findViewById<Button>(R.id.btnStartStream)
        val stopStreamButton = findViewById<Button>(R.id.btnStopStream)

        captureManager.attachPreview(previewView)

        connectButton.setOnClickListener {
            captureManager.requestPermissionAndOpen(
                onOpened = { /* update UI kalau perlu */ },
                onError = { e -> statusText.text = "Gagal connect: ${e.message}" }
            )
        }

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

        // Tampilkan status secara live di layar HP juga (opsional, buat debugging)
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
        statusServer = StatusServer().apply { start(NanoHTTPD_TIMEOUT_MS, false) }
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
        captureManager.close()
    }

    companion object {
        // NanoHTTPD start() minta timeout (ms) untuk socket; 5 detik cukup untuk polling ringan.
        private const val NanoHTTPD_TIMEOUT_MS = 5000
    }
}
