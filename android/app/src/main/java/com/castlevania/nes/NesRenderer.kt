package com.castlevania.nes

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class NesRenderer : GLSurfaceView.Renderer {

    companion object {
        const val FILTER_MODE_NORMAL = 0
        const val FILTER_MODE_FIDELITYFX = 1
        const val FILTER_MODE_IA_XBR = 2
        const val FILTER_MODE_CRT = 3
        const val FILTER_MODE_BILINEAR = 4

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Mode 0 & 4: Normal Pixel Art / Bilinear passthrough
        private const val FRAGMENT_SHADER_NORMAL = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                // Mesen buffer is 0xAARRGGBB (Little-Endian BGRA in memory)
                gl_FragColor = vec4(c.b, c.g, c.r, 1.0);
            }
        """

        // Mode 1: AMD FidelityFX™ FSR / CAS (Contrast Adaptive Sharpening & Vibrance)
        private const val FRAGMENT_SHADER_FIDELITYFX = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uOutputSize;

            vec3 sampleRGB(vec2 uv) {
                vec4 c = texture2D(uTexture, uv);
                return vec3(c.b, c.g, c.r);
            }

            void main() {
                vec2 step = 1.0 / max(uOutputSize, vec2(1.0, 1.0));

                // 3x3 kernel samples
                vec3 a = sampleRGB(vTexCoord + vec2(-step.x, -step.y));
                vec3 b = sampleRGB(vTexCoord + vec2(0.0, -step.y));
                vec3 c = sampleRGB(vTexCoord + vec2(step.x, -step.y));
                vec3 d = sampleRGB(vTexCoord + vec2(-step.x, 0.0));
                vec3 e = sampleRGB(vTexCoord);
                vec3 f = sampleRGB(vTexCoord + vec2(step.x, 0.0));
                vec3 g = sampleRGB(vTexCoord + vec2(-step.x, step.y));
                vec3 h = sampleRGB(vTexCoord + vec2(0.0, step.y));
                vec3 i = sampleRGB(vTexCoord + vec2(step.x, step.y));

                // Min / Max local contrast bounds
                vec3 minCross = min(min(b, d), min(f, h));
                vec3 minDiag  = min(min(a, c), min(g, i));
                vec3 minRGB   = min(min(minCross, minDiag), e);

                vec3 maxCross = max(max(b, d), max(f, h));
                vec3 maxDiag  = max(max(a, c), max(g, i));
                vec3 maxRGB   = max(max(maxCross, maxDiag), e);

                // AMD FidelityFX CAS adaptive algorithm
                vec3 amp = clamp(min(minRGB, vec3(2.0) - maxRGB) / max(maxRGB, vec3(0.001)), 0.0, 1.0);
                vec3 w = sqrt(amp) * -0.22;

                vec3 sharp = (b + d + f + h) * w + e;
                sharp = sharp / (vec3(1.0) + vec3(4.0) * w);
                sharp = clamp(sharp, minRGB, maxRGB);

                // Contrast & Vibrance pop for modern displays
                float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
                sharp = mix(vec3(luma), sharp, 1.12);
                sharp = pow(max(sharp, vec3(0.0)), vec3(0.96));

                gl_FragColor = vec4(sharp, 1.0);
            }
        """

        // Mode 2: IA Upscale (xBR Smart Corner Reconstruction)
        private const val FRAGMENT_SHADER_XBR = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;

            vec3 sampleRGB(vec2 uv) {
                vec4 c = texture2D(uTexture, uv);
                return vec3(c.b, c.g, c.r);
            }

            float colorDist(vec3 c1, vec3 c2) {
                vec3 diff = c1 - c2;
                return dot(diff, diff);
            }

            void main() {
                vec2 pos = vTexCoord * uTextureSize;
                vec2 f = fract(pos);
                vec2 centerUV = (floor(pos) + 0.5) / uTextureSize;
                vec2 step = 1.0 / uTextureSize;

                vec3 e = sampleRGB(centerUV);
                vec3 u = sampleRGB(centerUV - vec2(0.0, step.y));
                vec3 d = sampleRGB(centerUV + vec2(0.0, step.y));
                vec3 l = sampleRGB(centerUV - vec2(step.x, 0.0));
                vec3 r = sampleRGB(centerUV + vec2(step.x, 0.0));

                vec3 ul = sampleRGB(centerUV - step);
                vec3 ur = sampleRGB(centerUV + vec2(step.x, -step.y));
                vec3 dl = sampleRGB(centerUV + vec2(-step.x, step.y));
                vec3 dr = sampleRGB(centerUV + step);

                vec3 result = e;

                if (f.x > 0.5 && f.y < 0.5) {
                    float d1 = colorDist(u, r);
                    float d2 = colorDist(e, ur);
                    if (d1 < d2 && colorDist(e, u) > 0.03) {
                        float t = smoothstep(0.0, 0.6, f.x - f.y);
                        result = mix(e, (u + r) * 0.5, t);
                    }
                } else if (f.x < 0.5 && f.y > 0.5) {
                    float d1 = colorDist(d, l);
                    float d2 = colorDist(e, dl);
                    if (d1 < d2 && colorDist(e, d) > 0.03) {
                        float t = smoothstep(0.0, 0.6, f.y - f.x);
                        result = mix(e, (d + l) * 0.5, t);
                    }
                } else if (f.x < 0.5 && f.y < 0.5) {
                    float d1 = colorDist(u, l);
                    float d2 = colorDist(e, ul);
                    if (d1 < d2 && colorDist(e, u) > 0.03) {
                        float t = smoothstep(0.0, 0.6, 1.0 - f.x - f.y);
                        result = mix(e, (u + l) * 0.5, t);
                    }
                } else {
                    float d1 = colorDist(d, r);
                    float d2 = colorDist(e, dr);
                    if (d1 < d2 && colorDist(e, d) > 0.03) {
                        float t = smoothstep(0.0, 0.6, f.x + f.y - 1.0);
                        result = mix(e, (d + r) * 0.5, t);
                    }
                }

                gl_FragColor = vec4(result, 1.0);
            }
        """

        // Mode 3: CRT Arcade Realista (Scanlines + Trinitron RGB Mask + Warm Bloom)
        private const val FRAGMENT_SHADER_CRT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;

            vec3 sampleRGB(vec2 uv) {
                vec4 c = texture2D(uTexture, uv);
                return vec3(c.b, c.g, c.r);
            }

            vec2 crtDistort(vec2 uv) {
                vec2 cc = (uv - 0.5) * 2.0;
                float r2 = dot(cc, cc);
                cc *= 1.0 + r2 * 0.04;
                return cc * 0.5 + 0.5;
            }

            void main() {
                vec2 uv = crtDistort(vTexCoord);

                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                vec3 color = sampleRGB(uv);

                // 1. Scanlines
                float scanline = sin(uv.y * uTextureSize.y * 3.14159265 * 2.0);
                scanline = 0.5 + 0.5 * scanline;
                float scanIntensity = mix(0.72, 1.10, pow(scanline, 1.4));
                color *= scanIntensity;

                // 2. Phosphor Mask (Trinitron RGB aperture grille)
                float maskX = mod(gl_FragCoord.x, 3.0);
                vec3 mask = vec3(0.88);
                if (maskX < 1.0) {
                    mask = vec3(1.22, 0.88, 0.88);
                } else if (maskX < 2.0) {
                    mask = vec3(0.88, 1.22, 0.88);
                } else {
                    mask = vec3(0.88, 0.88, 1.22);
                }
                color *= mask;

                // 3. Phosphor Bloom / Warmth
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                color += vec3(luma * 0.07);

                // 4. Subtle Vignette
                vec2 vig = uv * (1.0 - uv);
                float vignette = clamp(pow(vig.x * vig.y * 15.0, 0.25), 0.0, 1.0);
                color *= vignette;

                gl_FragColor = vec4(color, 1.0);
            }
        """
    }

    private class ShaderProgram(
        val program: Int,
        val posHandle: Int,
        val texCoordHandle: Int,
        val textureUniformHandle: Int,
        val textureSizeUniformHandle: Int,
        val outputSizeUniformHandle: Int
    )

    private var progNormal: ShaderProgram? = null
    private var progFidelityFx: ShaderProgram? = null
    private var progXbr: ShaderProgram? = null
    private var progCrt: ShaderProgram? = null

    private var textureId: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    var filterMode: Int = FILTER_MODE_NORMAL
    var isStretchToScreen: Boolean = false

    // Backwards compatibility property
    var isSmoothFilter: Boolean
        get() = filterMode == FILTER_MODE_BILINEAR
        set(value) {
            filterMode = if (value) FILTER_MODE_BILINEAR else FILTER_MODE_NORMAL
        }

    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )

    // Invert Y coordinate for standard texture display
    private val texCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertexCoords)
            position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(texCoords)
            position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        progNormal = buildShaderProgram(FRAGMENT_SHADER_NORMAL)
        progFidelityFx = buildShaderProgram(FRAGMENT_SHADER_FIDELITYFX)
        progXbr = buildShaderProgram(FRAGMENT_SHADER_XBR)
        progCrt = buildShaderProgram(FRAGMENT_SHADER_CRT)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        Log.i("NesRenderer", "GL Surface created, textureId=$textureId")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        Log.i("NesRenderer", "Surface changed: ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear whole surface
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Upload latest emulation frame to GPU (native bridge handles allocation & upload)
        NativeBridge.nativeRender(textureId)

        // Texture filtering mode
        val texFilter = when (filterMode) {
            FILTER_MODE_FIDELITYFX, FILTER_MODE_CRT, FILTER_MODE_BILINEAR -> GLES20.GL_LINEAR
            else -> GLES20.GL_NEAREST
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, texFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, texFilter)

        val frameW = NativeBridge.nativeGetFrameWidth().coerceAtLeast(256)
        val frameH = NativeBridge.nativeGetFrameHeight().coerceAtLeast(240)

        val vpW: Int
        val vpH: Int
        val vpX: Int
        val vpY: Int

        if (isStretchToScreen) {
            vpX = 0
            vpY = 0
            vpW = screenWidth
            vpH = screenHeight
        } else {
            // Maintain 4:3 NES aspect ratio pillarboxed/letterboxed
            val targetAspect = 4.0f / 3.0f
            val windowAspect = if (screenHeight > 0) screenWidth.toFloat() / screenHeight.toFloat() else targetAspect

            if (windowAspect > targetAspect) {
                // Screen is wider than 4:3 (pillarbox)
                vpH = screenHeight
                vpW = (screenHeight * targetAspect).toInt()
                vpX = (screenWidth - vpW) / 2
                vpY = 0
            } else {
                // Screen is taller than 4:3 (letterbox)
                vpW = screenWidth
                vpH = if (targetAspect > 0f) (screenWidth / targetAspect).toInt() else screenHeight
                vpX = 0
                vpY = (screenHeight - vpH) / 2
            }
        }

        GLES20.glViewport(vpX, vpY, vpW, vpH)

        // Select active shader program
        val activeProg = when (filterMode) {
            FILTER_MODE_FIDELITYFX -> progFidelityFx ?: progNormal
            FILTER_MODE_IA_XBR -> progXbr ?: progNormal
            FILTER_MODE_CRT -> progCrt ?: progNormal
            else -> progNormal
        } ?: return

        GLES20.glUseProgram(activeProg.program)

        GLES20.glEnableVertexAttribArray(activeProg.posHandle)
        GLES20.glVertexAttribPointer(activeProg.posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(activeProg.texCoordHandle)
        GLES20.glVertexAttribPointer(activeProg.texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(activeProg.textureUniformHandle, 0)

        if (activeProg.textureSizeUniformHandle != -1) {
            GLES20.glUniform2f(activeProg.textureSizeUniformHandle, frameW.toFloat(), frameH.toFloat())
        }
        if (activeProg.outputSizeUniformHandle != -1) {
            GLES20.glUniform2f(activeProg.outputSizeUniformHandle, vpW.toFloat(), vpH.toFloat())
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(activeProg.posHandle)
        GLES20.glDisableVertexAttribArray(activeProg.texCoordHandle)
    }

    private fun buildShaderProgram(fragCode: String): ShaderProgram? {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragCode)
        if (vertexShader == 0 || fragmentShader == 0) return null

        val prog = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("NesRenderer", "Program link error: " + GLES20.glGetProgramInfoLog(it))
                GLES20.glDeleteProgram(it)
                return null
            }
        }

        return ShaderProgram(
            program = prog,
            posHandle = GLES20.glGetAttribLocation(prog, "aPosition"),
            texCoordHandle = GLES20.glGetAttribLocation(prog, "aTexCoord"),
            textureUniformHandle = GLES20.glGetUniformLocation(prog, "uTexture"),
            textureSizeUniformHandle = GLES20.glGetUniformLocation(prog, "uTextureSize"),
            outputSizeUniformHandle = GLES20.glGetUniformLocation(prog, "uOutputSize")
        )
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("NesRenderer", "Shader compile failed ($type): " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
