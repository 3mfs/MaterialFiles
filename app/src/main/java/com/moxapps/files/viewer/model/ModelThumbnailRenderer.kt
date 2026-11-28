package com.moxapps.files.viewer.model

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object ModelThumbnailRenderer {
    // Isometric-like view angles (radians)
    private const val AZIMUTH_RAD = 0.6108652f   // 35 deg
    private const val ELEVATION_RAD = 0.4363323f // 25 deg

    // Key light (normalized) in camera space
    private val LIGHT_KEY = normalize3(0.45f, 0.70f, 0.55f)
    private val LIGHT_FILL = normalize3(-0.60f, -0.30f, 0.74f)

    fun render(mesh: ModelMesh, sizePx: Int = 192): Bitmap {
        val w = sizePx.coerceIn(64, 512)
        val h = w
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (mesh.triangleCount <= 0) {
            return bitmap
        }

        val cosA = cos(AZIMUTH_RAD)
        val sinA = sin(AZIMUTH_RAD)
        val cosE = cos(ELEVATION_RAD)
        val sinE = sin(ELEVATION_RAD)

        val verts = mesh.vertices
        val norms = mesh.normals
        val floatCount = mesh.triangleCount * 9

        // First pass: find projected 2D bounds for tight framing.
        var minPx = Float.POSITIVE_INFINITY
        var maxPx = Float.NEGATIVE_INFINITY
        var minPy = Float.POSITIVE_INFINITY
        var maxPy = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < floatCount) {
            val x = verts[i]
            val y = verts[i + 1]
            val z = verts[i + 2]
            val rx = x * cosA - z * sinA
            val rz1 = x * sinA + z * cosA
            val ry = y * cosE - rz1 * sinE
            if (rx < minPx) minPx = rx
            if (rx > maxPx) maxPx = rx
            if (ry < minPy) minPy = ry
            if (ry > maxPy) maxPy = ry
            i += 3
        }

        val spanX = (maxPx - minPx).coerceAtLeast(1e-4f)
        val spanY = (maxPy - minPy).coerceAtLeast(1e-4f)
        val scale = (w * 0.84f) / max(spanX, spanY)
        val midPx = (minPx + maxPx) * 0.5f
        val midPy = (minPy + maxPy) * 0.5f
        val halfW = w * 0.5f
        val halfH = h * 0.5f

        val pixels = IntArray(w * h)
        val zBuffer = FloatArray(w * h) { Float.NEGATIVE_INFINITY }

        // Base material color: Material blue-slate (#6096BA)
        val baseR = 96f
        val baseG = 160f
        val baseB = 208f

        var t = 0
        while (t < floatCount) {
            // Rotate face normal into camera space
            val nx0 = norms[t]
            val ny0 = norms[t + 1]
            val nz0 = norms[t + 2]
            val rnx = nx0 * cosA - nz0 * sinA
            val rnz1 = nx0 * sinA + nz0 * cosA
            val rny = ny0 * cosE - rnz1 * sinE
            val rnz = ny0 * sinE + rnz1 * cosE

            // Transform vertices to screen space
            val x0 = verts[t]; val y0 = verts[t + 1]; val z0 = verts[t + 2]
            val rx0 = x0 * cosA - z0 * sinA
            val rz0a = x0 * sinA + z0 * cosA
            val ry0 = y0 * cosE - rz0a * sinE
            val sx0 = halfW + (rx0 - midPx) * scale
            val sy0 = halfH - (ry0 - midPy) * scale
            val sz0 = y0 * sinE + rz0a * cosE

            val x1 = verts[t + 3]; val y1 = verts[t + 4]; val z1 = verts[t + 5]
            val rx1 = x1 * cosA - z1 * sinA
            val rz1a = x1 * sinA + z1 * cosA
            val ry1 = y1 * cosE - rz1a * sinE
            val sx1 = halfW + (rx1 - midPx) * scale
            val sy1 = halfH - (ry1 - midPy) * scale
            val sz1 = y1 * sinE + rz1a * cosE

            val x2 = verts[t + 6]; val y2 = verts[t + 7]; val z2 = verts[t + 8]
            val rx2 = x2 * cosA - z2 * sinA
            val rz2a = x2 * sinA + z2 * cosA
            val ry2 = y2 * cosE - rz2a * sinE
            val sx2 = halfW + (rx2 - midPx) * scale
            val sy2 = halfH - (ry2 - midPy) * scale
            val sz2 = y2 * sinE + rz2a * cosE

            val area = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0)
            if (area == 0f) {
                t += 9
                continue
            }

            // Two-sided shading for robustness against flipped STL normals
            val sign = if (rnz < 0f) -1f else 1f
            val lnx = rnx * sign
            val lny = rny * sign
            val lnz = rnz * sign
            val diffKey = max(0f, lnx * LIGHT_KEY[0] + lny * LIGHT_KEY[1] + lnz * LIGHT_KEY[2])
            val diffFill = max(0f, lnx * LIGHT_FILL[0] + lny * LIGHT_FILL[1] + lnz * LIGHT_FILL[2])
            val intensity = (0.28f + 0.62f * diffKey + 0.20f * diffFill).coerceIn(0.2f, 1.05f)
            val r = (baseR * intensity).toInt().coerceIn(0, 255)
            val g = (baseG * intensity).toInt().coerceIn(0, 255)
            val b = (baseB * intensity).toInt().coerceIn(0, 255)
            val color = Color.argb(255, r, g, b)

            val minX = max(0, floor(min(sx0, min(sx1, sx2))).toInt())
            val maxX = min(w - 1, ceil(max(sx0, max(sx1, sx2))).toInt())
            val minY = max(0, floor(min(sy0, min(sy1, sy2))).toInt())
            val maxY = min(h - 1, ceil(max(sy0, max(sy1, sy2))).toInt())

            val invArea = 1f / area
            for (py in minY..maxY) {
                val cy = py + 0.5f
                val rowOff = py * w
                for (px in minX..maxX) {
                    val cx = px + 0.5f
                    val w0 = ((sx1 - cx) * (sy2 - cy) - (sy1 - cy) * (sx2 - cx)) * invArea
                    val w1 = ((sx2 - cx) * (sy0 - cy) - (sy2 - cy) * (sx0 - cx)) * invArea
                    val w2 = 1f - w0 - w1
                    if (w0 >= -1e-4f && w1 >= -1e-4f && w2 >= -1e-4f) {
                        val depth = w0 * sz0 + w1 * sz1 + w2 * sz2
                        val idx = rowOff + px
                        if (depth > zBuffer[idx]) {
                            zBuffer[idx] = depth
                            pixels[idx] = color
                        }
                    }
                }
            }
            t += 9
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / len, y / len, z / len)
    }
}
