package com.carlink.app.switcher

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import com.carlink.app.R
import com.carlink.core.connection.Connection
import com.carlink.core.connection.SessionRequest
import com.carlink.core.connection.SessionState
import com.carlink.core.core.Engine
import com.carlink.core.detect.PairedPhone
import com.carlink.core.detect.PhonesRepository
import com.carlink.app.ui.UiPreferences
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.UnifiedSettingsStore
import com.carlink.app.ui.HomeActivity
import com.carlink.app.ui.switcher.SwitchPrompt
import com.carlink.app.ui.switcher.SwitcherActions
import com.carlink.app.ui.switcher.SwitcherBubble
import com.carlink.app.ui.switcher.SwitcherOverlay
import com.carlink.app.ui.switcher.SwitcherPage
import com.carlink.app.ui.switcher.SwitcherUi
import com.carlink.app.ui.switcher.switchCandidates
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.UiProfileDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The floating switcher (main process, main thread): a draggable bubble over the projection and
 * the panel it opens. Both are TYPE_APPLICATION_OVERLAY windows owned by this process.
 *
 * Why the main process owns them, also over Android Auto (whose activity runs in ":aa"): the
 * session ([Connection]), the paired phones ([PhonesRepository]) and CarLink's settings all live
 * here, and an overlay window belongs to the app, not to an activity, so it can float over any
 * process's activity. ":aa" only reports when its projection is resumed ([ProjectionVisibility]).
 *
 * Why the panel is an overlay window too, not a transparent activity: starting an activity would
 * pause the projection activity underneath (CarPlay's host and DiAuto react to pause), take the
 * driver out of the projection's task, and need its own task/affinity handling. The panel has no
 * text field, so it needs no keyboard; a focusable overlay receives Back, and its scrim catches
 * taps outside the card.
 */
object SwitcherController {
    private const val TAG = "CarLink-Switcher"
    private const val PREFS = "carlink_switcher"
    private const val IDLE_FADE_MS = 4_000L
    private const val IDLE_ALPHA = 0.4f
    /** How long "Switching…" stays up after the new session was requested. */
    private const val SWITCH_LINGER_MS = 1_500L

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var installed = false
    private var observingSession: Job? = null

    private var session: SessionState = SessionState.Idle
    private var front = FrontState()
    private var connectedRequest: Any? = null
    private var connectedSince: Long? = null

    private var bubble: BubbleWindow? = null
    private var panel: PanelWindow? = null
    private var phones: PhonesRepository? = null
    private var phonesJob: Job? = null
    private var allPhones: List<PairedPhone> = emptyList()
    private var panelUi by mutableStateOf<SwitcherUi?>(null)
    private var safeInsets by mutableStateOf(SafeInsets())
    private var switching = false

    /** The session's "Switch to …?" offer (a second phone plugged in), and its prompt window. */
    private var pending: SessionRequest? = null
    private var prompt: PanelWindow? = null
    private var promptRequest by mutableStateOf<SessionRequest?>(null)
    private val autoDecline = Runnable {
        Log.i(TAG, "switch prompt unanswered; declining")
        Connection.get(app).declineSwitch()
    }

    private val windowContext: Context by lazy { createWindowContext() }
    private val windowManager: WindowManager by lazy { windowContext.getSystemService(WindowManager::class.java) }
    private val preferences by lazy { UiPreferences(app) }

    private val fade = Runnable { bubble?.animate()?.alpha(IDLE_ALPHA)?.setDuration(350)?.start() }

    /** Main process, from Application.onCreate. */
    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        ProjectionVisibility.installInMainProcess(application)
        scope.launch {
            ProjectionVisibility.front.collect { state ->
                front = state
                // Any CarLink or projection activity means a session can exist; only then is the
                // session layer touched (creating it early would change its start-up behaviour).
                if (state.projections.isNotEmpty() || state.carLinkScreenInFront) observeSession()
                evaluate()
            }
        }
        application.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                // Window metrics follow the rotation a moment later than the callback.
                main.post { relayout() }
            }
            @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
        })
    }

    /** The driver changed the setting (Settings › General). */
    fun onSettingChanged() {
        if (installed) evaluate()
    }

    private fun observeSession() {
        if (observingSession != null) return
        val connection = Connection.get(app)
        scope.launch {
            connection.pendingSwitch.collect { request ->
                pending = request
                evaluate()
            }
        }
        observingSession = scope.launch {
            connection.state.collect { state ->
                session = state
                trackConnectedTime(state)
                ProjectionVisibility.forgetDeadEngineProcess(app)
                evaluate()
            }
        }
    }

    private fun trackConnectedTime(state: SessionState) {
        val active = state as? SessionState.Active
        when {
            active == null -> { connectedRequest = null; connectedSince = null }
            active.phase == SessionState.Phase.Connected && connectedRequest != active.request -> {
                connectedRequest = active.request
                connectedSince = SystemClock.elapsedRealtime()
            }
            active.request != connectedRequest -> connectedSince = null
        }
    }

    /** The last show decision and its inputs, logged on change so a hidden bubble can be explained. */
    private var lastDecision: String? = null

    private fun canDrawOverlays() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)

    private fun evaluate() {
        val request = pending
        if (request != null && SwitcherRules.shouldShowSwitchPrompt(request, canDrawOverlays())) showPrompt(request)
        else removePrompt()
        val show = SwitcherRules.shouldShow(session, front.projections, front.carLinkScreenInFront,
            canDrawOverlays(), preferences.floatingSwitcher, promptShowing = prompt != null)
        val inputs = "show=$show phase=${(session as? com.carlink.core.connection.SessionState.Active)?.phase?.javaClass?.simpleName} " +
            "projections=${front.projections} carLinkInFront=${front.carLinkScreenInFront} overlay=${canDrawOverlays()} " +
            "enabled=${preferences.floatingSwitcher} prompt=${prompt != null}"
        if (inputs != lastDecision) {
            lastDecision = inputs
            Log.i(TAG, inputs)
            com.carlink.core.diagnostics.EventLog.add("Switcher", inputs)
        }
        if (switching) {
            // "Switching…" stays until its linger ends, unless CarLink's own screen took over.
            if (front.carLinkScreenInFront || !canDrawOverlays()) finishSwitch()
            return
        }
        if (!show) {
            closePanel(reevaluate = false)
            removeBubble()
            return
        }
        if (panel != null) refreshPanel() else showBubble()
    }

    // ---- Bubble -------------------------------------------------------------------------------

    private fun showBubble() {
        if (bubble != null) return
        val geometry = geometry()
        val placement = restore(geometry)
        val view = BubbleWindow(themedContext(), windowManager, geometry.bubble, placement, bubbleCallbacks) {
            Themed { SwitcherBubble() }
        }
        view.contentDescription = app.getString(R.string.carlink_switcher_bubble)
        runCatching { view.show() }.onFailure {
            Log.w(TAG, "cannot add the bubble", it)
            return
        }
        bubble = view
        wake()
    }

    private fun removeBubble() {
        main.removeCallbacks(fade)
        bubble?.remove()
        bubble = null
    }

    private val bubbleCallbacks = object : BubbleWindow.Callbacks {
        override fun onTap() = openPanel()
        override fun onTouchStart() = wake()
        override fun onDrag(x: Int, y: Int): Pair<Int, Int> = geometry().clampDrag(x, y)
        override fun onRelease(x: Int, y: Int) {
            val geometry = geometry()
            val placement = geometry.snap(x, y)
            bubble?.moveTo(placement.x, placement.y)
            store(geometry, placement)
            wake()
        }
    }

    /** Full opacity now, fade to [IDLE_ALPHA] after [IDLE_FADE_MS] without a touch. */
    private fun wake() {
        val view = bubble ?: return
        view.animate().cancel()
        view.alpha = 1f
        main.removeCallbacks(fade)
        main.postDelayed(fade, IDLE_FADE_MS)
    }

    private fun relayout() {
        val view = bubble ?: return
        val geometry = geometry()
        val placement = restore(geometry)
        view.moveTo(placement.x, placement.y)
        if (panel != null) safeInsets = geometry.insets
    }

    // ---- Panel --------------------------------------------------------------------------------

    private fun openPanel() {
        if (panel != null) return
        val active = session as? SessionState.Active ?: return
        val geometry = geometry()
        safeInsets = geometry.insets
        val repository = PhonesRepository(app).also { it.start() }
        phones = repository
        allPhones = repository.phones.value
        panelUi = SwitcherUi(
            phoneName = active.request.phoneName, engine = active.request.engine, transport = active.request.transport,
            path = active.path, connectedSince = connectedSince,
            otherPhones = candidates(active),
            usbKinds = com.carlink.app.ui.premium.usbPhoneKinds(app),
            method = UnifiedSettingsStore(app).load().connectionMethod,
            edge = bubble?.let { if (it.params.x + geometry.bubble / 2 < geometry.screenWidth / 2) Edge.LEFT else Edge.RIGHT }
                ?: Edge.RIGHT,
        )
        val view = PanelWindow(themedContext(), windowManager, onBack = ::onBack) {
            Themed {
                val ui = panelUi ?: return@Themed
                val density = LocalDensity.current
                val insets = safeInsets
                val padding = with(density) {
                    PaddingValues(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())
                }
                SwitcherOverlay(ui.copy(safePadding = padding), panelActions)
            }
        }
        runCatching { view.show() }.onFailure {
            Log.w(TAG, "cannot add the panel", it)
            repository.stop()
            phones = null
            return
        }
        panel = view
        removeBubble()
        phonesJob = scope.launch {
            repository.phones.collect { list ->
                allPhones = list
                refreshPanel()
            }
        }
    }

    private fun candidates(active: SessionState.Active) =
        switchCandidates(allPhones, active.request.phoneAddress, active.request.phoneName)

    private fun refreshPanel() {
        val ui = panelUi ?: return
        val active = session as? SessionState.Active ?: return
        if (switching) return
        panelUi = ui.copy(phoneName = active.request.phoneName, engine = active.request.engine,
            transport = active.request.transport, path = active.path, connectedSince = connectedSince,
            otherPhones = candidates(active), usbKinds = com.carlink.app.ui.premium.usbPhoneKinds(app))
    }

    private fun onBack() {
        val ui = panelUi
        if (ui != null && ui.page != SwitcherPage.MAIN && !switching) panelUi = ui.copy(page = SwitcherPage.MAIN)
        else closePanel()
    }

    private fun closePanel(reevaluate: Boolean = true) {
        val view = panel ?: return
        panel = null
        phonesJob?.cancel()
        phonesJob = null
        phones?.stop()
        phones = null
        view.remove()
        panelUi = null
        // Back to the bubble when the projection is still there.
        if (reevaluate) evaluate()
    }

    private fun finishSwitch() {
        if (!switching) return
        switching = false
        main.removeCallbacks(endSwitch)
        closePanel()
    }

    private val endSwitch = Runnable { finishSwitch() }

    private val panelActions = object : SwitcherActions {
        override fun switchTo(phone: PairedPhone) {
            if (switching) return
            switching = true
            panelUi = panelUi?.copy(switchingTo = phone.address)
            // One frame for "Switching…" to draw, then replace the session.
            main.postDelayed({
                Connection.get(app).connect(phone.address, phone.kind, phone.name)
                main.postDelayed(endSwitch, SWITCH_LINGER_MS)
            }, 50)
        }

        override fun openHome() {
            closePanel()
            runCatching {
                app.startActivity(Intent(app, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.onFailure { Log.w(TAG, "cannot open Home", it) }
        }

        override fun showPage(page: SwitcherPage) {
            panelUi = panelUi?.copy(page = page)
        }

        override fun setConnectionMethod(method: ConnectionMethod) {
            // Read just before writing: Home or Settings may have changed other values.
            val store = UnifiedSettingsStore(app)
            store.save(store.load().copy(connectionMethod = method))
            panelUi = panelUi?.copy(method = method, page = SwitcherPage.MAIN)
        }

        override fun disconnect() {
            closePanel()
            Connection.get(app).disconnect()
        }

        override fun close() = closePanel()
    }

    // ---- Switch prompt ------------------------------------------------------------------------

    private fun showPrompt(request: SessionRequest) {
        if (prompt != null) {
            if (promptRequest != request) {
                promptRequest = request
                restartAutoDecline()
            }
            return
        }
        // Modal: the panel and the bubble step aside while the driver answers.
        if (switching) {
            switching = false
            main.removeCallbacks(endSwitch)
        }
        closePanel(reevaluate = false)
        removeBubble()
        promptRequest = request
        safeInsets = geometry().insets
        val view = PanelWindow(themedContext(), windowManager, onBack = ::declineSwitch) {
            Themed {
                val asked = promptRequest ?: return@Themed
                val insets = safeInsets
                val padding = with(LocalDensity.current) {
                    PaddingValues(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())
                }
                val current = (session as? SessionState.Active)?.request?.engine
                    ?: if (asked.engine == Engine.CARPLAY) Engine.ANDROID_AUTO else Engine.CARPLAY
                SwitchPrompt(
                    phoneName = asked.phoneName ?: app.getString(R.string.carlink_switcher_phone_fallback),
                    newEngine = asked.engine, currentEngine = current, newTransport = asked.transport,
                    onSwitch = ::acceptSwitch, onDecline = ::declineSwitch, safePadding = padding,
                )
            }
        }
        runCatching { view.show() }.onFailure {
            Log.w(TAG, "cannot add the switch prompt", it)
            promptRequest = null
            return
        }
        prompt = view
        restartAutoDecline()
    }

    private fun restartAutoDecline() {
        main.removeCallbacks(autoDecline)
        main.postDelayed(autoDecline, SwitcherRules.SWITCH_PROMPT_TIMEOUT_MS)
    }

    private fun removePrompt() {
        main.removeCallbacks(autoDecline)
        val view = prompt ?: return
        prompt = null
        promptRequest = null
        view.remove()
    }

    private fun acceptSwitch() {
        main.removeCallbacks(autoDecline)
        Connection.get(app).acceptSwitch()
    }

    private fun declineSwitch() {
        main.removeCallbacks(autoDecline)
        Connection.get(app).declineSwitch()
    }

    // ---- Geometry and theme -------------------------------------------------------------------

    private fun profile(): UiProfile = UiProfileDetector.detect(app, preferences.uiProfileMode)

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        CarLinkTheme(profile(), preferences.themeMode, preferences.interfaceScale,
            com.carlink.app.ui.theme.RenderingPolicy.lite(app, preferences.renderingMode), content)
    }

    private fun themedContext(): Context = ContextThemeWrapper(windowContext, R.style.Theme_CarLink)

    /** The screen, its system bars and the bubble's size, in pixels. */
    private fun geometry(): BubbleGeometry {
        val (width, height, insets) = screen()
        val unit = canvasDensity(min(width, height))
        val car = profile() == UiProfile.CAR
        return BubbleGeometry(width, height, bubble = ((if (car) 64 else 56) * unit).toInt(),
            margin = ((if (car) 16 else 12) * unit).toInt(), insets = insets)
    }

    /** Pixels per CarLink dp, the same scale CarLinkTheme uses (car canvas: 810dp short side). */
    private fun canvasDensity(shortSidePx: Int): Float {
        val scale = preferences.interfaceScale.coerceIn(0.85f, 1.35f)
        return if (profile() == UiProfile.CAR) shortSidePx / 810f * scale else app.resources.displayMetrics.density * scale
    }

    @Suppress("DEPRECATION")
    private fun screen(): Triple<Int, Int, SafeInsets> {
        if (Build.VERSION.SDK_INT >= 30) {
            // A window context's current metrics are the area its overlay windows get: the display.
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Triple(metrics.bounds.width(), metrics.bounds.height(),
                SafeInsets(bars.left, bars.top, bars.right, bars.bottom))
        }
        val real = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        fun dimen(name: String) = app.resources.getIdentifier(name, "dimen", "android")
            .takeIf { it != 0 }?.let { app.resources.getDimensionPixelSize(it) } ?: 0
        return Triple(real.widthPixels, real.heightPixels,
            SafeInsets(top = dimen("status_bar_height"), bottom = dimen("navigation_bar_height")))
    }

    private fun orientationKey(geometry: BubbleGeometry) = if (geometry.screenWidth >= geometry.screenHeight) "land" else "port"

    private fun restore(geometry: BubbleGeometry): BubblePlacement {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = orientationKey(geometry)
        val edge = prefs.getString("edge_$key", null)?.let { runCatching { Edge.valueOf(it) }.getOrNull() }
            ?: return geometry.default()
        return geometry.fromFraction(edge, prefs.getFloat("y_$key", BubbleGeometry.DEFAULT_FRACTION))
    }

    private fun store(geometry: BubbleGeometry, placement: BubblePlacement) {
        val key = orientationKey(geometry)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("edge_$key", placement.edge.name)
            .putFloat("y_$key", geometry.fractionOf(placement))
            .apply()
    }

    /** A window context for overlays (API 30+), so the windows follow the display's configuration. */
    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT < 30) return app
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return app
        return app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
}
