package com.carlink.app.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * A device the gallery renders on.
 *
 * [display] is the Robolectric qualifier string for the panel. [splitWidthDp] (in the panel's
 * system dp) emulates split screen: CarLink's car profile scales from the panel's short side
 * (DESIGN_SPEC §5.0), so a ⅓ split on a 1920×1080 head unit is a 640px-wide window on a
 * 1080px-short panel, i.e. a 480×810dp canvas. Robolectric always sizes the activity window to
 * the panel, so the harness narrows Configuration and the layout itself and crops the image.
 */
data class GalleryConfig(
    val name: String,
    val profile: UiProfile,
    val display: String,
    val splitWidthDp: Int? = null,
    val themes: List<ThemeMode> = listOf(ThemeMode.DARK),
    /** Only entries that list this config by name render on it (see [GalleryEntry.configs]). */
    val optIn: Boolean = false,
    /** Lite rendering (ui/theme/RenderingMode.kt), as Automatic picks it on a low-end head unit. */
    val lite: Boolean = false,
    /** "area/name" of the entries this config renders, whatever their [GalleryEntry.configs]; null for the usual rules. */
    val entries: Set<String>? = null,
)

/** The low-end head-unit configs render these states (docs/LOW_END_PERFORMANCE.md). */
private val lowEndEntries = setOf("home/ready-no-phone", "home/ready-iphone-same-wifi-offer", "home/connecting-carplay-usb",
    "home/connected-carplay", "home/failed-wifi-off", "home/sheet-phones", "settings/general-lab", "settings/sheet-rendering",
    "home/connecting-notice-wifi", "setup/permissions", "setup/pair-waiting", "setup/pair-found-iphone",
    // A stereo whose Bluetooth is an external module (the user's FYT unit is a 1280×720 cheap head unit).
    "home/failed-external-bluetooth", "diagnostics/stereo-bluetooth-external", "setup/pair-external-bluetooth",
    // Report on GitHub from the stereo itself (Diagnostics, Home's failure state).
    "diagnostics/idle", "diagnostics/report-confirm", "diagnostics/report-qr", "home/failed-engine",
    // Steering-wheel buttons are set up on the stereo itself.
    "settings/sheet-wheel-keys", "settings/sheet-wheel-keys-learning")

val galleryConfigs = listOf(
    // The user's Redmi Pad Pro: 2560×1600px at 281dpi, about 1460×914dp in landscape (HANDHELD).
    GalleryConfig("tablet", UiProfile.HANDHELD, "w1463dp-h914dp-land-280dpi",
        themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT)),
    // 1440×810dp canvas at 1.5× (2160×1215px).
    GalleryConfig("car-landscape", UiProfile.CAR, "w1440dp-h810dp-land-hdpi",
        themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT)),
    // A 1920×1080px head unit that reports 240dpi; the car canvas must still be 1440×810dp.
    GalleryConfig("car-1080p", UiProfile.CAR, "w1280dp-h720dp-land-hdpi"),
    // BYD ⅓ split: 640×1080px window on a 1920×1080px panel → 480×810dp.
    GalleryConfig("car-split-third", UiProfile.CAR, "w1920dp-h1080dp-land-mdpi", splitWidthDp = 640),
    GalleryConfig("phone-portrait", UiProfile.HANDHELD, "w400dp-h860dp-port-xxhdpi",
        themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT)),
    GalleryConfig("phone-landscape", UiProfile.HANDHELD, "w860dp-h400dp-land-xxhdpi"),
    GalleryConfig("tablet-portrait", UiProfile.HANDHELD, "w800dp-h1280dp-port-hdpi"),
    // The user's Redmi Pad Pro in landscape (2560×1600px, about 1458×911dp).
    GalleryConfig("tablet-landscape", UiProfile.HANDHELD, "w1458dp-h911dp-land-hdpi",
        themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT), optIn = true),
    // A cheap head unit: 1024×600px at 160dpi (a 600/810 car canvas), full effects and lite rendering.
    GalleryConfig("headunit-1024x600", UiProfile.CAR, "w1024dp-h600dp-land-mdpi", entries = lowEndEntries),
    GalleryConfig("headunit-1024x600-lite", UiProfile.CAR, "w1024dp-h600dp-land-mdpi", lite = true,
        themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT), entries = lowEndEntries),
)

