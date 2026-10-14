package com.carlink.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The release package the profiles are generated and measured for. */
const val PACKAGE = "com.carlink.app"

private const val WAIT_MS = 5_000L

/**
 * The driver's journeys through CarLink without a phone (none can be attached to an emulator).
 * Labels are matched by text or content description, in English (CarLink ships English only).
 */
private fun MacrobenchmarkScope.find(label: String, timeout: Long = WAIT_MS): UiObject2? {
    val selectors: List<BySelector> = listOf(By.text(label), By.desc(label))
    val deadline = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < deadline) {
        for (selector in selectors) device.findObject(selector)?.let { return it }
        device.waitForIdle(200)
    }
    return null
}

private fun MacrobenchmarkScope.tap(label: String): Boolean {
    val node = find(label) ?: return false
    node.click()
    device.waitForIdle()
    return true
}

/**
 * Home is ready when its Settings button is on screen. A fresh install first shows the setup over
 * Home; a driver who skips it lands on Home.
 */
fun MacrobenchmarkScope.waitForHome() {
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        (device.findObject(By.desc("Skip setup")) ?: device.findObject(By.text("Skip setup")))?.let { skip ->
            runCatching { skip.click() }
            device.wait(Until.gone(By.desc("Skip setup")), WAIT_MS)
        }
        if (device.findObject(By.desc("Settings")) != null || device.findObject(By.text("Settings")) != null) return
        device.waitForIdle(250)
    }
    error("Home did not show its Settings button")
}

/** The largest scrollable on screen: Settings' content pane (the category rail is narrower). */
private fun MacrobenchmarkScope.mainScrollable(): UiObject2? =
    device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }

/**
 * Two swipes over the content pane. Plain swipes from its bounds, read once: Compose replaces the
 * accessibility node while a list scrolls, so UiObject2.scroll goes stale half-way.
 */
private fun MacrobenchmarkScope.scrollDownAndUp() {
    val bounds = runCatching { mainScrollable()?.visibleBounds }.getOrNull() ?: return
    val x = bounds.centerX()
    val top = bounds.top + bounds.height() / 5
    val bottom = bounds.bottom - bounds.height() / 5
    device.swipe(x, bottom, x, top, 20)
    device.waitForIdle()
    device.swipe(x, top, x, bottom, 20)
    device.waitForIdle()
}

/** Home → Settings, scroll the content, visit the categories, back to Home. */
fun MacrobenchmarkScope.browseSettings() {
    check(tap("Settings")) { "no Settings button" }
    find("General")
    scrollDownAndUp()
    for (category in listOf("Display", "Sound & voice", "Navigation", "Car", "General", "Connection")) {
        if (tap(category)) scrollDownAndUp()
    }
    device.pressBack()
    find("Settings")
}

/** Home → Settings › General › Diagnostics, scroll, back to Home. */
fun MacrobenchmarkScope.openDiagnostics() {
    check(tap("Settings")) { "no Settings button" }
    tap("General")
    if (find("Diagnostics", 1_500) == null) {
        val bounds = runCatching { mainScrollable()?.visibleBounds }.getOrNull()
        if (bounds != null) device.swipe(bounds.centerX(), bounds.bottom - bounds.height() / 5, bounds.centerX(),
            bounds.top + bounds.height() / 5, 20)
    }
    if (tap("Diagnostics")) {
        device.waitForIdle()
        scrollDownAndUp()
        device.pressBack()
    }
    device.pressBack()
    find("Settings")
}

/** Home → Settings and straight back, the transition the round-1 numbers measured. */
fun MacrobenchmarkScope.settingsAndBack() {
    check(tap("Settings")) { "no Settings button" }
    find("General")
    device.pressBack()
    find("Settings")
}
