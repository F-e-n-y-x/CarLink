package com.shilapi.xcertplay.hud

/** One of the car's call-state writes: the value sent for a call and the one BYD's CarPlay app sends when it ends. */
internal class CarPlayCallWrite(val label: String, val call: Int, val idle: Int)

/**
 * Applies a call's writes all or nothing. A feature this firmware does not have is skipped; a write the
 * car refuses sets the ones already accepted back to their idle values, newest first, so the car is not
 * left half in a call. The caller name is written last and is hidden by the ended instrument state.
 */
internal object CarPlayCallWrites {
    enum class Result { DONE, MISSING, REFUSED }

    /** Returns false when a write was refused and the accepted ones were undone. */
    fun apply(
        writes: List<CarPlayCallWrite>,
        write: (step: CarPlayCallWrite, value: Int, undo: Boolean) -> Result,
        name: () -> Result,
    ): Boolean {
        val accepted = ArrayList<CarPlayCallWrite>()
        for (step in writes) {
            when (write(step, step.call, false)) {
                Result.DONE -> accepted += step
                Result.MISSING -> Unit
                Result.REFUSED -> return undo(accepted, write)
            }
        }
        if (name() == Result.REFUSED) return undo(accepted, write)
        return true
    }

    private fun undo(accepted: List<CarPlayCallWrite>, write: (CarPlayCallWrite, Int, Boolean) -> Result): Boolean {
        accepted.asReversed().forEach { write(it, it.idle, true) }
        return false
    }
}
