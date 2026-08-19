package com.ufc.app.server

import com.ufc.app.StatusRepository
import fi.iki.elonen.NanoHTTPD

/**
 * HTTP server ringan yang HANYA expose GET /status (read-only).
 * Berjalan di thread miliknya sendiri (bawaan NanoHTTPD) — sengaja
 * dipisah total dari pipeline capture/encode/push supaya polling dari
 * PC Monitor tidak pernah mengganggu proses live streaming.
 *
 * Lihat docs/API.md untuk skema JSON responsenya.
 */
class StatusServer(port: Int = DEFAULT_PORT) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/status" -> {
                val json = StatusRepository.status.value.toJson()
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Not found. Coba GET /status"
            )
        }
    }
}
