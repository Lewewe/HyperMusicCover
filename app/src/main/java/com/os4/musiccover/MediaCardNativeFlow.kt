/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
 * Adapted from HyperLyrics-Enhanced, revision 912df58. */
package com.os4.musiccover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class MediaAmbientFlowPalette(val mainColor: Int, val colors: IntArray)

internal class NativeMusicBgApi private constructor(
    private val viewClass: Class<*>,
    private val constructor: java.lang.reflect.Constructor<*>,
    private val setGradientColorMethod: Method,
    private val startMethod: Method,
    private val resumeMethod: Method,
    private val pauseMethod: Method,
    private val getMainColorMethod: Method,
    private val getPaletteColorMethod: Method,
    private val drawableToBitmapMethod: Method,
    private val frameLoopShaderField: Field?,
    private val frameLoopEnableMethod: Method?,
    private val frameLoopEnableValue: Any?
) {
    fun createView(context: Context): View = constructor.newInstance(context) as View

    fun accepts(view: View): Boolean = viewClass.isInstance(view)

    fun setGradientColor(view: View, mainColor: Int, colors: IntArray) {
        setGradientColorMethod.invoke(view, mainColor, colors)
    }

    fun start(view: View) {
        startMethod.invoke(view)
    }

    fun resume(view: View) {
        resumeMethod.invoke(view)
    }

    fun pause(view: View) {
        pauseMethod.invoke(view)
    }

    
    fun ensureFrameLoopEnabled(view: View): Boolean {
        val shaderField = frameLoopShaderField ?: return false
        val enableMethod = frameLoopEnableMethod ?: return false
        val enableValue = frameLoopEnableValue ?: return false
        if (!accepts(view)) return false
        return runCatching {
            val shader = shaderField.get(view) ?: return false
            enableMethod.invoke(shader, enableValue)
            true
        }.getOrDefault(false)
    }

    fun extractSystemPalette(drawable: Drawable): MediaAmbientFlowPalette {
        val bitmap = if (drawableToBitmapMethod.parameterCount == 1) {
            drawableToBitmapMethod.invoke(null, drawable) as Bitmap
        } else {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            drawableToBitmapMethod.invoke(null, drawable, width, height) as Bitmap
        }
        val mainColor = getMainColorMethod.invoke(null, bitmap) as Int
        return createPalette(mainColor)
    }

    fun createPalette(mainColor: Int): MediaAmbientFlowPalette {
        val colors = intArrayOf(
            getPaletteColor(mainColor, "primary", 12),
            getPaletteColor(mainColor, "primary", 10),
            getPaletteColor(mainColor, "tertiary", 12)
        )
        return MediaAmbientFlowPalette(mainColor, colors)
    }

    private fun getPaletteColor(mainColor: Int, role: String, tone: Int): Int {
        return getPaletteColorMethod.invoke(null, mainColor, role, tone) as Int
    }

    companion object {
        fun create(classLoader: ClassLoader): NativeMusicBgApi {
            val viewClass = classLoader.loadClass("com.mi.widget.view.MusicBgView")
            val constructor = viewClass.getDeclaredConstructor(Context::class.java).apply {
                isAccessible = true
            }
            val setGradientColor = viewClass.getDeclaredMethod(
                "setGradientColor",
                Int::class.javaPrimitiveType,
                IntArray::class.java
            ).apply { isAccessible = true }
            val start = viewClass.getDeclaredMethod("start").apply { isAccessible = true }
            val resume = viewClass.getDeclaredMethod("resume").apply { isAccessible = true }
            val pause = viewClass.getDeclaredMethod("pause").apply { isAccessible = true }

            val drawableUtils = classLoader.loadClass("com.miui.utils.DrawableUtils")
            val drawableToBitmap = drawableUtils.declaredMethods
                .firstOrNull { method ->
                    method.name == "drawable2Bitmap" &&
                        method.returnType == Bitmap::class.java &&
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Drawable::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                            )
                        )
                }
                ?: drawableUtils.declaredMethods.firstOrNull { method ->
                    method.name == "drawable2Bitmap" &&
                        method.returnType == Bitmap::class.java &&
                        method.parameterTypes.contentEquals(arrayOf(Drawable::class.java))
                }
                ?.apply { isAccessible = true }
                ?: error("No compatible DrawableUtils.drawable2Bitmap method")

            val miPalette = classLoader.loadClass("miuix.mipalette.MiPalette")
            miPalette.declaredMethods.firstOrNull { method ->
                method.name == "init" && method.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(null)
            val getMainColor = miPalette.getDeclaredMethod(
                "getMainColorHCT",
                Bitmap::class.java
            ).apply { isAccessible = true }
            val getPaletteColor = miPalette.getDeclaredMethod(
                "getPaletteColor",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val frameLoopKick = resolveFrameLoopKick(viewClass)
            if (frameLoopKick == null) {
                MediaCardLog.w("MediaNativeFlow", "Native frame-loop recovery unavailable; using pause/resume")
            }

            return NativeMusicBgApi(
                viewClass = viewClass,
                constructor = constructor,
                setGradientColorMethod = setGradientColor,
                startMethod = start,
                resumeMethod = resume,
                pauseMethod = pause,
                getMainColorMethod = getMainColor,
                getPaletteColorMethod = getPaletteColor,
                drawableToBitmapMethod = drawableToBitmap,
                frameLoopShaderField = frameLoopKick?.first,
                frameLoopEnableMethod = frameLoopKick?.second,
                frameLoopEnableValue = frameLoopKick?.third
            )
        }

        
        private fun resolveFrameLoopKick(
            viewClass: Class<*>
        ): Triple<Field, Method, Any>? {
            return runCatching {
                val shaderField = viewClass.declaredFields
                    .firstOrNull { it.name == "mShader" }
                    ?: return null
                shaderField.isAccessible = true
                val enableMethod = shaderField.type.declaredMethods.firstOrNull { method ->
                    method.name.startsWith("setFrameLoopStrategy") &&
                        method.parameterCount == 1
                } ?: return null
                enableMethod.isAccessible = true
                val strategyType = enableMethod.parameterTypes[0]
                val enableValue = runCatching {
                    strategyType.getField("ENABLE").get(null)
                }.getOrNull() ?: return null
                Triple(shaderField, enableMethod, enableValue)
            }.getOrNull()
        }
    }
}

