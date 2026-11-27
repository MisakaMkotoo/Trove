package com.exodidio.tune.backdrop

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exodidio.tune.player.DynamicBackdropSettings
import com.exodidio.tune.player.DynamicBackdropStyle
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.concurrent.withLock

/**
 * 动态背景层：GLES 渲染网格 / Titanium 流沙。
 * 使用 TextureView + 自管 EGL10 线程，确保在 Compose layerBackdrop / hazeSource / 透明度变换下
 * 仍能正常参与 HWUI 合成，不会出现 SurfaceView 打洞导致的黑屏问题。
 */
@Composable
internal fun DynamicBackdrop(
    settings: DynamicBackdropSettings,
    artwork: android.graphics.Bitmap?,
    artworkIdentity: String?,
    lyricsVisible: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    /** 用于固定形变的种子，通常传歌曲名：同一首歌每次都是同一套形变。 */
    presetSeed: String? = null,
) {
    var glView by remember { mutableStateOf<GLTextureView?>(null) }
    var renderer by remember { mutableStateOf<FluidRenderer?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var appliedIdentity by remember { mutableStateOf<String?>(null) }

    /** 用歌曲名的稳定哈希从勾选池里选形变；同一首歌始终同一套；7 = 随机 6x6（带种子）。 */
    fun pickPreset(seed: String?): Presets.Preset {
        val pool = settings.meshPresetPool
        if (pool.isEmpty()) return Presets.ALL[0]
        val sorted = pool.sorted()
        val hash = stableHash(seed ?: "")
        val index = sorted[Math.floorMod(hash, sorted.size)]
        return if (index == 7) {
            RandomMesh.generate(6, 6, hash.toLong() * 31L + 17L)
        } else {
            Presets.ALL[index.coerceIn(0, Presets.ALL.lastIndex)]
        }
    }

    val scrim = settings.titaniumLyricScrim && lyricsVisible

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLTextureView(context).apply {
                alpha = 0f
                onFirstFrameRendered = {
                    post {
                        if (isAttachedToWindow && alpha < 1f) {
                            animate().cancel()
                            animate()
                                .alpha(1f)
                                .setDuration(180L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                    }
                }
                val fluid = fluidRenderer
                val initialPreset = pickPreset(presetSeed)
                fluid.setPreset(initialPreset)
                applySettings(fluid, settings, scrim)
                if (artwork != null && !artwork.isRecycled) {
                    appliedIdentity = artworkIdentity
                    fluid.setAlbum(artwork, initialPreset)
                }
                renderer = fluid
                glView = this
                setPaused(!(active && lifecycleResumed))
            }
        },
        update = { view ->
            renderer?.let { applySettings(it, settings, scrim) }
            view.requestRender()
        },
        onRelease = { view ->
            view.animate().cancel()
            view.onFirstFrameRendered = null
            view.release()
            if (glView === view) {
                glView = null
                renderer = null
            }
        },
    )

    // 勾选池变化时按当前歌曲的哈希立刻重算一次，便于看到反馈
    LaunchedEffect(settings.meshPresetPool, presetSeed, renderer) {
        renderer?.setPreset(pickPreset(presetSeed))
    }
    LaunchedEffect(artwork, artworkIdentity, presetSeed, renderer) {
        val bitmap = artwork ?: return@LaunchedEffect
        val fluid = renderer ?: return@LaunchedEffect
        if (appliedIdentity == artworkIdentity && artworkIdentity != null) return@LaunchedEffect
        appliedIdentity = artworkIdentity
        fluid.setAlbum(bitmap, pickPreset(presetSeed))
    }

    val syncActive: () -> Unit = {
        val view = glView
        if (view != null) {
            val shouldRun = active && lifecycleResumed
            view.setPaused(!shouldRun)
            if (shouldRun) {
                view.requestRender()
            }
        }
    }
    DisposableEffect(lifecycleOwner, glView) {
        lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                    syncActive()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleResumed = false
                    syncActive()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncActive()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView?.setPaused(true)
        }
    }
    LaunchedEffect(active, glView) {
        syncActive()
    }

    // 帧调度：平常按设置帧率，换歌淡入时跟屏幕刷新率
    val fps = settings.fps.coerceIn(15, 120)
    val animating = active && lifecycleResumed && !settings.staticMode
    LaunchedEffect(glView, animating, fps) {
        val view = glView ?: return@LaunchedEffect
        if (!animating) return@LaunchedEffect
        val choreographer = Choreographer.getInstance()
        val intervalNs = 1_000_000_000L / fps
        var lastRequested = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val crossfading = renderer?.isCrossfading() == true
                if (crossfading || frameTimeNanos - lastRequested >= intervalNs) {
                    lastRequested = frameTimeNanos
                    view.requestRender()
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        try {
            awaitCancellation()
        } finally {
            choreographer.removeFrameCallback(callback)
        }
    }
}

private class GLTextureView(context: Context) :
    TextureView(context),
    TextureView.SurfaceTextureListener,
    FluidRenderer.Host {

    val fluidRenderer: FluidRenderer = FluidRenderer(this)

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private var currentSurfaceTexture: SurfaceTexture? = null
    private val pendingReleaseTextures = ArrayDeque<SurfaceTexture>()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceReplaced = false
    private var sizeChanged = false
    private var paused = true
    private var renderRequested = false
    private var shouldExit = false
    private var firstFrameNotified = false
    private val eventQueue = ArrayDeque<Runnable>()

    @Volatile
    var onFirstFrameRendered: (() -> Unit)? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
        Thread(::runGlLoop, "dynamic-backdrop-gl").apply {
            isDaemon = true
            start()
        }
    }

    override fun queueEvent(runnable: Runnable) {
        lock.withLock {
            if (!shouldExit) {
                eventQueue.addLast(runnable)
                condition.signalAll()
            }
        }
    }

    override fun requestRender() {
        lock.withLock {
            if (!shouldExit) {
                renderRequested = true
                condition.signalAll()
            }
        }
    }

    fun setPaused(pause: Boolean) {
        lock.withLock {
            if (paused != pause) {
                paused = pause
                if (!pause) {
                    renderRequested = true
                }
                condition.signalAll()
            }
        }
    }

    fun release() {
        fluidRenderer.release()
        lock.withLock {
            shouldExit = true
            eventQueue.clear()
            condition.signalAll()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        lock.withLock {
            currentSurfaceTexture?.let { previous ->
                if (previous !== surface) {
                    pendingReleaseTextures.addLast(previous)
                }
            }
            currentSurfaceTexture = surface
            surfaceWidth = width
            surfaceHeight = height
            surfaceReplaced = true
            sizeChanged = true
            renderRequested = true
            condition.signalAll()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        lock.withLock {
            if (currentSurfaceTexture === surface) {
                surfaceWidth = width
                surfaceHeight = height
                sizeChanged = true
                renderRequested = true
                condition.signalAll()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        lock.withLock {
            if (currentSurfaceTexture === surface) {
                currentSurfaceTexture = null
            }
            pendingReleaseTextures.addLast(surface)
            condition.signalAll()
        }
        // 返回 false，由 GL 线程在销毁 EGLSurface 后释放 SurfaceTexture，
        // 既避免阻塞 UI 线程，也保证渲染线程不会向已销毁的纹理提交帧。
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun runGlLoop() {
        var egl: EGL10? = null
        var eglDisplay: EGLDisplay? = null
        var eglConfig: EGLConfig? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var rendererInitialized = false

        fun destroySurface() {
            val e = egl ?: return
            val d = eglDisplay ?: return
            if (eglSurface != null && eglSurface !== EGL10.EGL_NO_SURFACE) {
                e.eglMakeCurrent(d, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                e.eglDestroySurface(d, eglSurface)
                eglSurface = null
            }
        }

        fun destroyContextAndDisplay() {
            destroySurface()
            val e = egl ?: return
            val d = eglDisplay ?: return
            if (eglContext != null && eglContext !== EGL10.EGL_NO_CONTEXT) {
                e.eglDestroyContext(d, eglContext)
                eglContext = null
            }
            if (d !== EGL10.EGL_NO_DISPLAY) {
                e.eglTerminate(d)
                eglDisplay = null
            }
            eglConfig = null
            rendererInitialized = false
        }

        fun releasePendingTextures(textures: List<SurfaceTexture>) {
            for (texture in textures) {
                runCatching { texture.release() }
            }
        }

        try {
            while (true) {
                var exiting = false
                var targetSurface: SurfaceTexture? = null
                var width = 0
                var height = 0
                var needRecreateSurface = false
                var needSizeChanged = false
                var needDraw = false
                var eventsToRun: List<Runnable> = emptyList()
                var texturesToRelease: List<SurfaceTexture> = emptyList()

                lock.withLock {
                    while (true) {
                        if (pendingReleaseTextures.isNotEmpty()) {
                            texturesToRelease = pendingReleaseTextures.toList()
                            pendingReleaseTextures.clear()
                        }
                        if (shouldExit) {
                            exiting = true
                            break
                        }
                        if (currentSurfaceTexture == null && eglSurface != null) {
                            // 窗口纹理已销毁，先在锁外拆掉 EGLSurface 并释放纹理
                            break
                        }
                        if (texturesToRelease.isNotEmpty() && eglSurface == null) {
                            break
                        }

                        val ready = !paused && currentSurfaceTexture != null && surfaceWidth > 0 && surfaceHeight > 0
                        val hasWork = ready && (
                            eglSurface == null ||
                                surfaceReplaced ||
                                sizeChanged ||
                                renderRequested ||
                                eventQueue.isNotEmpty()
                            )
                        if (hasWork) {
                            targetSurface = currentSurfaceTexture
                            width = surfaceWidth
                            height = surfaceHeight
                            needRecreateSurface = surfaceReplaced || eglSurface == null
                            needSizeChanged = sizeChanged || needRecreateSurface
                            needDraw = renderRequested || needSizeChanged
                            surfaceReplaced = false
                            sizeChanged = false
                            renderRequested = false
                            if (eventQueue.isNotEmpty()) {
                                eventsToRun = eventQueue.toList()
                                eventQueue.clear()
                            }
                            break
                        }
                        condition.await()
                    }
                }

                if (exiting) {
                    destroyContextAndDisplay()
                    releasePendingTextures(texturesToRelease)
                    break
                }

                if (targetSurface == null || needRecreateSurface) {
                    destroySurface()
                }
                if (texturesToRelease.isNotEmpty()) {
                    releasePendingTextures(texturesToRelease)
                }

                val activeSurface = targetSurface ?: continue
                if (paused) continue

                val e = egl ?: (EGLContext.getEGL() as EGL10).also { egl = it }

                if (eglDisplay == null) {
                    val display = e.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                    if (display === EGL10.EGL_NO_DISPLAY || !e.eglInitialize(display, IntArray(2))) {
                        Log.w(TAG, "eglInitialize failed: 0x${Integer.toHexString(e.eglGetError())}")
                        continue
                    }
                    eglDisplay = display
                    eglConfig = chooseEglConfig(e, display)
                    if (eglConfig == null) {
                        Log.w(TAG, "No compatible EGLConfig found")
                        destroyContextAndDisplay()
                        continue
                    }
                }

                val display = eglDisplay!!
                val config = eglConfig!!

                if (eglContext == null) {
                    val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
                    val ctx = e.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
                    if (ctx == null || ctx === EGL10.EGL_NO_CONTEXT) {
                        Log.w(TAG, "eglCreateContext failed: 0x${Integer.toHexString(e.eglGetError())}")
                        destroyContextAndDisplay()
                        continue
                    }
                    eglContext = ctx
                    rendererInitialized = false
                }

                val ctx = eglContext!!

                if (eglSurface == null) {
                    val surf = e.eglCreateWindowSurface(display, config, activeSurface, null)
                    if (surf == null || surf === EGL10.EGL_NO_SURFACE) {
                        Log.w(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(e.eglGetError())}")
                        continue
                    }
                    eglSurface = surf
                }

                val surf = eglSurface!!
                if (!e.eglMakeCurrent(display, surf, surf, ctx)) {
                    val err = e.eglGetError()
                    Log.w(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(err)}")
                    if (err == EGL11.EGL_CONTEXT_LOST) {
                        destroyContextAndDisplay()
                        requestRender()
                    } else {
                        destroySurface()
                    }
                    continue
                }

                if (!rendererInitialized) {
                    fluidRenderer.onSurfaceCreated()
                    rendererInitialized = true
                    needSizeChanged = true
                }
                if (needSizeChanged) {
                    fluidRenderer.onSurfaceChanged(width, height)
                }
                for (event in eventsToRun) {
                    event.run()
                }
                if (needDraw) {
                    fluidRenderer.onDrawFrame()
                    if (!e.eglSwapBuffers(display, surf)) {
                        val err = e.eglGetError()
                        if (err == EGL11.EGL_CONTEXT_LOST) {
                            destroyContextAndDisplay()
                            requestRender()
                        } else if (err != EGL10.EGL_SUCCESS) {
                            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(err)}")
                            destroySurface()
                        }
                    } else if (!firstFrameNotified) {
                        firstFrameNotified = true
                        onFirstFrameRendered?.invoke()
                    }
                }
            }
        } catch (_: InterruptedException) {
            destroyContextAndDisplay()
        } catch (t: Throwable) {
            Log.e(TAG, "DynamicBackdrop GL loop crashed", t)
            destroyContextAndDisplay()
        }
    }

    private fun chooseEglConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        val candidates = listOf(
            intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE,
            ),
            intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_NONE,
            ),
        )
        for (spec in candidates) {
            if (egl.eglChooseConfig(display, spec, configs, 1, numConfig) && numConfig[0] > 0) {
                return configs[0]
            }
        }
        return null
    }

    private companion object {
        const val TAG = "GLTextureView"
        const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        const val EGL_OPENGL_ES2_BIT = 4
    }
}

/** FNV-1a 32 位：跨进程稳定，同一首歌名总是同一结果。 */
private fun stableHash(value: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (char in value) {
        hash = hash xor char.code
        hash *= 0x01000193
    }
    return hash
}

private fun applySettings(
    renderer: FluidRenderer,
    settings: DynamicBackdropSettings,
    scrim: Boolean,
) {
    renderer.setRendererMode(
        when (settings.style) {
            DynamicBackdropStyle.Mesh -> 0
            DynamicBackdropStyle.Titanium -> 1
        },
    )
    renderer.setFlowSpeed(settings.flowSpeed)
    renderer.setVolume(settings.volume)
    renderer.setRenderScale(settings.renderScale)
    renderer.setStaticMode(settings.staticMode)
    renderer.setTitaniumMaterial(
        settings.titaniumGrain,
        settings.titaniumSparkle,
        settings.titaniumSheen,
        settings.titaniumMetalness,
    )
    renderer.setTitaniumCrestShiftMs(settings.titaniumCrestShiftMs.toFloat())
    renderer.setTitaniumDithering(settings.titaniumDithering)
    renderer.setTitaniumLyricScrim(scrim)
}
