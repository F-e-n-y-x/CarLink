package com.carlink.app.engine

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.andrerinas.openheadunit.HostIntegration
import com.carlink.app.R
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayHostIntegration.MenuItem
import com.shilapi.xcertplay.DiPlayPalette
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.andrerinas.openheadunit.R as AaR
import com.shilapi.xcertplay.host.R as CpR

/**
 * The only engine UI the driver sees is drawn around and over the projected picture: CarPlay's
 * waiting screen, in-session menu, side panel, picture and safe-area panels, and Android Auto's
 * loading screen, renderer banner and exit menu. This dresses them in Graphite and Ion
 * (design/RESEARCH_PREMIUM_UX.md §6): graphite canvas with one soft light, Inter, glass surfaces
 * with a top-lit edge, pill buttons, Ion only on what can be tapped, and CarLink's mark.
 *
 * The engines build these screens from plain Views, so this works on the built View tree through
 * the engines' host hooks; it never changes what a control does.
 */
object EngineChrome {

    /** Graphite and Ion for DiPlay's chrome; stable instances, DiPlay compares them by identity. */
    private val carPlayDark = DiPlayPalette(
        background = 0xFF0C1016.toInt(), surface = 0xFF1B1E24.toInt(), button = 0xFF24282D.toInt(),
        outline = 0xFF2A2F37.toInt(), railSelected = 0xFF12343E.toInt(), railSelectedOutline = 0xFF5BD6F0.toInt(),
        primaryText = 0xFFEEF2F6.toInt(), secondaryText = 0xFFA9B3C0.toInt(),
        accent = 0xFF5BD6F0.toInt(), onAccent = 0xFF03222B.toInt(), accentTrack = 0xFF2F6978.toInt(),
        trackOff = 0xFF2C3036.toInt(), warning = 0xFFFFB547.toInt(), success = 0xFF7EE2A8.toInt(),
        danger = 0xFFFF7A70.toInt(), ripple = 0x335BD6F0, focusRing = 0xFF5BD6F0.toInt(),
        systemBar = 0xFF0C1016.toInt(), systemBarIconsAreDark = false,
        overlayBackground = 0xFF0C1016.toInt(), overlayPrimaryText = 0xFFEEF2F6.toInt(),
        overlaySecondaryText = 0xFFA9B3C0.toInt(), overlayAccent = 0xFF5BD6F0.toInt(),
        overlayOnAccent = 0xFF03222B.toInt(), overlayAccentTrack = 0xFF2F6978.toInt(),
        overlayTrackOff = 0xFF2C3036.toInt(), overlayDanger = 0xFFC2352B.toInt(),
    )
    private val carPlayLight = DiPlayPalette(
        background = 0xFFE8EBEF.toInt(), surface = 0xFFF7F8FA.toInt(), button = 0xFFDDE2E8.toInt(),
        outline = 0xFFC9CFD6.toInt(), railSelected = 0xFFCBEFF6.toInt(), railSelectedOutline = 0xFF006A7E.toInt(),
        primaryText = 0xFF0D1117.toInt(), secondaryText = 0xFF46505C.toInt(),
        accent = 0xFF006A7E.toInt(), onAccent = Color.WHITE, accentTrack = 0xFF006A7E.toInt(),
        trackOff = 0xFFC9CFD6.toInt(), warning = 0xFF965A00.toInt(), success = 0xFF18703F.toInt(),
        danger = 0xFFC2352B.toInt(), ripple = 0x33006A7E, focusRing = 0xFF006A7E.toInt(),
        systemBar = 0xFFE8EBEF.toInt(), systemBarIconsAreDark = true,
        overlayBackground = 0xFFE8EBEF.toInt(), overlayPrimaryText = 0xFF0D1117.toInt(),
        overlaySecondaryText = 0xFF46505C.toInt(), overlayAccent = 0xFF006A7E.toInt(),
        overlayOnAccent = Color.WHITE, overlayAccentTrack = 0xFF006A7E.toInt(),
        overlayTrackOff = 0xFFC9CFD6.toInt(), overlayDanger = 0xFFC2352B.toInt(),
    )

