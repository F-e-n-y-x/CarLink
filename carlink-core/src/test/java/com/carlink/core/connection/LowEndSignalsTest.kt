package com.carlink.core.connection

import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weak-GPU and slow-CPU signals, and when ":aa" is prewarmed (docs/LOW_END_PERFORMANCE.md, round 2). */
class LowEndSignalsTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    // The user's FYT stereo: Unisoc UIS8581A (SC9863A), 8× Cortex-A55 at 1.6 GHz, PowerVR GE8322, 4 GB.
    private val uis8581a = DeviceSignals(totalRamMb = 3_720, lowRamDevice = false, cpuCores = 8, supportedAbis = arm,
        cpuMaxMhz = 1_600, gpuRenderer = "PowerVR Rogue GE8322", memoryClassMb = 256)
    private val tablet = DeviceSignals(totalRamMb = 7_600, lowRamDevice = false, cpuCores = 8, supportedAbis = arm,
        cpuMaxMhz = 2_800, gpuRenderer = "Adreno (TM) 710", memoryClassMb = 256)
    private val uis7862With4Gb = DeviceSignals(totalRamMb = 3_740, lowRamDevice = false, cpuCores = 8, supportedAbis = arm,
        cpuMaxMhz = 1_800, gpuRenderer = "Mali-G52", memoryClassMb = 256)

    @Test fun theUsersStereoDrawsLiteButKeepsItsPictureRules() {
        assertTrue(uis8581a.liteRendering)
        assertEquals(listOf("GPU PowerVR Rogue GE8322", "CPU at most 1600 MHz"), uis8581a.liteReasons)
        // Picture rules (Android Auto 60 fps) still see a 4 GB, 8-core unit as not low-end.
        assertFalse(uis8581a.lowEnd)
    }

    @Test fun capableHardwareStaysFull() {
        assertFalse(tablet.liteRendering)
        assertFalse(uis7862With4Gb.liteRendering)
        // Before the first GPU probe, or where sysfs is unreadable, those are not signals.
        assertFalse(uis8581a.copy(gpuRenderer = null, cpuMaxMhz = 0).liteRendering)
    }

    @Test fun eitherNewSignalAloneIsEnough() {
        assertTrue(uis8581a.copy(cpuMaxMhz = 2_000).liteRendering)
        assertTrue(uis8581a.copy(gpuRenderer = "Mali-G57").liteRendering)
        assertFalse(uis8581a.copy(gpuRenderer = "Mali-G57", cpuMaxMhz = 2_000).liteRendering)
    }

    @Test fun lowEndHardwareIsAlsoLite() {
        val mt8163 = DeviceSignals(totalRamMb = 1_950, lowRamDevice = false, cpuCores = 4, supportedAbis = arm)
        assertTrue(mt8163.lowEnd)
        assertTrue(mt8163.liteRendering)
    }

    @Test fun weakGpusByRendererName() {
        listOf("PowerVR Rogue GE8322", "PowerVR Rogue GE8300", "PowerVR GE8100", "PowerVR SGX 544MP", "Mali-T720",
            "Mali-450 MP", "Mali-400 MP", "Mali-T860", "Mali-G31", "Mali-G51", "Adreno (TM) 306", "Adreno (TM) 505",
            "Vivante GC1000", "Android Emulator OpenGL ES Translator (Google SwiftShader)", "llvmpipe (LLVM 15.0.7, 256 bits)")
            .forEach { assertTrue(it, GpuClass.weak(it)) }
        listOf("Adreno (TM) 610", "Adreno (TM) 730", "Mali-G52", "Mali-G57 MC2", "Mali-G610 MC4", "PowerVR B-Series BXM-8-256",
            "Mali-G510", "Xclipse 920")
            .forEach { assertFalse(it, GpuClass.weak(it)) }
    }

    @Test fun prewarmOnMemoryTightUnitsOnlyForAKnownAndroidPhone() {
        val twoGb = DeviceSignals(totalRamMb = 1_950, lowRamDevice = false, cpuCores = 4, supportedAbis = arm)
        assertFalse(EnginePrewarmPolicy.shouldPrewarm(twoGb, emptyList()))
        assertFalse(EnginePrewarmPolicy.shouldPrewarm(twoGb, listOf(PhoneKind.IPHONE)))
        assertTrue(EnginePrewarmPolicy.shouldPrewarm(twoGb, listOf(PhoneKind.IPHONE, PhoneKind.ANDROID)))
        assertFalse(EnginePrewarmPolicy.shouldPrewarm(twoGb.copy(totalRamMb = 7_600, lowRamDevice = true), emptyList()))
        // 4 GB and more: always, as before.
        assertTrue(EnginePrewarmPolicy.shouldPrewarm(uis8581a, emptyList()))
        assertTrue(EnginePrewarmPolicy.shouldPrewarm(tablet, listOf(PhoneKind.IPHONE)))
    }
}
