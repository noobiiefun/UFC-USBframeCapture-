package com.ufc.app

import com.ufc.app.model.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sumber kebenaran tunggal untuk status stream terkini.
 *
 * - UvcCaptureManager & RtmpPusher menulis ke sini lewat update().
 * - StatusServer (HTTP) & UI membaca dari sini lewat StateFlow.
 *
 * Sengaja dibuat singleton object sederhana (bukan lewat DI) supaya
 * skeleton ini mudah dibaca dulu; boleh diganti ke Hilt/Koin nanti.
 */
object StatusRepository {

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val startTimeMs = System.currentTimeMillis()
    private var lastTxBytes: Long = 0
    private var lastRxBytes: Long = 0
    private var lastUpdateTime: Long = 0

    fun update(transform: (StreamStatus) -> StreamStatus) {
        _status.value = transform(_status.value)
    }

    /**
     * Memperbarui statistik jaringan berdasarkan TrafficStats.
     * Dipanggil secara periodik (misal tiap 1 detik) oleh Service.
     */
    fun updateNetworkStats(txBytes: Long, rxBytes: Long) {
        val now = System.currentTimeMillis()
        val deltaMs = now - lastUpdateTime
        if (deltaMs <= 0) return

        if (lastUpdateTime > 0) {
            val uploadKbps = ((txBytes - lastTxBytes) * 8 / deltaMs).toInt()
            val downloadKbps = ((rxBytes - lastRxBytes) * 8 / deltaMs).toInt()
            update { it.copy(uploadSpeedKbps = uploadKbps, downloadSpeedKbps = downloadKbps) }
        }

        lastTxBytes = txBytes
        lastRxBytes = rxBytes
        lastUpdateTime = now
    }

    fun currentUptimeSec(): Long = (System.currentTimeMillis() - startTimeMs) / 1000
}
