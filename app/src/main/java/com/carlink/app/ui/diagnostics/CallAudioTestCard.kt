package com.carlink.app.ui.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carlink.app.R
import com.carlink.app.diagnostics.CallAudioTest
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.settings.GlassGroup
import com.carlink.app.ui.settings.GroupHeader
import com.carlink.app.ui.settings.RowGlyph
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.settings.rowPadding
import com.carlink.core.settings.UnifiedSettingsStore
import kotlinx.coroutines.launch

/** Diagnostics › Test call audio ([CallAudioTest]); disabled while a session holds the microphone. */
@Composable
internal fun CallAudioTestCard(sessionRunning: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var peak by remember { mutableStateOf(0) }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_diag_call_test), icon = R.drawable.carlink_ic_mic) }) {
        Row(Modifier.fillMaxWidth().padding(rowPadding), verticalAlignment = Alignment.CenterVertically) {
            RowGlyph(R.drawable.carlink_ic_graphic_eq, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.carlink_diag_call_test_detail), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val shown = if (sessionRunning && !running) R.string.carlink_diag_call_test_busy else status
                if (shown != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(if (shown == R.string.carlink_diag_call_test_result) stringResource(shown, peak) else stringResource(shown),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            Spacer(Modifier.width(16.dp))
            if (!running && !sessionRunning) GlassButton(stringResource(R.string.carlink_diag_call_test_start), onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    status = R.string.carlink_diag_call_test_permission
                    return@GlassButton
                }
                val settings = UnifiedSettingsStore(context).load()
                running = true
                scope.launch {
                    val result = CallAudioTest.run(settings.micGainDb, settings.callAudioAsMedia) { phase ->
                        status = when (phase) {
                            CallAudioTest.Phase.RECORDING -> R.string.carlink_diag_call_test_recording
                            CallAudioTest.Phase.PLAYING -> R.string.carlink_diag_call_test_playing
                        }
                    }
                    status = when (result) {
                        is CallAudioTest.Result.Done -> {
                            peak = result.peakPercent
                            if (result.clipped) R.string.carlink_diag_call_test_clipped else R.string.carlink_diag_call_test_result
                        }
                        CallAudioTest.Result.Silent -> R.string.carlink_diag_call_test_silent
                        CallAudioTest.Result.Failed -> R.string.carlink_diag_call_test_failed
                    }
                    running = false
                }
            }, icon = R.drawable.carlink_ic_mic)
        }
    }
}
