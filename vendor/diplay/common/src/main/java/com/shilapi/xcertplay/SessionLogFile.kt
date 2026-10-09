package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            append(header)
        }
    }
    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendText(safe + "\n")
        }
        Unit
    }
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
    override fun close() = synchronized(lock) { closed = true }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        // CarLink: history is kept by size (6 MiB of archives, at most 64 runs), not by 8 runs: a
        // day of short test sessions rotated the runs that mattered out (RELIABILITY_AUDIT K12).
        const val ARCHIVE_BUDGET_BYTES = 6 * 1024 * 1024L
        private const val MAX_ARCHIVES = 64
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..MAX_ARCHIVES).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
