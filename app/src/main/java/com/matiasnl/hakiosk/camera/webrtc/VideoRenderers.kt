package com.matiasnl.hakiosk.camera.webrtc

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * A view that draws a remote track's frames. [RemoteVideoTrack.bind] calls [initRenderer] with the
 * shared EGL context and [RemoteVideoTrack.unbind] calls [release], so the track controls the context's
 * reference count. Main thread only (except [onFrame]).
 */
interface VideoRenderer : VideoSink {
    fun initRenderer(eglContext: EglBase.Context)

    /** Stops rendering and blocks until the render thread dropped its EGL context. Idempotent. */
    fun release()
}

/**
 * Aspect-fit [SurfaceViewRenderer] for the full-screen view: sizes itself to the frame under AT_MOST
 * constraints. A SurfaceView lives in its own window layer, so it is only suitable where nothing
 * scrolls, clips or overlaps it.
 */
class FitSurfaceViewRenderer(context: Context) : SurfaceViewRenderer(context), VideoRenderer {
    override fun initRenderer(eglContext: EglBase.Context) {
        init(eglContext, null)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        setEnableHardwareScaler(true)
    }

    override fun release() = super.release()

    override fun onFrame(frame: VideoFrame) = super.onFrame(frame)
}

/**
 * Aspect-fill (center crop) renderer backed by a [TextureView], for thumbnails.
 *
 * Unlike a SurfaceView, a TextureView is composited as part of the view hierarchy: it moves with
 * scrolling and pager translation, honours Compose clips (rounded cards, scroll viewports) and alpha,
 * and never punches through other content. It costs one extra GPU composition per frame, which is fine
 * at thumbnail sizes.
 *
 * Frames go through libwebrtc's [EglRenderer] on its own render thread into the TextureView's
 * [SurfaceTexture]; cropping is done by [EglRenderer.setLayoutAspectRatio] with the view's aspect ratio.
 * No bitmaps are created per frame.
 */
class TextureVideoRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoRenderer {
    private val eglRenderer = EglRenderer("TextureVideoRenderer")
    private var initialized = false
    private var released = false
    private var firstFrameReported = false

    /** Main thread; invoked once, when the first frame has actually been composited in this view. */
    var onFirstFrameRendered: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    override fun initRenderer(eglContext: EglBase.Context) {
        checkMainThread()
        if (initialized || released) return
        // CONFIG_PLAIN: no alpha, no depth; the frame is always opaque.
        eglRenderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        initialized = true
        val texture = surfaceTexture
        if (isAvailable && texture != null) attachSurface(texture, width, height)
    }

    override fun onFrame(frame: VideoFrame) = eglRenderer.onFrame(frame)

    override fun release() {
        checkMainThread()
        if (released) return
        released = true
        onFirstFrameRendered = null
        if (initialized) eglRenderer.release() // Also releases the EGL surface.
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized && !released) attachSurface(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateAspectRatio(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized && !released) {
            // Must finish before the SurfaceTexture is released (returning true releases it).
            val latch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { latch.countDown() }
            ThreadUtils.awaitUninterruptibly(latch)
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!firstFrameReported && initialized && !released) {
            firstFrameReported = true
            onFirstFrameRendered?.invoke()
        }
    }

    private fun attachSurface(surface: SurfaceTexture, width: Int, height: Int) {
        updateAspectRatio(width, height)
        eglRenderer.createEglSurface(surface)
    }

    private fun updateAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) eglRenderer.setLayoutAspectRatio(width.toFloat() / height)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TextureVideoRenderer must be used on the main thread" }
    }
}
