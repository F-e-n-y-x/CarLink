package com.carlink.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.carlink.app.R
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.IonButton
import java.text.DateFormat
import java.util.Date

/** Back up: Wi-Fi passwords stay out unless the driver includes them (the file is readable by whoever has it). */
@Composable
internal fun BackupPasswordsDialog(onChoose: (withPasswords: Boolean) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carlink_backup_passwords_title)) },
        text = { Text(stringResource(R.string.carlink_backup_passwords_body), color = scheme.onSurfaceVariant) },
        confirmButton = {
            IonButton(stringResource(R.string.carlink_backup_without_passwords), { onChoose(false) }, icon = R.drawable.carlink_ic_check)
        },
        dismissButton = {
            GlassButton(stringResource(R.string.carlink_backup_with_passwords), { onChoose(true) }, icon = R.drawable.carlink_ic_lock)
        },
        containerColor = scheme.surfaceContainerHigh,
    )
}

/** Restore: what the file holds and what it replaces, asked before anything is written. */
@Composable
internal fun RestoreConfirmDialog(backup: SettingsBackup.Backup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val count = pluralStringResource(R.plurals.carlink_restore_count, backup.count, backup.count)
    val body = listOfNotNull(
        backup.createdMillis?.let { stringResource(R.string.carlink_restore_body_dated, count,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
            ?: stringResource(R.string.carlink_restore_body, count),
        if (backup.withPasswords) null else stringResource(R.string.carlink_restore_passwords_kept),
    ).joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carlink_restore_title)) },
        text = { Text(body, color = scheme.onSurfaceVariant) },
        confirmButton = { IonButton(stringResource(R.string.carlink_restore_confirm), onConfirm, icon = R.drawable.carlink_ic_restart_alt) },
        dismissButton = { GlassButton(stringResource(R.string.carlink_home_cancel), onDismiss) },
        containerColor = scheme.surfaceContainerHigh,
    )
}

/** A file that is not a backup, or one that was changed: nothing was written. */
@Composable
internal fun RestoreErrorDialog(reason: String?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carlink_restore_error_title)) },
        text = { Text(stringResource(R.string.carlink_restore_error_body, reason ?: "?"), color = scheme.onSurfaceVariant) },
        confirmButton = { IonButton(stringResource(R.string.carlink_restore_error_ok), onDismiss) },
        containerColor = scheme.surfaceContainerHigh,
    )
}
