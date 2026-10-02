package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Android TV / hardware navigation keys -> CarPlay's native knob HID controller.
 *
 * CarPlay's non-touch focus model is rotary-first. A D-pad therefore maps to rotary wheel deltas
 * for normal focus traversal. Select and Back preserve real down/up transitions so long-press
 * semantics remain available to CarPlay. Touchscreen input is completely separate and unchanged.
 */
internal object CarPlayRemoteKeys {
    private const val WHEEL_STEP = 1
    private const val HELD_REPEAT_INTERVAL = 3

    fun dispatch(event: KeyEvent, controller: CarPlayController?): Boolean {
        val wheelDelta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> -WHEEL_STEP

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> WHEEL_STEP

            else -> null
        }

        if (wheelDelta != null) {
            return dispatchWheel(event, controller, wheelDelta)
        }

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_A -> Button.SELECT

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B -> Button.BACK

            else -> return false
        }

        return dispatchButton(event, controller, button)
    }

    private fun dispatchWheel(
        event: KeyEvent,
        controller: CarPlayController?,
        delta: Int,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val shouldSend =
                    event.repeatCount == 0 || event.repeatCount % HELD_REPEAT_INTERVAL == 0

                if (!shouldSend) {
                    true
                } else {
                    controller?.sendKnob(AirPlayKnobState(wheel = delta)) == true
                }
            }

            // Relative wheel movement has no release state.
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    private fun dispatchButton(
        event: KeyEvent,
        controller: CarPlayController?,
        button: Button,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    controller?.sendKnob(button.state(down = true), momentary = false) == true
                } else {
                    true
                }
            }

            KeyEvent.ACTION_UP ->
                controller?.sendKnob(AirPlayKnobState(), momentary = false) == true

            else -> false
        }
    }

    private enum class Button {
        SELECT,
        BACK;

        fun state(down: Boolean): AirPlayKnobState = when (this) {
            SELECT -> AirPlayKnobState(select = down)
            BACK -> AirPlayKnobState(back = down)
        }
    }
}
