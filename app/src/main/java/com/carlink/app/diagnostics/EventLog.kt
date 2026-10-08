package com.carlink.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CarLink's own connection events (detection, engine hand-offs, decisions), kept in memory in
 * the main process so Diagnostics can show them next to the engines' logs. A small ring buffer:
 * nothing is written to disk, and addresses are masked before they are stored.
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

    fun add(tag: String, message: String) = add(tag, message, System.currentTimeMillis())

    internal fun add(tag: String, message: String, timeMillis: Long) {
        val entry = Entry(timeMillis, tag, LogMasking.mask(message))
        synchronized(lock) {
            if (buffer.size == CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    internal fun clear() = synchronized(lock) {
        buffer.clear()
        _entries.value = emptyList()
    }
}

/** Masks hardware addresses the way DiAuto's report does ("[address]"). */
object LogMasking {
    private val mac = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f:])")

    fun mask(text: String): String = text.replace(mac, "[address]")
}
