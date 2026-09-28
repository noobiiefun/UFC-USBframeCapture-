package com.ufc.app.ui

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/**
 * Penghitung FPS aktual dari frame yang benar-benar sampai ke aplikasi
 * (bukan angka setting). Dipakai di overlay MainActivity & PreviewActivity.
 *
 * Cara pakai:
 *  - panggil [tick] tiap frame masuk (dari callback preview)
 *  - [start] untuk mulai memperbarui TextView tiap detik
 *  - [stop] saat activity/fragment mati
 */
class FpsCounter {

    private val frameCount = AtomicLong(0)

    @Volatile
    var lastFps: Int = 0
        private set

    private var handler: Handler? = null
    private var textView: TextView? = null
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            // ambil & reset jumlah frame dalam 1 detik terakhir
            val frames = frameCount.getAndSet(0L)
            lastFps = frames.toInt()
            textView?.text = "FPS: $lastFps"
            if (running) handler?.postDelayed(this, 1000L)
        }
    }

    /** Dipanggil dari thread manapun — aman (atomic). */
    fun tick() {
        frameCount.incrementAndGet()
    }

    fun start(tv: TextView?) {
        stop()
        textView = tv
        handler = Handler(Looper.getMainLooper())
        running = true
        handler?.post(ticker)
    }

    fun stop() {
        running = false
        handler?.removeCallbacks(ticker)
        handler = null
        textView = null
    }
}
