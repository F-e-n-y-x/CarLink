package com.carlink.app.engine

/**
 * Package-private broadcasts between CarLink's main process and the Android Auto engine's
 * ":aa" process. Every broadcast is explicit (setPackage/setClass); receivers are not exported.
 */
internal object AndroidAutoProtocol {
    // Main → ":aa" (AndroidAutoCommandReceiver, declared in the manifest so it starts the process).
    const val ACTION_COMMAND = "com.carlink.app.action.ANDROID_AUTO_COMMAND"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_ADDRESS = "address"
    /** The UsbDevice from the attach event (Android phones), for COMMAND_START_USB. */
    const val EXTRA_USB_DEVICE = "usb_device"

    const val COMMAND_START_USB = "start_usb"
    const val COMMAND_START_WIRELESS = "start_wireless"
    const val COMMAND_DISCONNECT = "disconnect"
    const val COMMAND_STOP = "stop"
    const val COMMAND_REPORT = "report"

    // ":aa" → main (registered at runtime by AndroidAutoEngine).
    const val ACTION_STATE = "com.carlink.app.action.ANDROID_AUTO_STATE"
    const val EXTRA_SERVICE_RUNNING = "service_running"
    const val EXTRA_CONNECTION = "connection"
    const val EXTRA_MESSAGE = "message"

    /** CommManager.ConnectionState names, in session order. */
    const val CONNECTION_DISCONNECTED = "Disconnected"
    const val CONNECTION_CONNECTING = "Connecting"
    const val CONNECTION_CONNECTED = "Connected"
    const val CONNECTION_STARTING_TRANSPORT = "StartingTransport"
    const val CONNECTION_HANDSHAKE_COMPLETE = "HandshakeComplete"
    const val CONNECTION_TRANSPORT_STARTED = "TransportStarted"
    const val CONNECTION_ERROR = "Error"

    const val ENGINE_PROCESS_SUFFIX = ":aa"
}
