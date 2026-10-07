package com.os4.musiccover.updater

import android.content.Context
import android.content.pm.PackageManager
import com.os4.musiccover.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest


/**
 * Finding out whether GitHub has a newer release than the one we are running.
 *
 * Only stable releases are considered. The project publishes two lines from the same tag-driven
 * workflow - `vMAJOR.MINOR.PATCH` and `nightly-YYYYMMDD`. The stable line's versionCode is
 * `(major*10000 + minor*100 + patch) * 1000`, and a nightly sits in the thousand above the release
 * it follows, named after it. A nightly is for whoever went and got one, not something to push
 * on everyone, so both the tag shape and the `prerelease` flag are checked.
 *
 * Nothing in here throws. A check that could not be completed and a check that found nothing are
 * the same answer - no update to offer - which is how [com.os4.musiccover.ModuleBridge] already
 * treats a question the module did not answer.
 */

private const val REPO = "zyl6932/HyperMusicCover"
private const val RELEASES_LIST = "https://api.github.com/repos/$REPO/releases"

/** The package this app ships as. A fork changes it, and a fork must never self-update. */
private const val OFFICIAL_APPLICATION_ID = "com.github.zyl6932.HyperMusicCover"

/** `v1.2.3`, and only that: three numeric parts, each within the width the versionCode packs. */
private val STABLE_TAG = Regex("^v(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")

/** The leading triple of a local version name, nightly `-nightly.<date>.<sha>` suffixes and all. */
private val LEADING_TRIPLE = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})")

/** One release, as much of it as this app uses. GitHub's release object carries dozens more. */
internal data class GithubRelease(
    val tagName: String,
    val prerelease: Boolean,
    val body: String,
    val htmlUrl: String,
    val assets: List<GithubAsset>,
)

internal data class GithubAsset(
    val name: String,
    val url: String,
)

/**
 * The release list, read with the platform's org.json.
 *
 * [str] and not `optString`: a release with no notes has `"body": null`, and `optString` turns a
 * JSON null into the four-letter string "null", which would then be shown as the changelog.
 */
internal fun parseReleases(text: String): List<GithubRelease> {
    val releases = JSONArray(text)
    return (0 until releases.length()).map { i ->
        val release = releases.getJSONObject(i)
        val assets = release.optJSONArray("assets") ?: JSONArray()
        GithubRelease(
            tagName = release.str("tag_name"),
            prerelease = release.optBoolean("prerelease"),
            body = release.str("body"),
            htmlUrl = release.str("html_url"),
            assets = (0 until assets.length()).map { j ->
                val asset = assets.getJSONObject(j)
                GithubAsset(asset.str("name"), asset.str("browser_download_url"))
            },
        )
    }
}

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/**
 * One HTTP exchange that another thread can abort.
 *
 * [cancel] disconnects the connection, which makes a read blocked on the socket throw at once;
 * one cancelled before [open] got as far as creating the connection is disconnected as soon as it
 * exists.
 *
 * [HttpURLConnection] has no call timeout, only a per-read one, so a server that drips a byte
 * every few seconds would hold a download open forever. [deadline] is the cap the reader checks
 * between reads instead.
 */
internal class HttpCall(private val url: String, private val connectTimeoutMs: Int) {

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var cancelled = false

    val deadline: Long = System.nanoTime() + CALL_TIMEOUT_NS

    /** Connects lazily, as [HttpURLConnection] does; the caller disconnects it when done. */
    fun open(accept: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = READ_TIMEOUT_MS
        if (accept != null) c.setRequestProperty("Accept", accept)
        connection = c
        if (cancelled) c.disconnect()
        return c
    }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    private companion object {
        const val READ_TIMEOUT_MS = 30_000
        const val CALL_TIMEOUT_NS = 10L * 60 * 1_000_000_000
    }
}

/** A newer stable release, already checked against the running build. */
data class UpdateInfo(
    /** The tag without its `v`, which is what CI stamps as this build's versionName. */
    val versionName: String,
    val versionCode: Int,
    /**
     * The changelog accumulated across every stable release newer than the running build, each
     * headed by its version. Markdown, and rendered as such - see
     * `ui/component/markdown/MarkdownContent.kt`.
     */
    val notes: String,
    /** The release asset, before any mirror is applied. */
    val apkUrl: String,
    val releaseUrl: String,
)

object UpdateApi {

