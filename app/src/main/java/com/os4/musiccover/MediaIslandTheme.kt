/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
 * Adapted from HyperLyrics-Enhanced, revision 912df58. */
package com.os4.musiccover

import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.lang.ref.WeakReference

/** Apply the themed native island resource and restore the OEM material when released. */
internal class MediaIslandTheme {
    private var content: WeakReference<View>? = null
    private var expanded: WeakReference<View>? = null
    private var appliedTheme = 0

    fun apply(player: View?, theme: Int) {
        if (theme == 0) { restore(); return }
        var parent = player?.parent as? View
        var target: View? = null
        var owner: View? = null
        while (parent != null) {
            if (parent.javaClass.name == "miui.systemui.dynamicisland.view.DynamicIslandExpandedView") target = parent
            if (target != null && generateSequence(parent.javaClass as Class<*>?) { it.superclass }.any {
                    it.name == "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
                }) { owner = parent; break }
            parent = parent.parent as? View
        }
        val view = target ?: return
        val host = owner ?: return
        if (expanded?.get() === view && appliedTheme == theme) return
        restore()
        val loader = view.javaClass.classLoader ?: return
        val context = themed(view.context, theme)
        val drawableId = Xp.findClass("miui.systemui.dynamicisland.R\$drawable", loader)
            .getDeclaredField("dynamic_island_liveupdate_background").getInt(null)
        val drawable = context.getDrawable(drawableId) ?: return
        // OS4's bionics material ignores classic blend colors; clear it before replacing the surface.
        runCatching { Xp.findClass("miui.systemui.util.MiBackgroundStyle", loader).getDeclaredMethod("clearBionicsMaterial", View::class.java).invoke(null, view) }
        val blur = Xp.findClass("miui.systemui.util.MiBlurCompat", loader)
        blur.getDeclaredMethod("setMiViewBlurModeCompat", View::class.java, Int::class.javaPrimitiveType).invoke(null, view, 0)
        blur.getDeclaredMethod("clearMiBackgroundBlendColorCompat", View::class.java).invoke(null, view)
        view.background = drawable
        content = WeakReference(host); expanded = WeakReference(view); appliedTheme = theme
    }

    fun restore() {
        val host = content?.get()
        val view = expanded?.get()
        if (host != null && view != null) runCatching { Xp.callMethod(host, "updateBackgroundBg", view, false) }
        content = null; expanded = null; appliedTheme = 0
    }

    private fun themed(context: Context, theme: Int): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
            if (theme == 1) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        return context.createConfigurationContext(configuration)
    }
}
