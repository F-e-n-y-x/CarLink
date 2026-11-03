package com.carlink.core.connection

import com.carlink.core.core.PhoneKind

/** A phone Home could start by itself: its [kind] decides the engine; [present] = its Bluetooth is connected now. */
data class PhoneOption(val address: String, val name: String?, val kind: PhoneKind, val present: Boolean)

/**
 * Which phone Home's automatic start connects, or that it must ask. An iPhone and an Android phone
 * both in reach would otherwise race: the last phone won even when the driver brought the other
 * (tablet, 2026-11-04). The driver's tap is never second-guessed; this is only for automatic starts.
 */
object PhoneChoice {
    sealed interface Decision {
        data class Start(val phone: PhoneOption) : Decision
        data class Ask(val phones: List<PhoneOption>) : Decision
        data object Nothing : Decision
    }

    /**
     * Asks only on a real conflict: phones of both kinds connected at the same time, and lists only
     * those. Without presence (nobody connected yet, or a module stereo where Android cannot see
     * the phones) the last phone starts, as before; knowing several phones is no conflict.
     */
    fun decide(options: List<PhoneOption>, lastPhone: String?): Decision {
        val usable = options.filter { it.kind.engine != null }.distinctBy { it.address.uppercase() }
        fun last(of: List<PhoneOption>) = of.firstOrNull { it.address.equals(lastPhone, ignoreCase = true) }
        val present = usable.filter { it.present }
        return when {
            present.map { it.kind.engine }.distinct().size > 1 -> Decision.Ask(present)
            present.isNotEmpty() -> Decision.Start(last(present) ?: present.first())
            else -> last(usable)?.let { Decision.Start(it) } ?: Decision.Nothing
        }
    }
}
