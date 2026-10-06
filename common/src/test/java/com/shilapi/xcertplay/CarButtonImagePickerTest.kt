package com.shilapi.xcertplay

import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class CarButtonImagePickerTest {
    @Test fun carButtonOffersGalleryAndFileBrowsing() {
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup().visible()
        try {
            val controls = LinearLayout(activity)
            DiPlayActivity::class.java.getDeclaredMethod("carButtonControls", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, controls)
            val buttons = (0 until controls.childCount).mapNotNull { controls.getChildAt(it) as? Button }

            buttons.single { it.text == activity.getString(R.string.choose_image) }.performClick()
            assertEquals(Intent.ACTION_GET_CONTENT, shadowOf(activity).nextStartedActivityForResult.intent.action)

            buttons.single { it.text == activity.getString(R.string.browse_image_files) }.performClick()
            val documentIntent = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, documentIntent.action)
            val acceptedTypes = listOfNotNull(documentIntent.type) +
                (documentIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: emptyList())
            assertTrue("image/*" in acceptedTypes)
            assertTrue(documentIntent.hasCategory(Intent.CATEGORY_OPENABLE))
        } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
        }
    }
}
