package com.moxapps.files.viewer.model

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ModelGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = ModelRenderer()

    private var azimuthDeg = DEFAULT_AZIMUTH
    private var elevationDeg = DEFAULT_ELEVATION
    private var distanceFactor = DEFAULT_DISTANCE_FACTOR
    private var panX = 0f
    private var panY = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerCount = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                distanceFactor = (distanceFactor / detector.scaleFactor).coerceIn(0.45f, 10f)
                updateCamera()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetCamera()
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: ModelMesh) {
        queueEvent {
            renderer.setMesh(mesh)
            requestRender()
        }
        resetCamera()
    }

    fun setWireframe(enabled: Boolean) {
        queueEvent {
            renderer.wireframe = enabled
            requestRender()
        }
    }

    fun resetCamera() {
        azimuthDeg = DEFAULT_AZIMUTH
        elevationDeg = DEFAULT_ELEVATION
        distanceFactor = DEFAULT_DISTANCE_FACTOR
        panX = 0f
        panY = 0f
        updateCamera()
    }

    private fun updateCamera() {
        val az = azimuthDeg
        val el = elevationDeg
        val dist = distanceFactor
        val px = panX
        val py = panY
        queueEvent {
            renderer.azimuthDeg = az
            renderer.elevationDeg = el
            renderer.distanceFactor = dist
            renderer.panX = px
            renderer.panY = py
            requestRender()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = gestureDetector.onTouchEvent(event)
        handled = scaleDetector.onTouchEvent(event) || handled

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerCount = 1
                handled = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = event.pointerCount
                lastTouchX = averageX(event)
                lastTouchY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                if (count != activePointerCount) {
                    activePointerCount = count
                    lastTouchX = averageX(event)
                    lastTouchY = averageY(event)
                    return true
                }
                val curX = averageX(event)
                val curY = averageY(event)
                val dx = curX - lastTouchX
                val dy = curY - lastTouchY
                lastTouchX = curX
                lastTouchY = curY

                if (count == 1 && !scaleDetector.isInProgress) {
                    azimuthDeg -= dx * 0.4f
                    elevationDeg = (elevationDeg + dy * 0.4f).coerceIn(-88f, 88f)
                    updateCamera()
                } else if (count >= 2 && !scaleDetector.isInProgress) {
                    val panScale = distanceFactor * 0.002f
                    panX -= dx * panScale
                    panY += dy * panScale
                    updateCamera()
                }
                handled = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = event.pointerCount - 1
                lastTouchX = averageXExcluding(event, event.actionIndex)
                lastTouchY = averageYExcluding(event, event.actionIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
            }
        }
        return handled || super.onTouchEvent(event)
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun averageXExcluding(event: MotionEvent, excludeIndex: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i != excludeIndex) {
                sum += event.getX(i)
                count++
            }
        }
        return if (count > 0) sum / count else event.x
    }

    private fun averageYExcluding(event: MotionEvent, excludeIndex: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i != excludeIndex) {
                sum += event.getY(i)
                count++
            }
        }
        return if (count > 0) sum / count else event.y
    }

    private class ModelRenderer : Renderer {
        var azimuthDeg = DEFAULT_AZIMUTH
        var elevationDeg = DEFAULT_ELEVATION
        var distanceFactor = DEFAULT_DISTANCE_FACTOR
        var panX = 0f
        var panY = 0f
        var wireframe = false

        private var program = 0
        private var posHandle = 0
        private var normalHandle = 0
        private var colorHandle = 0
        private var mvpHandle = 0
        private var mvHandle = 0

        private var vertexVbo = 0
        private var normalVbo = 0
        private var colorVbo = 0
        private var lineVbo = 0
        private var plateQuadVbo = 0
        private var plateGridVbo = 0
        private var vertexCount = 0
        private var lineVertexCount = 0
        private var plateGridVertexCount = 0
        private var meshRadius = 1f
        private var pendingMesh: ModelMesh? = null

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.22f, 0.25f, 0.29f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }
            posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
            colorHandle = GLES20.glGetAttribLocation(program, "aColor")
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            mvHandle = GLES20.glGetUniformLocation(program, "uMvMatrix")

            pendingMesh?.let {
                uploadMesh(it)
                pendingMesh = null
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
            Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.01f, 100f)
        }

        fun setMesh(mesh: ModelMesh) {
            if (program == 0) {
                pendingMesh = mesh
            } else {
                uploadMesh(mesh)
            }
        }

        private fun uploadMesh(mesh: ModelMesh) {
            deleteBuffers()
            uploadedMeshRef = mesh
            meshRadius = mesh.boundingRadius
            vertexCount = mesh.triangleCount * 3
            if (vertexCount <= 0) return

            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vertexVbo = buffers[0]
            normalVbo = buffers[1]

            val vBuf = uploadFloatVbo(vertexVbo, mesh.vertices, vertexCount * 3)
            val nBuf = uploadFloatVbo(normalVbo, mesh.normals, vertexCount * 3)
            val _unused = vBuf + nBuf

            val meshColors = mesh.colors
            if (meshColors != null && meshColors.size >= vertexCount * 3) {
                val cIds = IntArray(1)
                GLES20.glGenBuffers(1, cIds, 0)
                colorVbo = cIds[0]
                uploadByteVbo(colorVbo, meshColors, vertexCount * 3)
            }

            if (mesh.plateHalfSize > 0f) {
                uploadBuildPlate(mesh.plateHalfSize, mesh.plateY)
            }

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun uploadBuildPlate(half: Float, plateY: Float) {
            val quadY = plateY - 0.15f
            val quadVerts = floatArrayOf(
                -half, quadY, -half,
                half, quadY, -half,
                half, quadY, half,
                -half, quadY, -half,
                half, quadY, half,
                -half, quadY, half
            )
            val qIds = IntArray(1)
            GLES20.glGenBuffers(1, qIds, 0)
            plateQuadVbo = qIds[0]
            val qBuf = toFloatBuffer(quadVerts, quadVerts.size)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, plateQuadVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, qBuf.capacity() * 4, qBuf, GLES20.GL_STATIC_DRAW)

            val step = when {
                half <= 160f -> 10f
                half <= 320f -> 20f
                else -> 50f
            }
            val lines = ArrayList<Float>()
            var pos = -half
            while (pos <= half + 0.01f) {
                val p = pos.coerceIn(-half, half)
                // X-parallel line
                lines.add(-half); lines.add(plateY); lines.add(p)
                lines.add(half); lines.add(plateY); lines.add(p)
                // Z-parallel line
                lines.add(p); lines.add(plateY); lines.add(-half)
                lines.add(p); lines.add(plateY); lines.add(half)
                pos += step
            }
            plateGridVertexCount = lines.size / 3
            val gIds = IntArray(1)
            GLES20.glGenBuffers(1, gIds, 0)
            plateGridVbo = gIds[0]
            val gBuf = toFloatBuffer(lines.toFloatArray(), lines.size)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, plateGridVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, gBuf.capacity() * 4, gBuf, GLES20.GL_STATIC_DRAW)
        }

        private fun ensureLineVbo() {
            if (lineVbo != 0 || vertexVbo == 0 || vertexCount <= 0) return
            // Download isn't needed if we keep mesh, or we can build line VBO from pending?
            // Instead, let's build line VBO only when wireframe is toggled if we keep a reference,
            // or build it on demand from lastMesh!
        }

        private var uploadedMeshRef: ModelMesh? = null

        private fun uploadLineVboIfNeeded() {
            if (lineVbo != 0) return
            val mesh = uploadedMeshRef ?: return
            val triCount = mesh.triangleCount
            if (triCount <= 0) return
            // Cap wireframe edges for huge meshes to avoid OOM
            val maxWireTris = 150_000
            val step = ((triCount + maxWireTris - 1) / maxWireTris).coerceAtLeast(1)
            val outTris = (triCount + step - 1) / step
            val lineFloats = FloatArray(outTris * 18) // 3 edges * 2 verts * 3 floats
            val src = mesh.vertices
            var out = 0
            var t = 0
            while (t < triCount && out + 18 <= lineFloats.size) {
                val s = t * 9
                // edge 0->1
                lineFloats[out] = src[s]; lineFloats[out + 1] = src[s + 1]; lineFloats[out + 2] = src[s + 2]
                lineFloats[out + 3] = src[s + 3]; lineFloats[out + 4] = src[s + 4]; lineFloats[out + 5] = src[s + 5]
                // edge 1->2
                lineFloats[out + 6] = src[s + 3]; lineFloats[out + 7] = src[s + 4]; lineFloats[out + 8] = src[s + 5]
                lineFloats[out + 9] = src[s + 6]; lineFloats[out + 10] = src[s + 7]; lineFloats[out + 11] = src[s + 8]
                // edge 2->0
                lineFloats[out + 12] = src[s + 6]; lineFloats[out + 13] = src[s + 7]; lineFloats[out + 14] = src[s + 8]
                lineFloats[out + 15] = src[s]; lineFloats[out + 16] = src[s + 1]; lineFloats[out + 17] = src[s + 2]
                out += 18
                t += step
            }
            lineVertexCount = out / 3
            val lBuf = toFloatBuffer(lineFloats, out)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            lineVbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, lBuf.capacity() * 4, lBuf, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun deleteBuffers() {
            val toDelete = mutableListOf<Int>()
            if (vertexVbo != 0) { toDelete += vertexVbo; vertexVbo = 0 }
            if (normalVbo != 0) { toDelete += normalVbo; normalVbo = 0 }
            if (colorVbo != 0) { toDelete += colorVbo; colorVbo = 0 }
            if (lineVbo != 0) { toDelete += lineVbo; lineVbo = 0 }
            if (plateQuadVbo != 0) { toDelete += plateQuadVbo; plateQuadVbo = 0 }
            if (plateGridVbo != 0) { toDelete += plateGridVbo; plateGridVbo = 0 }
            plateGridVertexCount = 0
            if (toDelete.isNotEmpty()) {
                GLES20.glDeleteBuffers(toDelete.size, toDelete.toIntArray(), 0)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (vertexVbo == 0 || vertexCount <= 0) return

            // Normalize model scale into unit sphere so near/far planes are always exact
            val invR = 1f / meshRadius
            val azRad = Math.toRadians(azimuthDeg.toDouble()).toFloat()
            val elRad = Math.toRadians(elevationDeg.toDouble()).toFloat()
            val cosEl = cos(elRad)
            val eyeX = distanceFactor * cosEl * sin(azRad)
            val eyeY = distanceFactor * sin(elRad)
            val eyeZ = distanceFactor * cosEl * cos(azRad)

            Matrix.setLookAtM(
                viewMatrix, 0,
                eyeX, eyeY, eyeZ,
                0f, 0f, 0f,
                0f, 1f, 0f
            )
            // Apply screen-space pan & uniform model normalization
            val modelViewMatrix = FloatArray(16)
            Matrix.setIdentityM(modelViewMatrix, 0)
            Matrix.translateM(modelViewMatrix, 0, -panX, -panY, 0f)
            Matrix.multiplyMM(modelViewMatrix, 0, modelViewMatrix, 0, viewMatrix, 0)
            Matrix.scaleM(modelViewMatrix, 0, invR, invR, invR)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(mvHandle, 1, false, modelViewMatrix, 0)

            // Draw build plate & grid first when present
            if (plateQuadVbo != 0) {
                GLES20.glDisableVertexAttribArray(normalHandle)
                GLES20.glVertexAttrib3f(normalHandle, 0f, 1f, 0f)
                GLES20.glDisableVertexAttribArray(colorHandle)
                GLES20.glVertexAttrib3f(colorHandle, 0.16f, 0.19f, 0.23f)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, plateQuadVbo)
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            }
            if (plateGridVbo != 0 && plateGridVertexCount > 0) {
                GLES20.glDisableVertexAttribArray(normalHandle)
                GLES20.glVertexAttrib3f(normalHandle, 0f, 0f, 1f)
                GLES20.glDisableVertexAttribArray(colorHandle)
                GLES20.glVertexAttrib3f(colorHandle, 0.34f, 0.39f, 0.46f)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, plateGridVbo)
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, plateGridVertexCount)
            }

            if (wireframe) {
                uploadLineVboIfNeeded()
                if (lineVbo != 0 && lineVertexCount > 0) {
                    GLES20.glDisableVertexAttribArray(colorHandle)
                    GLES20.glVertexAttrib3f(colorHandle, 0.45f, 0.78f, 0.98f)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
                    GLES20.glEnableVertexAttribArray(posHandle)
                    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glDisableVertexAttribArray(normalHandle)
                    GLES20.glVertexAttrib3f(normalHandle, 0f, 0f, 1f)
                    GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVertexCount)
                }
            } else {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexVbo)
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, normalVbo)
                GLES20.glEnableVertexAttribArray(normalHandle)
                GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

                if (colorVbo != 0) {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colorVbo)
                    GLES20.glEnableVertexAttribArray(colorHandle)
                    GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_UNSIGNED_BYTE, true, 0, 0)
                } else {
                    GLES20.glDisableVertexAttribArray(colorHandle)
                    GLES20.glVertexAttrib3f(colorHandle, 0.38f, 0.64f, 0.85f)
                }

                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
            }

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(normalHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private fun uploadFloatVbo(vboId: Int, data: FloatArray, floatCount: Int): Int {
            val totalBytes = floatCount * 4
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, totalBytes, null, GLES20.GL_STATIC_DRAW)
            val chunkFloats = 262_144 // 1 MB scratch buffer
            val scratch = ByteBuffer.allocateDirect(chunkFloats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            var offset = 0
            while (offset < floatCount) {
                val count = minOf(chunkFloats, floatCount - offset)
                scratch.clear()
                scratch.put(data, offset, count)
                scratch.flip()
                GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, offset * 4, count * 4, scratch)
                offset += count
            }
            return totalBytes
        }

        private fun uploadByteVbo(vboId: Int, data: ByteArray, byteCount: Int) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, null, GLES20.GL_STATIC_DRAW)
            val chunkBytes = 1_048_576 // 1 MB scratch buffer
            val scratch = ByteBuffer.allocateDirect(chunkBytes)
            var offset = 0
            while (offset < byteCount) {
                val count = minOf(chunkBytes, byteCount - offset)
                scratch.clear()
                scratch.put(data, offset, count)
                scratch.flip()
                GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, offset, count, scratch)
                offset += count
            }
        }

        private fun toFloatBuffer(data: FloatArray, count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data, 0, count)
                    position(0)
                }

        init {
            // Keep mesh reference updated inside uploadMesh
        }
    }

    companion object {
        private const val DEFAULT_AZIMUTH = 35f
        private const val DEFAULT_ELEVATION = 25f
        private const val DEFAULT_DISTANCE_FACTOR = 2.6f

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uMvMatrix;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vNormal = normalize(mat3(uMvMatrix) * aNormal);
                vColor = aColor;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                vec3 n = normalize(vNormal);
                if (!gl_FrontFacing) {
                    n = -n;
                }
                vec3 keyDir = normalize(vec3(0.45, 0.70, 0.55));
                vec3 fillDir = normalize(vec3(-0.60, -0.30, 0.74));
                float key = max(dot(n, keyDir), 0.0);
                float fill = max(dot(n, fillDir), 0.0);
                float rim = 1.0 - max(dot(n, vec3(0.0, 0.0, 1.0)), 0.0);
                float light = 0.45 + 0.55 * key + 0.25 * fill + 0.08 * rim;
                gl_FragColor = vec4(vColor * clamp(light, 0.35, 1.05), 1.0);
            }
        """
    }
}
