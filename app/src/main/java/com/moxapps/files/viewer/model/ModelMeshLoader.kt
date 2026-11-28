package com.moxapps.files.viewer.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

    private inline fun <T> with3mfZipFile(inputStream: InputStream, block: (ZipFile) -> T): T =
        with3mfTempFile(inputStream) { file -> ZipFile(file).use(block) }

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

    fun extract3mfPlatePreview(file: File, plateNumber: Int = 1): ByteArray? {
        ZipFile(file).use { zip ->
            val candidates = listOf(
                "metadata/plate_${plateNumber}.png",
                "metadata/plate_1.png",
                "metadata/thumbnail.png",
                "thumbnails/thumbnail.png"
            )
            val entriesByName = mutableMapOf<String, java.util.zip.ZipEntry>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                entriesByName[entry.name.replace('\\', '/').trimStart('/').lowercase()] = entry
            }
            for (c in candidates) {
                val match = entriesByName[c] ?: continue
                val bytes = zip.getInputStream(match).use { it.readBytes() }
                if (bytes.isNotEmpty()) return bytes
            }
        }
        return null
    }

    @Throws(IOException::class)
    fun load3mf(
        inputStream: InputStream,
        maxTriangles: Int = Int.MAX_VALUE,
        plateIndex: Int = 0,
        plateBedWidth: Float = 256f,
        plateBedDepth: Float = 256f
    ): ModelMesh = with3mfTempFile(inputStream) { file ->
        load3mfFile(file, maxTriangles, plateIndex, plateBedWidth, plateBedDepth)
    }

    private inline fun <T> with3mfTempFile(inputStream: InputStream, block: (File) -> T): T {
        val tempFile = File.createTempFile("model3mf_", ".3mf")
        try {
            tempFile.outputStream().use { out ->
                inputStream.copyTo(out, 64 * 1024)
            }
            return block(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    @Throws(IOException::class)
    fun load3mfFile(
        file: File,
        maxTriangles: Int = Int.MAX_VALUE,
        plateIndex: Int = 0,
        plateBedWidth: Float = 256f,
        plateBedDepth: Float = 256f
    ): ModelMesh {
        val parsedModels = mutableMapOf<String, ThreeMfModel>()
        var bambuFilamentColors: IntArray = IntArray(0)
        var modelSettings = BambuModelSettings(emptyMap(), emptyMap(), emptySet(), emptyList())
        var totalEntries = 0
        val modelEntries = mutableMapOf<String, java.util.zip.ZipEntry>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                totalEntries++
                if (entry.isDirectory) continue
                val normalized = entry.name.replace('\\', '/').trimStart('/').lowercase()
                when {
                    normalized == "metadata/project_settings.config" -> {
                        val jsonText = zip.getInputStream(entry).use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        }
                        bambuFilamentColors = parseBambuFilamentColors(jsonText)
                    }
                    normalized == "metadata/model_settings.config" -> {
                        val xmlText = zip.getInputStream(entry).use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        }
                        modelSettings = parseBambuModelSettings(xmlText)
                    }
                    normalized.endsWith(".model") -> {
                        modelEntries[normalized] = entry
                    }
                }
            }
            if (modelEntries.isEmpty()) {
                throw IOException("No .model files found in 3MF archive ($totalEntries entries)")
            }
            val rootKey = if (modelEntries.containsKey("3d/3dmodel.model")) {
                "3d/3dmodel.model"
            } else {
                modelEntries.keys.first()
            }
            val rootModel = zip.getInputStream(modelEntries[rootKey]!!).use { entryStream ->
                parse3mfModelXml(BufferedInputStream(entryStream, 64 * 1024), maxTriangles)
            }
            parsedModels[rootKey] = rootModel

            val plates = modelSettings.plates
            val safePlateIndex = if (plates.isNotEmpty()) {
                plateIndex.coerceIn(0, plates.lastIndex)
            } else {
                0
            }
            val selectedPlateObjectIds = plates.getOrNull(safePlateIndex)?.objectIds ?: emptySet()
            val plateItems = if (rootModel.buildItems.isNotEmpty()) {
                if (selectedPlateObjectIds.isNotEmpty()) {
                    rootModel.buildItems.filter { it.objectId in selectedPlateObjectIds }
                        .ifEmpty { rootModel.buildItems }
                } else {
                    rootModel.buildItems
                }
            } else {
                emptyList()
            }

            // Only parse external .model files referenced by the selected plate
            val neededPaths = mutableSetOf<String>()
            fun collectNeededPaths(model: ThreeMfModel, objectId: String, depth: Int) {
                if (depth > 16) return
                val obj = model.objects[objectId] ?: return
                for (comp in obj.components) {
                    val cp = comp.path?.trimStart('/')?.lowercase()
                    if (cp != null && cp != rootKey && cp in modelEntries) {
                        neededPaths.add(cp)
                    } else {
                        collectNeededPaths(model, comp.objectId, depth + 1)
                    }
                }
            }
            if (plateItems.isNotEmpty()) {
                for (item in plateItems) collectNeededPaths(rootModel, item.objectId, 0)
            } else {
                for ((id, _) in rootModel.objects) collectNeededPaths(rootModel, id, 0)
            }
            if (neededPaths.size > 1) {
                val parsedExternal = runBlocking(Dispatchers.Default) {
                    neededPaths.map { path ->
                        async {
                            ZipFile(file).use { workerZip ->
                                val entry = workerZip.entries().asSequence().firstOrNull {
                                    !it.isDirectory &&
                                        it.name.replace('\\', '/').trimStart('/').lowercase() == path
                                } ?: return@async null
                                val model = workerZip.getInputStream(entry).use { entryStream ->
                                    parse3mfModelXml(
                                        BufferedInputStream(entryStream, 64 * 1024),
                                        maxTriangles
                                    )
                                }
                                path to model
                            }
                        }
                    }.awaitAll()
                }
                for (pair in parsedExternal) {
                    if (pair != null) parsedModels[pair.first] = pair.second
                }
            } else {
                for (path in neededPaths) {
                    val entry = modelEntries[path] ?: continue
                    zip.getInputStream(entry).use { entryStream ->
                        parsedModels[path] = parse3mfModelXml(
                            BufferedInputStream(entryStream, 64 * 1024),
                            maxTriangles
                        )
                    }
                }
            }
        }
        val objectExtruders = modelSettings.objectExtruders
        val partExtruders = modelSettings.partExtruders
        val skippedParts = modelSettings.skippedParts
        val plates = modelSettings.plates
        val safePlateIndex = if (plates.isNotEmpty()) {
            plateIndex.coerceIn(0, plates.lastIndex)
        } else {
            0
        }
        val selectedPlateObjectIds = plates.getOrNull(safePlateIndex)?.objectIds ?: emptySet()

        val rootKey = when {
            parsedModels.containsKey("3d/3dmodel.model") -> "3d/3dmodel.model"
            else -> parsedModels.keys.first()
        }
        val rootModel = parsedModels[rootKey]!!

        fun countTriangles(model: ThreeMfModel, objectId: String, depth: Int): Int {
            if (depth > 16) return 0
            val obj = model.objects[objectId] ?: return 0
            var total = (obj.meshIndices?.size ?: 0) / 3
            for (comp in obj.components) {
                val targetModel = if (comp.path != null) {
                    parsedModels[comp.path.trimStart('/').lowercase()] ?: model
                } else {
                    model
                }
                total += countTriangles(targetModel, comp.objectId, depth + 1)
            }
            return total
        }
        val plateItems = if (rootModel.buildItems.isNotEmpty()) {
            if (selectedPlateObjectIds.isNotEmpty()) {
                rootModel.buildItems.filter { it.objectId in selectedPlateObjectIds }
                    .ifEmpty { rootModel.buildItems }
            } else {
                rootModel.buildItems
            }
        } else {
            emptyList()
        }
        val expectedTriangles = if (plateItems.isNotEmpty()) {
            plateItems.sumOf { countTriangles(rootModel, it.objectId, 0) }
        } else {
            rootModel.objects.keys.sumOf { countTriangles(rootModel, it, 0) }
        }.coerceAtMost(maxTriangles)

        val collector = TriangleCollector(maxTriangles, expectedTriangles)
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            0f, 0f, 0f
        )

        fun emitObject(
            model: ThreeMfModel,
            objectId: String,
            transform: FloatArray,
            inheritedExtruder: Int,
            depth: Int,
            parentObjectId: String? = null
        ) {
            if (depth > 16) return
            val partKey = if (parentObjectId != null) "$parentObjectId:$objectId" else null
            if (partKey != null && partKey in skippedParts) return
            val obj = model.objects[objectId] ?: return
            val objExtruder = (partKey?.let { partExtruders[it] })
                ?: objectExtruders[objectId]
                ?: inheritedExtruder
            val objFallbackColor = when {
                obj.defaultColor != -1 -> obj.defaultColor
                objExtruder > 0 && objExtruder <= bambuFilamentColors.size ->
                    bambuFilamentColors[objExtruder - 1]
                else -> -1
            }
            if (obj.meshVertices != null && obj.meshIndices != null) {
                collector.addIndexedMesh(
                    meshVerts = obj.meshVertices,
                    indices = obj.meshIndices,
                    m = transform,
                    triColors = obj.meshTriColors,
                    triPaintExtruders = obj.meshPaintExtruders,
                    bambuFilamentColors = bambuFilamentColors,
                    fallbackColor = objFallbackColor
                )
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
                emitObject(targetModel, comp.objectId, combined, objExtruder, depth + 1, objectId)
            }
        }

        if (rootModel.buildItems.isNotEmpty()) {
            val plateItems = if (selectedPlateObjectIds.isNotEmpty()) {
                rootModel.buildItems.filter { it.objectId in selectedPlateObjectIds }
                    .ifEmpty { rootModel.buildItems }
            } else {
                rootModel.buildItems
            }
            for (item in plateItems) {
                val xform = item.transform ?: identity
                emitObject(rootModel, item.objectId, xform, 0, 0)
            }
        } else {
            for ((id, _) in rootModel.objects) {
                emitObject(rootModel, id, identity, 0, 0)
            }
        }
        parsedModels.clear()

        if (collector.triangleCount == 0) {
            throw IOException("No mesh triangles found in 3MF archive")
        }
        return ModelMesh.fromCadTriangles(
            rawVertices = collector.vertices,
            triangleCount = collector.triangleCount,
            rawColors = if (collector.hasColors) collector.colors else null,
            showBuildPlate = true,
            plateBedWidth = plateBedWidth,
            plateBedDepth = plateBedDepth,
            plates = plates,
            selectedPlateIndex = safePlateIndex
        )
    }

    private class TriangleCollector(
        private val maxTriangles: Int,
        expectedTriangles: Int = 4096
    ) {
        var vertices = FloatArray(expectedTriangles.coerceAtLeast(1) * 9)
        var colors = ByteArray(0)
        var hasColors = false
        var triangleCount = 0

        fun addIndexedMesh(
            meshVerts: FloatArray,
            indices: IntArray,
            m: FloatArray,
            triColors: IntArray? = null,
            triPaintExtruders: ShortArray? = null,
            bambuFilamentColors: IntArray = IntArray(0),
            fallbackColor: Int = -1
        ) {
            val triTotal = indices.size / 3
            val remainingBudget = (maxTriangles - triangleCount).coerceAtLeast(0)
            if (remainingBudget == 0) return
            val stride = if (triTotal > remainingBudget) {
                ceil(triTotal.toDouble() / remainingBudget.toDouble()).toInt().coerceAtLeast(1)
            } else {
                1
            }
            val meshHasAnyColor = triColors != null || triPaintExtruders != null || fallbackColor != -1
            if (meshHasAnyColor && !hasColors) {
                hasColors = true
                colors = ByteArray(vertices.size)
                // Backfill any previously emitted uncolored triangles with default color
                var p = 0
                val prevBytes = triangleCount * 9
                while (p < prevBytes) {
                    colors[p] = DEFAULT_COLOR_R
                    colors[p + 1] = DEFAULT_COLOR_G
                    colors[p + 2] = DEFAULT_COLOR_B
                    p += 3
                }
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
                    if (hasColors) {
                        colors = colors.copyOf(nextLen)
                    }
                }
                val out = triangleCount * 9
                transformPoint(meshVerts, i0, m, vertices, out)
                transformPoint(meshVerts, i1, m, vertices, out + 3)
                transformPoint(meshVerts, i2, m, vertices, out + 6)
                if (hasColors) {
                    val paintExt = triPaintExtruders?.getOrNull(t)?.toInt() ?: 0
                    val rgb = when {
                        paintExt > 0 && paintExt <= bambuFilamentColors.size ->
                            bambuFilamentColors[paintExt - 1]
                        triColors != null && t < triColors.size && triColors[t] != -1 ->
                            triColors[t]
                        fallbackColor != -1 -> fallbackColor
                        else -> DEFAULT_MODEL_COLOR
                    }
                    val r = ((rgb ushr 16) and 0xFF).toByte()
                    val g = ((rgb ushr 8) and 0xFF).toByte()
                    val b = (rgb and 0xFF).toByte()
                    colors[out] = r; colors[out + 1] = g; colors[out + 2] = b
                    colors[out + 3] = r; colors[out + 4] = g; colors[out + 5] = b
                    colors[out + 6] = r; colors[out + 7] = g; colors[out + 8] = b
                }
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
        val meshTriColors: IntArray?,
        val meshPaintExtruders: ShortArray?,
        val defaultColor: Int,
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
        val materialGroups = mutableMapOf<String, MutableList<Int>>()
        var currentGroupColors: MutableList<Int>? = null

        var currentObjectId: String? = null
        var currentObjectDefaultColor = -1
        var verts = FloatArray(1024 * 3)
        var vertCount = 0
        var tris = IntArray(1024 * 3)
        var triColors = IntArray(0)
        var hasTriColors = false
        var triPaintExtruders = ShortArray(0)
        var hasPaintExtruders = false
        var triCount = 0
        var seenTris = 0
        var parseStride = 1
        val maxStoredTris = maxTriangles
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
                                    equalsAscii(tagBuf, localStart, p, "basematerials") ||
                                        equalsAscii(tagBuf, localStart, p, "colorgroup") -> {
                                        currentGroupColors = null
                                    }
                                    equalsAscii(tagBuf, localStart, p, "mesh") -> inMesh = false
                                    equalsAscii(tagBuf, localStart, p, "object") -> {
                                        val id = currentObjectId
                                        if (id != null) {
                                            objects[id] = ThreeMfObject(
                                                meshVertices = if (vertCount > 0) verts.copyOf(vertCount * 3) else null,
                                                meshIndices = if (triCount > 0) tris.copyOf(triCount * 3) else null,
                                                meshTriColors = if (hasTriColors && triCount > 0) triColors.copyOf(triCount) else null,
                                                meshPaintExtruders = if (hasPaintExtruders && triCount > 0) triPaintExtruders.copyOf(triCount) else null,
                                                defaultColor = currentObjectDefaultColor,
                                                components = components
                                            )
                                        }
                                        verts = FloatArray(0)
                                        tris = IntArray(0)
                                        triColors = IntArray(0)
                                        triPaintExtruders = ShortArray(0)
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
                                            var triPid: String? = null
                                            var triP1 = -1
                                            var paintExt = 0
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
                                                    equalsAscii(tagBuf, aLocal, aEnd, "p1") -> triP1 = parseAsciiInt(tagBuf, vStart, vEnd)
                                                    equalsAscii(tagBuf, aLocal, aEnd, "pid") -> triPid = String(tagBuf, vStart, vEnd - vStart, Charsets.UTF_8)
                                                    equalsAscii(tagBuf, aLocal, aEnd, "paint_color") -> paintExt = decodeBambuPaintColorExtruder(tagBuf, vStart, vEnd)
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
                                                        if (hasTriColors && src < triColors.size) {
                                                            triColors[dst] = triColors[src]
                                                        }
                                                        if (hasPaintExtruders && src < triPaintExtruders.size) {
                                                            triPaintExtruders[dst] = triPaintExtruders[src]
                                                        }
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
                                                val resolvedTriColor = if (triPid != null && triP1 >= 0) {
                                                    materialGroups[triPid]?.getOrNull(triP1) ?: -1
                                                } else {
                                                    -1
                                                }
                                                if (resolvedTriColor != -1 && !hasTriColors) {
                                                    hasTriColors = true
                                                    triColors = IntArray(tris.size / 3) { -1 }
                                                }
                                                if (hasTriColors) {
                                                    if (triCount >= triColors.size) {
                                                        val oldLen = triColors.size
                                                        triColors = triColors.copyOf(tris.size / 3)
                                                        for (k in oldLen until triColors.size) triColors[k] = -1
                                                    }
                                                    triColors[triCount] = resolvedTriColor
                                                }
                                                if (paintExt > 0 && !hasPaintExtruders) {
                                                    hasPaintExtruders = true
                                                    triPaintExtruders = ShortArray(tris.size / 3)
                                                }
                                                if (hasPaintExtruders) {
                                                    if (triCount >= triPaintExtruders.size) {
                                                        triPaintExtruders = triPaintExtruders.copyOf(tris.size / 3)
                                                    }
                                                    triPaintExtruders[triCount] = paintExt.toShort()
                                                }
                                                val base = triCount * 3
                                                tris[base] = v1
                                                tris[base + 1] = v2
                                                tris[base + 2] = v3
                                                triCount++
                                            }
                                        }
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "basematerials") ||
                                        equalsAscii(tagBuf, localStart, nameEnd, "colorgroup") -> {
                                        val gid = findAsciiAttr(tagBuf, nameEnd, tagLen, "id")
                                        currentGroupColors = if (gid != null) {
                                            materialGroups.getOrPut(gid) { mutableListOf() }
                                        } else {
                                            null
                                        }
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "base") ||
                                        equalsAscii(tagBuf, localStart, nameEnd, "color") -> {
                                        val group = currentGroupColors
                                        if (group != null) {
                                            val hex = findAsciiAttr(tagBuf, nameEnd, tagLen, "displaycolor")
                                                ?: findAsciiAttr(tagBuf, nameEnd, tagLen, "color")
                                            group.add(parseHexColor(hex))
                                        }
                                    }
                                    equalsAscii(tagBuf, localStart, nameEnd, "mesh") -> inMesh = true
                                    equalsAscii(tagBuf, localStart, nameEnd, "object") -> {
                                        currentObjectId = findAsciiAttr(tagBuf, nameEnd, tagLen, "id")
                                        val objPid = findAsciiAttr(tagBuf, nameEnd, tagLen, "pid")
                                        val objPindex = findAsciiAttr(tagBuf, nameEnd, tagLen, "pindex")?.toIntOrNull() ?: 0
                                        currentObjectDefaultColor = if (objPid != null) {
                                            materialGroups[objPid]?.getOrNull(objPindex) ?: -1
                                        } else {
                                            -1
                                        }
                                        if (verts.isEmpty()) verts = FloatArray(1024 * 3)
                                        vertCount = 0
                                        if (tris.isEmpty()) tris = IntArray(1024 * 3)
                                        triCount = 0
                                        hasTriColors = false
                                        triColors = IntArray(0)
                                        hasPaintExtruders = false
                                        triPaintExtruders = ShortArray(0)
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

    private fun parseBambuFilamentColors(jsonText: String): IntArray {
        return try {
            val obj = JSONObject(jsonText)
            val arr = obj.optJSONArray("filament_colour")
                ?: obj.optJSONArray("default_filament_colour")
                ?: return IntArray(0)
            IntArray(arr.length()) { i -> parseHexColor(arr.optString(i)) }
        } catch (_: Exception) {
            IntArray(0)
        }
    }

    private class BambuModelSettings(
        val objectExtruders: Map<String, Int>,
        val partExtruders: Map<String, Int>,
        val skippedParts: Set<String>,
        val plates: List<ThreeMfPlateInfo>
    )

    private fun parseBambuModelSettings(xmlText: String): BambuModelSettings {
        val objectExtruders = mutableMapOf<String, Int>()
        val partExtruders = mutableMapOf<String, Int>()
        val skippedParts = mutableSetOf<String>()
        val plates = mutableListOf<ThreeMfPlateInfo>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(xmlText))
            var currentObjId: String? = null
            var currentPartId: String? = null
            var inPlate = false
            var inModelInstance = false
            var currentPlateId: String? = null
            var currentPlateName: String? = null
            val currentPlateObjects = mutableSetOf<String>()
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "object" -> if (!inPlate) {
                            currentObjId = parser.getAttributeValue(null, "id")
                            currentPartId = null
                        }
                        "part" -> if (!inPlate && currentObjId != null) {
                            currentPartId = parser.getAttributeValue(null, "id")
                            val subtype = parser.getAttributeValue(null, "subtype")
                            if (currentPartId != null && subtype != null && subtype != "normal_part") {
                                skippedParts.add("$currentObjId:$currentPartId")
                            }
                        }
                        "plate" -> {
                            inPlate = true
                            currentPlateId = null
                            currentPlateName = null
                            currentPlateObjects.clear()
                        }
                        "model_instance" -> if (inPlate) {
                            inModelInstance = true
                        }
                        "metadata" -> {
                            val key = parser.getAttributeValue(null, "key")
                            val value = parser.getAttributeValue(null, "value")
                            when {
                                currentObjId != null && key == "extruder" -> {
                                    value?.toIntOrNull()?.let { ext ->
                                        if (currentPartId != null) {
                                            partExtruders["$currentObjId:$currentPartId"] = ext
                                        } else {
                                            objectExtruders[currentObjId] = ext
                                        }
                                    }
                                }
                                inModelInstance && key == "object_id" && value != null -> {
                                    currentPlateObjects.add(value)
                                }
                                inPlate && !inModelInstance && key == "plater_id" -> {
                                    currentPlateId = value
                                }
                                inPlate && !inModelInstance && key == "plater_name" -> {
                                    currentPlateName = value?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                        "part" -> if (!inPlate) {
                            currentPartId = null
                        }
                        "object" -> if (!inPlate) {
                            currentObjId = null
                            currentPartId = null
                        }
                        "model_instance" -> inModelInstance = false
                        "plate" -> {
                            if (currentPlateObjects.isNotEmpty()) {
                                val id = currentPlateId ?: (plates.size + 1).toString()
                                val label = currentPlateName?.let { "Plate $id ($it)" } ?: "Plate $id"
                                plates.add(
                                    ThreeMfPlateInfo(
                                        id = id,
                                        label = label,
                                        objectIds = currentPlateObjects.toSet()
                                    )
                                )
                            }
                            inPlate = false
                            inModelInstance = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return BambuModelSettings(objectExtruders, partExtruders, skippedParts, plates)
    }

    private fun parseHexColor(hex: String?): Int {
        if (hex.isNullOrBlank()) return -1
        val clean = hex.trim().removePrefix("#")
        if (clean.length < 6) return -1
        return clean.substring(0, 6).toIntOrNull(16) ?: -1
    }

    private fun decodeBambuPaintColorExtruder(buf: ByteArray, start: Int, end: Int): Int {
        if (start >= end) return 0
        // Bambu/Prusa paint_color hex: single-state leaf has low 2 bits == 0, upper bits = 1-based extruder.
        var valAcc = 0
        var nibbles = 0
        var p = start
        while (p < end && nibbles < 4) {
            val c = buf[p++].toInt() and 0xFF
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -1
            }
            if (d >= 0) {
                valAcc = (valAcc shl 4) or d
                nibbles++
            }
        }
        if (nibbles == 0) return 0
        val ext = valAcc ushr 2
        return if (ext > 0) ext else 0
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val DEFAULT_MODEL_COLOR = 0x60A0D0
    private const val DEFAULT_COLOR_R: Byte = 0x60.toByte()
    private const val DEFAULT_COLOR_G: Byte = 0xA0.toByte()
    private const val DEFAULT_COLOR_B: Byte = 0xD0.toByte()
}
