package com.os4.musiccover.updater

import org.junit.Assert.*
import org.junit.Test

class ForkReleaseAssetTest {
    private fun release(vararg names: String) = GithubRelease(
        tagName = "v0.1.0", prerelease = false, body = "",
        htmlUrl = "https://github.com/Lewewe/HyperMusicCover-Enhanced/releases/tag/v0.1.0",
        assets = names.map { GithubAsset(it, "https://example.com/$it") },
    )

    @Test fun selectsPublishedEnhancedAssetEvenWhenAnUpstreamApkComesFirst() {
        val selected = UpdateApi.releaseApk(release(
            "HyperMusicCover-v0.1.0.apk", "HyperMusicCover-Enhanced-v0.1.0.apk",
        ))
        assertEquals("HyperMusicCover-Enhanced-v0.1.0.apk", selected?.name)
    }

    @Test fun upstreamAssetsAndNonApkAttachmentsDoNotBecomeForkUpdates() {
        assertNull(UpdateApi.releaseApk(release("HyperMusicCover-v0.1.0.apk")))
        assertNull(UpdateApi.releaseApk(release("HyperMusicCover-Enhanced-v0.1.0.apk.sha256")))
        assertNull(UpdateApi.releaseApk(release()))
    }

    @Test fun matchingTagTakesPrecedenceOverFallbackAssets() {
        val selected = UpdateApi.releaseApk(release(
            "HyperMusicCover-Enhanced-v0.0.9.apk", "HyperMusicCover-Enhanced-v0.1.0.apk",
        ))
        assertEquals("HyperMusicCover-Enhanced-v0.1.0.apk", selected?.name)
    }
}
