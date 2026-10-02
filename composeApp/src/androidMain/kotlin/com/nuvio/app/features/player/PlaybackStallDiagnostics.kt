package com.nuvio.app.features.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Temporary playback diagnostics for the Mangel-CC fork: when a source shows no progress for
 * STALL_TIMEOUT_MS, or the engine reports an error, a dialog shows a summary plus the tail of
 * this app's own logcat (player state, network loads, decoder errors) so it can be copied or
 * shared without adb. URLs are redacted (no query strings / tokens).
 */

private const val DIAG_TAG = "NuvioPlayerDiag"
private const val STALL_TIMEOUT_MS = 20_000L
private const val LOGCAT_LINES = 600

internal class PlaybackStallTracker(
    val sourceUrl: String,
    val engineName: () -> String,
) {
    private val startedAtMs = SystemClock.elapsedRealtime()
    var sawProgress by mutableStateOf(false)
        private set
    var lastSnapshot: PlayerPlaybackSnapshot? = null
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var showDialog by mutableStateOf(false)
    var dismissed by mutableStateOf(false)

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot) {
        lastSnapshot = snapshot
        if (snapshot.isPlaying || snapshot.positionMs > 0L) sawProgress = true
    }

    fun onError(message: String?) {
        if (message == null) return
        lastError = message
        Log.e(DIAG_TAG, "engine_error engine=${engineName()} message=$message")
        if (!dismissed) showDialog = true
    }

    fun elapsedSeconds(): Long = (SystemClock.elapsedRealtime() - startedAtMs) / 1000
}

/** Shows the diagnostics dialog when the tracked source stalls or fails. */
@Composable
internal fun PlaybackStallDiagnosticsHost(tracker: PlaybackStallTracker) {
    LaunchedEffect(tracker) {
        delay(STALL_TIMEOUT_MS)
        if (!tracker.sawProgress && !tracker.dismissed) {
            Log.w(DIAG_TAG, "stall_timeout engine=${tracker.engineName()} after=${STALL_TIMEOUT_MS}ms")
            tracker.showDialog = true
        }
    }
    if (!tracker.showDialog) return

    val context = LocalContext.current
    var report by remember(tracker) { mutableStateOf("Recopilando diagnóstico…") }
    LaunchedEffect(tracker, tracker.lastError) {
        report = withContext(Dispatchers.IO) { buildDiagnosticReport(context, tracker) }
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(if (tracker.lastError != null) "Error de reproducción" else "El video no arranca")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareText(context, report) }) { Text("Compartir") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { copyText(context, report) }) { Text("Copiar") }
                TextButton(onClick = {
                    tracker.showDialog = false
                    tracker.dismissed = true
                }) { Text("Cerrar") }
            }
        },
    )
}

private fun buildDiagnosticReport(context: Context, tracker: PlaybackStallTracker): String {
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val snapshot = tracker.lastSnapshot
    val summary = buildString {
        appendLine("== Nuvio diagnóstico ==")
        appendLine("app=${context.packageName} version=${packageInfo?.versionName}")
        appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}) device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("engine=${tracker.engineName()} elapsed=${tracker.elapsedSeconds()}s progress=${tracker.sawProgress}")
        appendLine("source=${redactUrls(tracker.sourceUrl)}")
        appendLine(
            "snapshot=" + (snapshot?.let {
                "loading=${it.isLoading} playing=${it.isPlaying} pos=${it.positionMs} " +
                    "buffered=${it.bufferedPositionMs} dur=${it.durationMs} video=${it.videoWidth}x${it.videoHeight}"
            } ?: "none"),
        )
        appendLine("error=${tracker.lastError ?: "none"}")
        appendLine()
        appendLine("== logcat (app) ==")
    }
    return summary + readOwnLogcat()
}

private fun readOwnLogcat(): String = runCatching {
    val process = ProcessBuilder(
        "logcat", "-d", "-v", "time", "-t", LOGCAT_LINES.toString(), "--pid=${Process.myPid()}",
    ).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    redactUrls(text).ifBlank { "(logcat vacío)" }
}.getOrElse { "(no se pudo leer logcat: ${it.message})" }