    /**
     * Is this build allowed to update itself at all?
     *
     * The package name keeps a fork from being offered the upstream APK, which it could not
     * install anyway - the signature would not match.
     *
     * It used to ask `!BuildConfig.DEBUG` as well, and that was the wrong question twice over.
     * It is the RUNNING build that was being judged, when what matters is whether the APK that
     * comes back is the project's own; and it left every development build unable to see an
     * update at all, which is exactly the build an update is being tested on. The signature
     * question moved to [isOurs], where the file it is about actually exists.
     */
    fun enabled(context: Context): Boolean =
        context.packageName == OFFICIAL_APPLICATION_ID

    /**
     * The certificate the project publishes releases under.
     *
     * Compared by SHA-256 of the certificate, not by its subject: a subject is free-form text
     * anybody can put in a self-signed certificate, and this is the one value that cannot be
     * forged without the private key. Taken from the published `v0.0.5` APK, which is signed
     * with this and nothing else.
     */
    private const val RELEASE_CERT_SHA256 =
        "f5b62166f821734dd854c94a254223b2c49252e3681ee3d31238d1814ad5e6d5"

    /**
     * Is [file] an APK this project is willing to install?
     *
     * EITHER certificate is enough, and that is the point rather than a leniency. The two cannot
     * both match: a development build is signed with the developer's debug key and a release
     * never is, so demanding an exact match with the running build is precisely what stopped a
     * debug install from ever seeing an update. What is being asked here is only "is this ours",
     * and both of those keys answer yes.
     *
     * Whether the system will then accept the swap across two different keys is the installer's
     * business and not this app's: a stock device refuses it, and a device with the signature
     * check patched out - CorePatch and its kin - goes through. Refusing here would take that
     * decision away from the people who have made it possible.
     */
    fun isOurs(context: Context, file: File): Boolean {
        val signer = signerOf(context, file) ?: return false
        return signer == RELEASE_CERT_SHA256 || signer == ownSigner(context)
    }

    /** SHA-256 of the certificate [file] is signed with, lowercase hex, or null if unreadable. */
    private fun signerOf(context: Context, file: File): String? = try {
        context.packageManager
            .getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            ?.signingInfo?.apkContentsSigners?.firstOrNull()
            ?.toByteArray()?.let(::sha256Hex)
    } catch (_: Exception) {
        null
    }

    /** The same, for the app that is running. */
    private fun ownSigner(context: Context): String? = try {
        context.packageManager
            .getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            .signingInfo?.apkContentsSigners?.firstOrNull()
            ?.toByteArray()?.let(::sha256Hex)
    } catch (_: Exception) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The newest stable release, or null when there is nothing newer or nothing was answered. */
    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = HttpCall(RELEASES_LIST, 15_000).open("application/vnd.github+json")
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                toUpdateInfo(parseReleases(text))
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A not-yet-executed call for the APK body.
     *
     * Returned instead of opened so the caller can hold the [HttpCall] and cancel it mid-download.
     *
     * A much shorter connect timeout than the release list's 15 seconds. The download
     * hands over a list of candidate hosts and takes the first that answers, and the one that does
     * not is a host that never answers at all - measured on the project's test device, the direct
     * GitHub URL spends the full 15 seconds of a healthy connect timeout before failing. Paying
     * that on every update to discover the same thing again is the whole of what made the download
     * feel slow. Six seconds is still far longer than any working connection needs.
     */
    internal fun newApkCall(url: String): HttpCall = HttpCall(url, 6_000)

    /**
     * The release worth offering, or null.
     *
     * The changelog is every stable release newer than the running build, so tapping "update
     * available" reads the whole path from here to the newest - not just the newest release's own
     * notes. A release carrying no APK asset still counts for the changelog, but the newest one
     * must ship an APK: the CI contract guarantees `HyperMusicCover-<tag>.apk`, so its absence
     * means something is wrong upstream, and a "new version available" notice whose button cannot
     * work is worse than silence.
     */
    private fun toUpdateInfo(releases: List<GithubRelease>): UpdateInfo? {
        // Newest first, stable only, newer than the running build. `parseRelease` drops
        // pre-releases and tags that are not a dotted triple; `isNewer` drops everything at or
        // below the build we are running.
        val newer = releases
            .mapNotNull(::parseRelease)
            .filter { isNewer(it.versionName, it.versionCode) }
        val latest = newer.firstOrNull() ?: return null
        val apkUrl = latest.apkUrl ?: return null
        return UpdateInfo(
            versionName = latest.versionName,
            versionCode = latest.versionCode,
            notes = accumulatedNotes(newer),
            apkUrl = apkUrl,
            releaseUrl = latest.releaseUrl,
        )
    }

    /** One stable release, stripped of the fields this app does not use. */
    private data class Release(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String?,
        val releaseUrl: String,
    )

