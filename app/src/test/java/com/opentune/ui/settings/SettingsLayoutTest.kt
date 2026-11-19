package com.opentune.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLayoutTest {
    /** A page left off the main list can only be reached by searching. */
    @Test fun everyPageIsOnTheMainList() {
        val listed = SETTINGS_HOME.flatMap { it.second }
        assertEquals(SETTINGS_CATEGORIES.map { it.key }.sorted(), listed.sorted())
    }

    @Test fun instagramMusicNotesIsListedWithConnectedServices() {
        val account = SETTINGS_CATEGORIES.single { it.key == "account" }
        val services = account.groups.single { it.first == "Connected services" }.second
        assertEquals(1, services.count { it == "Instagram Music Notes" })
    }
}
