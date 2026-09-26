package com.castlevania.nes

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class NesGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer: NesRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = NesRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
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
