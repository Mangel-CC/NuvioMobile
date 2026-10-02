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

        assertEquals(listOf("prologue", "intro", "outro", "preview"), intervals.map { it.type })
        assertEquals(90.0, intervals[1].startTime)
        assertEquals(180.0, intervals[1].endTime)
        assertEquals(1420.0, intervals[3].endTime)
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

    @Test
    fun bareIntroBeforeAnExplicitOpeningIsTheColdOpenAndIsNotSkipped() {
        val chapters = listOf(
            chapter(0, "Intro"),
            chapter(150, "Opening"),
            chapter(240, "Part A"),
            chapter(1300, "Ending"),
        )
        val intervals = ChapterSkipClassifier.toSkipIntervals(chapters, 1_420_000L, isMovie = false)

        assertEquals(listOf("intro", "outro"), intervals.map { it.type })
        assertEquals(150.0, intervals[0].startTime)
    }

    @Test
    fun bareIntroIsTheOpeningWhenThereIsNoOpeningChapter() {
        val chapters = listOf(chapter(0, "Prologue"), chapter(60, "Intro"), chapter(150, "Part A"))
        val types = ChapterSkipClassifier.toSkipIntervals(chapters, 1_420_000L, isMovie = false).map { it.type }

        assertEquals(listOf("prologue", "intro"), types)
    }

    @Test
    fun prologueGetsItsOwnManualOnlyType() {
        val chapters = listOf(chapter(0, "Prologue"), chapter(95, "OP"), chapter(185, "Part A"))
        val intervals = ChapterSkipClassifier.toSkipIntervals(chapters, 1_420_000L, isMovie = false)

        assertEquals(listOf(ChapterSkipClassifier.TYPE_PROLOGUE, "intro"), intervals.map { it.type })
        assertEquals(null, com.nuvio.app.features.player.skip.AutoSkipSegmentType.fromSkipIntervalType(ChapterSkipClassifier.TYPE_PROLOGUE))
    }

    @Test
    fun spanishPortugueseFrenchItalianAndGermanNamesAreRecognised() {
        val titles = mapOf(
            "Créditos" to "outro",
            "Crédits" to "outro",
            "Créditos finales" to "outro",
            "Créditos iniciales" to "intro",
            "Apertura" to "intro",
            "Abertura" to "intro",
            "Encerramento" to "outro",
            "Générique de début" to "intro",
            "Générique de fin" to "outro",
            "Générique" to "outro",
            "Sigla finale" to "outro",
            "Sigla" to "intro",
            "Resumen" to "recap",
            "Avance" to "preview",
            "Próximo episodio" to "preview",
            "Abspann" to "outro",
            "Vorschau" to "preview",
        )
        for ((title, expected) in titles) {
            val chapters = listOf(chapter(0, "Part A"), chapter(600, title), chapter(690, "Part B"))
            val types = ChapterSkipClassifier.toSkipIntervals(chapters, 1_400_000L, isMovie = false).map { it.type }
            assertEquals(listOf(expected), types, title)
        }
    }
}