private val urlRegex = Regex("(https?|ftp)://[^\\s\"'<>]+")
private val longTokenRegex = Regex("[A-Za-z0-9_\\-]{24,}")

/** Keeps scheme, host, port and file extension; drops query strings and token-like segments. */
internal fun redactUrls(text: String): String = urlRegex.replace(text) { match ->
    val uri = runCatching { Uri.parse(match.value) }.getOrNull() ?: return@replace "<url>"
    val path = uri.path.orEmpty()
        .split('/')
        .joinToString("/") { segment -> longTokenRegex.replace(segment, "…") }
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val query = if (uri.query != null) "?…" else ""
    "${uri.scheme}://${uri.host}$port$path$query"
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Nuvio diagnóstico", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Nuvio diagnóstico")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "Compartir diagnóstico").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/** Logs ExoPlayer network loads, decoder setup and codec/sink errors for the diagnostics dialog. */
@OptIn(UnstableApi::class)
internal fun createPlaybackDiagnosticsAnalyticsListener(): AnalyticsListener = object : AnalyticsListener {
    private fun describe(info: LoadEventInfo, data: MediaLoadData): String =
        "type=${dataTypeName(data.dataType)} track=${trackTypeName(data.trackType)} " +
            "uri=${redactUrls(info.uri.toString())} bytes=${info.bytesLoaded} durMs=${info.loadDurationMs}"

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        Log.i(DIAG_TAG, "load_started retry=$retryCount ${describe(loadEventInfo, mediaLoadData)} " +
            "range=${loadEventInfo.dataSpec.position}+${loadEventInfo.dataSpec.length}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.i(DIAG_TAG, "load_completed ${describe(loadEventInfo, mediaLoadData)} " +
            "contentType=${loadEventInfo.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()}")
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.i(DIAG_TAG, "load_canceled ${describe(loadEventInfo, mediaLoadData)}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(5)
            .joinToString(" -> ") { "${it.javaClass.simpleName}:${redactUrls(it.message.orEmpty()).take(200)}" }
        Log.e(DIAG_TAG, "load_error canceled=$wasCanceled ${describe(loadEventInfo, mediaLoadData)} error=$chain")
    }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        val format = mediaLoadData.trackFormat ?: return
        Log.i(DIAG_TAG, "format track=${trackTypeName(mediaLoadData.trackType)} " +
            "mime=${format.sampleMimeType} codecs=${format.codecs} ${format.width}x${format.height} " +
            "channels=${format.channelCount} rate=${format.sampleRate} bitrate=${format.bitrate}")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(DIAG_TAG, "video_decoder=$decoderName initMs=$initializationDurationMs")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(DIAG_TAG, "audio_decoder=$decoderName initMs=$initializationDurationMs")
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        Log.e(DIAG_TAG, "video_codec_error ${videoCodecError.javaClass.simpleName}: ${videoCodecError.message}")
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        Log.e(DIAG_TAG, "audio_codec_error ${audioCodecError.javaClass.simpleName}: ${audioCodecError.message}")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        Log.e(DIAG_TAG, "audio_sink_error ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.w(DIAG_TAG, "dropped_frames=$droppedFrames in ${elapsedMs}ms")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(6)
            .joinToString(" -> ") { "${it.javaClass.simpleName}:${redactUrls(it.message.orEmpty()).take(200)}" }
        Log.e(DIAG_TAG, "player_error code=${error.errorCodeName} chain=$chain")
    }
}

private fun dataTypeName(type: Int): String = when (type) {
    C.DATA_TYPE_MEDIA -> "media"
    C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
    C.DATA_TYPE_MANIFEST -> "manifest"
    C.DATA_TYPE_DRM -> "drm"
    C.DATA_TYPE_TIME_SYNCHRONIZATION -> "timesync"
    C.DATA_TYPE_AD -> "ad"
    else -> "other($type)"
}

private fun trackTypeName(type: Int): String = when (type) {
    C.TRACK_TYPE_VIDEO -> "video"
    C.TRACK_TYPE_AUDIO -> "audio"
    C.TRACK_TYPE_TEXT -> "text"
    C.TRACK_TYPE_UNKNOWN -> "unknown"
    C.TRACK_TYPE_DEFAULT -> "default"
    else -> "other($type)"
}
