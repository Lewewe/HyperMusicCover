package com.os4.musiccover.ui.screen.donate

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** Afdian support poster with an option to save the original image to the album. */
private data class DonateChannel(
    val name: String,
    val image: Int,
    val fileName: String,
    val mimeType: String,
)

@Composable
fun DonatePageContent(
    onBack: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val afdian = stringResource(R.string.donate_afdian)
    val channel = remember(afdian) {
        DonateChannel(afdian, R.drawable.donate_afdian,
            "HyperMusicCover-Enhanced-Afdian.jpg", "image/jpeg")
    }

    // null while nothing has been saved; true/false is the outcome of the last attempt, and it is
    // the button's own label that reports it. A Toast is not a channel this app has - they are
    // dropped whenever notifications are off, which is this app's default.
    var saveResult by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(saveResult) {
        if (saveResult != null) {
            delay(2000)
            saveResult = null
        }
    }

    PageScaffold(
        title = stringResource(R.string.donate_title),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    MiuixText(
                        text = stringResource(R.string.donate_thanks_title),
                        fontSize = 17.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    MiuixText(
                        text = stringResource(R.string.donate_thanks_summary),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            TabRow(
                tabs = listOf(channel.name),
                selectedTabIndex = 0,
                onTabSelected = {},
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
            )
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .fillMaxWidth()
                            .squircleClip(cornerRadius = 16.dp),
                        painter = painterResource(channel.image),
                        contentDescription = channel.name,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MiuixText(
                            text = stringResource(R.string.donate_scan_hint),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.donate_open_afdian),
                        onClick = { uriHandler.openUri("https://afdian.com/a/yzc26623") },
                    )
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = when (saveResult) {
                            true -> stringResource(R.string.donate_saved)
                            false -> stringResource(R.string.donate_save_failed)
                            null -> stringResource(R.string.donate_save)
                        },
                        onClick = {
                            scope.launch {
                                saveResult = withContext(Dispatchers.IO) {
                                    saveToAlbum(context, channel)
                                }
                            }
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * Copies the poster into the user's album.
 *
 * Copy the original JPEG without re-encoding so the saved QR code retains its image quality.
 *
 * No permission is asked for. Since Android 10 an app owns what it inserts into MediaStore, and
 * this app's minimum is well past that.
 */
private fun saveToAlbum(context: Context, channel: DonateChannel): Boolean = try {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, channel.fileName)
        put(MediaStore.Images.Media.MIME_TYPE, channel.mimeType)
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/HyperMusicCover-Enhanced",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
    if (uri == null) {
        false
    } else {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw IllegalStateException("no output stream")
            context.resources.openRawResource(channel.image).use { it.copyTo(out) }
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        true
    }
} catch (_: Throwable) {
    false
}
