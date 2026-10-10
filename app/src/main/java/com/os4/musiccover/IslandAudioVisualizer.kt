package com.os4.musiccover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.json.JSONObject

/** Live bars replace only the native music-wave drawable around the camera cutout. */
object IslandAudioVisualizer {
    private const val HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("MCIslandAudio", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper) }
    private val loaders = java.util.Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val views = WeakHashMap<ImageView, WaveState>()
    private val holders = java.util.Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val spectrum = IslandSpectrum()
    private val targets = FloatArray(5)
    private val controllerLoaders = java.util.Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val controllers = WeakHashMap<Any, Boolean>()
    private var hideDeviceIcon = false
    private var enabled = false
    private var receiverInstalled = false
    private var awake = true
    private var watching = false
    private var frameScheduled = false
    private var pendingCapture = false
    private var lease: AudioSpectrumCapture.Lease? = null
    @Volatile private var generation = 0L
    private var retryAt = 0L
    @Volatile private var samples = 0L
    private var lastSampleSeen = 0L
    private var staleAt = 0L

    @JvmStatic fun install(loader: ClassLoader) {
        installDeviceIconHook(loader)
        tryInstall(loader)
        if (loaders.isNotEmpty()) return
        runCatching { Xp.hookAllConstructors(Class.forName("dalvik.system.BaseDexClassLoader")) { chain ->
            val result = chain.proceed()
            if (loaders.isEmpty()) (chain.thisObject as? ClassLoader)?.let(::tryInstall)
            result
        } }.onFailure { Xp.w("Island audio loader watch failed: $it") }
    }

    private fun installDeviceIconHook(loader: ClassLoader) {
        if (loader in controllerLoaders) return
        val clazz = runCatching {
            Xp.findClass("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl", loader)
        }.getOrNull() ?: return
        runCatching {
            Xp.hookAll(clazz, "generateRightImage") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { controllers[it] = true }
                if (enabled && hideDeviceIcon) {
                    val root = chain.args.firstOrNull() as? JSONObject
                    val picture = root?.optJSONObject("imageTextInfoRight")?.optJSONObject("picInfo")
                    // Change only the music output-device slot. Connection alerts and other
                    // island activities keep their native icons; playback routing is untouched.
                    if (picture?.optString("pic") == "miui_music_cast_others") {
                        picture.put("type", 2)
                        picture.put("pic", "musicWave")
                        picture.put("autoplay", "true")
                    }
                }
                result
            }
            controllerLoaders.add(loader)
        }.onFailure { Xp.w("Island output icon hook failed: $it") }
    }

    private fun refreshDeviceIcons() {
        controllers.keys.toList().forEach { owner ->
            val media = field(owner, "topMediaData") ?: return@forEach
            runCatching {
                Xp.callMethod(owner, "addDynamicIslandView", field(owner, "miuiPlayerHolder"),
                    field(owner, "miuiDummyPlayerHolder"), media)
            }.onFailure { Xp.w("Island output icon refresh failed: $it") }
        }
    }

    private fun tryInstall(loader: ClassLoader) {
        if (loader in loaders) return
        val clazz = runCatching { Xp.findClass(HOLDER, loader) }.getOrNull() ?: return
        if (!loaders.add(loader)) return
        runCatching {
            // Automatic track changes can use the static slot without registering
            // a Lottie callback. Follow both native slots after their normal binding.
            for (method in arrayOf("registerLottieCallback", "bind", "showStaticImage", "onShow")) {
                Xp.hookAll(clazz, method) { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { holder -> main.post {
                        track(holder)
                        refresh()
                        scheduleWatch()
                    } }
                    result
                }
            }
            // HyperOS writes the artwork gradient asynchronously to the holder fields.
            // Read the committed native colors, including writes that bypass the getter hook.
            Xp.log("Island audio visualizer hooks installed")
        }.onFailure { loaders.remove(loader); Xp.w("Island audio hooks failed: $it") }
    }

    @JvmStatic fun configure(value: Boolean, hideOutputIcon: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { configure(value, hideOutputIcon) }; return }
        val iconSettingChanged = (enabled && hideDeviceIcon) != (value && hideOutputIcon)
        enabled = value
        hideDeviceIcon = hideOutputIcon
        if (iconSettingChanged) refreshDeviceIcons()
        if (!enabled) {
            main.removeCallbacks(watch); watching = false
            stopCapture()
            views.values.toList().forEach { it.restore() }
        } else { retryAt = 0L; refresh(); scheduleWatch() }
    }

    private fun track(holder: Any) {
        val pic = field(holder, "picInfo") ?: return
        val name = runCatching { Xp.callMethod(pic, "getPic") }.getOrNull()
        if (name != "musicWave" && name != "musicPause") return
        holders.add(holder)
        for (slot in arrayOf("lottieView", "lottieViewStatic")) {
            val image = field(holder, slot) as? ImageView ?: continue
            val state = views.getOrPut(image) {
                installReceiver(image.context)
                WaveState(image, holder, slot).also { item ->
                    image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { refresh(); scheduleWatch() }
                        override fun onViewDetachedFromWindow(v: View) { item.restore(); refresh() }
                    })
                }
            }
            state.holder = WeakReference(holder)
            state.syncNativeColors()
        }
    }

    private fun installReceiver(context: Context) {
        if (receiverInstalled) return
        val app = context.applicationContext ?: context
        awake = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    awake = intent.action != Intent.ACTION_SCREEN_OFF
                    if (!awake) { main.removeCallbacks(watch); watching = false }
                    refresh(); scheduleWatch()
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, Context.RECEIVER_NOT_EXPORTED)
            receiverInstalled = true
        }.onFailure { Xp.w("Island audio screen receiver failed: $it") }
    }

    private val watch = object : Runnable {
        override fun run() { watching = false; refresh(); scheduleWatch() }
    }
    private fun scheduleWatch() {
        if (!enabled || !awake || watching || holders.isEmpty()) return
        watching = true; main.postDelayed(watch, 400L)
    }

    private fun refresh() {
        // View stubs may be inflated after binding; discover them without changing
        // native visibility or starting capture for a hidden island.
        holders.toList().forEach { track(it) }
        views.values.forEach { it.syncNativeColors() }
        val active = enabled && awake && views.values.any { it.visibleWave() }
        if (!active) {
            stopCapture()
            views.values.toList().forEach { it.restore() }
            return
        }
        if (lease == null && !pendingCapture && SystemClock.uptimeMillis() >= retryAt) {
            pendingCapture = true
            val token = ++generation
            worker.post {
                if (generation != token) return@post
                try {
                    val created = AudioSpectrumCapture.acquire(true) { _, fft, rate ->
                        if (generation == token) { spectrum.sample(fft, rate); samples++ }
                    }
                    main.post deliver@{
                        if (generation != token) { worker.post { created.release() }; return@deliver }
                        pendingCapture = false; lease = created
                        lastSampleSeen = samples; staleAt = SystemClock.uptimeMillis()
                        refresh(); scheduleFrame()
                    }
                } catch (t: Throwable) {
                    main.post {
                        if (generation == token) {
                            pendingCapture = false; retryAt = SystemClock.uptimeMillis() + 10000L
                            views.values.toList().forEach { it.restore() }
                            Xp.w("Island audio capture unavailable: $t")
                        }
                    }
                }
            }
        }
        if (lease != null) {
            views.values.toList().forEach { if (it.visibleWave()) it.replace() else it.restore() }
            scheduleFrame()
        }
    }

    private fun stopCapture() {
        if (lease == null && !pendingCapture) return
        generation++; pendingCapture = false
        val old = lease; lease = null
        if (old != null) worker.post { old.release() }
        main.removeCallbacks(frame); frameScheduled = false
        spectrum.reset()
    }

    private val frame = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (lease == null || !enabled || !awake) return
            // Native visibility/alpha can change without a detach callback.
            if (views.values.none { it.visibleWave() }) { refresh(); return }
            val now = SystemClock.uptimeMillis()
            if (lastSampleSeen != samples) { lastSampleSeen = samples; staleAt = now }
            else if (now - staleAt > 3000L) {
                stopCapture(); retryAt = now + 10000L
                views.values.toList().forEach { it.restore() }; return
            }
            spectrum.copyTo(targets)
            views.values.toList().forEach { state ->
                if (state.visibleWave()) { state.replace(); state.bars.step(targets) }
                else state.restore()
            }
            scheduleFrame()
        }
    }
    private fun scheduleFrame() {
        if (lease == null || frameScheduled || !awake || !enabled) return
        frameScheduled = true; main.postDelayed(frame, 33L)
    }

    @JvmStatic fun describe(): String = "enabled=$enabled hideDeviceIcon=$hideDeviceIcon controllers=${controllers.size} routes=${controllers.keys.map { field(it, "castDeviceType") }} hooks=${loaders.size} views=${views.size} visible=${views.values.count { it.visibleWave() }} capturing=${lease != null} pending=$pendingCapture samples=$samples colors=${views.values.map { it.bars.colorReport() }} states=${views.values.map { it.describe() }}"

    private class WaveState(image: ImageView, holder: Any, val slot: String) {
        val image = WeakReference(image)
        var holder = WeakReference(holder)
        val bars = AudioWaveDrawable()
        private val visibleBounds = android.graphics.Rect()
        var original: Drawable? = null
        var replaced = false
        var wasAnimating = false

        fun syncNativeColors() {
            val owner = holder.get() ?: return
            val top = field(owner, "gradientTopColor") as? Int ?: return
            val bottom = field(owner, "gradientBottomColor") as? Int ?: return
            bars.colors(top, bottom)
        }

        fun visibleWave(): Boolean {
            val v = image.get() ?: return false
            val pic = field(holder.get(), "picInfo") ?: return false
            val name = runCatching { Xp.callMethod(pic, "getPic") as? String }.getOrNull()
            if ((name != "musicWave" && name != "musicPause") ||
                !AudioSpectrumCapture.mediaPlaying(null, name == "musicWave") || !v.isAttachedToWindow || !v.isShown || v.width <= 0 || v.height <= 0 || v.windowVisibility != View.VISIBLE) return false
            var parent: View? = v
            while (parent != null) { if (parent.alpha < .01f) return false; parent = parent.parent as? View }
            return v.getGlobalVisibleRect(visibleBounds)
        }

        fun describe(): String {
            val v = image.get() ?: return "$slot:released"
            val pic = field(holder.get(), "picInfo")
            val name = runCatching { Xp.callMethod(pic, "getPic") }.getOrNull()
            return "$slot:$name attached=${v.isAttachedToWindow} shown=${v.isShown} visibility=${v.visibility} alpha=${v.alpha} size=${v.width}x${v.height} live=${v.drawable === bars}"
        }

        fun replace() {
            val v = image.get() ?: return
            if (v.drawable === bars) return
            original = v.drawable
            bars.nativeSize(original?.intrinsicWidth ?: v.width, original?.intrinsicHeight ?: v.height)
            wasAnimating = runCatching { Xp.callMethod(v, "isAnimating") as? Boolean }.getOrNull() == true
            runCatching { Xp.callMethod(v, "pauseAnimation") }
            replaced = true
            v.setImageDrawable(bars)
        }

        fun restore() {
            if (!replaced) return
            val v = image.get()
            if (v != null && v.drawable === bars) {
                v.setImageDrawable(original)
                if (wasAnimating && awake && v.isShown) runCatching { Xp.callMethod(v, "resumeAnimation") }
            }
            replaced = false; original = null; wasAnimating = false
            bars.reset()
        }
    }

    internal fun nativeColors(): Pair<Int, Int>? {
        for (state in views.values) {
            val owner = state.holder.get() ?: continue
            val top = field(owner, "gradientTopColor") as? Int ?: continue
            val bottom = field(owner, "gradientBottomColor") as? Int ?: continue
            return top to bottom
        }
        return null
    }

    private fun field(owner: Any?, name: String): Any? = if (owner == null) null else runCatching { Xp.getObjectField(owner, name) }.getOrNull()
}
