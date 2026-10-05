package com.shilapi.xcertplay.hud

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Shared by shell commands/watchers so an old process cannot reset a newer call. */
internal class CarPlayCallWatchOwnership(private val directory: File = File("/data/local/tmp")) {
    private val owner = File(directory, "diplay-carplay-call-owner")
    private val lock = File(directory, "diplay-carplay-call-lock")

    fun claim(path: String, action: (File) -> Unit) = locked {
        val token = token(path)
        owner.writeText(token.path)
        action(token)
    }

    fun update(path: String, action: (File) -> Unit): Boolean = locked {
        val token = token(path)
        if (owner.readTextOrNull() != token.path) return@locked false
        action(token)
        true
    }

    fun retire(path: String, action: () -> Unit): Boolean = locked {
        val token = token(path)
        val ours = owner.readTextOrNull() == token.path
        if (ours) {
            action()
            owner.delete()
        }
        token.delete()
        ours
    }

    private fun token(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile &&
            file.name.matches(Regex("diplay-carplay-call-[A-Za-z0-9_.]+-[0-9a-f-]{36}"))) {
            "Invalid CarPlay call token"
        }
        return file
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        RandomAccessFile(lock, "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }

    companion object {
        // File locks serialize different shell processes; this also serializes threads in one JVM.
        private val processLock = Any()
        fun newToken(packageName: String): String {
            require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
            return "/data/local/tmp/diplay-carplay-call-$packageName-${UUID.randomUUID()}"
        }
    }
}
