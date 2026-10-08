package com.carlink.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import com.carlink.app.ui.theme.CarLinkTokens

/**
 * The D-pad/rotary focus ring (DESIGN_SPEC §7.2): a `primary` stroke drawn while the element
 * after this modifier, or one of its children, has focus. Touch never focuses clickables, so
 * the ring stays hidden for touch.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val width = CarLinkTokens.dimens.focusRing
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}
