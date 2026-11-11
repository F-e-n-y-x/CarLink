package com.carlink.core.connection.aa

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.carlink.core.connection.ResumeMusic
import com.carlink.core.settings.AudioLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ":aa" process: makes DiAuto a hosted engine and mirrors it to the main process.
 *
 * - No phantom engine (DA§10.6.1): AapService is not sticky and does not restart itself from
 *   onTaskRemoved, so after a low-memory kill or a task swipe only CarLink starts it again.
 * - No BYD full-window claim unless a session's vehicle profile is BYD (DA§12 #8; set per session
 *   by [AaCommandHandler]).
 * - Every [HostIntegration.EngineEvent] (DA§11.5.2) and every CommManager state goes to the main
 *   process as an explicit package broadcast ([AaProtocol]).
 *
 * Call [install] from `CarLinkApplication.onCreate` in the ":aa" process, after `super.onCreate()`.
 */
object AaEngineHooks {
    private const val TAG = "CarLink-AA2"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var installed = false

    fun install(context: Context) {
        // CarLink switches phones to Android Auto over USB itself (AaCommandHandler.startUsb);
        // the engine's own delayed fallback switch raced it on the S25 (tablet, 2026-10-09).
        HostIntegration.hostOwnsUsbSwitch = true
        if (installed) return
        installed = true
        val app = context.applicationContext
        HostIntegration.stickyService = false
        HostIntegration.restartOnTaskRemoved = false
        HostIntegration.claimHiddenBottomStrip = false
        refreshScreen(app)
        if (HostIntegration.turningScreen) Thread({ highResDecoder(app) }, "carlink-aa-decoder-probe").start()
        val prefs = app.getSharedPreferences(com.andrerinas.openheadunit.utils.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        HostIntegration.automaticDensityDpi = { dpi, w, h ->
            // Asked once per session (service discovery): the density the phone keeps until it reconnects.
            // A turning screen connects at the smallest size and shows the chosen one by its region
            // (refreshScreen), so every later size change is live, smaller ones included.
            val percent = if (HostIntegration.turningScreen) LIVE_BASE_PERCENT
                else prefs.getInt(AaSessionSettings.SIZE_PERCENT_KEY, 100)
            sessionPercent = percent
            AaSessionSettings.automaticDensityDpi(dpi, w, h, HostIntegration.turningScreen, percent).also {
                if (it != dpi) Log.i(TAG, "automatic DPI $dpi -> $it for a ${w}x$h px picture at size $percent% (car-sized layout, 748x450 dp minimum)")
            }
        }
        // The microphone gain chosen in CarLink (AudioLevels.MIC_DB), read at each microphone read.
        HostIntegration.microphonePcm = { data, length ->
            val db = prefs.getInt(AaSessionSettings.MIC_GAIN_KEY, 0)
            if (db != 0) AudioLevels.scalePcm16(data, 0, length, AudioLevels.linear(db))
        }
        val resume = ResumeMusic(app.getSharedPreferences("carlink_resume_music_aa", Context.MODE_PRIVATE), "aa_playing")
        HostIntegration.onPhonePlaying = resume::onPhonePlaying
        HostIntegration.onEngineEvent = { _, kind, detail ->
            if (kind == HostIntegration.EngineEvent.FIRST_VIDEO_FRAME) {
                resume.scheduleAfterFirstPicture(prefs.getBoolean(AaSessionSettings.RESUME_MUSIC_KEY, false), { pressPlay(app) }, TAG)
            }
            AaEngineMonitor.shared.onEngineEvent(kind.name)
            sendEvent(app, kind.name, detail)
            // The first frame also flips the state report's flag; send it so the two agree.
            if (kind == HostIntegration.EngineEvent.FIRST_VIDEO_FRAME) report(app)
        }
        scope.launch {
            App.provide(app).commManager.connectionState.collectLatest {
                if (it is ConnectionState.Disconnected) resume.onSessionEnded()
                report(app, it)
            }
        }
    }

    @Volatile private var sessionPercent = 100

    /** An explicit play key (not play/pause) to the phone, only while Android Auto runs. */
    private fun pressPlay(app: Context): Boolean {
        val comm = App.provide(app).commManager
        if (comm.connectionState.value !is ConnectionState.TransportStarted) return false
        // Straight to the transport: not a wheel key, so no keymap, Bluetooth routing or debounce.
        comm.send(KeyEvent.KEYCODE_MEDIA_PLAY, true)
        comm.send(KeyEvent.KEYCODE_MEDIA_PLAY, false)
        return true
    }

    /** A phone or tablet turns: one landscape stream, and a turn moves Android Auto by margins. Per start (#33). */
    fun refreshScreen(context: Context) {
        val app = context.applicationContext
        val aaPrefs = app.getSharedPreferences(com.andrerinas.openheadunit.utils.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        com.carlink.core.detect.ScreenType.forcedHandheld =
            com.carlink.core.detect.ScreenType.decode(aaPrefs.getString(com.carlink.core.detect.ScreenType.KEY, null))
        val turning = com.carlink.core.detect.HeadUnitDetector.handheld(app)
        HostIntegration.turningScreen = turning
        val chosen = app.getSharedPreferences(com.andrerinas.openheadunit.utils.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(AaSessionSettings.SIZE_PERCENT_KEY, 100)
        HostIntegration.turnSizeFactor = if (turning) maxOf(1f, chosen / LIVE_BASE_PERCENT.toFloat()) else 1f
    }

    /**
     * Per start, off the main thread: a turning screen whose HEVC decoder carries 1440p60 asks for the
     * 1440p stream ([com.carlink.core.connection.PicturePolicy.turningHighRes]). The answer does not
     * depend on the orientation, so the codec list is scanned once per process (warmed by [install]).
     */
    fun refreshStream(context: Context) {
        HostIntegration.turningHighResStream = HostIntegration.turningScreen && highResDecoder(context.applicationContext)
    }

    @Volatile private var highResCapable: Boolean? = null

    private fun highResDecoder(app: Context): Boolean = highResCapable ?: runCatching {
        com.carlink.core.connection.PicturePolicy.turningHighRes(com.carlink.core.connection.DeviceCapabilities.read(app))
    }.getOrDefault(false).also { highResCapable = it }

    /** Small: the size a turning screen connects at, so every interface size is reachable live. */
    private const val LIVE_BASE_PERCENT = 80

    /**
     * Interface size during a session. The phone takes the density only at connect, but on a turning
     * screen a smaller region scaled up draws Android Auto larger, live, like a turn. A size smaller
     * than the session's would need a region larger than the stream: that one reconnects.
     */
    fun setSize(context: Context, percent: Int) {
        val app = context.applicationContext
        app.getSharedPreferences(com.andrerinas.openheadunit.utils.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(AaSessionSettings.SIZE_PERCENT_KEY, percent).commit()
        val relayout = HostIntegration.relayoutTurningScreen
        val live = relayout != null && percent >= sessionPercent &&
            com.andrerinas.openheadunit.utils.HeadUnitScreenConfig.turning() &&
            App.provide(app).commManager.connectionState.value is ConnectionState.TransportStarted
        if (live) {
            HostIntegration.turnSizeFactor = percent / sessionPercent.toFloat()
            relayout?.invoke()
        }
        Log.i(TAG, "size $percent% (session $sessionPercent%): ${if (live) "live" else "needs a reconnect"}")
        sendEvent(app, AaProtocol.KIND_SIZE, "live=$live")
    }

    internal fun sendEvent(context: Context, kind: String, detail: String?) {
        Log.i(TAG, "engine event $kind${detail?.let { " ($it)" } ?: ""}")
        context.sendBroadcast(Intent(AaProtocol.ACTION_EVENT).setPackage(context.packageName)
            .putExtra(AaProtocol.EXTRA_KIND, kind)
            .putExtra(AaProtocol.EXTRA_DETAIL, detail))
    }

    internal fun report(context: Context) {
        report(context.applicationContext, App.provide(context).commManager.connectionState.value)
    }

    private fun report(context: Context, state: ConnectionState) {
        val (name, message) = when (state) {
            is ConnectionState.Disconnected -> AaProtocol.CONNECTION_DISCONNECTED to null
            ConnectionState.Connecting -> AaProtocol.CONNECTION_CONNECTING to null
            ConnectionState.Connected -> AaProtocol.CONNECTION_CONNECTED to null
            ConnectionState.StartingTransport -> AaProtocol.CONNECTION_STARTING_TRANSPORT to null
            ConnectionState.HandshakeComplete -> AaProtocol.CONNECTION_HANDSHAKE_COMPLETE to null
            ConnectionState.TransportStarted -> AaProtocol.CONNECTION_TRANSPORT_STARTED to null
            is ConnectionState.Error -> AaProtocol.CONNECTION_ERROR to state.message
        }
        val projecting = state is ConnectionState.HandshakeComplete || state is ConnectionState.TransportStarted
        val firstFrame = projecting && App.provide(context).videoDecoder.framesRenderedThisSession > 0L
        context.sendBroadcast(Intent(AaProtocol.ACTION_STATE).setPackage(context.packageName)
            .putExtra(AaProtocol.EXTRA_CONNECTION, name)
            .putExtra(AaProtocol.EXTRA_MESSAGE, message)
            .putExtra(AaProtocol.EXTRA_FIRST_FRAME, firstFrame)
            .putExtra(AaProtocol.EXTRA_SERVICE_RUNNING, DiAutoEngineAccess(context).isServiceRunning()))
    }
}
