package com.youki.dex.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.youki.dex.utils.ColorUtils
import com.youki.dex.utils.WallpaperManagerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiveWallpaperService — fully rebuilt wallpaper engine.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Core fixes in this version                                            │
 * │                                                                      │
 * │  ① GIF: AnimatedImageDrawable (API 28+) instead of the deprecated Movie API │
 * │     + Choreographer for VSync-aligned frame timing without delay        │
 * │  ② Coroutines: CoroutineScope tied to Engine lifecycle                 │
 * │     → cancel() on onDestroy() stops all work automatically             │
 * │  ③ Thread-safety: all state is mutated on the main thread only         │
 * │  ④ Video: SCALE_TO_FIT_WITH_CROPPING (cover) — never stretches         │
 * │  ⑤ Surface format: RGBX_8888 required for MediaPlayer hardware decoder │
 * │  ⑥ loadGeneration guard: cancels stale results when switching quickly  │
 * └──────────────────────────────────────────────────────────────────────┘
 */
class LiveWallpaperService : WallpaperService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_RELOAD = "com.youki.dex.RELOAD_WALLPAPER"
        private val THEME_KEYS = setOf(
            "theme", "dock_color", "dock_color_custom",
            "dock_alpha", "dock_transparency", "material_color"
        )
    }

    lateinit var sharedPreferences: SharedPreferences
        private set

    private var activeEngine: LiveWallpaperEngine? = null

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key in THEME_KEYS) activeEngine?.onThemeChanged()
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine().also { activeEngine = it }

    // ══════════════════════════════════════════════════════════════════════════
    inner class LiveWallpaperEngine : Engine() {

        // ── Coroutine scope tied to Engine lifecycle ────────────────────────────
        // SupervisorJob: one coroutine failing does not cancel the others
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // ── State — all access from main thread only ────────────────────────────
        private var mediaPlayer: MediaPlayer? = null
        private var staticBitmap: Bitmap? = null

        // GIF: AnimatedImageDrawable (API 28+)
        private var animatedDrawable: AnimatedImageDrawable? = null
        private var choreographerCallback: Choreographer.FrameCallback? = null

        // GIF: Legacy Movie API (API < 28)
        private var gifFrames: List<Pair<Bitmap, Int>>? = null
        private var gifFrameIndex = 0
        private var gifRunning = false

        private var isVisible = false
        private val mainHandler = Handler(Looper.getMainLooper())

        // Guard: every async load captures the generation number when it starts.
        // If the number changed (user picked a new wallpaper) → discard the stale result.
        private var loadGeneration = 0

        // Track surface size — avoid reloading on spurious onSurfaceChanged callbacks
        private var surfaceW = 0
        private var surfaceH = 0

        // ── Reload receiver ───────────────────────────────────────────────────
        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_RELOAD) return
                log("← Reload broadcast")
                val wasVideo = mediaPlayer != null
                stopAll()
                val gen = ++loadGeneration
                if (wasVideo) {
                    // Give the video decoder 200ms to release the surface before creating a new player
                    mainHandler.postDelayed({ if (loadGeneration == gen) startWallpaper() }, 200L)
                } else {
                    startWallpaper()
                }
            }
        }

        // ── Engine lifecycle ─────────────────────────────────────────────────────

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // RGBX_8888 required for MediaPlayer hardware video decoder.
            // The default RGB_565 gives a black screen on most devices.
            holder.setFormat(PixelFormat.RGBX_8888)
            ContextCompat.registerReceiver(
                this@LiveWallpaperService,
                reloadReceiver,
                IntentFilter(ACTION_RELOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            log("Engine created")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceW = 0; surfaceH = 0  // force reload on the first onSurfaceChanged
            log("Surface created")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            if (w == surfaceW && h == surfaceH) return  // spurious call — ignore
            surfaceW = w; surfaceH = h
            log("Surface changed → ${w}×${h}")
            stopAll()
            startWallpaper()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            if (visible) resumePlayback() else pausePlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopAll()
            surfaceW = 0; surfaceH = 0
        }

        override fun onDestroy() {
            super.onDestroy()
            activeEngine = null
            engineScope.cancel()  // stops all coroutines at once
            try { unregisterReceiver(reloadReceiver) } catch (_: Exception) {}
            stopAll()
            log("Engine destroyed")
        }

        fun onThemeChanged() {
            when {
                mediaPlayer != null      -> { /* video fills the surface automatically */ }
                staticBitmap != null     -> drawBitmap(surfaceHolder, staticBitmap!!)
                animatedDrawable != null -> { /* AnimatedImageDrawable redraws on its next frame */ }
                gifFrames != null        -> { /* GIF loop redraws on its next frame */ }
                else                     -> drawFallback()
            }
        }

        // ── Playback control ────────────────────────────────────────────────────

        private fun resumePlayback() {
            mediaPlayer?.start()

            // AnimatedImageDrawable
            animatedDrawable?.let {
                it.start()
                startChoreographerLoop()
            }

            // Legacy GIF
            if (gifFrames != null && !gifRunning) {
                gifRunning = true
                scheduleNextGifFrame()
            }

            // Static image: redraw manually since it has no animation loop
            staticBitmap?.let { drawBitmap(surfaceHolder, it) }

            // Nothing loaded: start fresh
            if (mediaPlayer == null && animatedDrawable == null &&
                gifFrames == null && staticBitmap == null) {
                startWallpaper()
            }
        }

        private fun pausePlayback() {
            mediaPlayer?.pause()
            animatedDrawable?.stop()
            stopChoreographerLoop()
            gifRunning = false
            mainHandler.removeCallbacksAndMessages(null)
        }

        // ── Dispatch ──────────────────────────────────────────────────────────

        private fun startWallpaper() {
            val file = WallpaperManagerHelper.getWallpaperFile(this@LiveWallpaperService)
                ?: run { drawFallback(); return }

            when {
                WallpaperManagerHelper.isVideo(file) -> startVideo(file)
                WallpaperManagerHelper.isGif(file)   -> startGif(file)
                WallpaperManagerHelper.isImage(file) -> startStaticImage(file)
                else -> { log("Unknown format: ${file.extension}"); drawFallback() }
            }
        }

        // ── Video ─────────────────────────────────────────────────────────────

        private fun startVideo(file: File) {
            // Important: setFixedSize() is never called here.
            // Calling it locks the surface to the video's native resolution,
            // and the framework stretches that surface to fill the screen incorrectly.
            // We keep the surface at screen resolution and let VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            // handle scaling (cover: fills without stretching, crops edges if needed).
            val gen = ++loadGeneration
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setSurface(surfaceHolder.surface)
                    isLooping = true
                    setVolume(0f, 0f)
                    setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    setOnPreparedListener { mp ->
                        if (loadGeneration != gen) { mp.release(); return@setOnPreparedListener }
                        mp.start()
                        if (!isVisible) mp.pause()
                        log("Video playing: ${file.name}")
                    }
                    setOnErrorListener { _, what, extra ->
                        log("MediaPlayer error what=$what extra=$extra → fallback")
                        drawFallback()
                        true
                    }
                    setOnCompletionListener { mp ->
                        // Should not happen (isLooping=true) but handled defensively
                        try { mp.seekTo(0); mp.start() } catch (_: Exception) {}
                    }
                    prepareAsync()
                }
                log("Video prepare started: ${file.name}")
            } catch (e: Exception) {
                log("Video start failed: ${e.message}")
                drawFallback()
            }
        }

        // ── GIF ───────────────────────────────────────────────────────────────

        private fun startGif(file: File) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                startGifModern(file)   // API 28+: AnimatedImageDrawable
            } else {
                startGifLegacy(file)   // API < 28: Movie API
            }
        }

        // ── Modern GIF: ImageDecoder + AnimatedImageDrawable (API 28+) ──────────

        private fun startGifModern(file: File) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val gen = ++loadGeneration
            engineScope.launch(Dispatchers.IO) {
                try {
                    val source = ImageDecoder.createSource(file)
                    val decoded = ImageDecoder.decodeDrawable(source)

                    withContext(Dispatchers.Main) {
                        if (loadGeneration != gen) return@withContext

                        if (decoded is AnimatedImageDrawable) {
                            decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            animatedDrawable = decoded
                            if (isVisible) {
                                decoded.start()
                                startChoreographerLoop()
                            }
                            log("AnimatedImageDrawable started: ${file.name}")
                        } else {
                            // Non-animated GIF — treat as a static image
                            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                ?: throw Exception("Static GIF: BitmapFactory returned null")
                            staticBitmap = bmp
                            drawBitmap(surfaceHolder, bmp)
                            log("Static GIF loaded as image: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    log("AnimatedImageDrawable failed (${e.message}) → legacy path")
                    withContext(Dispatchers.Main) {
                        if (loadGeneration == gen) startGifLegacy(file)
                    }
                }
            }
        }

        // ── Choreographer loop — syncs AnimatedImageDrawable drawing with VSync ─

        private fun startChoreographerLoop() {
            stopChoreographerLoop()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val drawable = animatedDrawable
                    if (!isVisible || drawable == null || !drawable.isRunning) return
                    drawAnimatedFrame(drawable)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            choreographerCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }

        private fun stopChoreographerLoop() {
            choreographerCallback?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }
            choreographerCallback = null
        }

        private fun drawAnimatedFrame(drawable: AnimatedImageDrawable) {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas() ?: return
                val sw = canvas.width.toFloat()
                val sh = canvas.height.toFloat()
                val dw = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
                val dh = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
                val scale = maxOf(sw / dw, sh / dh)
                val left = ((sw - dw * scale) / 2f).toInt()
                val top  = ((sh - dh * scale) / 2f).toInt()
                canvas.drawColor(Color.BLACK)
                canvas.save()
                canvas.translate(left.toFloat(), top.toFloat())
                canvas.scale(scale, scale)
                drawable.setBounds(0, 0, dw.toInt(), dh.toInt())
                drawable.draw(canvas)
                canvas.restore()
            } catch (e: Exception) {
                log("drawAnimatedFrame: ${e.message}")
            } finally {
                canvas?.let { surfaceHolder.unlockCanvasAndPost(it) }
            }
        }

        // ── Legacy GIF: Movie API (API < 28) ───────────────────────────────────

        private fun startGifLegacy(file: File) {
            val gen = ++loadGeneration
            engineScope.launch(Dispatchers.IO) {
                try {
                    val frames = decodeGifFrames(file)
                    withContext(Dispatchers.Main) {
                        if (loadGeneration != gen) {
                            frames.forEach { (bmp, _) -> bmp.recycle() }
                            return@withContext
                        }
                        if (frames.isEmpty()) {
                            BitmapFactory.decodeFile(file.absolutePath)?.let {
                                staticBitmap = it; drawBitmap(surfaceHolder, it)
                            } ?: drawFallback()
                            return@withContext
                        }
                        gifFrames = frames
                        gifFrameIndex = 0
                        gifRunning = true
                        scheduleNextGifFrame()
                        log("Legacy GIF: ${file.name} (${frames.size} frames)")
                    }
                } catch (e: Exception) {
                    log("Legacy GIF failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        if (loadGeneration == gen) drawFallback()
                    }
                }
            }
        }

        private fun scheduleNextGifFrame() {
            if (!gifRunning || !isVisible) return
            val frames = gifFrames ?: return
            val (bmp, delayMs) = frames[gifFrameIndex % frames.size]
            drawBitmap(surfaceHolder, bmp)
            gifFrameIndex++
            mainHandler.postDelayed({ scheduleNextGifFrame() }, delayMs.toLong().coerceAtLeast(16L))
        }

        @Suppress("DEPRECATION")
        private fun decodeGifFrames(file: File): List<Pair<Bitmap, Int>> {
            val movie = android.graphics.Movie.decodeFile(file.absolutePath)
                ?: return emptyList()

            val duration = movie.duration()
            val w = movie.width().coerceAtLeast(1)
            val h = movie.height().coerceAtLeast(1)

            if (duration == 0) {
                // Single-frame GIF
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                movie.setTime(0)
                movie.draw(Canvas(bmp), 0f, 0f)
                return listOf(bmp to 100)
            }

            // Cap at 120 frames max to conserve memory
            val frameCount = (duration / 16).coerceIn(1, 120)
            val step       = duration / frameCount      // duration of each frame in milliseconds
            return buildList {
                repeat(frameCount) { i ->
                    movie.setTime(i * step)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    movie.draw(Canvas(bmp), 0f, 0f)
                    add(bmp to step)
                }
            }
        }

        // ── Static image ────────────────────────────────────────────────────────

        private fun startStaticImage(file: File) {
            val gen = ++loadGeneration
            engineScope.launch(Dispatchers.IO) {
                try {
                    // HARDWARE config (API 26+): delegates storage to GPU memory → faster drawing
                    val opts = BitmapFactory.Options().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            inPreferredConfig = Bitmap.Config.HARDWARE
                    }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        ?: throw Exception("BitmapFactory returned null for ${file.name}")

                    withContext(Dispatchers.Main) {
                        if (loadGeneration != gen) { bmp.recycle(); return@withContext }
                        staticBitmap = bmp
                        drawBitmap(surfaceHolder, bmp)
                        log("Static image loaded: ${file.name} ${bmp.width}×${bmp.height}")
                    }
                } catch (e: Exception) {
                    log("Static image failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        if (loadGeneration == gen) drawFallback()
                    }
                }
            }
        }

        // ── Drawing ─────────────────────────────────────────────────────────────

        /**
         * Scale-to-cover: fills the entire surface without stretching.
         * The shorter axis is scaled to fill; excess edges are cropped from the center.
         */
        private fun drawBitmap(holder: SurfaceHolder, bitmap: Bitmap, retries: Int = 0) {
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) {
                    if (retries < 3)
                        mainHandler.postDelayed(
                            { drawBitmap(holder, bitmap, retries + 1) },
                            (retries + 1) * 60L
                        )
                    return
                }
                val sw = canvas.width.toFloat()
                val sh = canvas.height.toFloat()
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val scale = maxOf(sw / bw, sh / bh)
                val dx = (sw - bw * scale) / 2f
                val dy = (sh - bh * scale) / 2f
                val matrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            } catch (e: Exception) {
                log("drawBitmap: ${e.message}")
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun drawFallback() {
            var canvas: Canvas? = null
            try {
                val colors = ColorUtils.getMainColors(sharedPreferences, this@LiveWallpaperService)
                val mainColor = colors[0]; val alpha = colors[1]
                val themed = Color.argb(alpha,
                    Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor))
                canvas = surfaceHolder.lockCanvas()
                canvas?.drawColor(Color.BLACK)
                canvas?.drawColor(themed)
            } catch (_: Exception) {
            } finally {
                canvas?.let { surfaceHolder.unlockCanvasAndPost(it) }
            }
        }

        // ── Cleanup ─────────────────────────────────────────────────────────────

        private fun stopAll() {
            ++loadGeneration  // invalidates all in-flight async loads

            // MediaPlayer
            try {
                mediaPlayer?.run {
                    try { if (isPlaying) stop() } catch (_: Exception) {}
                    try { reset() } catch (_: Exception) {}
                    try { release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            mediaPlayer = null

            // AnimatedImageDrawable
            stopChoreographerLoop()
            try { animatedDrawable?.stop() } catch (_: Exception) {}
            animatedDrawable = null

            // Legacy GIF
            gifRunning = false
            mainHandler.removeCallbacksAndMessages(null)
            gifFrames?.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            gifFrames = null

            // Static bitmap
            staticBitmap?.let { if (!it.isRecycled) it.recycle() }
            staticBitmap = null
        }

        private fun log(msg: String) = Log.d("LiveWallpaper", msg)
    }
}