/**
 * Renders every [galleryEntries] × [galleryConfigs] × theme into docs/screenshots. Runs only
 * with `-Dcarlink.screenshots=true`, which `./gradlew :app:recordScreenshots` sets.
 *
 * SDK 35 because Robolectric's SDK 36+ sandbox cannot start on the current JDK without
 * extra `--add-exports` flags.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotGallery {

    @Test
    fun recordGallery() {
        assumeTrue("Set -Dcarlink.screenshots=true (./gradlew :app:recordScreenshots)",
            System.getProperty("carlink.screenshots") == "true")
        val out = File(repoRoot(), "docs/screenshots")
        // -Pcarlink.gallery.areas=switcher,home re-records only those areas;
        // -Pcarlink.only=<regex> renders only the images whose file name matches (fast iteration).
        val areas = System.getProperty("carlink.gallery.areas")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val entries = galleryEntries.filter { areas == null || it.area in areas }
        val only = System.getProperty("carlink.screenshots.only").orEmpty().takeIf { it.isNotBlank() }?.toRegex()
        if (only == null) {
            entries.map { it.area }.distinct().forEach { area ->
                File(out, area).listFiles { file -> file.extension == "png" || file.extension == "webp" }?.forEach { it.delete() }
            }
        }
        val written = mutableListOf<String>()
        for (config in galleryConfigs) {
            RuntimeEnvironment.setQualifiers(config.display)
            for (theme in config.themes) {
                for (entry in entries) {
                    if (entry.opensWindow && config.splitWidthDp != null) continue
                    if (config.entries != null) {
                        if ("${entry.area}/${entry.name}" !in config.entries) continue
                    } else if (entry.configs?.contains(config.name) == false || (config.optIn && entry.configs == null)) continue
                    val file = File(out, "${entry.area}/${entry.area}_${entry.name}_${config.name}_${theme.fileName}.webp")
                    if (only != null && !only.containsMatchIn(file.name)) continue
                    val bitmap = render(config, theme, entry)
                    file.parentFile!!.mkdirs()
                    // Half-size lossy WebP: ~1% of a full PNG and still sharp for reading the layout. The
                    // public repository would otherwise grow by gigabytes with every full re-record.
                    val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                    file.outputStream().use { small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
                    written += file.relativeTo(out).path
                }
            }
        }
        println("Screenshot gallery: wrote ${written.size} images to $out")
    }

    private fun render(config: GalleryConfig, theme: ThemeMode, entry: GalleryEntry): Bitmap {
        // "Remove animations": CarLinkTheme then sets LocalReduceMotion, which stops the hero phone's
        // infinite float and pulse. Under Robolectric's paused looper those never let the frame
        // queue drain, so the render hangs; still images want the resting pose anyway.
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val themed = @Composable {
                        CarLinkTheme(profile = config.profile, themeMode = theme, liteRendering = config.lite) { entry.content() }
                    }
                    val split = config.splitWidthDp
                    if (split == null) {
                        themed()
                    } else {
                        val base = LocalConfiguration.current
                        val narrowed = Configuration(base).apply {
                            screenWidthDp = split
                            orientation = if (split < base.screenHeightDp) Configuration.ORIENTATION_PORTRAIT
                            else Configuration.ORIENTATION_LANDSCAPE
                        }
                        CompositionLocalProvider(LocalConfiguration provides narrowed) {
                            Box(Modifier.fillMaxHeight().width(split.dp)) { themed() }
                        }
                    }
                }
            }
            // Let entrance animations, sheets and the theme cross-fade settle (all ≤350 ms).
            repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            lateinit var bitmap: Bitmap
            scenario.onActivity { activity ->
                bitmap = captureWindows(activity.window.decorView)
                val split = config.splitWidthDp
                if (split != null) {
                    val width = (split * activity.resources.displayMetrics.density).toInt().coerceAtMost(bitmap.width)
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, bitmap.height)
                }
            }
            return bitmap
        }
    }

    /** The activity window plus any windows above it (dialogs, bottom sheets, popups). */
    private fun captureWindows(base: View): Bitmap {
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        base.draw(canvas)
        for (view in rootViews()) {
            if (view === base || !view.isShown || view.width == 0 || view.height == 0) continue
            val location = IntArray(2).also(view::getLocationOnScreen)
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> = runCatching {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val names = globalClass.getMethod("getViewRootNames").invoke(global) as Array<String>
        val getRootView = globalClass.getMethod("getRootView", String::class.java)
        names.mapNotNull { getRootView.invoke(global, it) as View? }
    }.getOrDefault(emptyList())

    private val ThemeMode.fileName: String
        get() = when (this) {
            ThemeMode.DARK -> "dark"
            ThemeMode.LIGHT -> "light"
            ThemeMode.FOLLOW_SYSTEM -> "system"
        }

    /** Gradle passes the root; otherwise walk up from the test's working directory (app/). */
    private fun repoRoot(): File {
        System.getProperty("carlink.repoRoot")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "Cannot find the repository root from ${System.getProperty("user.dir")}" }
    }
}
