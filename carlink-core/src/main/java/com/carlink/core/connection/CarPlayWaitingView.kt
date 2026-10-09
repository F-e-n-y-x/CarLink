package com.carlink.app.connection

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.CarPlayHostIntegration

/**
 * The view CarPlay's projection window shows until the first frame, in place of DiPlay's own
 * waiting, preparation and failure panel (`CarPlayHostIntegration.waitingView`, DIPLAY.md F2).
 * DiPlay shows it full-bleed (it handles the window insets itself), hides it while CarPlay is on
 * screen and shows it again when the session drops back to waiting.
 *
 * The host supplies the real content ([CarLinkConfig.waitingView], or [register]), typically a
 * ComposeView: the host window is a ComponentActivity, so lifecycle and saved-state owners are
 * present. The content can follow the session through `CarPlayStatusBus`, [HostStates] and the
 * session state, and act with `CarPlayHostIntegration.retryNow()`, `stopSession()` and
 * `homeIntent()`.
 */
object CarPlayWaitingView {
    @Volatile
    private var factory: ((Context) -> View)? = null

    /** Supplies the waiting view; null restores the placeholder. Applies to the next host window. */
    fun register(factory: ((Context) -> View)?) {
        this.factory = factory
    }

    internal fun install() {
        CarPlayHostIntegration.waitingView = { context -> (factory ?: ::placeholder)(context) }
    }

    /**
     * A neutral stand-in when the host supplies no content: a progress indicator on a dark
     * background. It carries no text, so the backend ships no user-facing copy of its own.
     */
    fun placeholder(context: Context): View {
        val root = FrameLayout(context)
        root.setBackgroundColor(0xFF0C1016.toInt())
        root.addView(ProgressBar(context).apply { isIndeterminate = true },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return root
    }
}