    /**
     * The CarPlay menu keeps only what belongs to the running session or has no CarLink setting.
     * Hidden, and why:
     * - WIRELESS, WIFI_METHOD, LOCATION, DRIVING_SIDE, RESOLUTION, FRAME_RATE, PHYSICAL_LENGTH:
     *   CarLink writes these into DiPlay before every session (CarPlaySettingsPolicy in
     *   connection/CarPlaySettings.kt, applied from Connection), so an edit here would silently come undone; they live in CarLink Settings.
     * - DOCK, HEVC, SYSTEM_BARS, DEBUG_LOGS, GESTURE_FINGERS: CarLink Settings › Advanced has them.
     * - AUTO_START: DiPlay's boot receiver is not used; CarLink's own auto-start applies.
     * - LANGUAGE: CarLink ships in English only.
     * - EXIT_APPLICATION: kills the whole CarLink process (Home and the SessionManager with it);
     *   CarLink's Disconnect ends a session.
     * Kept: the side panel, picture safe area, MFi source, audio channel mapping, the car's name
     * and button icon and the size basis (no CarLink setting yet), the hotspot status (live), and
     * the button back to CarLink.
     */
    private val carPlayMenu = setOf(
        MenuItem.SIDE_PANEL, MenuItem.MFI_TARGET, MenuItem.HOTSPOT_STATUS, MenuItem.AUDIO_MAPPING,
        MenuItem.VEHICLE_IDENTITY, MenuItem.CAR_BUTTON_ICON, MenuItem.PHYSICAL_SIZE_BASIS, MenuItem.SAFE_AREA,
        MenuItem.HOME,
    )

    fun install(context: Context) {
        val app = context.applicationContext
        CarPlayHostIntegration.palette = { night -> if (night) carPlayDark else carPlayLight }
        CarPlayHostIntegration.menuItems = carPlayMenu
        CarPlayHostIntegration.chromeStyler = { root, chrome, night -> styleCarPlay(root, chrome, tokens(night)) }
        // Android Auto's Quick Settings is DiAuto's own settings screen; CarLink Settings owns them.
        HostIntegration.exitMenuHomeLabel = app.getString(R.string.carlink_engine_open_carlink)
        HostIntegration.chromeStyler = { root, chrome -> styleAndroidAuto(root, chrome, tokens(night = true)) }
    }

    // ---------------------------------------------------------------- tokens

    private class Tokens(val night: Boolean) {
        val base = if (night) 0xFF0C1016.toInt() else 0xFFE8EBEF.toInt()
        val light = if (night) 0xFF16324A.toInt() else 0xFFF3EEE6.toInt()
        val fill = if (night) 0xFF111A26.toInt() else 0xFFDDE2E8.toInt()
        /** Glass composited on [base] (white 6% at night), opaque so it reads over video. */
        val glass = if (night) 0xFF1B1E24.toInt() else 0xFFF7F8FA.toInt()
        val strokeTop = if (night) 0x29FFFFFF else 0xCCFFFFFF.toInt()
        val strokeBottom = if (night) 0x0AFFFFFF else 0x33FFFFFF
        val text = if (night) 0xFFEEF2F6.toInt() else 0xFF0D1117.toInt()
        val textSecondary = if (night) 0xFFA9B3C0.toInt() else 0xFF46505C.toInt()
        val accent = if (night) 0xFF5BD6F0.toInt() else 0xFF006A7E.toInt()
        val onAccent = if (night) 0xFF03222B.toInt() else Color.WHITE
        val fault = if (night) 0xFFFF7A70.toInt() else 0xFFC2352B.toInt()
        val danger = 0xFFC2352B.toInt()
        val ripple = if (night) 0x1AFFFFFF else 0x1A000000
    }

    private val dark = Tokens(true)
    private val day = Tokens(false)
    private fun tokens(night: Boolean) = if (night) dark else day

    private enum class Weight(val font: Int) {
        REGULAR(R.font.carlink_inter_regular), MEDIUM(R.font.carlink_inter_medium),
        SEMIBOLD(R.font.carlink_inter_semibold), DISPLAY(R.font.carlink_inter_display_semibold),
    }

    private val fonts = HashMap<Weight, Typeface?>()

    private fun font(context: Context, weight: Weight): Typeface? = fonts.getOrPut(weight) {
        runCatching { ResourcesCompat.getFont(context, weight.font) }.getOrNull()
    }

    private fun View.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- CarPlay (DiPlay)

