package com.ufc.app.server

import android.os.Build
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Broadcast UDP periodik supaya PC Monitor bisa auto-discover IP HP di
 * jaringan lokal, tanpa perlu input manual. Lihat docs/API.md untuk
 * format pesan.
 *
 * Pendekatan UDP broadcast dipilih untuk versi awal karena simpel & tanpa
 * dependency tambahan. Bisa diganti NsdManager (mDNS) belakangan.
 */
class DiscoveryBroadcaster(
    private val httpPort: Int,
    private val udpPort: Int = DEFAULT_UDP_PORT,
    private val deviceName: String = Build.MODEL ?: "Android"
) {
    companion object {
        const val DEFAULT_UDP_PORT = 8888
        private const val INTERVAL_MS = 2000L
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket().apply { broadcast = true }
            val message = "UFC_MONITOR|${localIpOrPlaceholder()}|$httpPort|$deviceName"
            val data = message.toByteArray()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")

            try {
                while (isActive) {
                    val packet = DatagramPacket(data, data.size, broadcastAddress, udpPort)
                    socket.send(packet)
                    delay(INTERVAL_MS)
                }
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun localIpOrPlaceholder(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (inter in interfaces) {
                val addrs = inter.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
