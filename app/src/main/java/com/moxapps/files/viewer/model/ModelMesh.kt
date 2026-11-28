package com.moxapps.files.viewer.model

import kotlin.math.max
import kotlin.math.sqrt

class ModelMesh(
    /** Centered Y-up vertices: 9 floats per triangle (x0, y0, z0, x1, y1, z1, x2, y2, z2). */
    val vertices: FloatArray,
    /** Normalized face normals: 9 floats per triangle. */
    val normals: FloatArray,
    val triangleCount: Int,
    /** Original CAD bounding box dimensions (width X, depth Y, height Z). */
    val cadSizeX: Float,
    val cadSizeY: Float,
    val cadSizeZ: Float,
    val boundingRadius: Float
) {
    companion object {
        /**
         * Builds a centered Y-up [ModelMesh] from raw CAD Z-up triangle coordinates.
         * Maps CAD (x, y, z) -> GL (x, z, -y) which preserves right-handed CCW winding.
         */
        fun fromCadTriangles(
            rawVertices: FloatArray,
            triangleCount: Int
        ): ModelMesh {
            if (triangleCount <= 0) {
                return ModelMesh(
                    FloatArray(0), FloatArray(0), 0, 0f, 0f, 0f, 1f
                )
            }
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY

            val floatCount = triangleCount * 9
            var i = 0
            while (i < floatCount) {
                val x = rawVertices[i]
                val y = rawVertices[i + 1]
                val z = rawVertices[i + 2]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
                i += 3
            }

            val cadSizeX = (maxX - minX).coerceAtLeast(0f)
            val cadSizeY = (maxY - minY).coerceAtLeast(0f)
            val cadSizeZ = (maxZ - minZ).coerceAtLeast(0f)
            val centerX = (minX + maxX) * 0.5f
            val centerY = (minY + maxY) * 0.5f
            val centerZ = (minZ + maxZ) * 0.5f

            val vertices = FloatArray(floatCount)
            val normals = FloatArray(floatCount)
            var maxDistSq = 0f

            var t = 0
            while (t < floatCount) {
                val x0 = rawVertices[t] - centerX
                val y0 = rawVertices[t + 2] - centerZ
                val z0 = -(rawVertices[t + 1] - centerY)

                val x1 = rawVertices[t + 3] - centerX
                val y1 = rawVertices[t + 5] - centerZ
                val z1 = -(rawVertices[t + 4] - centerY)

                val x2 = rawVertices[t + 6] - centerX
                val y2 = rawVertices[t + 8] - centerZ
                val z2 = -(rawVertices[t + 7] - centerY)

                vertices[t] = x0
                vertices[t + 1] = y0
                vertices[t + 2] = z0
                vertices[t + 3] = x1
                vertices[t + 4] = y1
                vertices[t + 5] = z1
                vertices[t + 6] = x2
                vertices[t + 7] = y2
                vertices[t + 8] = z2

                maxDistSq = max(maxDistSq, x0 * x0 + y0 * y0 + z0 * z0)
                maxDistSq = max(maxDistSq, x1 * x1 + y1 * y1 + z1 * z1)
                maxDistSq = max(maxDistSq, x2 * x2 + y2 * y2 + z2 * z2)

                // Face normal = (v1 - v0) x (v2 - v0)
                val ux = x1 - x0
                val uy = y1 - y0
                val uz = z1 - z0
                val vx = x2 - x0
                val vy = y2 - y0
                val vz = z2 - z0
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-12f) {
                    val inv = 1f / len
                    nx *= inv
                    ny *= inv
                    nz *= inv
                } else {
                    nx = 0f
                    ny = 1f
                    nz = 0f
                }

                normals[t] = nx
                normals[t + 1] = ny
                normals[t + 2] = nz
                normals[t + 3] = nx
                normals[t + 4] = ny
                normals[t + 5] = nz
                normals[t + 6] = nx
                normals[t + 7] = ny
                normals[t + 8] = nz

                t += 9
            }

            val radius = sqrt(maxDistSq).coerceAtLeast(1e-4f)
            return ModelMesh(
                vertices = vertices,
                normals = normals,
                triangleCount = triangleCount,
                cadSizeX = cadSizeX,
                cadSizeY = cadSizeY,
                cadSizeZ = cadSizeZ,
                boundingRadius = radius
            )
        }
    }
}
