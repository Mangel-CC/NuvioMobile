package com.nuvio.app.features.player.chapters

import com.nuvio.app.features.player.skip.SkipInterval

/**
 * A chapter marker read from the media container itself (Matroska Chapters, MP4 QuickTime
 * chapter track or Nero `chpl`).
 *
 * @property endMs Chapter end, or null when the container does not declare it (the next
 *   chapter's start, or the media duration, is used instead).
 */
data class EmbeddedChapter(
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
)

/**
 * Reads the chapters embedded in the media at [url] (HTTP(S) with range requests, or a local
 * file). Returns an empty list when the container has none or the platform does not support it.
 */
expect suspend fun probeEmbeddedChapters(
    url: String,
    headers: Map<String, String>,
): List<EmbeddedChapter>

/**
 * Turns embedded chapters into [SkipInterval]s by recognising the usual chapter names for
 * intros, recaps, endings/credits and next-episode previews (English, romanised Japanese and
 * kana, as used by anime fansub/BD releases).
 *
 * Only the start of a title is matched ("Opening", "OP - Song name", "Ending Credits"), so story
 * chapters such as "The Ending" are not mistaken for skippable segments.
 */
object ChapterSkipClassifier {

    const val PROVIDER = "chapters"

    const val TYPE_INTRO = "intro"
    const val TYPE_RECAP = "recap"
    const val TYPE_OUTRO = "outro"
    const val TYPE_MOVIE_CREDITS = "movie-credits"
    const val TYPE_PREVIEW = "preview"

    /**
     * "Prologue" chapters are a recap in some releases and new story in others, so they get a
     * manual Skip button only: no AutoSkipSegmentType maps to this type, so it is never auto-skipped.
     */
    const val TYPE_PROLOGUE = "prologue"

    private const val MAX_INTRO_MS = 5 * 60_000L
    private const val MAX_RECAP_MS = 6 * 60_000L
    private const val MAX_PREVIEW_MS = 3 * 60_000L
    private const val MAX_OUTRO_MS = 15 * 60_000L
    private const val MIN_SEGMENT_MS = 3_000L

    private enum class Kind { INTRO, WEAK_INTRO, RECAP, PROLOGUE, OUTRO, PREVIEW }

    // Leading numbering such as "01 ", "1. ", "chapter 3 - ", "ch 2: ".
    private val leadingNumbering = Regex("^(?:(?:chapter|chap|ch)\\s*)?\\d{1,3}\\s*[-.:)]?\\s+")
    private val punctuation = Regex("[\\[\\](){}「」『』【】\"'“”‘’_~|/\\\\]+")
    private val whitespace = Regex("\\s+")

    private val introPattern = Regex(
        "^(?:op\\d*|opening(?:\\s+(?:theme|song|credits|titles?|sequence))?|" +
            "intro\\s+(?:song|theme|credits|sequence)|" +
            "title\\s+sequence|main\\s+titles?|theme\\s+song|" +
            "o{1,2}puningu|oupuningu)\\b"
    )
    // A bare "Intro" is the opening theme unless the file also has an explicit Opening/OP chapter;
    // then it is the episode's cold open (e.g. "Intro" followed by "Opening").
    private val weakIntroPattern = Regex("^intro\\b")
    private val prologuePattern = Regex("^(?:prologue|prolog|purorogu)\\b")
    private val recapPattern = Regex(
        "^(?:recap|previously(?:\\s+on)?|last\\s+time|" +
            "zenkai(?:\\s+no\\s+arasuji)?|arasuji|matome)\\b"
    )
    private val outroPattern = Regex(
        "^(?:ed\\d*|ending(?:\\s+(?:theme|song|credits|titles?|sequence))?|" +
            "outro(?:\\s+(?:song|theme|credits))?|" +
            "(?:end|closing|ending)\\s+credits|credits|staff\\s+roll|" +
            "e{1,2}ndingu)\\b"
    )
    private val previewPattern = Regex(
        "^(?:preview|(?:episode|ep)\\s+preview|next\\s+(?:episode|ep|time)(?:\\s+preview)?|next\\s+on|" +
            "jikai(?:\\s+yokoku)?|yokoku)\\b"
    )

    private val kanaIntro = listOf("オープニング", "ＯＰ")
    private val kanaRecap = listOf("前回のあらすじ", "あらすじ", "前回")
    private val kanaOutro = listOf("エンディング", "ＥＤ", "スタッフロール")
    private val kanaPreview = listOf("次回予告", "予告")
    private val kanaPrologue = listOf("プロローグ")

