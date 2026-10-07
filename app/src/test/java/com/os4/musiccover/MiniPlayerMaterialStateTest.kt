package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerMaterialStateTest {
    @Test fun equalNativeArrayValuesHaveTheSameSignature() {
        val a = arrayOf<Any>(intArrayOf(1, 2), floatArrayOf(0.2f, 0.8f))
        val b = arrayOf<Any>(intArrayOf(1, 2), floatArrayOf(0.2f, 0.8f))
        assertEquals(MiniPlayerMaterialState.snapshot(a), MiniPlayerMaterialState.snapshot(b))
    }

    @Test fun nativeArrayReuseCannotMutateThePreviousSignature() {
        val values = floatArrayOf(0.2f, 0.8f)
        val previous = MiniPlayerMaterialState.snapshot(values)
        values[0] = 0.5f
        assertNotEquals(previous, MiniPlayerMaterialState.snapshot(values))
        assertEquals(listOf(0.2f, 0.8f), previous)
    }

    @Test fun systemStyleChangesKeepTheCustomBlurLayer() {
        val frosted = MiniPlayerMaterialState.styleKey("MediaViewBlurEffect", true, 1)
        val soft = MiniPlayerMaterialState.styleKey("MediaViewGlassEffect", true, 2)
        assertNotEquals(frosted, soft)
        assertFalse(MiniPlayerMaterialState.replacesLayer(frosted, soft))
        assertFalse(MiniPlayerMaterialState.replacesLayer(soft, frosted))
    }

    @Test fun disablingCustomBlurRestoresTheNativeMaterialFamily() {
        val custom = MiniPlayerMaterialState.styleKey("MediaViewGlassEffect", true, 1)
        val native = MiniPlayerMaterialState.styleKey("MediaViewGlassEffect", false, 2)
        assertTrue(MiniPlayerMaterialState.replacesLayer(custom, native))
        assertTrue(MiniPlayerMaterialState.replacesLayer(native, custom))
        assertTrue(MiniPlayerMaterialState.replacesLayer(native,
            MiniPlayerMaterialState.styleKey("MediaViewBlurEffect", false, 3)))
    }

    @Test fun onlyAnotherEffectReplacesTheLayer() {
        assertFalse(MiniPlayerMaterialState.replacesLayer(null, "Blur#1"))
        assertFalse(MiniPlayerMaterialState.replacesLayer("Blur#1", "Blur#2"))
        assertTrue(MiniPlayerMaterialState.replacesLayer("Blur#2", "Glass#3"))
    }
}
