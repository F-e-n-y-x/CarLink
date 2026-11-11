package com.shilapi.xcertplay.media

/**
 * CarLink: new file. Audio hooks a host app sets; standalone DiPlay leaves them unset and plays and
 * records exactly as upstream.
 */
object HostAudio {
    /**
     * Scales played PCM in place before it is written to the track. The first argument is the
     * stream's own channel (`AudioChannel` name: MEDIA, PHONE, ASSISTANT, NAVIGATION), also when a
     * call plays as media.
     */
    @Volatile
    @JvmStatic
    var playbackPcm: ((channel: String, data: ByteArray, offset: Int, length: Int) -> Unit)? = null

    /** Scales each microphone frame in place before it is encoded and sent to the iPhone. */
    @Volatile
    @JvmStatic
    var microphonePcm: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null

    /** Plays CarPlay calls as media (the path a BYD with its Bluetooth suspended already uses). */
    @Volatile
    @JvmStatic
    var callsAsMedia: Boolean = false
}
