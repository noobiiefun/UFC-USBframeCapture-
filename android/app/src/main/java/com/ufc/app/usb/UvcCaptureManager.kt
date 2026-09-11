package com.ufc.app.usb

import android.content.Context
import android.view.TextureView
import com.ufc.app.StatusRepository

/**
 * Membungkus library UVC pihak ketiga (AndroidUSBCamera / UVCCamera) supaya
 * bagian atas aplikasi (MainActivity, RtmpPusher) tidak perlu tahu detail
 * API library yang dipakai — kalau nanti ganti library, cukup ubah file ini.
 *
 * TODO: sesuaikan pemanggilan method di bawah dengan API resmi library
 * yang dipakai (lihat docs/SETUP.md bagian 1.2). Struktur & nama method
 * di sini adalah PLACEHOLDER yang menggambarkan alur logika yang diinginkan.
 */
class UvcCaptureManager(private val context: Context) {

    private var isOpen = false

    /**
     * Panggil saat activity onCreate/onResume, setelah TextureView siap.
     * previewView dipakai library untuk menampilkan preview mentah dari
     * capture card.
     */
    fun attachPreview(previewView: TextureView) {
        // TODO: cameraHelper.setPreviewView(previewView) — sesuai API library
    }

    /**
     * Dipanggil saat sistem mengirim broadcast USB_DEVICE_ATTACHED, atau
     * saat user menekan tombol "Connect" di UI.
     */
    fun requestPermissionAndOpen(onOpened: () -> Unit, onError: (Throwable) -> Unit) {
        // TODO:
        // 1. cek daftar UVC device yang terhubung (cameraHelper.getUsbDeviceList())
        // 2. minta permission USB kalau belum ada
        // 3. buka device (cameraHelper.openCamera(device))
        // 4. set callback frame availability
        //
        // Contoh alur (pseudo):
        // cameraHelper.setOnPreviewFrameListener { frame ->
        //     rtmpPusher?.onRawFrame(frame)
        // }
        // cameraHelper.setOnDeviceConnectListener(object : OnMyDevConnectListener {
        //     override fun onConnectDev() {
        //         isOpen = true
        //         StatusRepository.update { it.copy(connected = true) }
        //         onOpened()
        //     }
        //     override fun onDisConnectDev() {
        //         isOpen = false
        //         StatusRepository.update { it.copy(connected = false) }
        //     }
        // })
    }

    fun close() {
        // TODO: cameraHelper.release()
        isOpen = false
        StatusRepository.update { it.copy(connected = false) }
    }

    fun isConnected(): Boolean = isOpen
}
