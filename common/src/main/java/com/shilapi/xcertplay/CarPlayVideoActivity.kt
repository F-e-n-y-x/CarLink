package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R

/**
 * The car's own player for iOS 27 video in car (see [CarPlayVideo]). Full screen over CarPlay, which
 * stays connected underneath; a tap shows Back to CarPlay and play/pause for a few seconds.
 *
 * Media3 ExoPlayer parses media in the app: the head unit's own MP4 parser aborted on progressive
 * Safari video on a DiLink 5.0 Tang. URLs the car cannot load (an app's own scheme, app-served AES-128
 * keys) go to the iPhone (IphoneResolvingDataSource). The HLS encryption is logged, without URLs, so a
 * protected item that cannot play here is easy to tell apart.
 */
@OptIn(UnstableApi::class)
class CarPlayVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var controls: View
    private lateinit var playPause: TextView
    private var loadedUrl: String? = null
    private var prepared = false
    private var encryptionLogged = false
    private val hideControls = Runnable { controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = IphoneResolvingDataSource.Factory(DefaultDataSource.Factory(this, http))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    Log.i(TAG, "prepared duration=${player.duration} ms size=${player.videoSize.width}x${player.videoSize.height}")
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = logEncryption()

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName}: ${causes(error)}")
                CarPlayVideo.onPlayerFailed(
                    when (error.errorCode) {
                        in 2000..2999 -> VideoInCar.ERROR_NETWORK // IO
                        in 4000..4999 -> VideoInCar.ERROR_DECODER // decoding
                        else -> VideoInCar.ERROR_INCOMPATIBLE_ASSET // parsing, DRM, unsupported
                    },
                )
            }
        })
        val view = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            this.player = this@CarPlayVideoActivity.player
        }
        controls = controlBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
        })
        view.setOnClickListener { showControls() }
        CarPlayVideo.activity = this
        load()
        showControls()
    }

    private fun controlBar(): View {
        fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minHeight = dp(64)
            minWidth = dp(64)
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener { onClick(); showControls() }
        }
        playPause = pill("") { CarPlayVideo.setPlaying(!CarPlayVideo.playing) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("‹  " + getString(R.string.video_back_to_carplay)) { finish() })
            addView(playPause, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        }
    }

    private fun showControls() {
        updatePlayPause()
        controls.visibility = View.VISIBLE
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, CONTROLS_MILLIS)
    }

    private fun updatePlayPause() {
        val playing = CarPlayVideo.playing
        playPause.text = if (playing) "❚❚" else "▶"
        playPause.contentDescription = getString(if (playing) R.string.video_pause else R.string.video_play)
    }

    fun state(): VideoInCar.PlayerState {
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        return VideoInCar.PlayerState(
            prepared = prepared,
            playing = player.isPlaying,
            positionSeconds = player.currentPosition / 1000.0,
            durationSeconds = duration / 1000.0,
            bufferedSeconds = player.bufferedPosition / 1000.0,
        )
    }

    fun load() {
        val url = CarPlayVideo.url ?: return
        if (url == loadedUrl) return
        loadedUrl = url
        prepared = false
        encryptionLogged = false
        val item = MediaItem.Builder().setUri(url)
            .apply { if (CarPlayVideo.streaming) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, (CarPlayVideo.pendingSeekMillis ?: CarPlayVideo.startMillis).toLong())
        CarPlayVideo.pendingSeekMillis = null
        player.prepare()
        applyRate()
    }

    fun applyRate() {
        player.playWhenReady = CarPlayVideo.playing
        if (controls.visibility == View.VISIBLE) updatePlayPause()
    }

    /** Moves the playback position by [deltaMillis], within the video. */
    fun skip(deltaMillis: Int) {
        if (!prepared) return
        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMillis).coerceIn(0, end))
    }

    fun applySeek() {
        val target = CarPlayVideo.pendingSeekMillis ?: return
        player.seekTo(target.toLong()) // ExoPlayer seeks to the exact position
        CarPlayVideo.pendingSeekMillis = null
    }

    // Which encryption the HLS stream uses, without URLs (they carry tokens).
    private fun logEncryption() {
        if (encryptionLogged) return
        val manifest = player.currentManifest as? HlsManifest ?: return
        encryptionLogged = true
        val media = manifest.mediaPlaylist
        val keyScheme = media.segments.firstNotNullOfOrNull { it.fullSegmentEncryptionKeyUri }
            ?.let { android.net.Uri.parse(it).scheme ?: "relative" }
        val drm = media.protectionSchemes?.let { init ->
            (0 until init.schemeDataCount).map { init.get(it).uuid }
        }
        val sessionKeys = manifest.multivariantPlaylist.sessionKeyDrmInitData.map { it.schemeType }
        Log.i(TAG, "HLS encryption aes128KeyScheme=$keyScheme sampleAesSchemeType=${media.protectionSchemes?.schemeType} " +
            "drmUuids=$drm sessionKeys=$sessionKeys segments=${media.segments.size}")
    }

    private fun causes(error: Throwable): String = generateSequence(error) { it.cause }.take(4)
        .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message?.replace(Regex("\\w+://\\S+"), "<url>")})" }

    override fun onDestroy() {
        main.removeCallbacks(hideControls)
        if (CarPlayVideo.activity === this) {
            CarPlayVideo.activity = null
            CarPlayVideo.onPlayerClosed(if (prepared) player.currentPosition.toInt() else null)
        }
        player.release()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "DiPlay-Video"
        const val CONTROLS_MILLIS = 4_000L
    }
}
