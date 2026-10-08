package com.carlink.app.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Text helpers for status screens (Home, Diagnostics, About).
 */

/** Wraps user data (a phone name) in FSI…PDI so it can't reorder the surrounding sentence (§7.3). */
fun isolate(text: String): String = "⁨$text⁩"

/** Forces a technical value (timer, duration) to left-to-right inside any locale (§7.3). */
fun ltr(text: String): String = "⁦$text⁩"

/** "m:ss", or "h:mm:ss" after an hour, Western digits, isolated LTR. */
fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val text = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
    return ltr(text)
}

/**
 * Milliseconds since [since] (an elapsedRealtime timestamp), updated once a second while
 * [since] is set. Returns 0 when it is null.
 */
@Composable
fun rememberElapsedMillis(since: Long?): State<Long> {
    val state = remember { mutableLongStateOf(0L) }
    LaunchedEffect(since) {
        if (since == null) {
            state.longValue = 0L
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - since
            state.longValue = elapsed
            // Wake on the next whole second so the display never skips a digit.
            delay(1000 - (elapsed % 1000) + 5)
        }
    }
    return state
}
