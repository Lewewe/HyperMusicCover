package com.os4.musiccover

/**
 * Values to compare one card dressing against the next by. Arrays are copied into lists: the
 * OEM hands the same array to the next dressing, changed, and the previous recipe held by
 * reference would change with it and always read as equal. From TakeKazeX's PR #15.
 */
internal object MiniPlayerMaterialState {
    fun snapshot(value: Any?): Any? = when (value) {
        is IntArray -> value.toList()
        is FloatArray -> value.toList()
        is LongArray -> value.toList()
        is DoubleArray -> value.toList()
        is BooleanArray -> value.toList()
        is ByteArray -> value.toList()
        is ShortArray -> value.toList()
        is CharArray -> value.toList()
        is Array<*> -> value.map(::snapshot)
        is List<*> -> value.map(::snapshot)
        else -> value
    }

    /** Custom blur owns its layer; changing the OEM glass style only triggers a redress. */
    fun styleKey(effect: String, customBlur: Boolean, generation: Int): String =
        "${if (customBlur) "customBlur" else "$effect:native"}#$generation"

    /** Another effect, not only new values for the same one: keys are "effect#generation". */
    fun replacesLayer(previous: String?, next: String): Boolean =
        previous != null && previous.substringBefore('#') != next.substringBefore('#')
}
