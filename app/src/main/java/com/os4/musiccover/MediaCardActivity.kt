package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.os4.musiccover.ui.screen.features.MediaCardPageView
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Independent appearance profiles for native media cards. */
class MediaCardActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = try { ColorSchemeMode.valueOf(settings.themeMode) } catch (_: Exception) {
            ColorSchemeMode.System
        }
        setContent {
            AppTheme(themeMode = theme) {
                MediaCardPageView(settings.isBlurEnabled, resumes, onBack = { finish() })
            }
        }
    }

    private var resumes by mutableIntStateOf(0)
    private var resumedOnce = false
    override fun onResume() {
        super.onResume()
        if (resumedOnce) resumes++ else resumedOnce = true
    }
}