    private fun styleCarPlay(root: View, chrome: CarPlayHostIntegration.Chrome, t: Tokens) {
        // DiPlay paints the waiting screen once before it adds the content; style it once it is there.
        if (root is ViewGroup && root.childCount == 0) {
            root.background = if (chrome == CarPlayHostIntegration.Chrome.WAITING) atmosphere(t) else root.background
            root.post { if (root.childCount > 0) styleCarPlay(root, chrome, t) }
            return
        }
        val primary = setOfNotNull(
            root.context.getString(CpR.string.back_to_diplay).takeIf { chrome == CarPlayHostIntegration.Chrome.WAITING },
            root.context.getString(CpR.string.save_and_reconnect),
            root.context.getString(CpR.string.picture_done),
            root.context.getString(CpR.string.save).takeIf { chrome == CarPlayHostIntegration.Chrome.SAFE_AREA_EDITOR },
            root.context.getString(CpR.string.side_panel_full_screen).takeIf { chrome == CarPlayHostIntegration.Chrome.SIDE_PANEL },
        )
        when (chrome) {
            CarPlayHostIntegration.Chrome.WAITING -> {
                root.background = atmosphere(t)
                root.walk().filterIsInstance<ImageView>().forEach { icon ->
                    icon.setImageDrawable(mark(icon.context, t))
                    icon.contentDescription = icon.context.getString(R.string.app_name)
                }
                // The title is the first text under the mark; DiPlay sizes it, CarLink sets the face.
                root.walk().filterIsInstance<TextView>().firstOrNull { it !is Button }?.let {
                    it.typeface = font(it.context, Weight.DISPLAY)
                    it.letterSpacing = -0.01f
                }
            }
            CarPlayHostIntegration.Chrome.MENU -> {
                root.background = atmosphere(t)
                // DiPlay's centred column paints its own flat background over the canvas.
                ((root as? ViewGroup)?.getChildAt(0))?.background = null
            }
            CarPlayHostIntegration.Chrome.SIDE_PANEL, CarPlayHostIntegration.Chrome.SAFE_AREA_EDITOR ->
                root.setBackgroundColor(t.base)
            CarPlayHostIntegration.Chrome.PICTURE_PANEL -> {
                root.background = glass(t, root.dp(28f).toFloat())
                root.setPadding(root.dp(20f), root.dp(16f), root.dp(20f), root.dp(16f))
                root.walk().filterIsInstance<TextView>().firstOrNull { it !is Button }?.let {
                    it.typeface = font(it.context, Weight.SEMIBOLD)
                    it.textSize = 20f
                    it.setPadding(0, 0, 0, it.dp(8f))
                }
                root.walk().filterIsInstance<Button>().forEach { button ->
                    val row = button.parent as? LinearLayout ?: return@forEach
                    if (row.orientation == LinearLayout.HORIZONTAL && row.indexOfChild(button) > 0) {
                        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = button.dp(12f)
                    }
                }
            }
        }
        styleTree(root, t, primary, skipFirstTitle = chrome == CarPlayHostIntegration.Chrome.WAITING ||
            chrome == CarPlayHostIntegration.Chrome.PICTURE_PANEL)
    }

    // ---------------------------------------------------------------- Android Auto (DiAuto)

