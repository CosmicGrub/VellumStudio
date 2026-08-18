package com.vellum.studio.canvas.gl

import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix as GLMatrix
import com.vellum.studio.canvas.CanvasEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The actual GPU work: uploads each visible [com.vellum.studio.canvas.Layer]'s bitmap as a GL
 * texture (re-uploaded only when [com.vellum.studio.canvas.Layer.contentVersion] changes, so a
 * static layer costs nothing on frames where only a different layer changed) and composites them
 * in order as textured quads, replacing the `for (layer in eng.layers) canvas.drawBitmap(...)`
 * loop that's the expensive part of [com.vellum.studio.canvas.DrawingCanvasView.onDraw] — see
 * [LayerCompositorGLView]'s class doc for why this is deliberately scoped to only the idle/no-
 * active-stroke view, not the live-stroke rendering path.
 *
 * Scope limitation, on purpose and documented rather than silently wrong: GL ES 2.0 has no
 * built-in way for a fragment shader to read the framebuffer's current color (no
 * EXT_shader_framebuffer_fetch guarantee across devices), so only Normal-blend compositing is
 * implemented here. [LayerCompositorGLView]/EditorScreen only activate this renderer when every
 * visible layer is already Normal blend, so this limitation is never silently wrong on screen —
 * it's an activation precondition, not a per-layer fallback inside the shader.
 */
internal class CompositorRenderer : GLSurfaceView.Renderer {

    private var engine: CanvasEngine? = null
    private var matrixProvider: (() -> Matrix)? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uAlphaLoc = 0
    private var uTextureLoc = 0

    // GLES requires *direct*, native-order buffers -- a plain FloatBuffer.wrap() over a heap
    // array is not direct and glVertexAttribPointer rejects it at runtime.
    private val quadVertices = directFloatBuffer(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
    private val quadTexCoords = directFloatBuffer(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))

    // layer id -> GL texture handle, keyed against the content version it was uploaded at.
    private val textures = HashMap<String, TextureEntry>()
    private data class TextureEntry(val handle: Int, val uploadedVersion: Int)

    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val layerMatrix = FloatArray(16)
    private val layerMvpMatrix = FloatArray(16)
    private val glCanvasMatrix = FloatArray(16)
    private val matrixValues = FloatArray(9)

    fun attach(engine: CanvasEngine, matrixProvider: () -> Matrix) {
        this.engine = engine
        this.matrixProvider = matrixProvider
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Premultiplied-alpha source-over -- Android Bitmaps are premultiplied by default and
        // GLUtils.texImage2D uploads pixel data as-is (no un-premultiply step), so the texture's
        // RGB is already scaled by its own alpha. (ONE, ONE_MINUS_SRC_ALPHA) is the correct blend
        // func for that, matching the fragment shader scaling all four channels by uAlpha below.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        program = buildProgram()
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        // Surface was (re)created -- any previously-uploaded texture handles are gone with it.
        textures.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // Pixel-space ortho projection, Y increasing downward to match Android canvas convention.
        GLMatrix.orthoM(projMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val eng = engine ?: return
        val matrix = matrixProvider?.invoke() ?: return
        toGLMatrix(matrix, glCanvasMatrix)
        GLMatrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, glCanvasMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTextureLoc, 0)

        for (layer in eng.layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            val bitmap = layer.bitmap
            val handle = textureHandleFor(layer.id, layer.contentVersion, bitmap)

            // This layer's quad in canvas-pixel space, scaled from the unit quad up to the
            // bitmap's actual width/height, combined into the shared MVP.
            GLMatrix.setIdentityM(layerMatrix, 0)
            GLMatrix.scaleM(layerMatrix, 0, bitmap.width.toFloat(), bitmap.height.toFloat(), 1f)
            GLMatrix.multiplyMM(layerMvpMatrix, 0, mvpMatrix, 0, layerMatrix, 0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, layerMvpMatrix, 0)
            GLES20.glUniform1f(uAlphaLoc, layer.opacity)
            quadVertices.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun textureHandleFor(layerId: String, contentVersion: Int, bitmap: android.graphics.Bitmap): Int {
        val existing = textures[layerId]
        if (existing != null && existing.uploadedVersion == contentVersion) return existing.handle

        val handle = existing?.handle ?: run {
            val handles = IntArray(1)
            GLES20.glGenTextures(1, handles, 0)
            handles[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Uploads the bitmap's pixel data as-is -- premultiplied, since that's Android's default
        // Bitmap state -- matching the premultiplied blend func set up in onSurfaceCreated.
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[layerId] = TextureEntry(handle, contentVersion)
        return handle
    }

    private fun toGLMatrix(m: Matrix, out: FloatArray) {
        m.getValues(matrixValues)
        val v = matrixValues
        // android.graphics.Matrix is row-major 3x3 affine; expand + transpose into column-major
        // GL 4x4 (2D affine only -- z stays untouched, w-perspective terms carried through).
        out[0] = v[0]; out[1] = v[3]; out[2] = 0f; out[3] = v[6]
        out[4] = v[1]; out[5] = v[4]; out[6] = 0f; out[7] = v[7]
        out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
        out[12] = v[2]; out[13] = v[5]; out[14] = 0f; out[15] = v[8]
    }

    private fun buildProgram(): Int {
        val vertexShader = """
            uniform mat4 uMVP;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec4 texColor = texture2D(uTexture, vTexCoord);
                // Scale all four channels together -- correct for premultiplied-alpha data,
                // where RGB must stay proportional to alpha as opacity is applied.
                gl_FragColor = texColor * uAlpha;
            }
        """.trimIndent()
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

private fun directFloatBuffer(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }
