package com.nuvio.app.features.player.chapters

// Embedded chapter probing is only implemented for Android for now.
actual suspend fun probeEmbeddedChapters(
    url: String,
    headers: Map<String, String>,
): List<EmbeddedChapter> = emptyList()
