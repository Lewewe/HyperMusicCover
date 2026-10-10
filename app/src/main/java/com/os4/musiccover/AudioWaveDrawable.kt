package com.os4.musiccover

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Shared geometry and artwork gradient for all live music waves. */
internal class AudioWaveDrawable(private val contentScale: Float = 1f, private val horizontalOffsetFraction: Float = 0f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val values = FloatArray(5)
    private val drawMatrix = Matrix()
    private val transform = FloatArray(9)
    private var top = Color.WHITE
    private var bottom = Color.WHITE
    private var nativeWidth = 40
    private var nativeHeight = 40
    fun nativeSize(w: Int, h: Int) { nativeWidth = w.coerceAtLeast(1); nativeHeight = h.coerceAtLeast(1) }
    private var shaderHeight = -1f
    fun colors(a: Int, b: Int) {
        if (top == a && bottom == b) return
        top = a; bottom = b; shaderHeight = -1f; invalidateSelf()
    }
    fun colorReport() = "${Integer.toHexString(top)}/${Integer.toHexString(bottom)}"
    fun step(target: FloatArray, frameScale: Float = 1f) {
        val attack = if (frameScale == 1f) .6f else 1f - Math.pow(.4, frameScale.toDouble()).toFloat()
        val release = if (frameScale == 1f) .28f else 1f - Math.pow(.72, frameScale.toDouble()).toFloat()
        for (i in values.indices) values[i] += (target[i] - values[i]) * if (target[i] > values[i]) attack else release
        invalidateSelf()
    }
    fun reset() { values.fill(0f) }
    override fun draw(canvas: Canvas) {
        val w = bounds.width() * contentScale; val h = bounds.height() * contentScale
        if (w <= 0 || h <= 0) return
        if (shaderHeight != h) {
            paint.shader = LinearGradient(0f, bounds.exactCenterY() - h * .5f, 0f, bounds.exactCenterY() + h * .5f, top, bottom, Shader.TileMode.CLAMP)
            shaderHeight = h
        }
        var width = w / 12f
        var pitch = (w * .875f - width) / 4f
        val centerX = bounds.exactCenterX() + bounds.width() * horizontalOffsetFraction
        var x0 = centerX - 2f * pitch - width * .5f
        // Match the pixel grid after ImageView scales the drawable. Fractional edges
        // otherwise make one bar cover an extra pixel despite equal logical widths.
        canvas.getMatrix(drawMatrix)
        drawMatrix.getValues(transform)
        val scaleX = transform[Matrix.MSCALE_X]
        if (scaleX > 0f && transform[Matrix.MSKEW_X] == 0f && transform[Matrix.MSKEW_Y] == 0f) {
            width = kotlin.math.round(width * scaleX).coerceAtLeast(1f) / scaleX
            pitch = kotlin.math.round(pitch * scaleX).coerceAtLeast(width * scaleX + 1f) / scaleX
            val centered = centerX - 2f * pitch - width * .5f
            val tx = transform[Matrix.MTRANS_X]
            x0 = (kotlin.math.round(centered * scaleX + tx) - tx) / scaleX
        }
        for (i in values.indices) {
            val height = (h * (.09f + .85f * values[i])).coerceAtLeast(width)
            val x = x0 + i * pitch
            val y = bounds.exactCenterY() - height * .5f
            canvas.drawRoundRect(x, y, x + width, y + height, width * .5f, width * .5f, paint)
        }
    }
    override fun getIntrinsicWidth() = nativeWidth
    override fun getIntrinsicHeight() = nativeHeight
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
