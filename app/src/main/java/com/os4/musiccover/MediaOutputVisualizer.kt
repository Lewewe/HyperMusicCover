package com.os4.musiccover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Replaces only the output icon; native layout and original click listeners stay intact. */
internal object MediaOutputVisualizer {
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("MCMediaAudio", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper) }
    private val states = WeakHashMap<ImageView, Output>()
    private val spectrum = IslandSpectrum()
    private val levels = FloatArray(5)
    private var enabled = false
    private var awake = true
    @Volatile private var aod = false
    private var settleUntil = 0L
    private var nextAudioSample = 0L
    private var receiverInstalled = false
    private var watching = false
    private var changeQueued = false
    private var frameScheduled = false
    private var pending = false
    private var lease: AudioSpectrumCapture.Lease? = null
    @Volatile private var generation = 0L
    @Volatile private var samples = 0L
    private var lastSample = 0L
    private var lastSampleAt = 0L
    private var retryAt = 0L

    fun configure(value: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { configure(value) }; return }
        enabled = value
        main.removeCallbacks(watch); watching = false
        if (!enabled) { stopCapture(); states.values.forEach { it.restore() } }
        else { refresh(); scheduleWatch() }
    }

    fun track(icon: ImageView, button: View, album: ImageView, lockScreenCapable: Boolean, selected: () -> Boolean, playing: () -> Boolean) {
        val state = states.getOrPut(icon) {
            installReceiver(icon.context)
            Output(icon, button, album, lockScreenCapable).also {
                icon.addOnAttachStateChangeListener(it.attach)
                album.addOnLayoutChangeListener(it.layout)
                it.installDrawGuard()
            }
        }
        state.selected = selected
        state.playing = playing
        refresh(); scheduleWatch()
    }

    fun onMediaChanged() {
        if (!enabled || changeQueued) return
        changeQueued = true
        main.post { changeQueued = false; refresh(); scheduleWatch() }
    }

    fun detach(icon: ImageView) {
        states.remove(icon)?.let { it.restore(); icon.removeOnAttachStateChangeListener(it.attach); it.removeLayoutListener(); it.removeDrawGuard() }
        refresh()
    }

    private fun installReceiver(context: Context) {
        if (receiverInstalled) return
        val app = context.applicationContext ?: context
        awake = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        aod = !awake && Main.fullAodOn()
        settleUntil = SystemClock.uptimeMillis() + 2500L
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    awake = intent.action != Intent.ACTION_SCREEN_OFF
                    aod = !awake && Main.fullAodOn()
                    settleUntil = SystemClock.uptimeMillis() + 2500L
                    main.removeCallbacks(watch); watching = false
                    refresh(); scheduleWatch()
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, Context.RECEIVER_NOT_EXPORTED)
            receiverInstalled = true
        }.onFailure { Xp.w("Media audio screen receiver failed: $it") }
    }

    private val watch = object : Runnable {
        override fun run() { watching = false; refresh(); scheduleWatch() }
    }
    private fun scheduleWatch() {
        if (!enabled || watching || states.isEmpty() || (!awake && (!aod || SystemClock.uptimeMillis() >= settleUntil))) return
        watching = true; main.postDelayed(watch, 400L)
    }

    private fun refresh() {
        val visible = states.values.filter { enabled && displayable(it) }
        states.values.forEach { it.reconcileOwnership() }
        val active = visible.any { it.playing() }
        if (!active) stopCapture()
        val colors = IslandAudioVisualizer.nativeColors()
        visible.forEach {
            colors?.let { colors -> it.colors(colors.first, colors.second) }
            it.present()
            if (lease == null || !it.playing()) it.render(levelsZero)
        }
        if (!active || lease != null || pending || SystemClock.uptimeMillis() < retryAt) {
            scheduleFrame(); return
        }
        pending = true
        val token = ++generation
        worker.post {
            if (generation != token) return@post
            try {
                val created = AudioSpectrumCapture.acquire(true) { _, fft, rate ->
                    if (generation == token) {
                        val now = SystemClock.uptimeMillis()
                        if (!aod || now >= nextAudioSample) {
                            nextAudioSample = now + 200L
                            spectrum.sample(fft, rate); samples++
                        }
                    }
                }
                main.post deliver@{
                    if (generation != token) { worker.post { created.release() }; return@deliver }
                    pending = false; lease = created
                    lastSample = samples; lastSampleAt = SystemClock.uptimeMillis()
                    refresh(); scheduleFrame()
                }
            } catch (t: Throwable) {
                main.post {
                    if (generation == token) {
                        pending = false; retryAt = SystemClock.uptimeMillis() + 10000L
                        states.values.forEach { it.render(levelsZero) }
                        Xp.w("Media output audio unavailable: $t")
                    }
                }
            }
        }
    }

    private fun stopCapture() {
        if (lease == null && !pending) return
        generation++; pending = false
        val old = lease; lease = null
        if (old != null) worker.post { old.release() }
        main.removeCallbacks(frame); frameScheduled = false
        spectrum.reset()
    }

    private val frame = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (!enabled || (!awake && !aod) || lease == null) return
            val visible = states.values.filter { displayable(it) }
            if (visible.none { it.playing() }) { refresh(); return }
            val now = SystemClock.uptimeMillis()
            if (lastSample != samples) { lastSample = samples; lastSampleAt = now }
            else if (now - lastSampleAt > 3000L) {
                stopCapture(); retryAt = now + 10000L
                states.values.forEach { it.render(levelsZero) }; return
            }
            spectrum.copyTo(levels)
            val aodColors = if (aod) IslandAudioVisualizer.nativeColors() else null
            states.values.forEach {
                aodColors?.let { colors -> it.colors(colors.first, colors.second) }
                if (it in visible) {
                    it.present()
                    it.render(if (it.playing()) levels else levelsZero)
                } else it.reconcileOwnership()
            }
            scheduleFrame()
        }
    }
    private val levelsZero = FloatArray(5)
    private fun scheduleFrame() {
        if (!enabled || (!awake && !aod) || lease == null || frameScheduled) return
        frameScheduled = true; main.postDelayed(frame, if (aod) 200L else 33L)
    }

    private fun displayable(state: Output) = if (awake) state.visible() else aod && state.visibleOnAod()

    @JvmStatic fun describe(): String = "outputWave enabled=$enabled awake=$awake aod=$aod fps=${if (aod) 5 else 30} views=${states.size} visible=${states.values.count { displayable(it) }} replaced=${states.values.count { it.replaced }} aodOverlays=${states.values.count { it.hasAodOverlay() }} capturing=${lease != null} samples=$samples"

    private class Output(icon: ImageView, button: View, album: ImageView, private val lockScreenCapable: Boolean) {
        private val icon = WeakReference(icon)
        private val button = WeakReference(button)
        private val album = WeakReference(album)
        val bars = AudioWaveDrawable(.72f, if (lockScreenCapable) .125f else 0f)
        private val aodBars = AudioWaveDrawable(.72f)
        private val aodBlank = ColorDrawable(Color.TRANSPARENT)
        private var aodHost: WeakReference<ViewGroup>? = null
        private var aodButtonAlpha: Float? = null
        private var awakeButtonAlpha = button.alpha.takeIf { it > 0f } ?: 1f
        private val albumRect = Rect()
        private val rect = Rect()
        var selected: () -> Boolean = { false }
        var playing: () -> Boolean = { false }
        var replaced = false
        private var original: Drawable? = null
        private var tint: ColorStateList? = null
        private var filter: ColorFilter? = null
        private var clickable = false
        private var focusable = false
        private var accessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { installDrawGuard(); refresh(); scheduleWatch() }
            override fun onViewDetachedFromWindow(v: View) { removeDrawGuard(); restore(); refresh() }
        }

        private var drawObserver: WeakReference<ViewTreeObserver>? = null
        private val drawGuard = ViewTreeObserver.OnPreDrawListener {
            // Native async bindings and alpha animations can overwrite our icon between audio
            // frames. Reconcile only owned output views before drawing; never sample audio here.
            reconcileOwnership()
            if (enabled && aod && hasAodOverlay()) suppressNativeButton()
            true
        }
        fun installDrawGuard() {
            val v = icon.get() ?: return
            if (!v.isAttachedToWindow) return
            val observer = v.viewTreeObserver
            if (drawObserver?.get() === observer) return
            removeDrawGuard()
            observer.addOnPreDrawListener(drawGuard)
            drawObserver = WeakReference(observer)
        }
        fun removeDrawGuard() {
            drawObserver?.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(drawGuard)
            drawObserver = null
        }

        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (enabled && aod) { refresh(); scheduleWatch() }
        }
        fun removeLayoutListener() { album.get()?.removeOnLayoutChangeListener(layout) }
        fun colors(top: Int, bottom: Int) { bars.colors(top, bottom); aodBars.colors(top, bottom) }
        fun hasAodOverlay() = aodHost?.get() != null
        fun reconcileOwnership() {
            val v = icon.get()
            if (!enabled || !selected() || v?.isAttachedToWindow != true || (!awake && !aod)) {
                restore()
            } else if (aod) {
                if (lockScreenCapable && Main.keyguardLocked()) replace(aodBlank) else restore()
            } else {
                if (hasAodOverlay()) removeAod()
                replace()
            }
        }
        fun present() { if (aod) showAod() else { removeAod(); replace() } }
        fun render(values: FloatArray) { if (aod) aodBars.step(values, 6f) else bars.step(values) }

        fun visibleOnAod(): Boolean {
            // Big-cover mode hides the thumbnail, but the player itself remains visible.
            val v = commonHost() ?: return false
            if (!lockScreenCapable || !Main.keyguardLocked() || !selected() || !v.isAttachedToWindow || !v.isShown || v.windowVisibility != View.VISIBLE) return false
            var parent: View? = v
            while (parent != null) { if (parent.alpha < .01f) return false; parent = parent.parent as? View }
            return v.getGlobalVisibleRect(rect)
        }

        private fun commonHost(): ViewGroup? {
            val parents = mutableSetOf<View>()
            var parent: View? = album.get()
            while (parent != null) { parents.add(parent); parent = parent.parent as? View }
            parent = button.get()?.parent as? View
            while (parent != null) {
                if (parent in parents && parent is ViewGroup) return parent
                parent = parent.parent as? View
            }
            return null
        }

        private fun showAod() {
            // A transparent drawable remains blank even if an OEM render-thread animation
            // temporarily raises the button alpha. Keep ownership throughout AOD.
            replace(aodBlank)
            val v = album.get() ?: return
            val host = aodHost?.get() ?: commonHost() ?: return
            if (host.width <= 0 || host.height <= 0) return
            v.getDrawingRect(albumRect)
            host.offsetDescendantRectToMyCoords(v, albumRect)
            val density = host.resources.displayMetrics.density
            val size = (24f * density).toInt().coerceAtLeast(1)
            val centerX = host.width - 29f * density
            val centerY = albumRect.exactCenterY()
            aodBars.setBounds((centerX - size * .5f).toInt(), (centerY - size * .5f).toInt(),
                (centerX + size * .5f).toInt(), (centerY + size * .5f).toInt())
            if (aodHost?.get() == null) { host.overlay.add(aodBars); aodHost = WeakReference(host) }
            suppressNativeButton()
        }

        private fun suppressNativeButton() {
            // Preserve the latest native alpha for restoration without changing layout.
            button.get()?.let { b ->
                if (aodButtonAlpha == null || b.alpha != 0f) aodButtonAlpha = b.alpha
                if (b.alpha != 0f) b.alpha = 0f
            }
        }

        private fun removeAod() {
            aodHost?.get()?.overlay?.remove(aodBars); aodHost = null; aodBars.reset()
            aodButtonAlpha?.let { nativeAlpha ->
                button.get()?.let { b ->
                    if (b.alpha == 0f) b.alpha = if (aod) nativeAlpha else awakeButtonAlpha
                }
            }
            aodButtonAlpha = null
        }

        fun visible(): Boolean {
            val v = icon.get() ?: return false
            if (!selected() || !v.isAttachedToWindow || !v.isShown || v.windowVisibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return false
            var parent: View? = v
            while (parent != null) { if (parent.alpha < .01f) return false; parent = parent.parent as? View }
            return v.getGlobalVisibleRect(rect)
        }

        fun replace(drawable: Drawable = bars) {
            val v = icon.get() ?: return
            val b = button.get() ?: return
            if (!aod) awakeButtonAlpha = b.alpha
            if (!replaced) {
                tint = v.imageTintList; filter = v.colorFilter
                clickable = b.isClickable; focusable = b.isFocusable; accessibility = b.importantForAccessibility
                replaced = true
            }
            if (v.drawable !== drawable) {
                if (v.drawable !== bars && v.drawable !== aodBlank) original = v.drawable
                bars.nativeSize(original?.intrinsicWidth?.takeIf { it > 0 } ?: v.width,
                    original?.intrinsicHeight?.takeIf { it > 0 } ?: v.height)
                v.setImageDrawable(drawable)
            }
            // Native theme updates can tint this icon again while the wave is visible.
            v.imageTintList?.let { tint = it; v.imageTintList = null }
            v.colorFilter?.let { filter = it; v.clearColorFilter() }
            b.isClickable = false; b.isFocusable = false
            b.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun restore() { removeAod(); restoreIcon() }

        private fun restoreIcon() {
            if (!replaced) return
            icon.get()?.let { v ->
                if (v.drawable === bars || v.drawable === aodBlank) {
                    v.setImageDrawable(original)
                    v.imageTintList = tint
                    if (filter != null) v.colorFilter = filter else v.clearColorFilter()
                }
            }
            button.get()?.let { b -> b.isClickable = clickable; b.isFocusable = focusable; b.importantForAccessibility = accessibility }
            original = null; replaced = false; bars.reset()
        }
    }
}