    private fun parseRelease(release: GithubRelease): Release? {
        if (release.prerelease) return null
        val match = STABLE_TAG.matchEntire(release.tagName) ?: return null
        val (major, minor, patch) = match.destructured
        val apk = release.assets.firstOrNull { it.name == "HyperMusicCover-${release.tagName}.apk" }
            ?: release.assets.firstOrNull {
                it.name.startsWith("HyperMusicCover-v") && it.name.endsWith(".apk")
            }
        return Release(
            versionName = release.tagName.removePrefix("v"),
            versionCode = (major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()) * 1000,
            notes = release.body.trim(),
            apkUrl = apk?.url,
            releaseUrl = release.htmlUrl,
        )
    }

    /**
     * Every version's notes stacked newest-first, each under its own `# vX.Y.Z` heading and cut at
     * the auto-generated `**Full Changelog**` link, which reads as noise once several are shown.
     */
    private fun accumulatedNotes(releases: List<Release>): String =
        releases.joinToString("\n\n---\n\n") { release ->
            buildString {
                append("# v").append(release.versionName).append("\n\n")
                append(stripChangelogLink(release.notes))
            }
        }

    private fun stripChangelogLink(notes: String): String {
        val index = notes.indexOf("**Full Changelog**")
        return if (index >= 0) notes.substring(0, index).trim() else notes
    }

    /**
     * Is a release newer than the build we are running?
     *
     * The dotted triple is compared part by part and preferred, because the packed versionCode
     * only orders correctly while minor and patch each stay under 100. `v1.2.345` packs to 10545
     * and would beat the later `v1.3.0` at 10300 - the release workflow accepts any numeric part,
     * so nothing upstream prevents that tag. The packed comparison stays as the fallback for a
     * local version name that does not parse at all.
     */
    private fun isNewer(versionName: String, versionCode: Int): Boolean {
        val local = LEADING_TRIPLE.find(BuildConfig.VERSION_NAME)?.value
        val remote = LEADING_TRIPLE.find(versionName)?.value
        if (local != null && remote != null) {
            val l = local.split('.').map(String::toInt)
            val r = remote.split('.').map(String::toInt)
            for (i in 0..2) if (r[i] != l[i]) return r[i] > l[i]
            return false
        }
        return versionCode > BuildConfig.VERSION_CODE
    }

    /**
     * Where to try fetching the APK, in order.
     *
     * The release page itself is on `github.com`, and that host is not reachable on every network
     * this app runs on - measured on the project's own test device, a direct connection to
     * `github.com:443` aborts after the 15 second connect timeout while a proxy in front of the
     * same URL returns all 2.9MB in under two seconds. So the direct URL is tried first and the
     * proxy is the fallback, with nothing for the user to configure: a download source is not a
     * preference anyone should have to know about to get an update.
     *
     * The proxy is a third party and is the one part of this that could go away without notice.
     * If it does, the direct URL is still first in this list, so nothing regresses for the
     * networks where that works.
     */
    fun apkCandidates(browserUrl: String): List<String> =
        listOf(browserUrl, PROXY_PREFIX + browserUrl)

    /**
     * [apkCandidates], but starting from whichever one worked last time.
     *
     * Which of them is reachable is a property of the network this device is on, and that does not
     * change between one update and the next. Trying the other one first every time would mean
     * paying its connect timeout on every update to relearn what the last one already knew.
     */
    fun orderedCandidates(context: Context, browserUrl: String): List<String> {
        val candidates = apkCandidates(browserUrl)
        val last = UpdateSource.last(context)
        if (last !in candidates.indices) return candidates
        return listOf(candidates[last]) + candidates.filterIndexed { i, _ -> i != last }
    }

    /** Records which candidate [orderedCandidates] returned, so the next call starts there. */
    fun rememberSource(context: Context, browserUrl: String, url: String) {
        UpdateSource.set(context, apkCandidates(browserUrl).indexOf(url))
    }

    /** ghproxy's convention: the whole GitHub URL is appended to the proxy's own. */
    private const val PROXY_PREFIX = "https://gh.sevencdn.com/"
}

/**
 * The download host that answered last time.
 *
 * No UI: this is not a preference anyone chooses, it is a fact about the network that the app
 * works out once and reuses. Kept out of [com.os4.musiccover.AppSettings] for the same reason the
 * old mirror setting was - it describes this device's connection, not the user.
 */
private object UpdateSource {

    private const val PREFS_NAME = "update_source"
    private const val KEY_INDEX = "candidate_index"

    fun last(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_INDEX, -1)

    fun set(context: Context, index: Int) {
        if (index < 0) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INDEX, index)
            .apply()
    }
}
