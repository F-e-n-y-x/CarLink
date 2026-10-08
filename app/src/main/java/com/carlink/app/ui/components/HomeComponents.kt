package com.carlink.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Small building blocks shared by Home and Connecting; generally useful for any status screen.
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

/** Tabular figures so a ticking timer doesn't jitter (§5.2). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

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

/** An animation duration that becomes 0 when the system animator scale is 0 (§5.7). */
@Composable
fun motionMillis(millis: Int): Int = if (LocalReduceMotion.current) 0 else millis

/**
 * The D-pad / rotary focus ring (§7.2): a primary-colored stroke drawn while the element has
 * focus. Touch input never focuses Compose buttons, so it stays hidden for touch.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val width = CarLinkTokens.dimens.focusRing
    val color = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}

/**
 * Moves focus to [requester] once, but only when the driver is using keys, a joystick or a
 * rotary: Home focuses its primary, Connecting its Cancel, an error its recovery (§7.2).
 */
@Composable
fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(key) {
        if (inputMode == InputMode.Keyboard) runCatching { requester.requestFocus() }
    }
}

/** A presence dot; always paired with text, never color alone (§7.1). */
@Composable
fun PresenceDot(color: Color, modifier: Modifier = Modifier, size: Dp = CarLinkTokens.dimens.iconTertiary / 2) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** "iPhone · CarPlay" / "Android · Android Auto": what CarLink detected. */
@Composable
fun detectedLabel(kind: PhoneKind): String = stringResource(
    when (kind) {
        PhoneKind.IPHONE -> R.string.carlink_home_detected_iphone
        PhoneKind.ANDROID -> R.string.carlink_home_detected_android
        PhoneKind.UNKNOWN -> R.string.carlink_home_detected_unknown
    },
)

/** The engine a phone uses, or "Unknown type". */
@Composable
fun phoneTypeLabel(kind: PhoneKind): String = kind.engine?.let { engineName(it) }
    ?: stringResource(R.string.carlink_home_kind_unknown)

/** Spoken name of the phone glyph, for avatars that carry meaning. */
@Composable
fun phoneKindDescription(kind: PhoneKind): String = stringResource(
    when (kind) {
        PhoneKind.IPHONE -> R.string.carlink_home_avatar_iphone
        PhoneKind.ANDROID -> R.string.carlink_home_avatar_android
        PhoneKind.UNKNOWN -> R.string.carlink_home_avatar_unknown
    },
)

/** The phone glyph that matches an engine (for sessions where only the engine is known). */
fun kindOf(engine: Engine?): PhoneKind = when (engine) {
    Engine.CARPLAY -> PhoneKind.IPHONE
    Engine.ANDROID_AUTO -> PhoneKind.ANDROID
    null -> PhoneKind.UNKNOWN
}
