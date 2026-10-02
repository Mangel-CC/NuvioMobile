package com.nuvio.app.features.player.chapters

import android.net.Uri
import android.util.Log
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "EmbeddedChapters"
private const val HEAD_BYTES = 64 * 1024
private const val PROBE_TIMEOUT_MS = 20_000L

/** Random-access byte source for the chapter probes. */
internal interface ChapterByteSource {
    /** Total length in bytes, or null if unknown. */
    val length: Long?

    /** Reads up to [size] bytes at [offset]; may return fewer bytes at end of input. */
    fun read(offset: Long, size: Int): ByteArray?
}

actual suspend fun probeEmbeddedChapters(
    url: String,
    headers: Map<String, String>,
): List<EmbeddedChapter> {
    if (!isChapterProbeCandidate(url)) return emptyList()
    return withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val uri = Uri.parse(url)
                when (uri.scheme?.lowercase(Locale.ROOT)) {
                    "http", "https" -> probe(
                        HttpChapterByteSource(
                            PlayerPlaybackNetworking.sideRequestHttpClient,
                            url,
                            PlayerPlaybackNetworking.sideRequestHeaders(headers),
                        ),
                    )
                    "file" -> uri.path?.let(::probeFile).orEmpty()
                    null, "" -> probeFile(url)
                    else -> emptyList()
                }
            }.onFailure { Log.d(TAG, "Chapter probe failed: ${it.message}") }
                .getOrDefault(emptyList())
        }.orEmpty()
    }
}

private fun probeFile(path: String): List<EmbeddedChapter> {
    val file = File(path)
    if (!file.isFile) return emptyList()
    return RandomAccessFile(file, "r").use { raf -> probe(FileChapterByteSource(raf)) }
}

private fun probe(source: ChapterByteSource): List<EmbeddedChapter> {
    val head = source.read(0, HEAD_BYTES) ?: return emptyList()
    val chapters = if (MatroskaChapterProbe.isMatroska(head)) {
        MatroskaChapterProbe.probe(source, head)
    } else {
        Mp4ChapterProbe.probe(source, head)
    }
    Log.d(TAG, "Embedded chapters: ${chapters.size}")
    return chapters
}

private fun isChapterProbeCandidate(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase(Locale.ROOT) ?: lower
    val streamingExtensions = listOf(".m3u8", ".mpd", ".ism", ".ts", ".m2ts")
    return streamingExtensions.none { path.endsWith(it) } && !path.contains("/manifest")
}

private class HttpChapterByteSource(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
) : ChapterByteSource {
    private var knownLength: Long? = null
    override val length: Long? get() = knownLength

    override fun read(offset: Long, size: Int): ByteArray? {
        if (size <= 0) return ByteArray(0)
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$offset-${offset + size - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206 && !(response.code == 200 && offset == 0L)) return null
            response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?.let { knownLength = it }
            val body = response.body ?: return null
            if (response.code == 200) {
                body.contentLength().takeIf { it > 0 }?.let { knownLength = it }
            }
            val stream = body.byteStream()
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(buffer, read, size - read)
                if (n < 0) break
                read += n
            }
            return if (read == size) buffer else buffer.copyOf(read)
        }
    }
}

private class FileChapterByteSource(private val raf: RandomAccessFile) : ChapterByteSource {
    override val length: Long = raf.length()

    override fun read(offset: Long, size: Int): ByteArray? {
        if (offset >= length) return null
        val n = minOf(size.toLong(), length - offset).toInt()
        val buffer = ByteArray(n)
        raf.seek(offset)
        raf.readFully(buffer)
        return buffer
    }
}
