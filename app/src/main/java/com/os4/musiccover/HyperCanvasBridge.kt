package com.os4.musiccover

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional extension IPC; no Canvas implementation is loaded into the host app. */
internal object HyperCanvasBridge {
    private val uri = Uri.parse("content://com.yzc26623.HyperCanvas.bridge")
    suspend fun query(context: Context): Bundle = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.call(uri, "query", null, null) }.getOrNull() ?: Bundle()
    }
    /** No provider or restart is needed on devices without the optional APK. */
    suspend fun synchronizeBlock(context: Context, blocked: Boolean): Bundle = withContext(Dispatchers.IO) {
        val current = query(context)
        if (!current.getBoolean("installed") || current.getBoolean("hostBlocked") == blocked)
            return@withContext current
        val result = runCatching {
            context.contentResolver.call(uri, "block", null, Bundle().apply { putBoolean("hostBlocked", blocked) })
        }.getOrNull() ?: return@withContext current
        if (result.getBoolean("hostBlocked") == blocked) ModuleBridge.restartCanvasScopes()
        result
    }
    suspend fun configure(context: Context, data: Bundle): Bundle = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.call(uri, "configure", null, data) }.getOrNull() ?: Bundle()
    }
}
