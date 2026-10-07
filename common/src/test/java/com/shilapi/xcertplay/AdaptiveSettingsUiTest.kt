package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AdaptiveSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun compactSettingsOpenOnAnEverydayDriverOverview() {
        val screen = openSettings()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_overview) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun readinessAsksForAnIphoneBeforeWirelessCanConnect() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val screen = openSettings()
        // Robolectric has no CarPlay authentication assets, which is a setup error of its own.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_title) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_action) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
    }

    @Test fun searchOpensTheCategoryThatOwnsTheSetting() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val results = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "searchSettings",
            ReflectionHelpers.ClassParameter(List::class.java, index),
            ReflectionHelpers.ClassParameter(String::class.java, "FRAME rate"))
        val frameRate = results.single {
            ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.frame_rate)
        }

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(frameRate.javaClass, frameRate))

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
    }

    @Test fun cardControlsShareOneHeightAndGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        val density = screen.resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        val buttons = descendants(scroll).filterIsInstance<android.widget.Button>()
            .filter { it !is Switch && (it.parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL }
            .toList()

        assertTrue(buttons.size >= 4)
        buttons.forEach { button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            assertEquals(button.text.toString(), dp(60), lp.height)
            assertEquals(button.text.toString(), 0, lp.topMargin)
            assertTrue(button.text.toString(), lp.bottomMargin in setOf(0, dp(6), dp(12)))
        }
        val page = scroll.getChildAt(0)
        assertFalse(descendants(scroll).any { it.tag == "spacer" && it.parent !== page })
    }

    @Test fun searchNamesOpenTheirOwnCategoryNotACardThatLinksToIt() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val advanced = index.single { ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.settings_advanced) }

        assertEquals(SettingsCategory.ADVANCED, ReflectionHelpers.getField<SettingsCategory>(advanced, "category"))
    }

    @Test fun aRowBeforeAButtonKeepsTheGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_vehicle)) }
            .performClick()
        val chooseImage = texts(screen).single { it.text == screen.getString(R.string.choose_image) }
        val card = chooseImage.parent as LinearLayout
        val preview = card.getChildAt(card.indexOfChild(chooseImage) - 1)

        assertEquals(Math.round(12 * screen.resources.displayMetrics.density),
            (preview.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun tappingAToggleRowChangesTheSetting() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        (fullScreen.parent as View).performClick()

        assertTrue(fullScreen.isChecked)
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
    }

    @Test fun compactDetailReturnsToOverviewBeforeLeavingSettings() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun openingACompactCategoryAlwaysStartsAtTheTop() {
        val screen = openSettings()
        val firstScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        firstScroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        firstScroll.layout(0, 0, 600, 700)
        firstScroll.scrollTo(0, 400)
        assertTrue(firstScroll.scrollY > 0)

        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        val detailScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        assertEquals(0, detailScroll.scrollY)
    }

    @Test fun quickFullScreenChangesBothExistingPreferencesTogether() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        fullScreen.performClick()

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(true, fullScreen.isChecked)
    }

    @Test fun systemBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun toolbarBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        texts(screen).single { it.text == screen.getString(R.string.back) }.performClick()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
    }

    @Test fun connectionSetupOpenedDirectlyStillReturnsHome() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
        ).setup().get().also { activity = it }

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun advancedContainsOnlyExpertSections() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { candidate ->
                candidate.contentDescription == screen.getString(
                    R.string.settings_open_category,
                    screen.getString(R.string.settings_advanced),
                ) && descendants(candidate).filterIsInstance<ImageView>().count() == 2
            }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_advanced_caution_title) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.advanced_vehicle_data) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.audio_routing) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.location) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.diagnostics) })
    }

    @Test fun displayOpensPictureAdjustments() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.picture_adjustments) })
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        val screen = openSettings()
        fun visibleIn(category: Int): List<CharSequence> {
            descendants(screen.window.decorView).first { candidate ->
                candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
            }.performClick()
            return texts(screen).map { it.text }.toList().also { screen.onBackPressedDispatcher.onBackPressed() }
        }
        fun text(id: Int) = screen.getString(id)

        val display = visibleIn(R.string.settings_display)
        val audio = visibleIn(R.string.audio)
        val vehicle = visibleIn(R.string.settings_vehicle)
        val advanced = visibleIn(R.string.settings_advanced)

        assertTrue(audio.any { it.startsWith(text(R.string.music_buffer)) })
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.contrib_audio_home_toggle_audio_focus).forEach {
            assertTrue(text(it), text(it) in advanced)
            assertFalse(text(it), text(it) in audio)
        }
        assertTrue(text(R.string.right_hand_drive) in vehicle)
        assertTrue(text(R.string.car_button_in_carplay) in vehicle)
        assertTrue(text(R.string.wheel_siri_key) in vehicle)
        assertTrue(text(R.string.settings_wheel_keys) in vehicle)
        assertTrue(text(R.string.side_panel) in advanced)
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.right_hand_drive, R.string.car_button_in_carplay,
            R.string.side_panel, R.string.split_screen_areas, R.string.carplay_rotation).forEach {
            assertFalse(text(it), text(it) in display)
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailStaysOutsideTheScrollingCategory() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val railDestination = descendants(screen.window.decorView).first {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) &&
                descendants(it).filterIsInstance<ImageView>().count() == 1
        }

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) && isInside(it, categoryScroll) })
        assertFalse(isInside(railDestination, categoryScroll))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailUsesAnIconForEveryDestination() {
        val screen = openSettings()

        val destinations = descendants(screen.window.decorView).filter { destination ->
            destination.contentDescription?.startsWith("Open ") == true &&
                destination.contentDescription?.endsWith(" settings") == true &&
                descendants(destination).filterIsInstance<ImageView>().count() == 1
        }.toList()
        assertEquals(8, destinations.size)
        destinations.forEach { destination ->
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun overviewUtilitiesAreOneGroupedCardWithSectionSpacing() {
        val screen = openSettings()
        fun utilityRow(category: Int) = descendants(screen.window.decorView).single { candidate ->
            candidate.contentDescription == screen.getString(
                R.string.settings_open_category,
                screen.getString(category),
            ) && descendants(candidate).filterIsInstance<ImageView>().count() == 2
        }

        val diagnostics = utilityRow(R.string.diagnostics)
        val advanced = utilityRow(R.string.settings_advanced)
        assertSame(diagnostics.parent, advanced.parent)
        val card = diagnostics.parent as View
        assertEquals(Math.round(18 * screen.resources.displayMetrics.density), (card.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun openConnectionSetupFromSettings(): DiPlayActivity = openSettings().also { screen ->
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.connection)) }
            .performClick()
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view.parent) { it.parent }.any { it === ancestor }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
