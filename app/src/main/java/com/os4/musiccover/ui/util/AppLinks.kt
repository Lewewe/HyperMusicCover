/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
package com.os4.musiccover.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens a Telegram group invite link, returning false when no Telegram client is installed.
 *
 * tg://resolve?domain= is answered by every client, forks and third-party ones included, so no
 * package is named; the domain is the last segment of the t.me link the page displays.
 */
fun Context.openTelegramGroup(url: String): Boolean {
    val domain = url.trimEnd('/').substringAfterLast('/')
    return startIfHandled(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$domain")))
}

private fun Context.startIfHandled(intent: Intent): Boolean {
    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false
    startActivity(intent)
    return true
}
