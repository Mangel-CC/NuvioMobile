package com.nuvio.app.features.player.chapters

/**
 * Reads Matroska/WebM chapters (the `Chapters` level-1 element) without going through the stock
 * Media3 1.8 MatroskaExtractor, which predates chapter support.
 *
 * Follows Media3 1.11's Matroska chapter handling: the default (or first non-hidden) edition is
 * used, only top-level enabled, non-hidden ChapterAtoms are kept, and the first ChapterDisplay
 * string is the title. The Chapters element is located through the SeekHead, or by walking the
 * Segment's top-level elements up to the first Cluster.
 */
internal object MatroskaChapterProbe {

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_TIME_END = 0x92L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private const val HEAD_BYTES = 64 * 1024
    private const val MAX_TOP_LEVEL_ELEMENTS = 64
    private const val MAX_CHAPTERS_BYTES = 2 * 1024 * 1024

    private class Element(val id: Long, val headerSize: Int, val dataSize: Long, val unknownSize: Boolean)

    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 && readId(head, 0)?.first == ID_EBML

    fun probe(source: ChapterByteSource, head: ByteArray): List<EmbeddedChapter> {
        if (!isMatroska(head)) return emptyList()
        val ebml = readElement(head, 0) ?: return emptyList()
        val segmentOffset = ebml.headerSize + ebml.dataSize
        val segment = readElementAt(source, head, segmentOffset) ?: return emptyList()
        if (segment.id != ID_SEGMENT) return emptyList()
        val segmentDataOffset = segmentOffset + segment.headerSize
        val segmentEnd = if (segment.unknownSize) source.length else segmentDataOffset + segment.dataSize

        var chaptersOffset: Long? = null
        var offset = segmentDataOffset
        for (i in 0 until MAX_TOP_LEVEL_ELEMENTS) {
            if (segmentEnd != null && offset >= segmentEnd) break
            val element = readElementAt(source, head, offset) ?: break
            when (element.id) {
                ID_CHAPTERS -> {
                    chaptersOffset = offset
                    break
                }
                ID_SEEK_HEAD -> if (chaptersOffset == null && !element.unknownSize) {
                    val data = readRange(source, head, offset + element.headerSize, element.dataSize)
                    if (data != null) {
                        findSeekPosition(data, ID_CHAPTERS)?.let { chaptersOffset = segmentDataOffset + it }
                    }
                }
                ID_CLUSTER -> break
            }
            if (element.unknownSize) break
            offset += element.headerSize + element.dataSize
        }

        val start = chaptersOffset ?: return emptyList()
        val chapters = readElementAt(source, head, start) ?: return emptyList()
        if (chapters.id != ID_CHAPTERS || chapters.unknownSize || chapters.dataSize > MAX_CHAPTERS_BYTES) {
            return emptyList()
        }
        val data = readRange(source, head, start + chapters.headerSize, chapters.dataSize) ?: return emptyList()
        return parseChapters(data)
    }

    // ---- Chapters --------------------------------------------------------------------------

    private class Atom {
        var startNs: Long = -1
        var endNs: Long = -1
        var hidden = false
        var enabled = true
        var title: String? = null
    }

    private fun parseChapters(data: ByteArray): List<EmbeddedChapter> {
        var selected: List<Atom>? = null
        var selectedIsDefault = false
        forEachChild(data, 0, data.size) { id, start, end ->
            if (id != ID_EDITION_ENTRY) return@forEachChild
            var isDefault = false
            var isHidden = false
            val atoms = ArrayList<Atom>()
            forEachChild(data, start, end) { childId, cStart, cEnd ->
                when (childId) {
                    ID_EDITION_FLAG_DEFAULT -> isDefault = readUInt(data, cStart, cEnd) == 1L
                    ID_EDITION_FLAG_HIDDEN -> isHidden = readUInt(data, cStart, cEnd) == 1L
                    ID_CHAPTER_ATOM -> atoms += parseAtom(data, cStart, cEnd)
                }
            }
            val usable = atoms.filter { it.enabled && it.startNs >= 0 }
            if (usable.isEmpty()) return@forEachChild
            val preferThis = selected == null || (isDefault && !selectedIsDefault)
            if (preferThis && !(isHidden && selected != null)) {
                selected = usable
                selectedIsDefault = isDefault
            }
        }
        return selected.orEmpty()
            .filterNot { it.hidden }
            .map { atom ->
                EmbeddedChapter(
                    startMs = atom.startNs / 1_000_000,
                    endMs = atom.endNs.takeIf { it > atom.startNs }?.let { it / 1_000_000 },
                    title = atom.title,
                )
            }
    }

