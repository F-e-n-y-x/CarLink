package com.carlink.core.connection

import com.carlink.core.core.PhoneKind

/**
 * Whether to start Android Auto's ":aa" process ahead of time ([Connection.prewarm]). The warm
 * process saves a cold process start when an Android phone connects, and costs its memory for as
 * long as CarLink runs (18 MB PSS on the low-end emulator, 2026-10-15). On a memory-tight unit
 * (a low-RAM build or 3 GB or less) it is only worth that when an Android phone is known; with
 * only iPhones, or none, the process starts when an Android phone first connects, as it always
 * could. Units with more memory always prewarm, as before.
 */
object EnginePrewarmPolicy {
    fun memoryTight(signals: DeviceSignals): Boolean =
        signals.lowRamDevice || signals.totalRamMb in 1..DeviceSignals.LOW_END_RAM_MB

    fun shouldPrewarm(signals: DeviceSignals, knownPhones: Collection<PhoneKind>): Boolean =
        !memoryTight(signals) || PhoneKind.ANDROID in knownPhones
}
