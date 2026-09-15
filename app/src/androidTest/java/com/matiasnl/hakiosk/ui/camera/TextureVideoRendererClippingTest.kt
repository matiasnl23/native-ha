package com.matiasnl.hakiosk.ui.camera

import android.os.Handler
import android.os.HandlerThread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Card
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matiasnl.hakiosk.camera.webrtc.TextureVideoRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The thumbnail video renderer with synthetic red 16:9 frames (no network, no Home Assistant):
 * the picture fills a square tile (center crop) and stays clipped to a rounded card inside a
 * vertical scroll and a horizontal pager, as on the dashboard.
 */
@RunWith(AndroidJUnit4::class)
class TextureVideoRendererClippingTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var eglBase: EglBase
    private val renderers = mutableListOf<TextureVideoRenderer>()
    private val rendered = AtomicBoolean(false)
    private val feederThread = HandlerThread("frame-feeder")
    private lateinit var feeder: Handler
    private val feeding = AtomicBoolean(true)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        eglBase = EglBase.create()
        feederThread.start()
        feeder = Handler(feederThread.looper)
    }

    @After
    fun tearDown() {
        feeding.set(false)
        rule.runOnIdle { renderers.forEach { it.release() } }
        feederThread.quitSafely()
        eglBase.release()
    }

    private fun renderer(context: android.content.Context) = TextureVideoRenderer(context).also { renderer ->
        renderer.onFirstFrameRendered = { rendered.set(true) }
        renderer.initRenderer(eglBase.eglBaseContext)
        renderers += renderer
        feedRedFrames(renderer)
    }

    /** Sends a red 320x180 frame every 50 ms until the test ends (frames before the surface exists are dropped). */
    private fun feedRedFrames(renderer: TextureVideoRenderer) {
        feeder.post(object : Runnable {
            override fun run() {
                if (!feeding.get()) return
                val buffer = JavaI420Buffer.allocate(320, 180)
                fill(buffer.dataY, 81)
                fill(buffer.dataU, 90)
                fill(buffer.dataV, 240)
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                renderer.onFrame(frame)
                frame.release()
                feeder.postDelayed(this, 50)
            }
        })
    }

    private fun fill(plane: java.nio.ByteBuffer, value: Int) {
        for (i in 0 until plane.capacity()) plane.put(i, value.toByte())
    }

    private fun awaitRendered() {
        rule.waitUntil(timeoutMillis = 10_000) { rendered.get() }
        // A few more frames so the latest one (after any layout change) is on screen.
        Thread.sleep(300)
        rule.waitForIdle()
    }

    private fun ImageBitmap.isRed(xPx: Float, yPx: Float): Boolean {
        val color = toPixelMap()[xPx.roundToInt(), yPx.roundToInt()]
        return color.red > 0.75f && color.green < 0.25f && color.blue < 0.25f
    }

    @Test
    fun videoFillsTheTileAndIsClippedByTheScrollViewportAndRoundedCard() {
        lateinit var scrollState: ScrollState
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            scrollState = rememberScrollState()
            Box(Modifier.size(400.dp).background(Color.Green)) {
                // Scroll viewport: 300x300 dp at (50, 50).
                Column(
                    Modifier
                        .offset(50.dp, 50.dp)
                        .size(300.dp)
                        .background(Color.Blue)
                        .verticalScroll(scrollState),
                ) {
                    Spacer(Modifier.height(200.dp))
                    Card(shape = RoundedCornerShape(40.dp), modifier = Modifier.size(200.dp)) {
                        AndroidView(factory = ::renderer, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(600.dp))
                }
            }
        }
        awaitRendered()
        // Card top at 200 dp in content; scrolled by 150 dp it spans 50..250 dp of the viewport, i.e. 100..300 dp on screen.
        rule.runOnIdle { runBlocking { scrollState.scrollTo((150 * density).roundToInt()) } }
        awaitRendered()

        val image = rule.onRoot().captureToImage()
        fun dp(v: Int) = v * density
        // Center of the card, and near its left edge at mid height: red with aspect fill (FIT would leave bars above/below, not here) .
        assertTrue("card center", image.isRed(dp(150), dp(200)))
        assertTrue("left edge (aspect fill)", image.isRed(dp(52), dp(200)))
        // Rounded corner cut-out (bottom-left corner of the card): not video.
        assertTrue("rounded corner", !image.isRed(dp(52), dp(298)))
        // Now scroll so the card crosses the viewport's top edge: the part above the viewport is not drawn.
        rule.runOnIdle { runBlocking { scrollState.scrollTo((300 * density).roundToInt()) } }
        awaitRendered()
        val scrolled = rule.onRoot().captureToImage()
        // Card spans -50..150 dp of the viewport → on screen 0..200 dp; the viewport starts at 50 dp.
        assertTrue("inside viewport", scrolled.isRed(dp(150), dp(100)))
        assertTrue("above viewport", !scrolled.isRed(dp(150), dp(25)))
    }

    @Test
    fun videoMovesWithThePagerAndStaysOnItsPage() {
        lateinit var pagerState: PagerState
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            pagerState = rememberPagerState { 2 }
            Box(Modifier.size(400.dp).background(Color.Green)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.offset(50.dp, 50.dp).size(300.dp).background(Color.Blue),
                ) { page ->
                    Box(Modifier.fillMaxSize()) {
                        if (page == 0) {
                            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize()) {
                                AndroidView(factory = ::renderer, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        awaitRendered()
        rule.runOnIdle { runBlocking { pagerState.scrollToPage(0, 0.5f) } }
        awaitRendered()

        val image = rule.onRoot().captureToImage()
        fun dp(v: Int) = v * density
        // Page 0 is shifted left by 150 dp: its right half occupies 50..200 dp on screen.
        assertTrue("visible half of page 0", image.isRed(dp(120), dp(200)))
        assertTrue("page 1 area", !image.isRed(dp(280), dp(200)))
        assertTrue("left of the pager", !image.isRed(dp(25), dp(200)))
    }
}
