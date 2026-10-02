package com.nuvio.app.features.player.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterSkipClassifierTest {

    private fun chapter(startSec: Int, title: String?, endSec: Int? = null) =
        EmbeddedChapter(startSec * 1000L, endSec?.let { it * 1000L }, title)

    @Test
    fun typicalAnimeChaptersMapToIntroOutroAndPreview() {
        val chapters = listOf(
            chapter(0, "Prologue"),
            chapter(90, "Opening"),
            chapter(180, "Part A"),
            chapter(720, "Part B"),
            chapter(1290, "Ending"),
            chapter(1380, "Preview"),
        )
        val intervals = ChapterSkipClassifier.toSkipIntervals(chapters, 1_420_000L, isMovie = false)

        assertEquals(listOf("intro", "outro", "preview"), intervals.map { it.type })
        assertEquals(90.0, intervals[0].startTime)
        assertEquals(180.0, intervals[0].endTime)
        assertEquals(1420.0, intervals[2].endTime)
    }

    @Test
    fun romanizedAndKanaTitlesAreRecognised() {
        val chapters = listOf(
            chapter(0, "Zenkai no Arasuji"),
            chapter(40, "OP"),
            chapter(130, "Honpen"),
            chapter(1200, "エンディング"),
            chapter(1290, "Jikai Yokoku"),
        )
        val types = ChapterSkipClassifier.toSkipIntervals(chapters, 1_320_000L, isMovie = false).map { it.type }

        assertEquals(listOf("recap", "intro", "outro", "preview"), types)
    }

    @Test
    fun storyChaptersThatMerelyContainKeywordsAreIgnored() {
        val chapters = listOf(
            chapter(0, "Chapter 1"),
            chapter(60, "The Ending"),
            chapter(120, "Introduction"),
            chapter(180, "Editor's Notes"),
        )
        assertTrue(ChapterSkipClassifier.toSkipIntervals(chapters, 240_000L, isMovie = false).isEmpty())
    }

    @Test
    fun movieCreditsUseMovieCreditsTypeAndAdjacentSegmentsMerge() {
        val chapters = listOf(
            chapter(0, "Chapter 01"),
            chapter(5400, "End Credits"),
            chapter(5700, "Credits"),
        )
        val intervals = ChapterSkipClassifier.toSkipIntervals(chapters, 6_000_000L, isMovie = true)

        assertEquals(1, intervals.size)
        assertEquals("movie-credits", intervals[0].type)
        assertEquals(6000.0, intervals[0].endTime)
    }

    @Test
    fun finalChapterWithoutEndNeedsKnownDuration() {
        val chapters = listOf(chapter(0, "Part A"), chapter(1300, "Ōpuningu"))

        assertTrue(ChapterSkipClassifier.toSkipIntervals(chapters, 0L, isMovie = false).isEmpty())
        assertEquals(1, ChapterSkipClassifier.toSkipIntervals(chapters, 1_400_000L, isMovie = false).size)
    }
}
