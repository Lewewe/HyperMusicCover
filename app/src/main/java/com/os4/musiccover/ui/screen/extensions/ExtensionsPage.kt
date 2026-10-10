package com.os4.musiccover.ui.screen.extensions

import android.os.Bundle
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.HyperCanvasBridge
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.screen.features.ValueSlider
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ExtensionsPageView(isBlurEnabled: Boolean, isCurrent: Boolean, extraBottomPadding: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Bundle()) }
    var hostBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(isCurrent) {
        if (isCurrent) while (true) {
            val host = ModuleBridge.query(context)
            if (host.alive) {
                hostBlocked = host.hidePlayerBackground
                state = HyperCanvasBridge.synchronizeBlock(context, hostBlocked)
            } else state = HyperCanvasBridge.query(context)
            delay(2500L)
        }
    }
    fun push(key: String, value: Int) {
        val updated = Bundle(state)
        if (key == "enabled" || key == "showInPill") updated.putBoolean(key, value != 0) else updated.putInt(key, value)
        state = updated
        scope.launch { state = HyperCanvasBridge.configure(context, updated) }
    }
    val blocked = hostBlocked || state.getBoolean("hostBlocked")
    val installed = state.getBoolean("installed")
    val enabled = state.getBoolean("enabled")
    val status = when {
        blocked -> R.string.hypercanvas_background_blocked
        !installed -> R.string.hypercanvas_missing
        state.getString("scopeState") == "awaiting-approval" -> R.string.hypercanvas_approval
        state.getString("scopeState") == "enable-module" -> R.string.hypercanvas_enable_module
        state.getString("scopeState") == "restart-scopes" -> R.string.hypercanvas_restart
        state.getString("scopeState") == "approval-failed" -> R.string.hypercanvas_scope_failed
        state.getLong("spotifySeen") == 0L || state.getLong("systemSeen") == 0L -> R.string.hypercanvas_restart
        else -> R.string.hypercanvas_connected
    }
    PageScaffold(title = stringResource(R.string.tab_extensions), isBlurEnabled = isBlurEnabled, extraBottomPadding = extraBottomPadding) {
        item { SmallTitle("HyperCanvas · Beta") }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.hypercanvas_title), summary = stringResource(R.string.hypercanvas_summary),
                    checked = enabled, enabled = installed && !blocked, onCheckedChange = { push("enabled", if (it) 1 else 0) })
                Text(stringResource(status), modifier = Modifier.padding(16.dp))
                if (installed && enabled) {
                    SwitchPreference(title = stringResource(R.string.hypercanvas_show_in_pill),
                        summary = stringResource(R.string.hypercanvas_show_in_pill_summary),
                        checked = state.getBoolean("showInPill"),
                        onCheckedChange = { push("showInPill", if (it) 1 else 0) })
                    ValueSlider(title = stringResource(R.string.hypercanvas_dim), value = state.getInt("dim", 35).toFloat(), valueRange = 0f..80f,
                        enabled = true, detent = 35f, label = { "${it.toInt()}%" }, onValueChange = { push("dim", it.toInt()) })
                    ValueSlider(title = stringResource(R.string.hypercanvas_cover_blur), value = state.getInt("blur", 18).toFloat(), valueRange = 0f..40f,
                        enabled = true, detent = 18f, onValueChange = { push("blur", it.toInt()) })
                    ValueSlider(title = stringResource(R.string.hypercanvas_cover_dim), value = state.getInt("coverDim", 45).toFloat(), valueRange = 0f..80f,
                        enabled = true, detent = 45f, label = { "${it.toInt()}%" }, onValueChange = { push("coverDim", it.toInt()) })
                    Text(stringResource(R.string.hypercanvas_cache, state.getInt("count")), modifier = Modifier.padding(16.dp))
                }
            }
        }
        item { Text(stringResource(R.string.hypercanvas_note), modifier = Modifier.padding(16.dp)) }
    }
}
