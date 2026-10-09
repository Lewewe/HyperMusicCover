package com.os4.musiccover.ui.screen.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText

private const val DICT_ABSENT = 0
private const val DICT_DOWNLOADING = 1
private const val DICT_READY = 2
private const val DICT_ERROR = 3

@Composable
internal fun DictionariesPageView(isBlurEnabled: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) { module = ModuleBridge.queryAlive(context) }
    LaunchedEffect(module.alive, module.onDeviceTransliterationState, module.localChineseState) {
        if (!module.alive || (module.onDeviceTransliterationState != DICT_DOWNLOADING
                    && module.localChineseState != DICT_DOWNLOADING)) return@LaunchedEffect
        while (module.onDeviceTransliterationState == DICT_DOWNLOADING
            || module.localChineseState == DICT_DOWNLOADING) {
            delay(400)
            module = ModuleBridge.query(context)
        }
    }

    PageScaffold(
        title = stringResource(R.string.lyrics_dictionaries),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAdd = true }, enabled = module.alive
                    && (module.onDeviceTransliterationState == DICT_ABSENT
                    || module.localChineseState == DICT_ABSENT)) {
                Icon(Icons.Rounded.Add, stringResource(R.string.lyrics_dictionary_add),
                    tint = MiuixTheme.colorScheme.onBackground)
            }
        },
    ) {
        item {
            val japaneseState = module.onDeviceTransliterationState
            val chineseState = module.localChineseState
            if (japaneseState == DICT_ABSENT && chineseState == DICT_ABSENT) {
                MiuixText(
                    text = stringResource(R.string.lyrics_dictionaries_empty),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(28.dp),
                )
            }
            if (japaneseState != DICT_ABSENT) {
                DictionaryRow(
                    title = stringResource(R.string.lyrics_dictionary_japanese),
                    forText = stringResource(R.string.lyrics_dictionary_japanese_for),
                    state = japaneseState, progress = module.onDeviceTransliterationProgress,
                    error = module.onDeviceTransliterationError,
                ) {
                    ModuleBridge.manageJapaneseDictionary(context, "delete")
                    module = module.copy(onDeviceTransliterationState = DICT_ABSENT,
                        onDeviceTransliterationProgress = -1, onDeviceTransliterationError = "")
                }
            }
            if (chineseState != DICT_ABSENT) {
                DictionaryRow(
                    title = stringResource(R.string.lyrics_dictionary_chinese),
                    forText = stringResource(R.string.lyrics_dictionary_chinese_for),
                    state = chineseState, progress = module.localChineseProgress,
                    error = module.localChineseError,
                ) {
                    ModuleBridge.manageChineseDictionary(context, "delete")
                    module = module.copy(localChineseState = DICT_ABSENT,
                        localChineseProgress = -1, localChineseError = "")
                }
            }
            // These engines are code-only. Keep them in one Miuix card rather than scattering
            // bare preference rows through a page otherwise made of dictionary cards.
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column {
                    SwitchPreference(title = stringResource(R.string.lyrics_dictionary_korean),
                        summary = stringResource(R.string.lyrics_dictionary_korean_for), checked = module.localKorean,
                        enabled = module.alive, onCheckedChange = { on -> module = module.copy(localKorean = on); ModuleBridge.setLocalRomanizer(context, "korean", on) })
                    SwitchPreference(title = stringResource(R.string.lyrics_dictionary_cyrillic),
                        summary = stringResource(R.string.lyrics_dictionary_cyrillic_for), checked = module.localCyrillic,
                        enabled = module.alive, onCheckedChange = { on -> module = module.copy(localCyrillic = on); ModuleBridge.setLocalRomanizer(context, "cyrillic", on) })
                    SwitchPreference(title = stringResource(R.string.lyrics_dictionary_greek),
                        summary = stringResource(R.string.lyrics_dictionary_greek_for), checked = module.localGreek,
                        enabled = module.alive, onCheckedChange = { on -> module = module.copy(localGreek = on); ModuleBridge.setLocalRomanizer(context, "greek", on) })
                }
            }
        }
    }

    if (showAdd) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.lyrics_dictionary_add),
            onDismissRequest = { showAdd = false },
        ) {
            if (module.onDeviceTransliterationState == DICT_ABSENT) ArrowPreference(
                title = stringResource(R.string.lyrics_dictionary_japanese),
                summary = stringResource(R.string.lyrics_dictionary_japanese_summary),
                enabled = module.alive && module.onDeviceTransliterationState != DICT_DOWNLOADING,
                onClick = {
                    showAdd = false
                    module = module.copy(onDeviceTransliterationState = DICT_DOWNLOADING,
                        onDeviceTransliterationProgress = 0, onDeviceTransliterationError = "")
                    ModuleBridge.manageJapaneseDictionary(context, "download")
                },
            )
            if (module.localChineseState == DICT_ABSENT) ArrowPreference(
                title = stringResource(R.string.lyrics_dictionary_chinese),
                summary = stringResource(R.string.lyrics_dictionary_chinese_summary),
                enabled = module.alive,
                onClick = {
                    showAdd = false
                    module = module.copy(localChineseState = DICT_DOWNLOADING,
                        localChineseProgress = 0, localChineseError = "")
                    ModuleBridge.manageChineseDictionary(context, "download")
                },
            )
        }
    }
}

@Composable
private fun DictionaryRow(title: String, forText: String, state: Int, progress: Int,
                          error: String, onDelete: () -> Unit) {
    val detail = when (state) {
        DICT_DOWNLOADING -> if (progress in 0..100)
            stringResource(R.string.lyrics_on_device_transliteration_downloading_progress, progress)
        else stringResource(R.string.lyrics_on_device_transliteration_downloading)
        DICT_READY -> stringResource(R.string.lyrics_dictionary_ready)
        DICT_ERROR -> error.ifBlank {
            stringResource(R.string.lyrics_dictionary_error)
        }
        else -> ""
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MiuixText(title, fontSize = 16.sp)
                MiuixText(
                    forText,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                MiuixText(
                    detail,
                    fontSize = 13.sp,
                    color = if (state == DICT_ERROR) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state == DICT_DOWNLOADING) LinearProgressIndicator(
                    progress = progress.takeIf { it in 0..100 }?.div(100f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (state == DICT_READY) {
                Spacer(Modifier.padding(horizontal = 4.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.lyrics_dictionary_delete),
                        tint = MiuixTheme.colorScheme.onBackground)
                }
            }
        }
    }
}
