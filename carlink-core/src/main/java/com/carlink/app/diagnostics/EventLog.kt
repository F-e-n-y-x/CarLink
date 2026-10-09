package com.carlink.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CarLink's own connection events (detection, engine hand-offs, decisions), kept in memory in
 * the main process so Diagnostics can show them next to the engines' logs. A small ring buffer,
 * mirrored to `files/logs/carlink.log` once [attachFile] is called (Android's own log rotates
 * within minutes on busy devices, so connection timings were lost). Addresses are masked before
 * they are stored.
 *
 * Call [add] from anywhere in the main process; it is cheap and thread-safe.
 */
object EventLog {
    const val CAPACITY = 400

    data class Entry(val timeMillis: Long, val tag: String, val message: String)

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Oldest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "carlink-eventlog").apply { isDaemon = true } }
    private const val FILE_LIMIT_BYTES = 1_000_000L

    /** Mirrors every entry to [dir]/carlink.log ("epochMillis [tag] message"), keeping one previous file. */
    fun attachFile(dir: java.io.File) {
        dir.mkdirs()
        file = java.io.File(dir, "carlink.log")
    }

    fun add(tag: String, message: String) = add(tag, message, System.currentTimeMillis())

    /** [add] with an explicit time (tests, imported entries). */
    fun add(tag: String, message: String, timeMillis: Long) {
        val entry = Entry(timeMillis, tag, LogMasking.mask(message))
        file?.let { target ->
            writer.execute {
                runCatching {
                    if (target.length() > FILE_LIMIT_BYTES) target.renameTo(java.io.File(target.parentFile, "carlink.previous.log"))
                    target.appendText("${entry.timeMillis} [${entry.tag}] ${entry.message}\n")
                }
            }
        }
        synchronized(lock) {
            if (buffer.size == CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _entries.value = emptyList()
    }
}

/** Masks hardware addresses the way DiAuto's report does ("[address]"). */
object LogMasking {
    // `[-:]`, not `[:-]`: Android's ICU regex reads `[:` as a POSIX class and fails to compile.
    private val mac = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[-:]){5}[0-9a-f]{2}(?![0-9a-f:])")

    fun mask(text: String): String = text.replace(mac, "[address]")
}
