package com.castlevania.nes

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder

class NesGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer: NesRenderer
    private var targetRefreshRate: Float = 120.0f

    init {
        setEGLContextClientVersion(2)
        renderer = NesRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // Hook surface lifecycle to ensure 120Hz VSync is enforced
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                applySurfaceRefreshRate(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                applySurfaceRefreshRate(holder)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyViewRefreshRate()
    }

    fun setTargetRefreshRate(hz: Float) {
        targetRefreshRate = hz
        applySurfaceRefreshRate(holder)
        applyViewRefreshRate()
    }

    private fun applySurfaceRefreshRate(surfaceHolder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                surfaceHolder.surface?.let { surface ->
                    if (surface.isValid) {
                        surface.setFrameRate(targetRefreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun applyViewRefreshRate() {
        try {
            val method = android.view.View::class.java.getMethod(
                "setFrameRate",
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(this, targetRefreshRate, 0)
        } catch (ignored: Exception) {}
    }

    fun setFilterMode(mode: Int) {
        renderer.filterMode = mode
    }

    fun setSmoothFilter(smooth: Boolean) {
        renderer.isSmoothFilter = smooth
    }

    fun setStretchToScreen(stretch: Boolean) {
        renderer.isStretchToScreen = stretch
    }
}
