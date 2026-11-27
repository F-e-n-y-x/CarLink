package com.carlink.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.carlink.app.R
import com.carlink.app.switcher.NavigationBarPreference
import com.carlink.app.switcher.NavigationBarService

/**
 * Settings › General › Navigation bar: the switch, and, while it is on but Android's accessibility
 * service is not, a row that opens Android's Accessibility settings (the driver cannot be sent to
 * the service itself, only to the list). The service state is read again on every resume because
 * the driver turns it on in another screen.
 */
@Composable
internal fun NavigationBarRows() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NavigationBarPreference.enabled(context)) }
    var serviceOn by remember { mutableStateOf(NavigationBarService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = NavigationBarService.isEnabled(context)
        onPauseOrDispose { }
    }
    val openAccessibility = {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.carlink_nav_bar_accessibility_unavailable, Toast.LENGTH_LONG).show()
        }
    }
    SwitchRow(
        stringResource(R.string.carlink_nav_bar_title), enabled,
        { on ->
            NavigationBarPreference.setEnabled(context, on)
            enabled = on
            if (on && !serviceOn) openAccessibility()
        },
        description = stringResource(R.string.carlink_nav_bar_detail), icon = R.drawable.carlink_ic_dashboard,
    )
    if (enabled && !serviceOn) {
        GroupDivider()
        LinkRow(
            stringResource(R.string.carlink_nav_bar_permission), openAccessibility,
            stringResource(R.string.carlink_nav_bar_permission_detail), R.drawable.carlink_ic_lock, external = true,
        )
    }
}
