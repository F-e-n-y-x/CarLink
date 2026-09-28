package com.andrerinas.openheadunit.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A guided entry point to the existing native wireless transports. */
class ConnectionSetupFragment : Fragment() {
    private val settings get() = Settings(requireContext())
    private var content: LinearLayout? = null
    private var pendingCarHotspotSetup = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingCarHotspotSetup = state?.getBoolean("pending_car_hotspot") ?: false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ScrollView(requireContext()).apply {
            setBackgroundColor(color(R.color.da_background))
            isFillViewport = true
            content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(32), dp(20), dp(32), dp(32))
            }
            addView(content)
        }

    override fun onResume() { super.onResume(); render() }
    override fun onDestroyView() { content = null; super.onDestroyView() }

    private fun render() {
        val root = content ?: return
        val scroll = root.parent as? ScrollView
        val previousScroll = scroll?.scrollY ?: 0
        root.removeAllViews()
        root.addView(MaterialToolbar(requireContext()).apply {
            title = "Connection setup"
            setTitleTextColor(color(R.color.da_text))
            setNavigationIcon(R.drawable.ic_arrow_back_white)
            setNavigationOnClickListener { findNavController().popBackStack() }
        })
        text(root, "Set up once. Ready for the next drive.", 28)
        text(root, "Your details stay saved. Changes apply to the next connection.", 16)
        card(root, "1 · Choose your connection") { box ->
            val options = listOf(
                Triple(1, "Built-in car hotspot", "Use the car’s own hotspot. Select 5 GHz in car settings if available."),
                Triple(0, "Wi-Fi Direct", "Alternative setup · Requires the car’s Wi-Fi switch on. A 2.4 GHz link may stutter.")
            )
            val wide = resources.configuration.screenWidthDp >= 850
            val choices = LinearLayout(requireContext()).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            box.addView(choices)
            for ((index, choice) in options.withIndex()) {
                val (mode, title, hint) = choice
                val option = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(16)
                } else LinearLayout.LayoutParams(-1, -2))
                val selected = if (pendingCarHotspotSetup) mode == 1 else settings.wifiConnectionMode == 3 && settings.nativeApTransport == mode
                button(option, if (selected) "✓  $title" else title, selected) {
                    if (mode == 1) { pendingCarHotspotSetup = true; render() } else selectMode(mode)
                }
                text(option, hint, 16)
            }
        }
        if (pendingCarHotspotSetup || (settings.wifiConnectionMode == 3 && settings.nativeApTransport == 1)) {
            card(root, "2 · Set up the car hotspot") { box ->
                text(box, "Turn on the hotspot in the car settings and select 5 GHz if available. Copy its name and password exactly. Leave the hotspot on when connecting. You do not need to join it manually on your phone; DiAuto sends the details over Bluetooth after you tap Connect phone.", 16)
                text(box, when (com.andrerinas.openheadunit.utils.SoftApStateReader.read(requireContext())) {
                    com.andrerinas.openheadunit.aap.SoftApState.ENABLED -> "Car hotspot is on · Check that 5 GHz is selected in car settings."
                    com.andrerinas.openheadunit.aap.SoftApState.NOT_ENABLED -> "Car hotspot is off · Turn it on before connecting."
                    else -> "Hotspot status unavailable · Check it in car settings."
                }, 16)
                button(box, "Open car hotspot settings") { openSystem(Intent("com.android.settings.WIFI_TETHER_SETTINGS")) }
                button(box, if (pendingCarHotspotSetup) "Save hotspot details and use this mode" else "Edit saved hotspot · ${settings.hotspotSsid.ifEmpty { "Not set" }}") { editHotspot(pendingCarHotspotSetup) }
                if (pendingCarHotspotSetup) text(box, "Finish setup · Save your hotspot details to use this mode.", 16)
                val granted = AppPermissions.isWriteSettingsGranted(requireContext())
                box.addView(androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                    text = "Turn hotspot on automatically"
                    setTextColor(color(R.color.da_text))
                    minHeight = dp(60)
                    isChecked = settings.autoEnableHotspot
                    setOnCheckedChangeListener { _, checked -> settings.autoEnableHotspot = checked; render() }
                })
                text(box, "Optional. Requires Modify system settings access and compatible car firmware. If the hotspot stays off, turn it on in car settings.", 16)
                if (!granted) button(box, "Allow automatic hotspot control") {
                    openSystem(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
                }
                text(box, if (granted) "System settings access allowed" else "Automatic control needs permission", 15)
            }
        } else if (settings.wifiConnectionMode != 3) {
            card(root, "2 · Select a setup above") { box ->
                text(box, "Your existing connection settings are saved. Choose one of the wireless options above to use this guided setup.", 16)
            }
        } else {
            card(root, "2 · Prepare the car") { box ->
                text(box, "Turn the car’s Wi-Fi switch on. Allow Location / Nearby devices when requested and enable Location when Android asks. If playback stutters, try the built-in car hotspot at 5 GHz.", 16)
                button(box, "Open car Wi-Fi settings") { openSystem(Intent(SystemSettings.ACTION_WIFI_SETTINGS)) }
            }
        }
        card(root, "3 · Pair and connect") { box ->
            text(box, "Keep Bluetooth and Wi-Fi on your Android phone. Pair with the car’s Bluetooth. Return to DiAuto, tap Connect phone and select your phone. Accept the Android Auto prompts on the phone. A car internet plan is not required; mobile data depends on your phone’s network settings.", 16)
            button(box, "Open Bluetooth settings") { openSystem(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS)) }
            button(box, "Review app permissions") { findNavController().navigate(R.id.permissionsFragment) }
            button(box, "Done · Return to DiAuto") { requireActivity().finish() }.isEnabled = !pendingCarHotspotSetup
        }
        card(root, "Prefer a cable?") { box ->
            text(box, "Use a USB data cable and the car’s USB data port. Unlock your phone and tap Connect with USB on DiAuto’s home screen. No hotspot setup is needed.", 16)
        }
        scroll?.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun selectMode(mode: Int) {
        pendingCarHotspotSetup = false
        settings.nativeApTransport = mode
        settings.wifiConnectionMode = 3
        render()
    }

    private fun editHotspot(select: Boolean) {
        val fields = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        text(fields, "Copy the name and password from the car’s hotspot settings. Saving here does not change the car’s hotspot.", 16)
        val name = EditText(requireContext()).apply { hint = "Hotspot name"; setText(settings.hotspotSsid); setSingleLine() }
        val secret = EditText(requireContext()).apply {
            hint = "Hotspot password"; setText(settings.hotspotPassword); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        name.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        secret.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = secret.windowToken ?: name.windowToken
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            name.clearFocus(); secret.clearFocus()
        }
        name.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { secret.requestFocus(); true } else false
        }
        secret.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(name); fields.addView(secret)
        fields.addView(CheckBox(requireContext()).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                secret.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                secret.setSelection(secret.text.length)
            }
        })
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle("Car hotspot details")
            .setView(ScrollView(requireContext()).apply { addView(fields) })
            .setPositiveButton("Save details", null).setNegativeButton(R.string.cancel) { _, _ -> hideKeyboard() }
            .setNeutralButton("Hide keyboard", null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString().trim()
                val password = secret.text.toString()
                if (ssid.isEmpty() || '\u0000' in ssid || ssid.toByteArray(Charsets.UTF_8).size > 32) {
                    name.error = "Enter the hotspot name (1–32 bytes)"; return@setOnClickListener
                }
                if (password.length !in 8..63 || password.any { it.code !in 32..126 }) {
                    secret.error = "Enter the hotspot password (8–63 ASCII characters)"; return@setOnClickListener
                }
                settings.hotspotSsid = ssid
                settings.hotspotPassword = password
                hideKeyboard()
                dialog.dismiss()
                if (select) selectMode(1) else render()
            }
        }
        dialog.show()
    }

    private fun openSystem(intent: Intent) {
        if (requireContext().packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(SystemSettings.ACTION_SETTINGS)) }.onFailure {
                Toast.makeText(requireContext(), "Open the car settings from its home screen.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun card(parent: LinearLayout, title: String, body: (LinearLayout) -> Unit) {
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundResource(R.drawable.da_card)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        text(box, title, 22)
        body(box)
    }
    private fun text(parent: LinearLayout, value: String, size: Int) {
        parent.addView(TextView(requireContext()).apply {
            text = value; textSize = size.toFloat()
            setTextColor(color(if (size >= 22) R.color.da_text else R.color.da_muted))
            setPadding(0, dp(8), 0, dp(12))
        })
    }
    private fun button(parent: LinearLayout, title: String, selected: Boolean = false, action: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            text = title; isAllCaps = false; textSize = 18f; minHeight = dp(60); cornerRadius = dp(16)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(if (selected) R.color.da_accent else R.color.da_surface))
            setTextColor(color(if (selected) R.color.da_background else R.color.da_text))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(color(if (selected) R.color.da_accent else R.color.da_muted))
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
}
