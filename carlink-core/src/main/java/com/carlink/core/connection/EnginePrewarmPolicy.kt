package com.carlink.core.connection

import com.carlink.core.core.PhoneKind

/**
 * Whether to start Android Auto's ":aa" process ahead of time ([Connection.prewarm]). The warm
 * process saves a cold process start when an Android phone connects, and costs its memory for as
 * long as CarLink runs (18 MB PSS on the low-end emulator, 2026-10-15). Units with more than 3 GB
 * always prewarm. A 3 GB unit ([THREE_GB_CLASS_MB] or more reported) prewarms unless every phone it
 * knows is an iPhone: with no phone known yet, the first one is as likely an Android phone, and
 * 18 MB is under 1% of its memory. Below that (2 GB, low-RAM builds) only a known Android phone
 * earns it; with only iPhones, or none, the process starts when an Android phone first connects.
 */
object EnginePrewarmPolicy {
    /** A nominal 3 GB unit reports 2.6-2.9 GB (firmware carve-outs); a 2 GB one at most ~1.95 GB. */
    const val THREE_GB_CLASS_MB = 2_500L

    fun memoryTight(signals: DeviceSignals): Boolean =
        signals.lowRamDevice || signals.totalRamMb in 1..DeviceSignals.LOW_END_RAM_MB

    /** A constrained unit ([DeviceSignals.constrained]) never holds the second process warm: 18-27 MB for its whole run. */
    fun shouldPrewarm(signals: DeviceSignals, knownPhones: Collection<PhoneKind>): Boolean = when {
        signals.constrained -> false
        !memoryTight(signals) || PhoneKind.ANDROID in knownPhones -> true
        else -> !signals.lowRamDevice && signals.totalRamMb >= THREE_GB_CLASS_MB && knownPhones.isEmpty()
    }
}
