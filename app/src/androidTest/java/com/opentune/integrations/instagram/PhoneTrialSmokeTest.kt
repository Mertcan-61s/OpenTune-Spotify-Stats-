package com.opentune.integrations.instagram

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneTrialSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun node(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }?.let { return it }
            SystemClock.sleep(200)
        }
        throw AssertionError("Missing trial control: $text")
    }

    @Test fun opensPrivateTrialAndRejectsEmptyAccountBeforeLogin() {
        val app = instrumentation.targetContext
        val component = ComponentName(app, PhoneNotesTrialActivity::class.java)
        val info = app.packageManager.getActivityInfo(component, 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":notes_trial", info.processName)
        app.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(node("Instagram’ı burada aç").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        node("Geçerli bir Instagram kullanıcı adı gir.")
        assertTrue(node("Kapat").performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
