package com.shilapi.xcertplay

/** What the Settings overview tells the driver about the next connection, most urgent first. */
internal enum class SettingsReadiness {
    SETUP_ERROR, CONNECTED, CONNECTING, CHOOSE_IPHONE, READY_WIRELESS, READY_USB;

    val needsAction: Boolean get() = this == SETUP_ERROR || this == CHOOSE_IPHONE

    companion object {
        fun of(setupError: Boolean, active: Boolean, running: Boolean, wireless: Boolean, phoneChosen: Boolean) = when {
            setupError -> SETUP_ERROR
            active -> CONNECTED
            running -> CONNECTING
            wireless && !phoneChosen -> CHOOSE_IPHONE
            wireless -> READY_WIRELESS
            else -> READY_USB
        }
    }
}
