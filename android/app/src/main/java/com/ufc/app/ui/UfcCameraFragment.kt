package com.ufc.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.ufc.app.StatusRepository
import com.ufc.app.stream.RtmpPusher

/**
 * Fragment inti yang menangani capture UVC, mengikuti pola resmi library
 * AndroidUSBCamera (lihat docs/SETUP.md 1.2). Semua callback di sini
 * SUDAH sesuai API publik yang didokumentasikan di README resmi library.
 *
 * Bagian streaming (addEncodeDataCallBack -> RtmpPusher) memakai method
 * yang didokumentasikan ("acquire encode data(H.264 or AAC)"), tapi nama
 * parameter callback persisnya perlu dicek ulang begitu Android Studio
 * selesai sync (klik kanan -> Go to Declaration di addEncodeDataCallBack).
 */
class UfcCameraFragment : CameraFragment() {

    private lateinit var previewView: AspectRatioTextureView
    private lateinit var container: FrameLayout

    var rtmpPusher: RtmpPusher? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = FrameLayout(requireContext())
        previewView = AspectRatioTextureView(requireContext())
        this.container = FrameLayout(requireContext())
        this.container.addView(previewView)
        root.addView(this.container)
        return root
    }

    override fun getCameraView(): IAspectRatio = previewView

    override fun getCameraViewContainer(): ViewGroup = container

    // Konfigurasi resolusi/format capture. Nilai default dipilih untuk
    // device kelas entry-level (Helio G36 / RAM 3-4GB) — lihat README.md.
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG) // MJPEG direkomendasikan library utk performa
            .setAspectRatioShow(true)
            .create()
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.callback.ICameraStateCallBack.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                StatusRepository.update { it.copy(connected = true) }
                // Mulai ambil stream H.264/AAC yang sudah di-encode library,
                // lalu salurkan ke RtmpPusher lewat addEncodeDataCallBack.
                captureStreamStart()
            }
            ICameraStateCallBack.State.CLOSED -> {
                StatusRepository.update { it.copy(connected = false) }
                captureStreamStop()
            }
            ICameraStateCallBack.State.ERROR -> {
                StatusRepository.update { it.copy(connected = false) }
            }
        }
    }

    // TODO: signature callback persis (nama class/parameter) perlu dicek di
    // source library setelah Gradle sync — README hanya menyebut method ini
    // ada, tanpa contoh signature lengkap. Intinya: setiap kali ada frame
    // H.264/AAC baru, teruskan byte-nya ke RtmpPusher.onEncodedFrame(...).
    //
    // override fun initEncodeDataCallBack() {
    //     addEncodeDataCallBack(object : IEncodeDataCallBack {
    //         override fun onEncodeData(type: IEncodeDataCallBack.DataType, buffer: ByteBuffer, ...) {
    //             rtmpPusher?.onEncodedFrame(buffer, type == IEncodeDataCallBack.DataType.VIDEO)
    //         }
    //     })
    // }
}
