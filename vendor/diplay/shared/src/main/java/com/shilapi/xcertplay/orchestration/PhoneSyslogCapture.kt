package com.shilapi.xcertplay.orchestration

/**
 * CarLink: switches DiPlay's temporary wired "phone authentication diagnostic capture" (the
 * iPhone's syslog relayed over USBMUX for two minutes) on or off. Off by default: it competes
 * with CarPlay's own traffic on the same USB link. Turn on only to diagnose authentication.
 */
object PhoneSyslogCapture {
    @Volatile
    @JvmStatic
    var enabled: Boolean = false
}
