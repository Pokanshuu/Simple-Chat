package com.simplechat.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImageSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun personalizedStyleDefaultsOn() {
        assertTrue(ShareImageSettings().usePersonalizedStyle)
    }

    @Test fun roundTripsDisabledChoice() {
        val raw = json.encodeToString(ShareImageSettings.serializer(), ShareImageSettings(usePersonalizedStyle = false))
        assertFalse(json.decodeFromString(ShareImageSettings.serializer(), raw).usePersonalizedStyle)
    }

    @Test fun missingFieldFallsBackToDefault() {
        assertTrue(json.decodeFromString(ShareImageSettings.serializer(), "{}").usePersonalizedStyle)
    }

    @Test fun unknownFieldsAreIgnored() {
        val decoded = json.decodeFromString(
            ShareImageSettings.serializer(),
            """{"usePersonalizedStyle":false,"futureOption":"wallpaper"}""",
        )
        assertFalse(decoded.usePersonalizedStyle)
    }
}
