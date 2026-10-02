package com.nuvio.app.features.player.chapters

import java.nio.charset.Charset

/**
 * Reads chapter markers from MP4 / MOV / M4V files without going through the stock Media3 1.8
 * Mp4Extractor, which predates chapter support.
 *
 * Mirrors Media3 1.11's MP4 chapter support: QuickTime chapter tracks (a text track referenced
 * through `tref/chap`) are preferred, and Nero chapters (`moov/udta/chpl`) are the fallback.
 *
 * Only the top-level box headers, the `moov` box and the chapter text samples are fetched.
 */
internal object Mp4ChapterProbe {

    private const val MAX_MOOV_BYTES = 32L * 1024 * 1024
    private const val MAX_TOP_LEVEL_BOXES = 64
    private const val MAX_CHAPTER_SAMPLES = 512
    private const val MAX_SAMPLE_BYTES = 4096
    private const val MAX_BATCH_SPAN = 1024 * 1024

    private val TOP_LEVEL_TYPES = setOf(
        "ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "styp", "sidx", "moof", "meta"
    )

    fun probe(source: ChapterByteSource, head: ByteArray): List<EmbeddedChapter> {
        if (head.size < 8 || boxType(head, 4) !in TOP_LEVEL_TYPES) return emptyList()

        val moov = findAndReadMoov(source, head) ?: return emptyList()
        val parsed = parseMoov(moov)
        val quickTime = readQuickTimeChapters(source, parsed)
        if (quickTime.isNotEmpty()) return quickTime
        return parsed.neroChapters
    }

    // ---- Top level -------------------------------------------------------------------------