    private fun parseAtom(data: ByteArray, start: Int, end: Int): Atom {
        val atom = Atom()
        // Nested ChapterAtoms (sub-chapters) are not descended into.
        forEachChild(data, start, end) { id, cStart, cEnd ->
            when (id) {
                ID_CHAPTER_TIME_START -> atom.startNs = readUInt(data, cStart, cEnd)
                ID_CHAPTER_TIME_END -> atom.endNs = readUInt(data, cStart, cEnd)
                ID_CHAPTER_FLAG_HIDDEN -> atom.hidden = readUInt(data, cStart, cEnd) == 1L
                ID_CHAPTER_FLAG_ENABLED -> atom.enabled = readUInt(data, cStart, cEnd) != 0L
                ID_CHAPTER_DISPLAY -> if (atom.title == null) {
                    forEachChild(data, cStart, cEnd) { dId, dStart, dEnd ->
                        if (dId == ID_CHAP_STRING && atom.title == null) {
                            atom.title = data.decodeToString(dStart, dEnd).trimEnd('\u0000').trim()
                                .takeIf { it.isNotEmpty() }
                        }
                    }
                }
            }
        }
        return atom
    }

    private fun findSeekPosition(seekHead: ByteArray, targetId: Long): Long? {
        var found: Long? = null
        forEachChild(seekHead, 0, seekHead.size) { id, start, end ->
            if (id != ID_SEEK || found != null) return@forEachChild
            var seekId: Long? = null
            var position: Long? = null
            forEachChild(seekHead, start, end) { childId, cStart, cEnd ->
                when (childId) {
                    ID_SEEK_ID -> seekId = readUInt(seekHead, cStart, cEnd)
                    ID_SEEK_POSITION -> position = readUInt(seekHead, cStart, cEnd)
                }
            }
            if (seekId == targetId) found = position
        }
        return found
    }

    // ---- EBML helpers ----------------------------------------------------------------------

    private inline fun forEachChild(
        data: ByteArray,
        start: Int,
        end: Int,
        block: (id: Long, dataStart: Int, dataEnd: Int) -> Unit,
    ) {
        var pos = start
        while (pos < end) {
            val element = readElement(data, pos) ?: return
            if (element.unknownSize) return
            val dataStart = pos + element.headerSize
            val dataEnd = dataStart + element.dataSize
            if (dataEnd > end || element.dataSize < 0) return
            block(element.id, dataStart, dataEnd.toInt())
            pos = dataEnd.toInt()
        }
    }

    private fun readElementAt(source: ChapterByteSource, head: ByteArray, offset: Long): Element? {
        if (offset + 12 <= head.size) return readElement(head, offset.toInt())
        val bytes = source.read(offset, 12) ?: return null
        return readElement(bytes, 0)
    }

    private fun readRange(source: ChapterByteSource, head: ByteArray, offset: Long, size: Long): ByteArray? {
        if (size < 0 || size > MAX_CHAPTERS_BYTES) return null
        if (offset + size <= head.size) return head.copyOfRange(offset.toInt(), (offset + size).toInt())
        val bytes = source.read(offset, size.toInt()) ?: return null
        return bytes.takeIf { it.size.toLong() == size }
    }

    private fun readElement(data: ByteArray, offset: Int): Element? {
        val (id, idLength) = readId(data, offset) ?: return null
        val sizeOffset = offset + idLength
        if (sizeOffset >= data.size) return null
        val first = data[sizeOffset].toInt() and 0xFF
        val length = vintLength(first) ?: return null
        if (sizeOffset + length > data.size) return null
        var value = (first and (0xFF ushr length)).toLong()
        var allOnes = value == (0xFF ushr length).toLong()
        for (i in 1 until length) {
            val b = data[sizeOffset + i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return Element(id, idLength + length, if (allOnes) -1 else value, allOnes)
    }

    /** Element IDs keep their length-marker bits. */
    private fun readId(data: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        val length = vintLength(first)?.takeIf { it <= 4 } ?: return null
        if (offset + length > data.size) return null
        var id = 0L
        for (i in 0 until length) id = (id shl 8) or (data[offset + i].toLong() and 0xFF)
        return id to length
    }

    private fun vintLength(firstByte: Int): Int? {
        for (i in 0 until 8) if (firstByte and (0x80 ushr i) != 0) return i + 1
        return null
    }

    private fun readUInt(data: ByteArray, start: Int, end: Int): Long {
        var value = 0L
        for (i in start until minOf(end, start + 8)) value = (value shl 8) or (data[i].toLong() and 0xFF)
        return value
    }
}