    private val diacritics = mapOf(
        'ā' to 'a', 'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a',
        'ē' to 'e', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'ī' to 'i', 'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
        'ō' to 'o', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o',
        'ū' to 'u', 'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    )

    /**
     * @param durationMs Media duration, used as the end of a final chapter with no declared end.
     *   Pass 0 or a negative value when unknown.
     * @param isMovie Ending/credits chapters then map to [TYPE_MOVIE_CREDITS] instead of
     *   [TYPE_OUTRO].
     */
    fun toSkipIntervals(
        chapters: List<EmbeddedChapter>,
        durationMs: Long,
        isMovie: Boolean,
    ): List<SkipInterval> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters
            .filter { it.startMs >= 0L }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        val kinds = sorted.map { classify(it.title) }
        val hasExplicitOpening = kinds.any { it == Kind.INTRO }
        val result = ArrayList<SkipInterval>()
        sorted.forEachIndexed { index, chapter ->
            val kind = when (val raw = kinds[index]) {
                Kind.WEAK_INTRO -> if (hasExplicitOpening) null else Kind.INTRO
                else -> raw
            } ?: return@forEachIndexed
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val endMs = chapter.endMs?.takeIf { it > chapter.startMs }
                ?: nextStart
                ?: durationMs.takeIf { it > chapter.startMs }
                ?: return@forEachIndexed
            val lengthMs = endMs - chapter.startMs
            val maxMs = when (kind) {
                Kind.INTRO, Kind.WEAK_INTRO -> MAX_INTRO_MS
                Kind.RECAP, Kind.PROLOGUE -> MAX_RECAP_MS
                Kind.PREVIEW -> MAX_PREVIEW_MS
                Kind.OUTRO -> MAX_OUTRO_MS
            }
            if (lengthMs < MIN_SEGMENT_MS || lengthMs > maxMs) return@forEachIndexed
            val type = when (kind) {
                Kind.INTRO, Kind.WEAK_INTRO -> TYPE_INTRO
                Kind.RECAP -> TYPE_RECAP
                Kind.PROLOGUE -> TYPE_PROLOGUE
                Kind.PREVIEW -> TYPE_PREVIEW
                Kind.OUTRO -> if (isMovie) TYPE_MOVIE_CREDITS else TYPE_OUTRO
            }
            result += SkipInterval(
                startTime = chapter.startMs / 1000.0,
                endTime = endMs / 1000.0,
                type = type,
                provider = PROVIDER,
            )
        }
        return mergeAdjacent(result)
    }

    /** Joins back-to-back segments of the same type (e.g. "Ending" followed by "Credits"). */
    private fun mergeAdjacent(intervals: List<SkipInterval>): List<SkipInterval> {
        if (intervals.size < 2) return intervals
        val merged = ArrayList<SkipInterval>(intervals.size)
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && last.type == interval.type && interval.startTime - last.endTime <= 1.0) {
                merged[merged.lastIndex] = last.copy(endTime = maxOf(last.endTime, interval.endTime))
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun classify(rawTitle: String?): Kind? {
        if (rawTitle == null) return null
        val trimmed = rawTitle.trim()
        when {
            kanaIntro.any { trimmed.startsWith(it) } -> return Kind.INTRO
            kanaRecap.any { trimmed.startsWith(it) } -> return Kind.RECAP
            kanaOutro.any { trimmed.startsWith(it) } -> return Kind.OUTRO
            kanaPreview.any { trimmed.startsWith(it) } -> return Kind.PREVIEW
            kanaPrologue.any { trimmed.startsWith(it) } -> return Kind.PROLOGUE
        }
        val title = normalize(trimmed)
        if (title.isEmpty()) return null
        return when {
            introPattern.containsMatchIn(title) -> Kind.INTRO
            weakIntroPattern.containsMatchIn(title) -> Kind.WEAK_INTRO
            prologuePattern.containsMatchIn(title) -> Kind.PROLOGUE
            recapPattern.containsMatchIn(title) -> Kind.RECAP
            outroPattern.containsMatchIn(title) -> Kind.OUTRO
            previewPattern.containsMatchIn(title) -> Kind.PREVIEW
            else -> null
        }
    }

    internal fun normalize(title: String): String {
        val folded = buildString(title.length) {
            for (c in title.lowercase()) append(diacritics[c] ?: c)
        }
        return folded
            .replace(punctuation, " ")
            .replace(whitespace, " ")
            .trim()
            .replace(leadingNumbering, "")
            .trim()
    }
}
