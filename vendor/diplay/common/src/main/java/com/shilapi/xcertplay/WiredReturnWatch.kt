package com.shilapi.xcertplay

/**
 * CarLink (new file): whether the iPhone came back on USB after leaving it, i.e. it re-enumerated.
 * An iPhone that stays listed is not "back": its wired retry keeps the normal delay.
 */
internal class WiredReturnWatch {
    private var left = false

    fun back(listed: Boolean): Boolean {
        if (!listed) left = true
        return left && listed
    }

    companion object {
        const val POLL_MILLIS = 100L
    }
}
