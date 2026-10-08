package com.carlink.app.diagnostics

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Where a connection-log line came from. */
enum class LogSource { CARPLAY, ANDROID_AUTO, CARLINK }

/** One line of the combined connection log. [level] is V/D/I/W/E/A when the source records it. */
data class LogLine(val timeMillis: Long, val source: LogSource, val level: Char?, val text: String) {
    val isProblem: Boolean get() = level == 'E' || level == 'A' || level == 'W'
}

/**
 * The combined connection log Diagnostics shows: DiAuto's journal (written by the `:aa`
 * process to `files/diagnostics/`), DiPlay's session log (`files/logs/diplay.log`) and
 * CarLink's own [EventLog]. All three files belong to this app, so the main process reads them
 * directly. Call [read] and [export] off the main thread.
 */
object ConnectionLog {
    /** Lines kept per source; the screen shows the newest first. */
    const val LINES_PER_SOURCE = 400
    private const val TAIL_BYTES = 192 * 1024L

    fun diAutoFiles(context: Context): List<File> =
        File(context.filesDir, "diagnostics").let { listOf(File(it, "previous.log"), File(it, "current.log")) }

    fun diPlayFiles(context: Context): List<File> =
        File(context.filesDir, "logs").let { listOf(File(it, "previous.log"), File(it, "diplay.log")) }

    /** A cheap fingerprint of the engine log files, to skip re-reading when nothing changed. */
    fun stamp(context: Context): Long =
        (diAutoFiles(context) + diPlayFiles(context)).fold(17L) { acc, f -> acc * 31 + f.length() * 7 + f.lastModified() }

    /** Each engine's recent lines, oldest first: DiAuto, then DiPlay. */
    fun readEngines(context: Context): List<List<LogLine>> = listOf(
        parseDiAuto(diAutoFiles(context).flatMap { tail(it) }).takeLast(LINES_PER_SOURCE),
        diPlayFiles(context).flatMap { file -> parseDiPlay(tail(file), file.lastModified()) }.takeLast(LINES_PER_SOURCE),
    )

    /** CarLink's own events as log lines, oldest first. */
    fun events(entries: List<EventLog.Entry>): List<LogLine> =
        entries.takeLast(LINES_PER_SOURCE).map { LogLine(it.timeMillis, LogSource.CARLINK, null, "${it.tag}: ${it.message}") }

    /** Every source merged, newest first. */
    fun read(context: Context, entries: List<EventLog.Entry> = EventLog.entries.value): List<LogLine> =
        merge(*(readEngines(context) + listOf(events(entries))).toTypedArray())

    /** Merges sources given oldest first into one list, newest first; same-millisecond lines keep their order reversed. */
    fun merge(vararg sources: List<LogLine>): List<LogLine> =
        sources.toList().flatMap { it.asReversed() }.sortedByDescending { it.timeMillis }

    /**
     * DiAuto: "<epoch millis> [<android.util.Log priority>] <message>", already redacted by
     * DiAuto; addresses are masked again in case an older build wrote the file.
     */
    fun parseDiAuto(lines: List<String>): List<LogLine> = lines.mapNotNull { raw ->
        val match = DIAUTO_LINE.matchEntire(raw.trimEnd()) ?: return@mapNotNull null
        val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val level = when (match.groupValues[2].toIntOrNull()) {
            2 -> 'V'; 3 -> 'D'; 4 -> 'I'; 5 -> 'W'; 6 -> 'E'; 7 -> 'A'
            else -> null
        }
        LogLine(time, LogSource.ANDROID_AUTO, level, LogMasking.mask(match.groupValues[3]))
    }

    /**
     * DiPlay: a header "DiPlay log started yyyy-MM-dd HH:mm:ss.SSS …", then "HH:mm:ss.SSS  <message>".
     * Lines carry only the time of day, so the date comes from the header (or the file's
     * modification time) and moves on a day when the clock wraps past midnight.
     */
    fun parseDiPlay(lines: List<String>, fileModified: Long): List<LogLine> {
        val header = lines.firstNotNullOfOrNull { DIPLAY_HEADER.find(it) }
        val day = Calendar.getInstance()
        val start = header?.let { runCatching { headerFormat().parse(it.groupValues[1]) }.getOrNull() }
        day.time = start ?: Date(if (fileModified > 0) fileModified else System.currentTimeMillis())
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
        var lastTime = -1L
        val out = ArrayList<LogLine>(lines.size)
        for (raw in lines) {
            val match = DIPLAY_LINE.matchEntire(raw.trimEnd()) ?: continue
            val (h, m, s, ms) = match.destructured
            var time = day.timeInMillis + ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
            if (lastTime >= 0 && time < lastTime - 60 * 60 * 1000) {
                day.add(Calendar.DAY_OF_MONTH, 1)
                time += 24 * 60 * 60 * 1000L
            }
            lastTime = time
            val message = match.groupValues[5]
            out += LogLine(time, LogSource.CARPLAY, diPlayLevel(message), LogMasking.mask(message))
        }
        return out
    }

    private fun diPlayLevel(message: String): Char? {
        val lower = message.lowercase(Locale.ROOT)
        return when {
            "error" in lower || "failed" in lower || "exception" in lower -> 'E'
            "warn" in lower || "timeout" in lower || "timed out" in lower -> 'W'
            else -> null
        }
    }

    /** The last [TAIL_BYTES] of a file as lines (the first, likely partial, line dropped). */
    internal fun tail(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).lines()
            // Keep DiPlay's header even when the tail starts mid-file: it carries the date.
            if (start > 0) lines.drop(1) else lines
        }
    }.getOrDefault(emptyList())

    /** One plain-text report of every line, oldest first, for sharing. */
    fun format(lines: List<LogLine>, header: String): String = buildString {
        appendLine(header)
        appendLine()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (line in lines.sortedBy { it.timeMillis }) {
            append(time.format(Date(line.timeMillis))).append("  ")
            append(sourceTag(line.source)).append(' ')
            append(line.level ?: '-').append("  ")
            appendLine(line.text)
        }
    }

    fun sourceTag(source: LogSource): String = when (source) {
        LogSource.CARPLAY -> "CP"
        LogSource.ANDROID_AUTO -> "AA"
        LogSource.CARLINK -> "CL"
    }

    /** Writes the combined log into the shared cache folder and returns it (see CarLinkFileProvider). */
    fun export(context: Context, header: String): File {
        val dir = File(context.cacheDir, CarLinkFileProvider.SHARED_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "carlink-connection-log-$stamp.txt").apply { writeText(format(read(context), header)) }
    }

    private fun headerFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // DiAuto prefixes the logging thread's id ("[2] ") to each message; it is noise here.
    private val DIAUTO_LINE = Regex("(\\d{10,})\\s+\\[(\\d)]\\s?(?:\\[\\d+]\\s)?(.*)")
    private val DIPLAY_LINE = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(.*)")
    private val DIPLAY_HEADER = Regex("DiPlay log started (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
}
