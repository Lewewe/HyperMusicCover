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
    suspend fun configure(context: Context, data: Bundle): Bundle = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.call(uri, "configure", null, data) }.getOrNull() ?: Bundle()
    }
}
