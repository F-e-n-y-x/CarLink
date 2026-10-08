package com.carlink.app.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.carlink.app.R
import com.carlink.app.diagnostics.CarPlayAuthSource
import com.carlink.app.diagnostics.CheckFix
import com.carlink.app.diagnostics.CheckId
import com.carlink.app.diagnostics.CheckResult
import com.carlink.app.diagnostics.CheckStatus
import com.carlink.app.diagnostics.DecoderInfo
import com.carlink.app.ui.components.ltr
import com.shilapi.xcertplay.orchestration.MfiTarget

/** What a check row says: title, supporting line, an optional note, and the Fix label. */
data class CheckText(val title: String, val supporting: String? = null, val note: String? = null, val fixLabel: String? = null)

/** The driver-facing words for a [CheckResult]. Kept apart from [com.carlink.app.diagnostics.Checks] so that stays string-free. */
@Composable
fun checkText(result: CheckResult): CheckText {
    val ok = result.status == CheckStatus.PASS
    val fixLabel = when (result.fix) {
        null -> null
        CheckFix.ResumeAutoConnect -> stringResource(R.string.carlink_diag_resume)
        else -> stringResource(R.string.carlink_diag_fix)
    }
    @Composable fun s(id: Int) = stringResource(id)
    val text = when (result.id) {
        CheckId.CARPLAY_AUTHENTICATION -> when (result.authSource) {
            CarPlayAuthSource.BUNDLED -> CheckText(s(R.string.carlink_diag_auth_ok), s(R.string.carlink_diag_auth_bundled))
            CarPlayAuthSource.INSTALLED -> CheckText(s(R.string.carlink_diag_auth_ok), s(R.string.carlink_diag_auth_installed))
            CarPlayAuthSource.EXTERNAL -> CheckText(s(R.string.carlink_diag_auth_external),
                stringResource(R.string.carlink_diag_auth_external_body, s(when (result.mfiTarget) {
                    MfiTarget.I2C -> R.string.carlink_diag_auth_target_i2c
                    MfiTarget.REMOTE -> R.string.carlink_diag_auth_target_remote
                    else -> R.string.carlink_diag_auth_target_ch341
                })))
            CarPlayAuthSource.MISSING, null -> CheckText(s(R.string.carlink_diag_auth_missing),
                note = s(R.string.carlink_diag_auth_missing_note))
        }
        CheckId.BLUETOOTH -> when (result.status) {
            CheckStatus.PASS -> CheckText(s(R.string.carlink_diag_bt_on))
            CheckStatus.INFO -> CheckText(s(R.string.carlink_diag_bt_absent), s(R.string.carlink_diag_bt_absent_body))
            else -> CheckText(s(R.string.carlink_diag_bt_off), s(R.string.carlink_diag_bt_off_body))
        }
        CheckId.BLUETOOTH_PERMISSION -> if (ok) CheckText(s(R.string.carlink_diag_bt_perm_ok))
            else CheckText(s(R.string.carlink_diag_bt_perm_missing), s(R.string.carlink_diag_bt_perm_body))
        CheckId.PAIRED_PHONES -> {
            val count = result.count ?: 0
            if (count == 0) CheckText(s(R.string.carlink_diag_phones_none), s(R.string.carlink_diag_phones_none_body))
            else CheckText(
                pluralStringResource(R.plurals.carlink_diag_phones_count, count, count),
                if (result.unknownCount > 0) {
                    pluralStringResource(R.plurals.carlink_diag_phones_unknown, result.unknownCount, result.unknownCount)
                } else s(R.string.carlink_diag_phones_body),
            )
        }
        CheckId.AUTO_CONNECT -> when (result.status) {
            CheckStatus.PASS -> CheckText(s(R.string.carlink_diag_auto_on))
            CheckStatus.INFO -> CheckText(s(R.string.carlink_diag_auto_off), s(R.string.carlink_diag_auto_off_body))
            else -> CheckText(s(R.string.carlink_diag_auto_paused), s(R.string.carlink_diag_auto_paused_body))
        }
        CheckId.LOCATION_PERMISSION -> if (ok) CheckText(s(R.string.carlink_diag_location_ok))
            else CheckText(s(R.string.carlink_diag_location_missing), s(R.string.carlink_diag_location_body))
        CheckId.NEARBY_WIFI_PERMISSION -> if (ok) CheckText(s(R.string.carlink_diag_nearby_ok))
            else CheckText(s(R.string.carlink_diag_nearby_missing), s(R.string.carlink_diag_nearby_body))
        CheckId.MICROPHONE_PERMISSION -> if (ok) CheckText(s(R.string.carlink_diag_mic_ok))
            else CheckText(s(R.string.carlink_diag_mic_missing), s(R.string.carlink_diag_mic_body))
        CheckId.NOTIFICATIONS_PERMISSION -> if (ok) CheckText(s(R.string.carlink_diag_notif_ok))
            else CheckText(s(R.string.carlink_diag_notif_missing), s(R.string.carlink_diag_notif_body))
        CheckId.OVERLAY -> if (ok) CheckText(s(R.string.carlink_diag_overlay_ok))
            else CheckText(s(R.string.carlink_diag_overlay_missing), s(R.string.carlink_diag_overlay_body))
        CheckId.BATTERY_OPTIMIZATION -> if (ok) CheckText(s(R.string.carlink_diag_battery_ok))
            else CheckText(s(R.string.carlink_diag_battery_on), s(R.string.carlink_diag_battery_body))
        CheckId.USB_HOST -> if (ok) CheckText(s(R.string.carlink_diag_usb_ok), s(R.string.carlink_diag_usb_ok_body))
            else CheckText(s(R.string.carlink_diag_usb_missing), s(R.string.carlink_diag_usb_missing_body))
        CheckId.WIFI_DIRECT -> if (ok) CheckText(s(R.string.carlink_diag_p2p_ok))
            else CheckText(s(R.string.carlink_diag_p2p_missing), s(R.string.carlink_diag_p2p_missing_body))
        CheckId.H264_DECODER -> if (ok) CheckText(s(R.string.carlink_diag_avc_ok), decoderDetail(result.decoder))
            else CheckText(s(R.string.carlink_diag_avc_missing), s(R.string.carlink_diag_avc_missing_body))
        CheckId.HEVC_DECODER -> if (ok) CheckText(s(R.string.carlink_diag_hevc_ok), decoderDetail(result.decoder))
            else CheckText(s(R.string.carlink_diag_hevc_missing), s(R.string.carlink_diag_hevc_missing_body))
    }
    return text.copy(fixLabel = fixLabel)
}

@Composable
private fun decoderDetail(decoder: DecoderInfo?): String? {
    decoder ?: return null
    val w = decoder.maxWidth
    val h = decoder.maxHeight
    return if (w != null && h != null) stringResource(R.string.carlink_diag_decoder_detail, ltr(decoder.name), ltr("$w×$h"))
    else ltr(decoder.name)
}
