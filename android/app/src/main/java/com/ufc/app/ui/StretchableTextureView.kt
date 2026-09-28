package com.ufc.app.ui

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import com.jiangdg.ausbc.widget.IAspectRatio

/**
 * View preview yang bisa "stretch" (penuhi layar, gambar boleh gepeng)
 * atau "biasa" (jaga rasio aspek, letterbox).
 *
 * Ini meniru API [com.jiangdg.ausbc.widget.AspectRatioTextureView] (interface
 * IAspectRatio) supaya bisa dipakai sebagai pengganti langsung di
 * UfcCameraFragment/PreviewActivity. Bedanya:
 *  - stretch = false : ukuran view dikunci ke rasio video (letterbox), sama
 *    seperti AspectRatioTextureView bawaan library.
 *  - stretch = true  : view selalu memenuhi parent (match_parent), lalu isi
 *    texture di-stretch lewat setTransform(Matrix) sehingga memenuhi layar
 *    penuh tanpa bar hitam (rasio bisa berubah/gepeng).
 */
class StretchableTextureView(context: Context) : TextureView(context), IAspectRatio {

    private var videoWidth = 0
    private var videoHeight = 0
    private var isStretch = false
    private val transformMatrix = Matrix()

    override fun setAspectRatio(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        requestLayout()
        applyTransform()
    }

    /** Panggil saat user mengganti pilihan stretch/biasa (tanpa buka ulang kamera). */
    fun setStretch(stretch: Boolean) {
        if (isStretch == stretch) return
        isStretch = stretch
        requestLayout()
        applyTransform()
    }

    override fun getSurfaceWidth(): Int = videoWidth

    override fun getSurfaceHeight(): Int = videoHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isStretch && videoWidth > 0 && videoHeight > 0) {
            // Mode "biasa": kunci rasio aspek video, muat sekecil-kecilnya di dalam parent
            val wMode = MeasureSpec.getMode(widthMeasureSpec)
            val hMode = MeasureSpec.getMode(heightMeasureSpec)
            val wSize = MeasureSpec.getSize(widthMeasureSpec)
            val hSize = MeasureSpec.getSize(heightMeasureSpec)
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()

            var w = wSize
            var h = hSize
            if (wMode == MeasureSpec.EXACTLY && hMode != MeasureSpec.EXACTLY) {
                h = (w / ratio).toInt().coerceAtMost(hSize)
            } else if (hMode == MeasureSpec.EXACTLY && wMode != MeasureSpec.EXACTLY) {
                w = (h * ratio).toInt().coerceAtMost(wSize)
            } else if (wMode != MeasureSpec.EXACTLY && hMode != MeasureSpec.EXACTLY) {
                w = videoWidth
                h = videoHeight
            } else {
                // dua-duanya EXACTLY (parent FrameLayout match_parent): pilih fit di dalam box
                if (wSize > hSize * ratio) w = (hSize * ratio).toInt() else h = (wSize / ratio).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        } else {
            // Mode stretch: ikut parent apa adanya (biasanya match_parent)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    private fun applyTransform() {
        if (!isStretch) {
            // kembalikan ke tampilan normal (texture mengikuti ukuran view = rasio asli)
            setTransform(Matrix())
            return
        }
        val vw = width
        val vh = height
        if (vw == 0 || vh == 0) return
        // Regangkan konten texture agar penuh mengisi view (gepeng kalau rasio beda)
        transformMatrix.reset()
        transformMatrix.postScale(vw.toFloat(), vh.toFloat())
        setTransform(transformMatrix)
    }
}
