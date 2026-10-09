package com.carlink.core.connection

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log

/** A picture size in pixels. */
data class VideoSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val portrait: Boolean get() = height > width

    /** This size at [percent] %, rounded to even dimensions as DiPlay does (CarPlayDisplayScale.applyPercent). */
    fun scaled(percent: Int): VideoSize = VideoSize(scale(width, percent), scale(height, percent))

    fun swapped(): VideoSize = VideoSize(height, width)

    override fun toString() = "${width}x$height"

    private fun scale(pixels: Int, percent: Int): Int =
        ((pixels.toLong() * percent + 50L) / 100L).toInt().coerceAtLeast(1).let { if (it % 2 == 0) it else it + 1 }
}

/**
 * What one video decoder says it can carry, probed once per device (decoders do not change).
 *
 * @property hardware a vendor (hardware) component; false for Android's software decoders.
 * @property maxFps for every probed size, the highest of 60 or 30 fps the decoder claims to carry,
 *   following DiAuto's `DecoderCapabilityReport.adequate`: the size is supported, the rate is
 *   supported, and (Android 10+) a performance point covers it when the decoder declares any. A size
 *   the decoder cannot carry at 30 fps is absent.
 */
data class DecoderInfo(val name: String, val hardware: Boolean, val maxFps: Map<VideoSize, Int>) {
    fun fpsAt(size: VideoSize): Int = maxFps[size] ?: 0
    fun carries(size: VideoSize, fps: Int = 30): Boolean = fpsAt(size) >= fps
}

/**
 * The few facts about this head unit or tablet that CarLink adapts to: memory, processor and
 * ABIs (for lite rendering, `ui/theme/RenderingMode.kt`) and the hardware video decoders (for
 * Automatic picture, [PicturePolicy]). Built by [DeviceCapabilities.signals] (cheap, main thread
 * is fine) and [DeviceCapabilities.read] (scans the codec list: call off the main thread).
 */
data class DeviceSignals(
    val totalRamMb: Long,
    /** `ActivityManager.isLowRamDevice`: Android Go and ≤1 GB builds. */
    val lowRamDevice: Boolean,
    val cpuCores: Int,
    val supportedAbis: List<String>,
) {
    val has64BitAbi: Boolean get() = supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }

    /** Why this device counts as low-end hardware; empty when it does not. */
    val lowEndReasons: List<String>
        get() = buildList {
            if (lowRamDevice) add("low-RAM device")
            if (totalRamMb in 1..LOW_END_RAM_MB) add("${totalRamMb} MB RAM")
            if (supportedAbis.isNotEmpty() && !has64BitAbi) add("32-bit only")
            if (cpuCores in 1..LOW_END_CORES) add("$cpuCores CPU cores")
        }

    val lowEnd: Boolean get() = lowEndReasons.isNotEmpty()

    companion object {
        /**
         * "3 GB or less". A 3 GB head unit reports about 2.7-2.9 GB to apps (the kernel and the
         * GPU carve-out are not counted) and a 4 GB one about 3.6-3.8 GB, so the line sits between.
         */
        const val LOW_END_RAM_MB = 3_300L
        /** Quad-core A7/A53 head units (MT8163, MT8227, Allwinner T3); current SoCs have 8 cores. */
        const val LOW_END_CORES = 4
    }
}

data class DeviceCapabilities(
    /** The panel in its current orientation, in real pixels (system bars included). */
    val panel: VideoSize,
    val signals: DeviceSignals,
    /** The decoder `MediaCodec.createDecoderByType("video/avc")` would get: the first hardware one, else the first. */
    val h264: DecoderInfo?,
    val h265: DecoderInfo?,
) {
    val hardwareH264: Boolean get() = h264?.hardware == true
    val hardwareHevc: Boolean get() = h265?.hardware == true

    companion object {
        private const val TAG = "CarLink-Capabilities"
        @Volatile private var signalsCache: DeviceSignals? = null
        @Volatile private var cache: DeviceCapabilities? = null

        /** Memory, cores and ABIs. Cheap; cached for the process. */
        fun signals(context: Context): DeviceSignals = signalsCache ?: run {
            val am = context.applicationContext.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
            DeviceSignals(
                totalRamMb = memory.totalMem / (1024 * 1024),
                lowRamDevice = runCatching { am?.isLowRamDevice == true }.getOrDefault(false),
                cpuCores = Runtime.getRuntime().availableProcessors(),
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            ).also { signalsCache = it }
        }

        /**
         * Everything, including the decoders. Scans MediaCodecList (tens to hundreds of ms on a
         * slow head unit): call from a worker thread. Cached per panel size.
         */
        fun read(context: Context): DeviceCapabilities {
            val panel = panelSize(context)
            cache?.takeIf { it.panel == panel }?.let { return it }
            val sizes = PicturePolicy.probeSizes(panel)
            val started = android.os.SystemClock.uptimeMillis()
            val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
            val capabilities = DeviceCapabilities(
                panel = panel,
                signals = signals(context),
                h264 = probe(list, MediaFormat.MIMETYPE_VIDEO_AVC, sizes),
                h265 = probe(list, MediaFormat.MIMETYPE_VIDEO_HEVC, sizes),
            )
            Log.i(TAG, "probed in ${android.os.SystemClock.uptimeMillis() - started} ms: $capabilities")
            cache = capabilities
            return capabilities
        }

        @Suppress("DEPRECATION") // getRealMetrics is the API-25-compatible way to read the panel.
        private fun panelSize(context: Context): VideoSize {
            val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            val display = manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            if (display != null) display.getRealMetrics(metrics) else metrics.setTo(context.resources.displayMetrics)
            return VideoSize(metrics.widthPixels, metrics.heightPixels)
        }

        private fun probe(list: List<MediaCodecInfo>, mime: String, sizes: Set<VideoSize>): DecoderInfo? {
            val decoders = list.filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            val chosen = decoders.firstOrNull { isHardware(it) } ?: decoders.firstOrNull() ?: return null
            val video = runCatching { chosen.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            val rates = buildMap {
                if (video != null) sizes.forEach { size ->
                    val fps = listOf(60, 30).firstOrNull { adequate(video, size, it) }
                    if (fps != null) put(size, fps)
                }
            }
            return DecoderInfo(chosen.name, isHardware(chosen), rates)
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("omx.ffmpeg.") ||
                name.contains(".sw.") || name.contains("software"))
        }

        /** DiAuto's `DecoderCapabilityReport.adequate`, with an empty performance-point list read as "not declared". */
        private fun adequate(video: MediaCodecInfo.VideoCapabilities, size: VideoSize, fps: Int): Boolean {
            val sizeOk = runCatching { video.isSizeSupported(size.width, size.height) }.getOrDefault(false)
            if (!sizeOk) return false
            val rateOk = runCatching { video.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }.getOrDefault(false)
            if (!rateOk) return false
            if (Build.VERSION.SDK_INT < 29) return true
            val points = runCatching { video.supportedPerformancePoints }.getOrNull()
            if (points.isNullOrEmpty()) return true
            val target = MediaCodecInfo.VideoCapabilities.PerformancePoint(size.width, size.height, fps)
            return points.any { it.covers(target) }
        }
    }
}
