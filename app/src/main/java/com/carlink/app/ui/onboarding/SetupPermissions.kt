package com.carlink.app.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.carlink.app.R
import com.carlink.app.ui.components.PermissionRowStatus

/** How much CarLink depends on a permission; decides its label and whether it gates Continue. */
internal enum class PermissionNeed(@param:StringRes val label: Int, val required: Boolean) {
    REQUIRED(R.string.carlink_onboarding_need_required, true),
    WIRELESS(R.string.carlink_onboarding_need_wireless, true),
    RECOMMENDED(R.string.carlink_onboarding_need_recommended, false),
}

/** The permission checklist of DESIGN_SPEC §6.4, required items first. */
internal enum class SetupPermission(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    val need: PermissionNeed,
    /** The first API level where this needs the user's consent at run time. */
    val minSdk: Int,
) {
    NEARBY_DEVICES(R.drawable.carlink_ic_bluetooth_searching, R.string.carlink_onboarding_perm_nearby_title,
        R.string.carlink_onboarding_perm_nearby_why, PermissionNeed.REQUIRED, 31),
    LOCATION(R.drawable.carlink_ic_location_on, R.string.carlink_onboarding_perm_location_title,
        R.string.carlink_onboarding_perm_location_why, PermissionNeed.WIRELESS, 23),
    NEARBY_WIFI(R.drawable.carlink_ic_wifi_find, R.string.carlink_onboarding_perm_wifi_title,
        R.string.carlink_onboarding_perm_wifi_why, PermissionNeed.WIRELESS, 33),
    MICROPHONE(R.drawable.carlink_ic_mic, R.string.carlink_onboarding_perm_mic_title,
        R.string.carlink_onboarding_perm_mic_why, PermissionNeed.REQUIRED, 23),
    NOTIFICATIONS(R.drawable.carlink_ic_notifications, R.string.carlink_onboarding_perm_notifications_title,
        R.string.carlink_onboarding_perm_notifications_why, PermissionNeed.RECOMMENDED, 33),
    OVERLAY(R.drawable.carlink_ic_layers, R.string.carlink_onboarding_perm_overlay_title,
        R.string.carlink_onboarding_perm_overlay_why, PermissionNeed.RECOMMENDED, 23),
    BATTERY(R.drawable.carlink_ic_battery_charging_full, R.string.carlink_onboarding_perm_battery_title,
        R.string.carlink_onboarding_perm_battery_why, PermissionNeed.RECOMMENDED, 23),
    ;

    /** Runtime permissions the row asks for in one system dialog; empty for special access. */
    val requested: List<String>
        get() = when (this) {
            NEARBY_DEVICES -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            // Android 12+ ignores a precise-location request that does not include approximate.
            LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            NEARBY_WIFI -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
            NOTIFICATIONS -> listOf(Manifest.permission.POST_NOTIFICATIONS)
            OVERLAY, BATTERY -> emptyList()
        }

    /** The grants that count: precise location, not just approximate. */
    val essential: List<String>
        get() = if (this == LOCATION) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else requested

    val isSpecialAccess: Boolean get() = requested.isEmpty()
}

/** One checklist row as the UI renders it. */
internal data class PermissionItem(val permission: SetupPermission, val status: PermissionRowStatus)

/**
 * Grant state of the checklist. Runtime permissions use the system dialog; "Display over other
 * apps" and the battery exemption open their system screens. Android stops showing a runtime
 * dialog after the user denies it twice, so a denied permission whose rationale flag is off
 * after CarLink has asked offers "Open settings" instead.
 */
@Stable
internal class SetupPermissionsState(private val context: Context) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private var pending: SetupPermission? = null
    internal var launchRuntime: (Array<String>) -> Unit = {}
    internal var launchScreen: (Intent) -> Unit = {}

    /** The rows that apply to this Android version. */
    val permissions: List<SetupPermission> = SetupPermission.entries.filter { Build.VERSION.SDK_INT >= it.minSdk }

    var items: List<PermissionItem> by mutableStateOf(read())
        private set

    val requiredGranted: Boolean
        get() = items.all { !it.permission.need.required || it.status == PermissionRowStatus.GRANTED }

    val allGranted: Boolean get() = items.all { it.status == PermissionRowStatus.GRANTED }

    fun refresh() {
        items = read()
    }

    /** [Allow]: the system dialog, or the special-access screen with fallbacks for head units. */
    fun request(permission: SetupPermission) {
        if (!permission.isSpecialAccess) {
            pending = permission
            launchRuntime(permission.requested.toTypedArray())
            return
        }
        val pkg = Uri.fromParts("package", context.packageName, null)
        val candidates = when (permission) {
            SetupPermission.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            )
            else -> listOf(batteryRequestIntent(pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        // Trimmed head-unit firmware often lacks these screens; fall through to the next one.
        for (intent in candidates) {
            try {
                launchScreen(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    internal fun onRuntimeResult() {
        pending?.let { permission ->
            store.edit { putStringSet(KEY_ASKED, asked() + permission.name) }
        }
        pending = null
        refresh()
    }

    // Keeping CarLink ready when the car wakes is its core job; the system asks the user.
    @SuppressLint("BatteryLife")
    private fun batteryRequestIntent(pkg: Uri) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)

    private fun asked(): Set<String> = store.getStringSet(KEY_ASKED, emptySet()).orEmpty()

    private fun read(): List<PermissionItem> {
        val asked = asked()
        val activity = context.findActivity()
        return permissions.map { permission ->
            val status = when {
                isGranted(permission) -> PermissionRowStatus.GRANTED
                permission.isSpecialAccess || permission.name !in asked || activity == null -> PermissionRowStatus.ASK
                permission.essential.filterNot(::granted)
                    .any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> PermissionRowStatus.ASK
                else -> PermissionRowStatus.OPEN_SETTINGS
            }
            PermissionItem(permission, status)
        }
    }

    private fun isGranted(permission: SetupPermission): Boolean = when (permission) {
        SetupPermission.OVERLAY -> runCatching { Settings.canDrawOverlays(context) }.getOrDefault(true)
        SetupPermission.BATTERY -> runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }.getOrDefault(true)
        else -> permission.essential.all(::granted)
    }

    private fun granted(name: String) =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STORE = "carlink_onboarding"
        const val KEY_ASKED = "asked_permissions"
    }
}

/** The checklist's state, re-read whenever CarLink resumes (users come back from system screens). */
@Composable
internal fun rememberSetupPermissions(): SetupPermissionsState {
    val context = LocalContext.current
    val state = remember(context) { SetupPermissionsState(context) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.onRuntimeResult()
    }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.refresh()
    }
    SideEffect {
        state.launchRuntime = { runtime.launch(it) }
        state.launchScreen = { screen.launch(it) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
