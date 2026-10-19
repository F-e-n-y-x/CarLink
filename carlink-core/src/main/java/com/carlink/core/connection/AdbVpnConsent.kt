package com.carlink.core.connection

import android.content.Context
import android.net.VpnService
import com.carlink.core.diagnostics.EventLog
import com.shilapi.xcertplay.VpnPreConsent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Wired CarPlay's VPN consent without Android's dialog: on a stereo whose own adbd (127.0.0.1:5555)
 * already trusts this device's key, DiPlay's [VpnPreConsent] allows the ACTIVATE_VPN app op (#604).
 * It never offers the key for approval, so nothing appears on screen. Where there is no adb, the
 * connection is refused at once and the caller falls back to the dialog.
 *
 * Tried once per process and waited for at most [BUDGET_MILLIS]: the session pre-flight calls it
 * on the main thread ([SessionManager] runs pre-flight there).
 */
object AdbVpnConsent {
    private const val BUDGET_MILLIS = 1_000L
    private val tried = AtomicBoolean(false)

    /** True when wired CarPlay's VPN consent is granted, after one silent adb attempt if it was not. */
    fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        return ensure({ runCatching { VpnService.prepare(app) == null }.getOrDefault(false) }, tried, BUDGET_MILLIS) {
            EventLog.add("CarLink", "VPN consent over adb: ${VpnPreConsent.grant(app)}")
        }
    }

    /**
     * Runs [grant] on its own thread the first time ([tried] unset) consent is missing, and waits
     * for it at most [budgetMillis]; a late grant still lands and a later check sees it.
     */
    internal fun ensure(granted: () -> Boolean, tried: AtomicBoolean, budgetMillis: Long, grant: () -> Unit): Boolean {
        if (granted()) return true
        if (!tried.compareAndSet(false, true)) return false
        thread(isDaemon = true, name = "carlink-vpn-adb") { runCatching(grant) }.join(budgetMillis)
        return granted()
    }
}
