package com.carlink.app.connection.aa

/**
 * Broadcasts between [com.carlink.app.connection.AndroidAutoAdapter] (main process) and
 * [AaCommandReceiver] / [AaEngineHooks] (the ":aa" process). Every broadcast is explicit
 * (setClass or setPackage) and every receiver is unexported, so no other app can drive or read the
 * engine.
 *
 * Broadcasts rather than a bound service: a bound connection from the main process would keep
 * ":aa" alive and raise its priority for the app's whole life, and v1's broadcasts are proven on the
 * user's tablet. Delivery order between two broadcasts from one process to one receiver is kept.
 */
internal object AaProtocol {
    const val ENGINE_PROCESS_SUFFIX = ":aa"

    // ---- main → ":aa" (manifest receiver; starts the process) ----
    const val ACTION_COMMAND = "com.carlink.app.action.AA2_COMMAND"
    const val EXTRA_COMMAND = "command"
    /** Correlates a stop with its acknowledgement. */
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_USB_DEVICE = "usb_device"
    /** [com.carlink.app.settings.VehicleProfile] name; gates BYD-only engine behaviour. */
    const val EXTRA_VEHICLE_PROFILE = "vehicle_profile"

    const val COMMAND_START_WIRELESS = "start_wireless"
    const val COMMAND_START_USB = "start_usb"
    const val COMMAND_STOP = "stop"
    const val COMMAND_REPORT = "report"
    /** Wake the chosen phone again on an armed, idle engine (DiAuto's manual poke is a single 20 s hold). */
    const val COMMAND_POKE = "poke"
    /** ":aa" → ":aa": the answer to CarLink's own USB permission request (PendingIntent). */
    const val COMMAND_USB_PERMISSION = "usb_permission"

    // ---- ":aa" → main (runtime receivers in the adapter) ----
    /** CommManager's connection state. */
    const val ACTION_STATE = "com.carlink.app.action.AA2_STATE"
    const val EXTRA_CONNECTION = "connection"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_SERVICE_RUNNING = "service_running"
    /** True once the current session rendered a video frame. */
    const val EXTRA_FIRST_FRAME = "first_frame"

    /** An engine event (HostIntegration.EngineEvent name) or one of the receiver's own kinds below. */
    const val ACTION_EVENT = "com.carlink.app.action.AA2_EVENT"
    const val EXTRA_KIND = "kind"
    const val EXTRA_DETAIL = "detail"

    /** The receiver could not start AapService (background start refused, …). */
    const val KIND_START_REFUSED = "CARLINK_START_REFUSED"
    /** The USB device was not given, or vanished before the switch. */
    const val KIND_USB_DEVICE_MISSING = "CARLINK_USB_DEVICE_MISSING"
    /** The phone did not take the Android Open Accessory switch. */
    const val KIND_AOA_SWITCH_FAILED = "CARLINK_AOA_SWITCH_FAILED"
    /** The phone was already in accessory mode at plug-in but never started Android Auto: it needs a re-plug. */
    const val KIND_AOA_STALE = "CARLINK_AOA_STALE"
    /** What the receiver did with a wireless start ([WirelessStartDecision] name in the detail). */
    const val KIND_START_DECISION = "CARLINK_START_DECISION"

    /** The stop finished: AapService is gone and the Wi-Fi Direct group was handled. */
    const val ACTION_STOPPED = "com.carlink.app.action.AA2_STOPPED"
    const val EXTRA_SERVICE_STOPPED = "service_stopped"
    /** True: no group of CarLink's is up; false: one is still up; absent: could not tell. */
    const val EXTRA_GROUP_RELEASED = "group_released"

    /** CommManager.ConnectionState names, in session order. */
    const val CONNECTION_DISCONNECTED = "Disconnected"
    const val CONNECTION_CONNECTING = "Connecting"
    const val CONNECTION_CONNECTED = "Connected"
    const val CONNECTION_STARTING_TRANSPORT = "StartingTransport"
    const val CONNECTION_HANDSHAKE_COMPLETE = "HandshakeComplete"
    const val CONNECTION_TRANSPORT_STARTED = "TransportStarted"
    const val CONNECTION_ERROR = "Error"

    /** Parses an engine event detail (`key=value;key=value`). */
    fun parseDetail(detail: String?): Map<String, String> =
        detail.orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
}
