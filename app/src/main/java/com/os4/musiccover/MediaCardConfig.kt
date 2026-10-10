package com.os4.musiccover

import org.json.JSONObject

/** Two independent card profiles; zero-valued appearance choices retain the native style. */
object MediaCardConfig {
    val scopes = listOf("notification", "island")
    val defaults = linkedMapOf("cover" to 0, "hideSource" to 0, "hideDevice" to 0,
        "background" to 0, "theme" to 0, "flow" to 0, "pauseRestore" to 1,
        "animate" to 1, "blur" to 8, "invert" to 1, "tone" to 1)

    fun limit(name: String, value: Int): Int = when (name) {
        "cover" -> value.coerceIn(0, 3)
        "background" -> value.coerceIn(0, 5)
        "theme" -> value.coerceIn(0, 2)
        "flow" -> value.coerceIn(0, 4)
        "blur" -> value.coerceIn(1, 20)
        else -> value.coerceIn(0, 1)
    }

    fun parse(json: String?): Map<String, Int> {
        val obj = runCatching { JSONObject(json ?: "{}") }.getOrNull()
        return buildMap {
            for (scope in scopes) for ((name, default) in defaults) {
                val key = "$scope.$name"
                put(key, limit(name, obj?.optInt(key, default) ?: default))
            }
        }
    }

    fun encode(values: Map<String, Int>): String = JSONObject().also { out ->
        for (scope in scopes) for ((name, default) in defaults) {
            val key = "$scope.$name"
            out.put(key, limit(name, values[key] ?: default))
        }
    }.toString()

    fun normalized(json: String?): String = encode(parse(json))
    fun customBackground(style: Int, flow: Int): Boolean = style > 0 || flow in 1..3
    fun animate(playing: Boolean, pauseRestore: Boolean, shown: Boolean, screenOn: Boolean): Boolean =
        shown && screenOn && (playing || !pauseRestore)
}

internal object MediaCardLog {
    fun i(tag: String, message: String) = Xp.d("$tag $message")
    fun w(tag: String, message: String) = Xp.w("$tag $message")
}