    private fun styleAndroidAuto(root: View, chrome: HostIntegration.Chrome, t: Tokens) {
        val context = root.context
        when (chrome) {
            HostIntegration.Chrome.LOADING -> {
                val content = root.findViewById<LinearLayout>(AaR.id.loading_default_content) ?: return
                // Custom loading media (an Android Auto option) keeps its own black stage.
                if (content.visibility == View.VISIBLE) {
                    root.background = atmosphere(t).apply { alpha = 0xF2 }
                }
                if (content.findViewWithTag<View>(MARK_TAG) == null) {
                    content.addView(ImageView(context).apply {
                        tag = MARK_TAG
                        setImageDrawable(mark(context, t))
                        contentDescription = context.getString(R.string.app_name)
                    }, 0, LinearLayout.LayoutParams(content.dp(72f), content.dp(72f)).apply {
                        bottomMargin = content.dp(28f)
                    })
                }
                root.findViewById<TextView>(AaR.id.overlay_text)?.apply {
                    typeface = font(context, Weight.SEMIBOLD)
                    setTextColor(t.text)
                }
                root.findViewById<TextView>(AaR.id.loading_custom_text)?.typeface = font(context, Weight.MEDIUM)
                root.findViewById<TextView>(AaR.id.overlay_detail)?.apply {
                    typeface = font(context, Weight.REGULAR)
                    setTextColor(t.textSecondary)
                    textSize = 18f
                }
                root.findViewById<Button>(AaR.id.disconnect_button)?.apply {
                    pill(this, t, PillKind.GLASS)
                    minHeight = dp(56f)
                    textSize = 18f
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(28f)
                }
                root.walk().filterIsInstance<ProgressBar>().forEach {
                    it.indeterminateTintList = ColorStateList.valueOf(t.accent)
                }
            }
            HostIntegration.Chrome.RENDERER_BANNER -> {
                root.background = glass(t, root.dp(28f).toFloat())
                root.setPadding(root.dp(24f), root.dp(12f), root.dp(12f), root.dp(12f))
                (root.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.setMargins(root.dp(16f), 0, root.dp(16f), root.dp(16f))
                    root.layoutParams = it
                }
                val yes = context.getString(AaR.string.renderer_confirm_yes)
                styleTree(root, t, setOf(yes))
                root.walk().filterIsInstance<Button>().forEach {
                    (it.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = it.dp(12f)
                }
            }
            HostIntegration.Chrome.EXIT_MENU -> {
                val stop = context.getString(AaR.string.exit_dialog_stop)
                fun styleMenu() {
                    styleTree(root, t, emptySet(), flatButtons = true)
                    root.walk().filterIsInstance<ListView>().forEach { list ->
                        list.divider = null
                        list.walk().forEach { styleExitRow(it, t, stop) }
                    }
                }
                styleMenu()
                // The rows are laid out after show(); color them as they arrive. Their size and font
                // come from CarLink's dialog_exit_item override: ListView measures fresh rows.
                root.walk().filterIsInstance<ListView>().forEach { list ->
                    list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View, child: View) {
                            child.walk().forEach { styleExitRow(it, t, stop) }
                        }
                        override fun onChildViewRemoved(parent: View, child: View) = Unit
                    })
                }
                root.post { styleMenu() }
            }
        }
    }

    private fun styleExitRow(view: View, t: Tokens, stop: String) {
        val row = view as? ViewGroup ?: return
        val text = row.walk().filterIsInstance<TextView>().firstOrNull() ?: return
        val icon = row.walk().filterIsInstance<ImageView>().firstOrNull()
        val isStop = text.text?.toString() == stop
        text.typeface = font(text.context, Weight.MEDIUM)
        text.setTextColor(if (isStop) t.fault else t.text)
        if (text.text?.toString() == HostIntegration.exitMenuHomeLabel) {
            icon?.clearColorFilter()
            icon?.setImageDrawable(mark(icon.context, t))
        } else {
            icon?.setColorFilter(if (isStop) t.fault else t.textSecondary)
        }
    }

    // ---------------------------------------------------------------- shared

    private enum class PillKind { ION, GLASS, DANGER }

    private fun styleTree(root: View, t: Tokens, primary: Set<String>, skipFirstTitle: Boolean = false,
                          flatButtons: Boolean = false) {
        var titleSkipped = !skipFirstTitle
        for (view in root.walk()) {
            when (view) {
                is RadioButton -> {
                    view.typeface = font(view.context, Weight.REGULAR)
                    view.buttonTintList = ColorStateList.valueOf(t.accent)
                }
                is CompoundButton -> view.typeface = font(view.context, Weight.REGULAR)
                is Button -> {
                    view.typeface = font(view.context, Weight.MEDIUM)
                    view.isAllCaps = false
                    if (flatButtons) {
                        view.setTextColor(t.accent)
                        continue
                    }
                    val label = view.text?.toString().orEmpty()
                    when {
                        label == "X" -> closeButton(view, t)
                        label in primary -> pill(view, t, PillKind.ION)
                        tintOf(view) == 0xFFC2352B.toInt() -> pill(view, t, PillKind.DANGER)
                        else -> pill(view, t, PillKind.GLASS)
                    }
                }
                is EditText -> {
                    view.typeface = font(view.context, Weight.REGULAR)
                    view.setTextColor(t.text)
                    view.setHintTextColor(t.textSecondary)
                    view.backgroundTintList = null
                    view.background = glass(t, view.dp(16f).toFloat())
                    view.setPadding(view.dp(16f), view.dp(12f), view.dp(16f), view.dp(12f))
                }
                is TextClock -> {
                    view.typeface = font(view.context, Weight.DISPLAY)
                    view.setTextColor(t.text)
                }
                is TextView -> {
                    if (!titleSkipped) { titleSkipped = true; continue }
                    val bold = view.typeface?.isBold == true
                    view.typeface = font(view.context, if (bold) Weight.SEMIBOLD else Weight.REGULAR)
                    // Ion is for what can be tapped: DiPlay's accent headers become eyebrows and
                    // its accent status lines plain secondary text.
                    if (view.currentTextColor == t.accent) {
                        view.setTextColor(t.textSecondary)
                        if (bold) {
                            view.isAllCaps = true
                            view.letterSpacing = 0.08f
                        }
                    }
                }
                is SeekBar -> {
                    view.progressTintList = ColorStateList.valueOf(t.accent)
                    view.thumbTintList = ColorStateList.valueOf(t.accent)
                    view.progressBackgroundTintList = ColorStateList.valueOf(t.textSecondary)
                }
            }
        }
    }

    private fun tintOf(view: View): Int? = view.backgroundTintList?.defaultColor
        ?: (view.background as? GradientDrawable)?.color?.defaultColor

    private fun pill(button: Button, t: Tokens, kind: PillKind) {
        val radius = button.dp(999f).toFloat()
        val shape = when (kind) {
            PillKind.ION -> GradientDrawable().apply { cornerRadius = radius; setColor(t.accent) }
            PillKind.DANGER -> GradientDrawable().apply { cornerRadius = radius; setColor(t.danger) }
            PillKind.GLASS -> glass(t, radius)
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(t.ripple), shape, mask)
        button.stateListAnimator = null
        button.elevation = 0f
        button.setTextColor(when (kind) { PillKind.ION -> t.onAccent; PillKind.DANGER -> Color.WHITE; PillKind.GLASS -> t.text })
        if (button.paddingStart < button.dp(20f)) {
            button.setPaddingRelative(button.dp(24f), button.paddingTop, button.dp(24f), button.paddingBottom)
        }
    }

    private fun closeButton(button: Button, t: Tokens) {
        val icon = button.context.getDrawable(R.drawable.carlink_ic_close)?.mutate()?.apply { setTint(t.text) }
        val circle = glass(t, button.dp(999f).toFloat())
        val inset = button.dp(12f)
        button.text = ""
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(t.ripple),
            if (icon == null) circle else LayerDrawable(arrayOf(circle, InsetDrawable(icon, inset))), null)
        button.stateListAnimator = null
    }

    /** Glass: [Tokens.glass] fill inside a 1dp edge lit from the top (white 16% → 4%). */
    private fun glass(t: Tokens, radius: Float): Drawable {
        val edge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(compose(t.strokeTop, t.glass), compose(t.strokeBottom, t.glass))).apply { cornerRadius = radius }
        val fill = GradientDrawable().apply { cornerRadius = (radius - 1).coerceAtLeast(0f); setColor(t.glass) }
        return LayerDrawable(arrayOf(edge, fill)).apply { setLayerInset(1, 2, 2, 2, 2) }
    }

    /** The canvas: graphite with one soft light top-start and a faint fill bottom-end (Premium.kt's Atmosphere). */
    private fun atmosphere(t: Tokens): Drawable = AtmosphereDrawable(t.base, t.light, t.fill)

    private class AtmosphereDrawable(private val base: Int, private val light: Int, private val fill: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private var lightShader: Shader? = null
        private var fillShader: Shader? = null
        private var alphaValue = 255

        override fun onBoundsChange(bounds: Rect) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            val diagonal = hypot(w, h)
            lightShader = RadialGradient(bounds.left + w * 0.08f, bounds.top - h * 0.1f, diagonal * 0.75f,
                light, light and 0x00FFFFFF, Shader.TileMode.CLAMP)
            fillShader = RadialGradient(bounds.left + w, bounds.top + h * 1.1f, diagonal * 0.6f,
                (fill and 0x00FFFFFF) or (0xB3 shl 24), fill and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) {
            paint.shader = null
            paint.color = base
            paint.alpha = alphaValue
            canvas.drawRect(bounds, paint)
            for (shader in listOf(lightShader, fillShader)) {
                paint.shader = shader
                paint.alpha = alphaValue
                canvas.drawRect(bounds, paint)
            }
        }

        override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = if (alphaValue == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
    }

    /** CarLink's mark: the frame in text color and the phone in Ion, as in Compose's CarLinkMark. */
    private fun mark(context: Context, t: Tokens): Drawable? {
        val frame = context.getDrawable(R.drawable.carlink_logo_mark_primary)?.mutate()?.apply { setTint(t.text) }
        val phone = context.getDrawable(R.drawable.carlink_logo_mark_accent)?.mutate()?.apply { setTint(t.accent) }
        return if (frame == null || phone == null) frame ?: phone else LayerDrawable(arrayOf(frame, phone))
    }

    /** [over] (with alpha) composited on the opaque [under]. */
    private fun compose(over: Int, under: Int): Int {
        val a = Color.alpha(over) / 255f
        fun channel(o: Int, u: Int) = (o * a + u * (1 - a)).roundToInt()
        return Color.rgb(channel(Color.red(over), Color.red(under)), channel(Color.green(over), Color.green(under)),
            channel(Color.blue(over), Color.blue(under)))
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        if (this@walk is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).walk())
    }

    private const val MARK_TAG = "carlink_mark"
}
