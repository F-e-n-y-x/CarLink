package com.carlink.app.connection

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.carlink.app.R
import com.carlink.app.core.Transport
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus

/**
 * The view CarPlay's projection window shows until the first frame, in place of DiPlay's own
 * waiting, preparation and failure panel (`CarPlayHostIntegration.waitingView`, DIPLAY.md F2).
 * DiPlay shows it full-bleed (it handles the window insets itself), hides it while CarPlay is on
 * screen and shows it again when the session drops back to waiting.
 *
 * CarLink's screens supply the real content with [register], typically a ComposeView: the host
 * window is a ComponentActivity, so lifecycle and saved-state owners are present. The content can
 * follow the session through `CarPlayStatusBus`, [HostStates] and CarLink's own session state,
 * and act with `CarPlayHostIntegration.retryNow()`, `stopSession()` and `homeIntent()`.
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

    /** A minimal stand-in until CarLink's own content is registered: the stage in plain words. */
    fun placeholder(context: Context): View {
        val text = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.WHITE)
            text = context.getString(R.string.carlink_carplay_waiting_phone)
        }
        val root = object : FrameLayout(context) {
            private val listener: (CarPlayStatus?) -> Unit = { status -> post { show(status) } }

            private fun show(status: CarPlayStatus?) {
                val transport = if (AirPlayPersistence.loadWirelessEnabled(context)) Transport.WIRELESS else Transport.USB
                val stage = status?.let { CarPlayEventMapping.stageOf(it, transport) } ?: return
                text.setText(when (stage) {
                    ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> R.string.carlink_carplay_waiting_phone
                    ProgressStage.HANDSHAKE -> R.string.carlink_carplay_waiting_handshake
                    ProgressStage.JOINING_WIFI -> R.string.carlink_carplay_waiting_wifi
                    ProgressStage.STARTING_PROJECTION -> R.string.carlink_carplay_waiting_starting
                })
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                CarPlayStatusBus.addListener(listener)
            }

            override fun onDetachedFromWindow() {
                CarPlayStatusBus.removeListener(listener)
                super.onDetachedFromWindow()
            }
        }
        root.setBackgroundColor(0xFF0C1016.toInt())
        root.addView(text, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        return root
    }
}
