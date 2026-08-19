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

    fun update(transform: (StreamStatus) -> StreamStatus) {
        _status.value = transform(_status.value)
    }

    fun currentUptimeSec(): Long = (System.currentTimeMillis() - startTimeMs) / 1000
}