    private fun findAndReadMoov(source: ChapterByteSource, head: ByteArray): ByteArray? {
        var offset = 0L
        repeat(MAX_TOP_LEVEL_BOXES) {
            val header = if (offset + 16 <= head.size) {
                head.copyOfRange(offset.toInt(), offset.toInt() + 16)
            } else {
                source.read(offset, 16) ?: return null
            }
            if (header.size < 8) return null
            var size = readUInt32(header, 0)
            val type = boxType(header, 4)
            var headerSize = 8
            if (size == 1L) {
                if (header.size < 16) return null
                size = readInt64(header, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (source.length ?: return null) - offset
            }
            if (size < headerSize) return null
            if (type == "moov") {
                if (size > MAX_MOOV_BYTES) return null
                val end = offset + size
                if (end <= head.size) return head.copyOfRange(offset.toInt(), end.toInt())
                val bytes = source.read(offset, size.toInt()) ?: return null
                return if (bytes.size.toLong() == size) bytes else null
            }
            offset += size
            val length = source.length
            if (length != null && offset >= length) return null
        }
        return null
    }

    // ---- moov parsing ----------------------------------------------------------------------

    private class TrackInfo {
        var id: Int = -1
        var chapterTrackId: Int = -1
        var timescale: Long = 0
        var durationUnits: Long = 0
        var handler: String? = null
        var sttsCounts: IntArray = IntArray(0)
        var sttsDeltas: LongArray = LongArray(0)
        var stscFirstChunk: IntArray = IntArray(0)
        var stscSamplesPerChunk: IntArray = IntArray(0)
        var sampleSizes: IntArray = IntArray(0)
        var fixedSampleSize: Int = 0
        var sampleCount: Int = 0
        var chunkOffsets: LongArray = LongArray(0)
    }

    private class MoovInfo(
        val movieTimescale: Long,
        val movieDuration: Long,
        val tracks: List<TrackInfo>,
        val neroChapters: List<EmbeddedChapter>
    )

    private fun parseMoov(moov: ByteArray): MoovInfo {
        var movieTimescale = 0L
        var movieDuration = 0L
        val tracks = ArrayList<TrackInfo>()
        var nero = emptyList<EmbeddedChapter>()
        forEachChild(moov, 8, moov.size) { type, start, end ->
            when (type) {
                "mvhd" -> {
                    val version = moov[start].toInt() and 0xFF
                    if (version == 1) {
                        movieTimescale = readUInt32(moov, start + 20)
                        movieDuration = readInt64(moov, start + 24)
                    } else {
                        movieTimescale = readUInt32(moov, start + 12)
                        movieDuration = readUInt32(moov, start + 16)
                    }
                }
                "trak" -> tracks += parseTrak(moov, start, end)
                "udta" -> forEachChild(moov, start, end) { childType, cStart, cEnd ->
                    if (childType == "chpl") nero = parseChpl(moov, cStart, cEnd)
                }
            }
        }
        val durationMs = if (movieTimescale > 0) movieDuration * 1000 / movieTimescale else 0L
        val neroWithEnds = nero.mapIndexed { index, chapter ->
            val nextStart = nero.getOrNull(index + 1)?.startMs
            chapter.copy(endMs = nextStart ?: durationMs.takeIf { it > chapter.startMs })
        }
        return MoovInfo(movieTimescale, movieDuration, tracks, neroWithEnds)
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int): TrackInfo {
        val track = TrackInfo()
        forEachChild(data, start, end) { type, s, e ->
            when (type) {
                "tkhd" -> {
                    val version = data[s].toInt() and 0xFF
                    track.id = readInt32(data, s + if (version == 1) 20 else 12)
                }
                "tref" -> forEachChild(data, s, e) { refType, rs, re ->
                    if (refType == "chap" && re - rs >= 4) track.chapterTrackId = readInt32(data, rs)
                }
                "mdia" -> forEachChild(data, s, e) { mType, ms, me ->
                    when (mType) {
                        "mdhd" -> {
                            val version = data[ms].toInt() and 0xFF
                            if (version == 1) {
                                track.timescale = readUInt32(data, ms + 20)
                                track.durationUnits = readInt64(data, ms + 24)
                            } else {
                                track.timescale = readUInt32(data, ms + 12)
                                track.durationUnits = readUInt32(data, ms + 16)
                            }
                        }
                        "hdlr" -> if (me - ms >= 12) track.handler = boxType(data, ms + 8)
                        "minf" -> forEachChild(data, ms, me) { nType, ns, ne ->
                            if (nType == "stbl") parseStbl(data, ns, ne, track)
                        }
                    }
                }
            }
        }
        return track
    }

    private fun parseStbl(data: ByteArray, start: Int, end: Int, track: TrackInfo) {
        forEachChild(data, start, end) { type, s, _ ->
            when (type) {
                "stts" -> {
                    val count = readInt32(data, s + 4).coerceIn(0, 1_000_000)
                    track.sttsCounts = IntArray(count) { readInt32(data, s + 8 + it * 8) }
                    track.sttsDeltas = LongArray(count) { readUInt32(data, s + 12 + it * 8) }
                }
                "stsc" -> {
                    val count = readInt32(data, s + 4).coerceIn(0, 1_000_000)
                    track.stscFirstChunk = IntArray(count) { readInt32(data, s + 8 + it * 12) }
                    track.stscSamplesPerChunk = IntArray(count) { readInt32(data, s + 12 + it * 12) }
                }
                "stsz" -> {
                    track.fixedSampleSize = readInt32(data, s + 4)
                    track.sampleCount = readInt32(data, s + 8).coerceIn(0, 10_000_000)
                    if (track.fixedSampleSize == 0) {
                        val n = minOf(track.sampleCount, MAX_CHAPTER_SAMPLES)
                        track.sampleSizes = IntArray(n) { readInt32(data, s + 12 + it * 4) }
                    }
                }
                "stco" -> {
                    val count = readInt32(data, s + 4).coerceIn(0, 1_000_000)
                    track.chunkOffsets = LongArray(minOf(count, MAX_CHAPTER_SAMPLES)) { readUInt32(data, s + 8 + it * 4) }
                }
                "co64" -> {
                    val count = readInt32(data, s + 4).coerceIn(0, 1_000_000)
                    track.chunkOffsets = LongArray(minOf(count, MAX_CHAPTER_SAMPLES)) { readInt64(data, s + 8 + it * 8) }
                }
            }
        }
    }

    private fun parseChpl(data: ByteArray, start: Int, end: Int): List<EmbeddedChapter> {
        // 1 byte version + 3 bytes flags + 1 byte reserved, then a 32-bit count.
        var pos = start + 5
        if (pos + 4 > end) return emptyList()
        val count = readInt32(data, pos)
        pos += 4
        val chapters = ArrayList<EmbeddedChapter>()
        for (i in 0 until count.coerceIn(0, MAX_CHAPTER_SAMPLES)) {
            if (pos + 9 > end) break
            val startMs = readInt64(data, pos) / 10_000 // 100 ns units
            pos += 8
            val titleLength = data[pos].toInt() and 0xFF
            pos += 1
            if (pos + titleLength > end) break
            val title = String(data, pos, titleLength, Charsets.UTF_8)
            pos += titleLength
            if (startMs >= 0) chapters += EmbeddedChapter(startMs, null, title)
        }
        return chapters
    }

    // ---- QuickTime chapter track -----------------------------------------------------------

    private fun readQuickTimeChapters(source: ChapterByteSource, moov: MoovInfo): List<EmbeddedChapter> {
        val chapterIds = moov.tracks.mapNotNull { it.chapterTrackId.takeIf { id -> id > 0 } }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val track = moov.tracks.firstOrNull { it.id in chapterIds && it.timescale > 0 } ?: return emptyList()
        val sampleCount = minOf(track.sampleCount, MAX_CHAPTER_SAMPLES)
        if (sampleCount == 0) return emptyList()

        // Sample offsets from stsc + stco/co64 + stsz.
        val offsets = LongArray(sampleCount)
        val sizes = IntArray(sampleCount) { idx ->
            if (track.fixedSampleSize != 0) track.fixedSampleSize else track.sampleSizes.getOrElse(idx) { 0 }
        }
        var sampleIndex = 0
        var stscIndex = 0
        chunkLoop@ for (chunk in track.chunkOffsets.indices) {
            val chunkNumber = chunk + 1
            while (stscIndex + 1 < track.stscFirstChunk.size && track.stscFirstChunk[stscIndex + 1] <= chunkNumber) {
                stscIndex++
            }
            val perChunk = track.stscSamplesPerChunk.getOrElse(stscIndex) { 1 }.coerceAtLeast(1)
            var offset = track.chunkOffsets[chunk]
            for (k in 0 until perChunk) {
                if (sampleIndex >= sampleCount) break@chunkLoop
                offsets[sampleIndex] = offset
                offset += sizes[sampleIndex]
                sampleIndex++
            }
        }
        if (sampleIndex == 0) return emptyList()

        // Sample start times from stts.
        val startsMs = LongArray(sampleIndex)
        var t = 0L
        var n = 0
        sttsLoop@ for (i in track.sttsCounts.indices) {
            for (k in 0 until track.sttsCounts[i].coerceAtLeast(0)) {
                if (n >= sampleIndex) break@sttsLoop
                startsMs[n++] = t * 1000 / track.timescale
                t += track.sttsDeltas[i]
            }
        }
        val trackDurationMs = track.durationUnits * 1000 / track.timescale
        val movieDurationMs = if (moov.movieTimescale > 0) moov.movieDuration * 1000 / moov.movieTimescale else 0L
        val endOfMediaMs = maxOf(trackDurationMs, movieDurationMs)

        val texts = readSamples(source, offsets, sizes, sampleIndex)
        val chapters = ArrayList<EmbeddedChapter>(sampleIndex)
        for (i in 0 until sampleIndex) {
            val endMs = if (i + 1 < sampleIndex) startsMs[i + 1] else endOfMediaMs.takeIf { it > startsMs[i] }
            chapters += EmbeddedChapter(startsMs[i], endMs, texts.getOrNull(i))
        }
        return chapters
    }

    private fun readSamples(source: ChapterByteSource, offsets: LongArray, sizes: IntArray, count: Int): List<String?> {
        val result = arrayOfNulls<String>(count)
        val min = (0 until count).minOf { offsets[it] }
        val max = (0 until count).maxOf { offsets[it] + sizes[it].coerceIn(0, MAX_SAMPLE_BYTES) }
        val span = max - min
        if (span in 1..MAX_BATCH_SPAN) {
            val block = source.read(min, span.toInt()) ?: return result.toList()
            for (i in 0 until count) {
                val rel = (offsets[i] - min).toInt()
                val size = sizes[i].coerceIn(0, MAX_SAMPLE_BYTES)
                if (rel + size <= block.size) result[i] = decodeTextSample(block, rel, size)
            }
        } else {
            for (i in 0 until minOf(count, 64)) {
                val size = sizes[i].coerceIn(0, MAX_SAMPLE_BYTES)
                val bytes = source.read(offsets[i], size) ?: continue
                result[i] = decodeTextSample(bytes, 0, bytes.size)
            }
        }
        return result.toList()
    }

    /** QuickTime / tx3g text sample: 16-bit length followed by UTF-8 or BOM-marked UTF-16 text. */
    private fun decodeTextSample(data: ByteArray, offset: Int, size: Int): String? {
        if (size < 2) return null
        val length = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val textLength = minOf(length, size - 2)
        if (textLength <= 0) return null
        val start = offset + 2
        val charset: Charset = if (textLength >= 2 &&
            ((data[start].toInt() and 0xFF) == 0xFE && (data[start + 1].toInt() and 0xFF) == 0xFF ||
                (data[start].toInt() and 0xFF) == 0xFF && (data[start + 1].toInt() and 0xFF) == 0xFE)
        ) Charsets.UTF_16 else Charsets.UTF_8
        return String(data, start, textLength, charset).trim().takeIf { it.isNotEmpty() }
    }

    // ---- Box helpers -----------------------------------------------------------------------

    /** Iterates the child boxes in [start, end); passes each child's payload range. */
    private inline fun forEachChild(
        data: ByteArray,
        start: Int,
        end: Int,
        block: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit
    ) {
        var pos = start
        while (pos + 8 <= end) {
            var size = readUInt32(data, pos)
            val type = boxType(data, pos + 4)
            var headerSize = 8
            if (size == 1L) {
                if (pos + 16 > end) return
                size = readInt64(data, pos + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < headerSize || pos + size > end) return
            block(type, pos + headerSize, (pos + size).toInt())
            pos += size.toInt()
        }
    }

    private fun boxType(data: ByteArray, offset: Int): String =
        String(data, offset, 4, Charsets.ISO_8859_1)

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readUInt32(data: ByteArray, offset: Int): Long = readInt32(data, offset).toLong() and 0xFFFFFFFFL

    private fun readInt64(data: ByteArray, offset: Int): Long =
        (readUInt32(data, offset) shl 32) or readUInt32(data, offset + 4)
}
