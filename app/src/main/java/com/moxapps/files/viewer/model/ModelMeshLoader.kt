package com.moxapps.files.viewer.model

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
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

    private inline fun <T> with3mfZipFile(inputStream: InputStream, block: (ZipFile) -> T): T {
        val tempFile = File.createTempFile("model3mf_", ".3mf")
        try {
            tempFile.outputStream().use { out ->
                inputStream.copyTo(out, 64 * 1024)
            }
            return ZipFile(tempFile).use(block)
        } finally {
            tempFile.delete()
        }
    }

    fun extract3mfThumbnail(inputStream: InputStream): ByteArray? = with3mfZipFile(inputStream) { zip ->
        var bestPriority = Int.MAX_VALUE
        var bestEntry: java.util.zip.ZipEntry? = null
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/').trimStart('/').lowercase()
            val priority = when {
                name == "metadata/plate_1.png" -> 0
                name == "metadata/thumbnail.png" -> 1
                name == "thumbnails/thumbnail.png" -> 2
                (name.startsWith("metadata/") || name.startsWith("thumbnails/")) &&
                    (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) -> 3
                else -> Int.MAX_VALUE
            }
            if (priority < bestPriority && entry.size != 0L) {
                bestPriority = priority
                bestEntry = entry
                if (priority == 0) break
            }
        }
        bestEntry?.let { entry ->
            zip.getInputStream(entry).use { it.readBytes() }.takeIf { it.isNotEmpty() }
        }
    }

    @Throws(IOException::class)
    fun load3mf(inputStream: InputStream, maxTriangles: Int = Int.MAX_VALUE): ModelMesh {
        val parsedModels = mutableMapOf<String, ThreeMfModel>()
        var totalEntries = 0
        with3mfZipFile(inputStream) { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                totalEntries++
                if (entry.isDirectory) continue
                val normalized = entry.name.replace('\\', '/').trimStart('/').lowercase()
                if (normalized.endsWith(".model")) {
                    zip.getInputStream(entry).use { entryStream ->
                        parsedModels[normalized] = parse3mfModelXml(
                            BufferedInputStream(entryStream, 64 * 1024),
                            maxTriangles
                        )
                    }
                }
            }
        }
        if (parsedModels.isEmpty()) {
            throw IOException("No .model files found in 3MF archive ($totalEntries entries)")
        }

        val rootKey = when {
            parsedModels.containsKey("3d/3dmodel.model") -> "3d/3dmodel.model"
            else -> parsedModels.keys.first()
        }
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
        parsedModels.clear()

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
                    val maxLen = if (maxTriangles < Int.MAX_VALUE / 9) {
                        maxTriangles * 9
                    } else {
                        Int.MAX_VALUE
                    }
                    val nextLen = (vertices.size + (vertices.size ushr 1)).coerceAtLeast(needed)
                        .coerceAtMost(maxLen)
                    vertices = vertices.copyOf(nextLen)
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

    private fun parse3mfModelXml(xmlStream: InputStream, maxTriangles: Int): ThreeMfModel {
        val objects = mutableMapOf<String, ThreeMfObject>()
        val buildItems = mutableListOf<ThreeMfBuildItem>()

        var currentObjectId: String? = null
        var verts = FloatArray(1024 * 3)
        var vertCount = 0
        var tris = IntArray(1024 * 3)
        var triCount = 0
        var seenTris = 0
        var parseStride = 1
        val maxStoredTris = if (maxTriangles < Int.MAX_VALUE / 2) maxTriangles * 2 else Int.MAX_VALUE
        var components = mutableListOf<ThreeMfComponent>()
        var inMesh = false

        var tagBuf = ByteArray(4096)
        var tagLen = 0
        var inTag = false
        var quoteByte = 0

        val readBuf = ByteArray(64 * 1024)
        while (true) {
            val bytesRead = xmlStream.read(readBuf)
            if (bytesRead <= 0) break
            var i = 0
            while (i < bytesRead) {
                val b = readBuf[i].toInt() and 0xFF
                if (!inTag) {
                    if (b == '<'.code) {
                        inTag = true
                        tagLen = 0
                        quoteByte = 0
                    }
                } else {
                    if (quoteByte != 0) {
                        if (b == quoteByte) {
                            quoteByte = 0
                        }
                        if (tagLen >= tagBuf.size) {
                            tagBuf = tagBuf.copyOf(tagBuf.size * 2)
                        }
                        tagBuf[tagLen++] = b.toByte()
                    } else if (b == '"'.code || b == '\''.code) {
                        quoteByte = b
                        if (tagLen >= tagBuf.size) {
                            tagBuf = tagBuf.copyOf(tagBuf.size * 2)
                        }
                        tagBuf[tagLen++] = b.toByte()
                    } else if (b == '>'.code) {
                        inTag = false
                        // Process completed tag in tagBuf[0 until tagLen]
                        var p = 0
                        while (p < tagLen && tagBuf[p].toInt() <= 32) p++
                        if (p < tagLen) {
                            val first = tagBuf[p].toInt() and 0xFF
                            if (first == '/'.code) {
                                // Closing tag
                                p++
                                val nameStart = p
                                while (p < tagLen && tagBuf[p].toInt() > 32) p++
                                val localStart = findLocalNameStart(tagBuf, nameStart, p)
                                when {
                                    equalsAscii(tagBuf, localStart, p, "mesh") -> inMesh = false
                                    equalsAscii(tagBuf, localStart, p, "object") -> {
                                        val id = currentObjectId
                                        if (id != null) {
                                            objects[id] = ThreeMfObject(
                                                meshVertices = if (vertCount > 0) verts.copyOf(vertCount * 3) else null,
                                                meshIndices = if (triCount > 0) tris.copyOf(triCount * 3) else null,
                                                components = components
                                            )
                                        }
                                        verts = FloatArray(0)
                                        tris = IntArray(0)
                                        currentObjectId = null
                                    }
                                }
                            } else if (first != '!'.code && first != '?'.code) {
                                // Opening or self-closing tag
                                var nameEnd = p
                                while (nameEnd < tagLen) {
                                    val c = tagBuf[nameEnd].toInt() and 0xFF
                                    if (c <= 32 || c == '/'.code) break
                                    nameEnd++
                                }
                                val localStart = findLocalNameStart(tagBuf, p, nameEnd)
                                when {
                                    inMesh && equalsAscii(tagBuf, localStart, nameEnd, "vertex") -> {
                                        var x = 0f
                                        var y = 0f
                                        var z = 0f
                                        var pos = nameEnd
                                        while (pos < tagLen) {
                                            while (pos < tagLen && (tagBuf[pos].toInt() and 0xFF) <= 32) pos++
                                            if (pos >= tagLen || tagBuf[pos] == '/'.code.toByte()) break
                                            val aStart = pos
                                            while (pos < tagLen && tagBuf[pos] != '='.code.toByte() && (tagBuf[pos].toInt() and 0xFF) > 32) pos++
                                            val aEnd = pos
                                            while (pos < tagLen && tagBuf[pos] != '"'.code.toByte() && tagBuf[pos] != '\''.code.toByte()) pos++
                                            if (pos >= tagLen) break
                                            val q = tagBuf[pos++]
                                            val vStart = pos
                                            while (pos < tagLen && tagBuf[pos] != q) pos++
                                            val vEnd = pos
                                            if (pos < tagLen) pos++
                                            val aLocal = findLocalNameStart(tagBuf, aStart, aEnd)
                                            when {
                                                equalsAscii(tagBuf, aLocal, aEnd, "x") -> x = parseAsciiFloat(tagBuf, vStart, vEnd)
                                                equalsAscii(tagBuf, aLocal, aEnd, "y") -> y = parseAsciiFloat(tagBuf, vStart, vEnd)
                                                equalsAscii(tagBuf, aLocal, aEnd, "z") -> z = parseAsciiFloat(tagBuf, vStart, vEnd)
                                            }
                                        }
                                        val needed = (vertCount + 1) * 3
                                        if (needed > verts.size) {
                                            val nextLen = (verts.size + (verts.size ushr 1)).coerceAtLeast(needed)
                                            verts = verts.copyOf(nextLen)
                                        }
                                        val base = vertCount * 3
                                        verts[base] = x
                                        verts[base + 1] = y
                                        verts[base + 2] = z
                                        vertCount++
                                    }
                                    inMesh && equalsAscii(tagBuf, localStart, nameEnd, "triangle") -> {
                                        val take = (seenTris % parseStride) == 0
                                        seenTris++
                                        if (take) {
                                            var v1 = -1
                                            var v2 = -1
                                            var v3 = -1
                                            var pos = nameEnd
                                            while (pos < tagLen) {
                                                while (pos < tagLen && (tagBuf[pos].toInt() and 0xFF) <= 32) pos++
                                                if (pos >= tagLen || tagBuf[pos] == '/'.code.toByte()) break
                                                val aStart = pos
                                                while (pos < tagLen && tagBuf[pos] != '='.code.toByte() && (tagBuf[pos].toInt() and 0xFF) > 32) pos++
                                                val aEnd = pos
                                                while (pos < tagLen && tagBuf[pos] != '"'.code.toByte() && tagBuf[pos] != '\''.code.toByte()) pos++
                                                if (pos >= tagLen) break
                                                val q = tagBuf[pos++]
                                                val vStart = pos
                                                while (pos < tagLen && tagBuf[pos] != q) pos++
                                                val vEnd = pos
                                                if (pos < tagLen) pos++
                                                val aLocal = findLocalNameStart(tagBuf, aStart, aEnd)
                                                when {
                                                    equalsAscii(tagBuf, aLocal, aEnd, "v1") -> v1 = parseAsciiInt(tagBuf, vStart, vEnd)
                                                    equalsAscii(tagBuf, aLocal, aEnd, "v2") -> v2 = parseAsciiInt(tagBuf, vStart, vEnd)
                                                    equalsAscii(tagBuf, aLocal, aEnd, "v3") -> v3 = parseAsciiInt(tagBuf, vStart, vEnd)
                                                }
                                            }
                                            if (v1 >= 0 && v2 >= 0 && v3 >= 0) {
                                                if (triCount >= maxStoredTris) {
                                                    // Compact existing triangles 2:1 and double sampling stride
                                                    var dst = 0
                                                    var src = 0
                                                    while (src < triCount) {
                                                        val sBase = src * 3
                                                        val dBase = dst * 3
                                                        tris[dBase] = tris[sBase]
                                                        tris[dBase + 1] = tris[sBase + 1]
                                                        tris[dBase + 2] = tris[sBase + 2]
                                                        dst++
                                                        src += 2
                                                    }
                                                    triCount = dst
                                                    parseStride *= 2
                                                }
                                                val needed = (triCount + 1) * 3
                                                if (needed > tris.size) {
                                                    val nextLen = (tris.size + (tris.size ushr 1)).coerceAtLeast(needed)
                                                    tris = tris.copyOf(nextLen)
                                                }
                                                val base = triCount * 3
                                                tris[base] = v1
                                                tris[base + 1] = v2
                                                tris[base + 2] = v3
                                                triCount++
                                            }
                                        }
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "mesh") -> inMesh = true
                                    equalsAscii(tagBuf, localStart, nameEnd, "object") -> {
                                        currentObjectId = findAsciiAttr(tagBuf, nameEnd, tagLen, "id")
                                        if (verts.isEmpty()) verts = FloatArray(1024 * 3)
                                        vertCount = 0
                                        if (tris.isEmpty()) tris = IntArray(1024 * 3)
                                        triCount = 0
                                        seenTris = 0
                                        parseStride = 1
                                        components = mutableListOf()
                                        inMesh = false
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "component") -> if (currentObjectId != null) {
                                        val refId = findAsciiAttr(tagBuf, nameEnd, tagLen, "objectid")
                                        val refPath = findAsciiAttr(tagBuf, nameEnd, tagLen, "path")
                                        val transform = parse3mfTransform(
                                            findAsciiAttr(tagBuf, nameEnd, tagLen, "transform")
                                        )
                                        if (refId != null) {
                                            components.add(ThreeMfComponent(refId, refPath, transform))
                                        }
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "item") -> {
                                        val itemId = findAsciiAttr(tagBuf, nameEnd, tagLen, "objectid")
                                        val transform = parse3mfTransform(
                                            findAsciiAttr(tagBuf, nameEnd, tagLen, "transform")
                                        )
                                        if (itemId != null) {
                                            buildItems.add(ThreeMfBuildItem(itemId, transform))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (tagLen >= tagBuf.size) {
                            tagBuf = tagBuf.copyOf(tagBuf.size * 2)
                        }
                        tagBuf[tagLen++] = b.toByte()
                    }
                }
                i++
            }
        }
        return ThreeMfModel(objects, buildItems)
    }

    private fun findLocalNameStart(buf: ByteArray, start: Int, end: Int): Int {
        var idx = start
        while (idx < end) {
            if (buf[idx] == ':'.code.toByte()) return idx + 1
            idx++
        }
        return start
    }

    private fun equalsAscii(buf: ByteArray, start: Int, end: Int, target: String): Boolean {
        val len = end - start
        if (len != target.length) return false
        for (i in 0 until len) {
            if ((buf[start + i].toInt() and 0xFF) != target[i].code) return false
        }
        return true
    }

    private fun findAsciiAttr(buf: ByteArray, start: Int, end: Int, attrName: String): String? {
        var pos = start
        while (pos < end) {
            while (pos < end && (buf[pos].toInt() and 0xFF) <= 32) pos++
            if (pos >= end || buf[pos] == '/'.code.toByte()) break
            val aStart = pos
            while (pos < end && buf[pos] != '='.code.toByte() && (buf[pos].toInt() and 0xFF) > 32) pos++
            val aEnd = pos
            while (pos < end && buf[pos] != '"'.code.toByte() && buf[pos] != '\''.code.toByte()) pos++
            if (pos >= end) break
            val q = buf[pos++]
            val vStart = pos
            while (pos < end && buf[pos] != q) pos++
            val vEnd = pos
            if (pos < end) pos++
            val aLocal = findLocalNameStart(buf, aStart, aEnd)
            if (equalsAscii(buf, aLocal, aEnd, attrName)) {
                return String(buf, vStart, vEnd - vStart, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun parseAsciiInt(buf: ByteArray, start: Int, end: Int): Int {
        var p = start
        while (p < end && (buf[p].toInt() and 0xFF) <= 32) p++
        var endTrim = end
        while (endTrim > p && (buf[endTrim - 1].toInt() and 0xFF) <= 32) endTrim--
        if (p >= endTrim) return -1
        var value = 0
        while (p < endTrim) {
            val d = (buf[p].toInt() and 0xFF) - '0'.code
            if (d !in 0..9) return -1
            value = value * 10 + d
            p++
        }
        return value
    }

    private fun parseAsciiFloat(buf: ByteArray, start: Int, end: Int): Float {
        var p = start
        while (p < end && (buf[p].toInt() and 0xFF) <= 32) p++
        var endTrim = end
        while (endTrim > p && (buf[endTrim - 1].toInt() and 0xFF) <= 32) endTrim--
        if (p >= endTrim) return 0f
        var sign = 1f
        val first = buf[p].toInt() and 0xFF
        if (first == '-'.code) {
            sign = -1f
            p++
        } else if (first == '+'.code) {
            p++
        }
        var intPart = 0.0
        var fracPart = 0.0
        var fracDiv = 1.0
        var seenDot = false
        while (p < endTrim) {
            val c = buf[p].toInt() and 0xFF
            when {
                c in '0'.code..'9'.code -> {
                    val d = c - '0'.code
                    if (!seenDot) {
                        intPart = intPart * 10.0 + d
                    } else {
                        fracPart = fracPart * 10.0 + d
                        fracDiv *= 10.0
                    }
                }
                c == '.'.code && !seenDot -> seenDot = true
                c == 'e'.code || c == 'E'.code -> {
                    // Fallback for scientific notation
                    return String(buf, start, end - start, Charsets.US_ASCII).toFloatOrNull() ?: 0f
                }
                else -> return String(buf, start, end - start, Charsets.US_ASCII).toFloatOrNull() ?: 0f
            }
            p++
        }
        return (sign * (intPart + fracPart / fracDiv)).toFloat()
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
