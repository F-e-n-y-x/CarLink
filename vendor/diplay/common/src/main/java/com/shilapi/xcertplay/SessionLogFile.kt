package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    @Volatile private var closed = false
    private var size = UNKNOWN_SIZE
    // Raw lines queue here; the writer thread redacts, rotates and writes them in order.
    private val writer: BoundedDiagnosticWriter<String> = BoundedDiagnosticWriter<String>(MAX_PENDING, ::drain)
    private var reportedDrops = 0L

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            size = 0
            write(header)
        }
    }

    /** Never waits for disk. Under overload the oldest queued line is discarded. */
    fun append(line: String) {
        if (!closed && !DiagnosticRedactor.neverExported(line)) writer.enqueue(line)
    }

    /** Writer thread only. Lines queued before [close] are still written. */
    private fun drain(line: String): Unit = synchronized(lock) {
        val dropped = writer.droppedCount
        if (dropped > reportedDrops) {
            write("${dropped - reportedDrops} lines dropped")
            reportedDrops = dropped
        }
        write(line)
    }

    /** Synchronous write for callers that are already off the hot path. */
    fun appendNow(line: String) = synchronized(lock) { if (!closed) write(line) }

    private fun write(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        runCatching {
            val bytes = (safe + "\n").toByteArray()
            if (size == UNKNOWN_SIZE) size = file.length()
            if (size > MAX_BYTES) {
                rotate()
                file.writeText("")
                size = 0
            }
            file.appendBytes(bytes)
            size += bytes.size
        }.onFailure { size = UNKNOWN_SIZE }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) moveOver(source, destination)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
        pruneToBudget()
    }
    /** CarLink: a rename, not a copy: dozens of archives are shifted on every session start. */
    private fun moveOver(source: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!source.renameTo(destination)) source.copyTo(destination, overwrite = true).also { source.delete() }
    }
    /** CarLink: the oldest archives go once all of them together exceed [ARCHIVE_BUDGET_BYTES]. */
    private fun pruneToBudget() {
        var total = 0L
        for (name in ARCHIVE_NAMES) {
            val archive = File(file.parentFile, name)
            if (!archive.exists()) continue
            total += archive.length()
            if (total > ARCHIVE_BUDGET_BYTES) archive.delete()
        }
    }
    /** Refuses new lines and waits a bounded time; the writer thread keeps draining what is queued. */
    override fun close() {
        closed = true
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
    }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        // CarLink: history is kept by size (6 MiB of archives, at most 64 runs), not by 8 runs: a
        // day of short test sessions rotated the runs that mattered out (RELIABILITY_AUDIT K12).
        const val ARCHIVE_BUDGET_BYTES = 6 * 1024 * 1024L
        private const val MAX_ARCHIVES = 64
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 250L
        private const val UNKNOWN_SIZE = -1L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..MAX_ARCHIVES).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
