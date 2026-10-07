// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.math.roundToInt

/** Portable settings for the HyperChanger lockscreen mini player. */
object MiniPlayerConfig {
    const val ENABLED = "enabled"
    const val WIDTH = "widthDp"
    const val HEIGHT_RADIUS = "heightRadiusDp"
    const val ART_RADIUS = "artRadiusDp"
    const val BACKGROUND_BLUR = "backgroundBlur"
    const val BACKGROUND_BLUR_RADIUS = "backgroundBlurRadiusDp"
    const val BACKGROUND_BLUR_BRIGHTNESS = "backgroundBlurBrightness"

    @JvmStatic fun blurBrightness(value: Double): Float =
        if (value.isFinite()) value.toFloat().coerceIn(0f, 100f) else 80f

    /** Fade the dark tint into a light tint at high brightness without hiding the sampled blur. */
    @JvmStatic fun blurTint(value: Double): Int {
        val brightness = blurBrightness(value)
        val alpha = ((60f - brightness.coerceAtMost(80f) * 0.5f) * 2.55f).roundToInt()
        val light = ((brightness - 70f) / 30f).coerceIn(0f, 1f)
        val red = (31f + 217f * light).roundToInt()
        val green = (35f + 215f * light).roundToInt()
        val blue = (36f + 216f * light).roundToInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    @JvmStatic fun blurRadius(value: Double): Float =
        if (value.isFinite()) value.toFloat().coerceIn(0f, 80f) else 30f

    /** The row takes the room a switched-off torch or camera leaves (MiniPlayerRuntime.pillRest). */
    const val ADAPTIVE_WIDTH = "adaptiveWidth"

    private val defaults = linkedMapOf<String, Any>(
        ENABLED to false,
        WIDTH to 221f,
        HEIGHT_RADIUS to 27f,
        ART_RADIUS to 12f,
        ADAPTIVE_WIDTH to false,
        BACKGROUND_BLUR to false,
        BACKGROUND_BLUR_RADIUS to 30f,
        BACKGROUND_BLUR_BRIGHTNESS to 80f,
    )

    @JvmStatic fun defaultJson(): String = normalizedJson(null)

    /**
     * Preserve feature switches and bounded background blur controls. The three size keys
     * always use the fixed values above.
     *
     * The sizes were sliders and are not settings any more - the app has no rows for them - so a
     * config that still carries one is not obeyed, whoever wrote it. They stay in the JSON all
     * the same: the module's runtime, `MiniPlayerGeometry` and the state file all read this map
     * by key, and a missing key would be read as zero rather than as the default wherever a
     * caller used `getDouble` directly.
     *
     * The values themselves were never arbitrary: 221dp is what fits between the two shortcut
     * discs on this screen, and both of the others are held to the pill's own height. See
     * MiniPlayerRuntime, which clamps them again against the room it actually has.
     *
     * [ART_RADIUS] is not read at all: the picture is the small island's circle now, its share of
     * the height, and a corner setting has nothing left to say (2026-09-28). The key stays in the
     * JSON for the reason above.
     */
    @JvmStatic fun normalizedJson(raw: String?): String {
        val input = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val out = JSONObject()
        defaults.forEach { (key, fallback) ->
            out.put(key, if (key == ENABLED || key == ADAPTIVE_WIDTH || key == BACKGROUND_BLUR) {
                runCatching { input.getBoolean(key) }.getOrDefault(fallback)
            } else if (key == BACKGROUND_BLUR_RADIUS) {
                blurRadius(input.optDouble(key, 30.0))
            } else if (key == BACKGROUND_BLUR_BRIGHTNESS) {
                blurBrightness(input.optDouble(key, 80.0))
            } else {
                fallback
            })
        }
        return out.toString()
    }

    @JvmStatic fun fromPreferences(prefs: SharedPreferences): String =
        normalizedJson(prefs.getString("config", null))

    @JvmStatic fun apply(prefs: SharedPreferences, raw: String?): String {
        val normalized = normalizedJson(raw)
        prefs.edit().putString("config", normalized).apply()
        return normalized
    }

    /** The original height setting is a shortcut radius, so the visible pill uses its diameter. */
    @JvmStatic fun visibleHeightDp(raw: String?): Float =
        MiniPlayerGeometry.heightDp(JSONObject(normalizedJson(raw))
            .getDouble(HEIGHT_RADIUS).toFloat())
}
