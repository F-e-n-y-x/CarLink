package com.carlink.app.ui.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/**
 * The one sheet of CarLink (Settings choices, Home's Phones and Network): a graphite panel that
 * rises from the bottom edge, with a top-lit hairline rim and a slim handle. It is opaque on
 * purpose: glass over moving content is not faked (RESEARCH_PREMIUM_UX §5.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val dark = scheme.background.luminance() < 0.5f
    val radius = if (car) 40.dp else 28.dp
    val shape = RoundedCornerShape(topStart = radius, topEnd = radius)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (car) 1240.dp else 760.dp,
        shape = shape,
        containerColor = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        contentColor = scheme.onSurface,
        scrimColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.32f),
        dragHandle = {
            Box(Modifier.padding(top = if (car) 18.dp else 12.dp, bottom = if (car) 14.dp else 8.dp)
                .size(width = if (car) 56.dp else 40.dp, height = if (car) 6.dp else 4.dp)
                .clip(RoundedCornerShape(50)).background(scheme.onSurface.copy(alpha = 0.22f)))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = if (car) dimens.screenMargin else dimens.gutter * 1.5f)
                .navigationBarsPadding().padding(bottom = dimens.gutter * 1.5f),
            content = content,
        )
    }
}

/** A sheet's title and its one-line explanation. */
@Composable
fun SheetHeader(title: String, body: String? = null) {
    val car = CarLinkTokens.profile == UiProfile.CAR
    Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() })
    if (body != null) {
        Spacer(Modifier.height(if (car) 8.dp else 4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
