package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterCreditsWindowTest {

    private fun chapterOutro(start: Double, end: Double) =
        SkipInterval(startTime = start, endTime = end, type = "outro", provider = "chapters")

    @Test
    fun creditsFollowedByAPreviewOnlyCoverTheCredits() {
        val window = PlayerNextEpisodeRules.chapterCreditsWindow(
            listOf(chapterOutro(1290.0, 1380.0)),
            durationMs = 1_420_000L,
        )!!

        assertTrue(window.hasContentAfter)
        assertFalse(window.contains(1_289_000L))
        assertTrue(window.contains(1_300_000L))
        assertFalse(window.contains(1_390_000L))
    }

    @Test
    fun creditsAtTheEndOfTheFileStayOpenUntilTheEnd() {
        val window = PlayerNextEpisodeRules.chapterCreditsWindow(
            listOf(chapterOutro(1330.0, 1418.0)),
            durationMs = 1_420_000L,
        )!!

        assertFalse(window.hasContentAfter)
        assertTrue(window.contains(1_419_000L))
        assertEquals(1_330_000L, window.startMs)
    }

    @Test
    fun externalOrEarlyCreditsDoNotOpenAChapterWindow() {
        val external = SkipInterval(startTime = 1290.0, endTime = 1380.0, type = "outro", provider = "introdb")
        assertNull(PlayerNextEpisodeRules.chapterCreditsWindow(listOf(external), durationMs = 1_420_000L))
        assertNull(PlayerNextEpisodeRules.chapterCreditsWindow(listOf(chapterOutro(100.0, 190.0)), 1_420_000L))
    }
}
