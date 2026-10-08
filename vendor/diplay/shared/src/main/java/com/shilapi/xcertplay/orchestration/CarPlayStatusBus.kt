package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CopyOnWriteArrayList

/**
 * CarLink: process-wide view of the current CarPlay session's stage, for screens outside
 * CarPlayHostActivity (the CarLink home and its engine dispatcher).
 *
 * [current] is null when no CarPlayController is open. Listeners are called on the main thread
 * for status changes and on the closing thread when the controller closes.
 */
object CarPlayStatusBus {
    @Volatile var current: CarPlayStatus? = null
        private set

    private val listeners = CopyOnWriteArrayList<(CarPlayStatus?) -> Unit>()

    fun addListener(listener: (CarPlayStatus?) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (CarPlayStatus?) -> Unit) {
        listeners -= listener
    }

    internal fun publish(status: CarPlayStatus?) {
        current = status
        listeners.forEach { it(status) }
    }
}
