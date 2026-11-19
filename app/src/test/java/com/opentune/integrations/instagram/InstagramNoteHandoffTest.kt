package com.opentune.integrations.instagram

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InstagramNoteHandoffTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = requireNotNull(app.getSystemService(ClipboardManager::class.java))
    private val searchText = "Şarkı Sanatçı"

    @Test fun missingInstagramKeepsSearchTextAndReportsThatTheAppDidNotOpen() {
        assertEquals("Song copied. The Instagram app could not be opened.", openInstagramWithSearchText(app, searchText))
        assertEquals(searchText, clipboard.primaryClip?.getItemAt(0)?.text)
        assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun installedInstagramReceivesOnlyItsLauncherIntent() {
        val component = ComponentName("com.instagram.android", "com.instagram.android.MainActivity")
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(component)
        packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })

        assertNull(openInstagramWithSearchText(app, searchText))
        val launched = requireNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(component, launched.component)
        assertNull(launched.extras)
        assertEquals(searchText, clipboard.primaryClip?.getItemAt(0)?.text)
    }
}
