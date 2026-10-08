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
            title = getString(R.string.da_connection_setup)
            setTitleTextColor(color(R.color.da_text))
            setNavigationIcon(R.drawable.ic_arrow_back_white)
            setNavigationOnClickListener { findNavController().popBackStack() }
        })
        text(root, getString(R.string.setup_intro), 28)
        text(root, getString(R.string.setup_saved_details_hint), 16)
        card(root, getString(R.string.setup_choose_connection)) { box ->
            val options = listOf(
                Triple(1, getString(R.string.setup_built_in_car_hotspot), getString(R.string.setup_car_hotspot_hint)),
                Triple(0, getString(R.string.setup_wi_fi_direct), getString(R.string.setup_wifi_direct_hint))
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
            card(root, getString(R.string.setup_configure_hotspot)) { box ->
                text(box, getString(R.string.setup_hotspot_instructions), 16)
                text(box, when (com.andrerinas.openheadunit.utils.SoftApStateReader.read(requireContext())) {
                    com.andrerinas.openheadunit.aap.SoftApState.ENABLED -> getString(R.string.setup_hotspot_on)
                    com.andrerinas.openheadunit.aap.SoftApState.NOT_ENABLED -> getString(R.string.setup_hotspot_off)
                    else -> getString(R.string.setup_hotspot_unknown)
                }, 16)
                button(box, getString(R.string.setup_open_car_hotspot_settings)) { openSystem(Intent("com.android.settings.WIFI_TETHER_SETTINGS")) }
                button(box, if (pendingCarHotspotSetup) getString(R.string.setup_save_and_use_hotspot) else getString(R.string.setup_edit_hotspot, settings.hotspotSsid.ifEmpty { getString(R.string.setup_not_set) })) { editHotspot(pendingCarHotspotSetup) }
                if (pendingCarHotspotSetup) text(box, getString(R.string.setup_hotspot_pending), 16)
                val granted = AppPermissions.isWriteSettingsGranted(requireContext())
                box.addView(androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                    text = getString(R.string.setup_turn_hotspot_on_automatically)
                    setTextColor(color(R.color.da_text))
                    minHeight = dp(60)
                    isChecked = settings.autoEnableHotspot
                    setOnCheckedChangeListener { _, checked -> settings.autoEnableHotspot = checked; render() }
                })
                text(box, getString(R.string.setup_auto_hotspot_hint), 16)
                if (!granted) button(box, getString(R.string.setup_allow_automatic_hotspot_control)) {
                    openSystem(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
                }
                text(box, if (granted) getString(R.string.setup_system_settings_access_allowed) else getString(R.string.setup_automatic_control_needs_permission), 15)
            }
        } else if (settings.wifiConnectionMode != 3) {
            card(root, getString(R.string.setup_select_mode)) { box ->
                text(box, getString(R.string.setup_existing_connection_hint), 16)
            }
        } else {
            card(root, getString(R.string.setup_prepare_car)) { box ->
                text(box, getString(R.string.setup_wifi_direct_instructions), 16)
                button(box, getString(R.string.native_local_hotspot_wifi_settings)) { openSystem(Intent(SystemSettings.ACTION_WIFI_SETTINGS)) }
            }
        }
        card(root, getString(R.string.setup_pair_and_connect)) { box ->
            text(box, getString(R.string.setup_pair_instructions), 16)
            button(box, getString(R.string.setup_open_bluetooth_settings)) { openSystem(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS)) }
            button(box, getString(R.string.setup_review_app_permissions)) { findNavController().navigate(R.id.permissionsFragment) }
            button(box, getString(R.string.setup_done_return_to_diauto)) { requireActivity().finish() }.isEnabled = !pendingCarHotspotSetup
        }
        card(root, getString(R.string.setup_prefer_a_cable)) { box ->
            text(box, getString(R.string.setup_usb_instructions), 16)
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
        text(fields, getString(R.string.setup_hotspot_copy_hint), 16)
        val name = EditText(requireContext()).apply { hint = getString(R.string.setup_hotspot_name); setText(settings.hotspotSsid); setSingleLine() }
        val secret = EditText(requireContext()).apply {
            hint = getString(R.string.setup_hotspot_password); setText(settings.hotspotPassword); setSingleLine()
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
            text = getString(R.string.setup_show_password)
            setOnCheckedChangeListener { _, checked ->
                secret.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                secret.setSelection(secret.text.length)
            }
        })
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.setup_car_hotspot_details))
            .setView(ScrollView(requireContext()).apply { addView(fields) })
            .setPositiveButton(getString(R.string.setup_save_details), null).setNegativeButton(R.string.cancel) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.setup_hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString().trim()
                val password = secret.text.toString()
                if (ssid.isEmpty() || '\u0000' in ssid || ssid.toByteArray(Charsets.UTF_8).size > 32) {
                    name.error = getString(R.string.setup_invalid_hotspot_name); return@setOnClickListener
                }
                if (password.length !in 8..63 || password.any { it.code !in 32..126 }) {
                    secret.error = getString(R.string.setup_invalid_hotspot_password); return@setOnClickListener
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
                Toast.makeText(requireContext(), getString(R.string.setup_open_settings_manually), Toast.LENGTH_LONG).show()
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
