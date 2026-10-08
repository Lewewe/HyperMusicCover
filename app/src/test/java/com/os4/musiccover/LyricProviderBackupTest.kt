package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricProviderBackupTest {
    @Test
    fun mixedProviderPreferencesRoundTripWithoutCredentials() {
        val state = ModuleBridge.State(
            providerQq = false,
            providerNetease = true,
            providerKuwo = false,
            providerKugou = true,
            providerLrcLib = false,
            providerVariants = false,
            spicyLyricsEnabled = false,
            spicyLyricsApiKey = "spicy-secret",
            lyricTranslateApiKey = "translation-secret",
        )
        val json = JSONObject()
        SettingsBackup.writeLyricProviders(json, state)
        val restored = SettingsBackup.readLyricProviders(JSONObject(json.toString()))
        assertEquals(mapOf("qq" to false, "netease" to true, "kuwo" to false,
            "kugou" to true, "lrclib" to false, "variants" to false), restored)
        assertFalse(json.getBoolean("spicyLyricsEnabled"))
        assertFalse(json.toString().contains("secret"))
        assertFalse(json.has("spicyLyricsApiKey"))
        assertFalse(json.has("lyricTranslateApiKey"))
    }

    @Test
    fun oldBackupDoesNotOverwriteAnyProvider() {
        assertTrue(SettingsBackup.readLyricProviders(JSONObject("{\"lyricsAlignment\":1}")).isEmpty())
    }

    @Test
    fun partialBackupRestoresOnlyPresentValidFields() {
        val json = JSONObject("""{"lyricsProviderQq":false,"lyricsProviderKuwo":null,
            "lyricsProviderNetease":"invalid","lyricsProviderVariants":true,
            "unknownProvider":false,"spicyLyricsApiKey":"ignored"}""")
        assertEquals(mapOf("qq" to false, "variants" to true), SettingsBackup.readLyricProviders(json))
    }
}
