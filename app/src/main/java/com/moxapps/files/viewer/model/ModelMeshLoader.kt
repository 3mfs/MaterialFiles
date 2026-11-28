package com.moxapps.files.viewer.model

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.ceil

object ModelMeshLoader {
    private const val MAX_TRIANGLES_HARD_LIMIT = 10_000_000

    @Throws(IOException::class)
    fun loadStl(inputStream: InputStream, maxTriangles: Int = Int.MAX_VALUE): ModelMesh {
        val buffered = BufferedInputStream(inputStream, 64 * 1024)
        buffered.mark(1024)
        val probe = ByteArray(1024)
        var probeRead = 0
        while (probeRead < probe.size) {
            val n = buffered.read(probe, probeRead, probe.size - probeRead)
            if (n <= 0) break
            probeRead += n
        }
        buffered.reset()

        val probeText = String(probe, 0, probeRead, Charsets.US_ASCII).lowercase()
        val isAscii = probeText.trimStart().startsWith("solid") && probeText.contains("facet")
        return if (isAscii) {
            loadAsciiStl(buffered, maxTriangles)
        } else {
            loadBinaryStl(buffered, maxTriangles)
        }
    }

    @Throws(IOException::class)
    private fun loadBinaryStl(inputStream: InputStream, maxTriangles: Int): ModelMesh {
        val header = ByteArray(84)
        readFully(inputStream, header, 84)
        val triCountLong = ByteBuffer.wrap(header, 80, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL
        if (triCountLong == 0L || triCountLong > MAX_TRIANGLES_HARD_LIMIT) {
            throw IOException("Invalid binary STL triangle count: $triCountLong")
        }
        val totalTriangles = triCountLong.toInt()
        val stride = if (totalTriangles > maxTriangles) {
            ceil(totalTriangles.toDouble() / maxTriangles.toDouble()).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val expectedOut = ((totalTriangles + stride - 1) / stride).coerceAtLeast(1)
        val vertices = FloatArray(expectedOut * 9)
        var outIndex = 0
        var actualTriangles = 0

        // Read in chunks of 50-byte records for fast buffered parsing.
        val batchRecords = 1024
        val recordBuf = ByteArray(batchRecords * 50)
        val bb = ByteBuffer.wrap(recordBuf).order(ByteOrder.LITTLE_ENDIAN)
        var triIndex = 0
        while (triIndex < totalTriangles) {
            val toReadRecords = minOf(batchRecords, totalTriangles - triIndex)
            val byteCount = toReadRecords * 50
            readFully(inputStream, recordBuf, byteCount)
            for (r in 0 until toReadRecords) {
                if ((triIndex % stride) == 0 && outIndex + 9 <= vertices.size) {
                    val base = r * 50 + 12 // skip 12-byte normal; ModelMesh computes exact normal
                    vertices[outIndex] = bb.getFloat(base)
                    vertices[outIndex + 1] = bb.getFloat(base + 4)
                    vertices[outIndex + 2] = bb.getFloat(base + 8)
                    vertices[outIndex + 3] = bb.getFloat(base + 12)
                    vertices[outIndex + 4] = bb.getFloat(base + 16)
                    vertices[outIndex + 5] = bb.getFloat(base + 20)
                    vertices[outIndex + 6] = bb.getFloat(base + 24)
                    vertices[outIndex + 7] = bb.getFloat(base + 28)
                    vertices[outIndex + 8] = bb.getFloat(base + 32)
                    outIndex += 9
                    actualTriangles++
                }
                triIndex++
            }
        }
        return ModelMesh.fromCadTriangles(vertices, actualTriangles)
    }

    @Throws(IOException::class)
    private fun loadAsciiStl(inputStream: InputStream, maxTriangles: Int): ModelMesh {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.US_ASCII), 64 * 1024)
        var vertices = FloatArray(4096 * 9)
        var triCount = 0
        var vInTri = 0
        val triCoords = FloatArray(9)

        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.startsWith("vertex", ignoreCase = true) &&
                trimmed.length > 6 && trimmed[6].isWhitespace()
            ) {
                if (parseThreeFloats(trimmed, 7, triCoords, vInTri * 3)) {
                    vInTri++
                    if (vInTri == 3) {
                        vInTri = 0
                        if (triCount < maxTriangles) {
                            val needed = (triCount + 1) * 9
                            if (needed > vertices.size) {
                                vertices = vertices.copyOf((vertices.size * 2).coerceAtLeast(needed))
                            }
                            System.arraycopy(triCoords, 0, vertices, triCount * 9, 9)
                            triCount++
                        }
                    }
                }
            } else if (trimmed.startsWith("endloop", ignoreCase = true)) {
                vInTri = 0
            }
        }
        if (triCount == 0) {
            throw IOException("No triangles found in ASCII STL")
        }
        return ModelMesh.fromCadTriangles(vertices, triCount)
    }

    fun extract3mfThumbnail(inputStream: InputStream): ByteArray? {
        val zis = ZipInputStream(BufferedInputStream(inputStream, 64 * 1024))
        var bestPriority = Int.MAX_VALUE
        var bestBytes: ByteArray? = null
        zis.use {
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/').lowercase()
                val priority = when {
                    name == "metadata/plate_1.png" -> 0
                    name == "metadata/thumbnail.png" -> 1
                    name == "thumbnails/thumbnail.png" -> 2
                    (name.startsWith("metadata/") || name.startsWith("thumbnails/")) &&
                        (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) -> 3
                    else -> Int.MAX_VALUE
                }
                if (priority < bestPriority) {
                    val bytes = zis.readBytes()
                    if (bytes.isNotEmpty()) {
                        bestPriority = priority
                        bestBytes = bytes
                        if (priority == 0) break
                    }
                }
            }
        }
        return bestBytes
    }

    @Throws(IOException::class)
    fun load3mf(inputStream: InputStream, maxTriangles: Int = Int.MAX_VALUE): ModelMesh {
        val modelFiles = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(inputStream, 64 * 1024)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val normalized = entry.name.trimStart('/').lowercase()
                if (normalized.endsWith(".model")) {
                    modelFiles[normalized] = zis.readBytes()
                }
            }
        }
        if (modelFiles.isEmpty()) {
            throw IOException("No .model files found in 3MF archive")
        }

        val rootKey = when {
            modelFiles.containsKey("3d/3dmodel.model") -> "3d/3dmodel.model"
            else -> modelFiles.keys.first()
        }
        val parsedModels = modelFiles.mapValues { (_, bytes) -> parse3mfModelXml(bytes) }
        val rootModel = parsedModels[rootKey]!!

        val collector = TriangleCollector(maxTriangles)
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            0f, 0f, 0f
        )

        fun emitObject(model: ThreeMfModel, objectId: String, transform: FloatArray, depth: Int) {
            if (depth > 16) return
            val obj = model.objects[objectId] ?: return
            if (obj.meshVertices != null && obj.meshIndices != null) {
                collector.addIndexedMesh(obj.meshVertices, obj.meshIndices, transform)
            }
            for (comp in obj.components) {
                val targetModel = if (comp.path != null) {
                    parsedModels[comp.path.trimStart('/').lowercase()] ?: model
                } else {
                    model
                }
                val combined = if (comp.transform != null) {
                    multiply3mfTransforms(comp.transform, transform)
                } else {
                    transform
                }
                emitObject(targetModel, comp.objectId, combined, depth + 1)
            }
        }

        if (rootModel.buildItems.isNotEmpty()) {
            for (item in rootModel.buildItems) {
                val xform = item.transform ?: identity
                emitObject(rootModel, item.objectId, xform, 0)
            }
        } else {
            for ((id, _) in rootModel.objects) {
                emitObject(rootModel, id, identity, 0)
            }
        }

        if (collector.triangleCount == 0) {
            throw IOException("No mesh triangles found in 3MF archive")
        }
        return ModelMesh.fromCadTriangles(collector.vertices, collector.triangleCount)
    }

    private class TriangleCollector(private val maxTriangles: Int) {
        var vertices = FloatArray(4096 * 9)
        var triangleCount = 0

        fun addIndexedMesh(meshVerts: FloatArray, indices: IntArray, m: FloatArray) {
            val triTotal = indices.size / 3
            val remainingBudget = (maxTriangles - triangleCount).coerceAtLeast(0)
            if (remainingBudget == 0) return
            val stride = if (triTotal > remainingBudget) {
                ceil(triTotal.toDouble() / remainingBudget.toDouble()).toInt().coerceAtLeast(1)
            } else {
                1
            }
            for (t in 0 until triTotal step stride) {
                if (triangleCount >= maxTriangles) break
                val i0 = indices[t * 3] * 3
                val i1 = indices[t * 3 + 1] * 3
                val i2 = indices[t * 3 + 2] * 3
                if (i0 + 2 >= meshVerts.size || i1 + 2 >= meshVerts.size || i2 + 2 >= meshVerts.size) {
                    continue
                }
                val needed = (triangleCount + 1) * 9
                if (needed > vertices.size) {
                    vertices = vertices.copyOf((vertices.size * 2).coerceAtLeast(needed))
                }
                val out = triangleCount * 9
                transformPoint(meshVerts, i0, m, vertices, out)
                transformPoint(meshVerts, i1, m, vertices, out + 3)
                transformPoint(meshVerts, i2, m, vertices, out + 6)
                triangleCount++
            }
        }

        private fun transformPoint(
            src: FloatArray,
            srcIdx: Int,
            m: FloatArray,
            dst: FloatArray,
            dstIdx: Int
        ) {
            val x = src[srcIdx]
            val y = src[srcIdx + 1]
            val z = src[srcIdx + 2]
            dst[dstIdx] = x * m[0] + y * m[3] + z * m[6] + m[9]
            dst[dstIdx + 1] = x * m[1] + y * m[4] + z * m[7] + m[10]
            dst[dstIdx + 2] = x * m[2] + y * m[5] + z * m[8] + m[11]
        }
    }

    private fun multiply3mfTransforms(local: FloatArray, parent: FloatArray): FloatArray {
        val r = FloatArray(12)
        for (row in 0 until 4) {
            val a0 = local[row * 3]
            val a1 = local[row * 3 + 1]
            val a2 = local[row * 3 + 2]
            r[row * 3] = a0 * parent[0] + a1 * parent[3] + a2 * parent[6] + (if (row == 3) parent[9] else 0f)
            r[row * 3 + 1] = a0 * parent[1] + a1 * parent[4] + a2 * parent[7] + (if (row == 3) parent[10] else 0f)
            r[row * 3 + 2] = a0 * parent[2] + a1 * parent[5] + a2 * parent[8] + (if (row == 3) parent[11] else 0f)
        }
        return r
    }

    private class ThreeMfComponent(
        val objectId: String,
        val path: String?,
        val transform: FloatArray?
    )

    private class ThreeMfObject(
        val meshVertices: FloatArray?,
        val meshIndices: IntArray?,
        val components: List<ThreeMfComponent>
    )

    private class ThreeMfBuildItem(
        val objectId: String,
        val transform: FloatArray?
    )

    private class ThreeMfModel(
        val objects: Map<String, ThreeMfObject>,
        val buildItems: List<ThreeMfBuildItem>
    )

    private fun parse3mfModelXml(xmlBytes: ByteArray): ThreeMfModel {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val objects = mutableMapOf<String, ThreeMfObject>()
        val buildItems = mutableListOf<ThreeMfBuildItem>()

        var currentObjectId: String? = null
        var verts = FloatArray(1024 * 3)
        var vertCount = 0
        var tris = IntArray(1024 * 3)
        var triCount = 0
        var components = mutableListOf<ThreeMfComponent>()
        var inMesh = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "object" -> {
                            currentObjectId = parser.getAttributeValue(null, "id")
                            vertCount = 0
                            triCount = 0
                            components = mutableListOf()
                            inMesh = false
                        }
                        "mesh" -> inMesh = true
                        "vertex" -> if (inMesh) {
                            val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                            val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                            val z = parser.getAttributeValue(null, "z")?.toFloatOrNull() ?: 0f
                            val needed = (vertCount + 1) * 3
                            if (needed > verts.size) {
                                verts = verts.copyOf(verts.size * 2)
                            }
                            val base = vertCount * 3
                            verts[base] = x
                            verts[base + 1] = y
                            verts[base + 2] = z
                            vertCount++
                        }
                        "triangle" -> if (inMesh) {
                            val v1 = parser.getAttributeValue(null, "v1")?.toIntOrNull()
                            val v2 = parser.getAttributeValue(null, "v2")?.toIntOrNull()
                            val v3 = parser.getAttributeValue(null, "v3")?.toIntOrNull()
                            if (v1 != null && v2 != null && v3 != null) {
                                val needed = (triCount + 1) * 3
                                if (needed > tris.size) {
                                    tris = tris.copyOf(tris.size * 2)
                                }
                                val base = triCount * 3
                                tris[base] = v1
                                tris[base + 1] = v2
                                tris[base + 2] = v3
                                triCount++
                            }
                        }
                        "component" -> if (currentObjectId != null) {
                            val refId = parser.getAttributeValue(null, "objectid")
                            val refPath = parser.getAttributeValue(null, "path")
                                ?: parser.getAttributeValue("http://schemas.microsoft.com/3dmanufacturing/production/2015/06", "path")
                            val transform = parse3mfTransform(parser.getAttributeValue(null, "transform"))
                            if (refId != null) {
                                components.add(ThreeMfComponent(refId, refPath, transform))
                            }
                        }
                        "item" -> {
                            val itemId = parser.getAttributeValue(null, "objectid")
                            val transform = parse3mfTransform(parser.getAttributeValue(null, "transform"))
                            if (itemId != null) {
                                buildItems.add(ThreeMfBuildItem(itemId, transform))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "mesh" -> inMesh = false
                        "object" -> {
                            val id = currentObjectId
                            if (id != null) {
                                objects[id] = ThreeMfObject(
                                    meshVertices = if (vertCount > 0) verts.copyOf(vertCount * 3) else null,
                                    meshIndices = if (triCount > 0) tris.copyOf(triCount * 3) else null,
                                    components = components
                                )
                            }
                            currentObjectId = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return ThreeMfModel(objects, buildItems)
    }

    private fun parse3mfTransform(value: String?): FloatArray? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(WHITESPACE_REGEX)
        if (parts.size != 12) return null
        return FloatArray(12) { i -> parts[i].toFloatOrNull() ?: return null }
    }

    private fun parseThreeFloats(line: String, startIndex: Int, out: FloatArray, outOffset: Int): Boolean {
        var idx = startIndex
        val len = line.length
        for (c in 0 until 3) {
            while (idx < len && line[idx].isWhitespace()) idx++
            val tokenStart = idx
            while (idx < len && !line[idx].isWhitespace()) idx++
            if (tokenStart == idx) return false
            out[outOffset + c] = line.substring(tokenStart, idx).toFloatOrNull() ?: return false
        }
        return true
    }

    @Throws(IOException::class)
    private fun readFully(inputStream: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = inputStream.read(buffer, offset, length - offset)
            if (n < 0) throw EOFException("Unexpected EOF while reading STL")
            offset += n
        }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
