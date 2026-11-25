package com.grafizum.lucid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading a moving picture: what the frames agree on is the answer.
 *
 * A reel is read by taking more than one frame, because the picture is never
 * still — a pan can smear a caption in the instant the lens looks, and the next
 * glance is sharp again. This runs two real reads of the same caption, one of
 * them blurred the way motion blurs it, and checks that the answer the frames
 * agree on is the words that both could see.
 */
@RunWith(AndroidJUnit4::class)
class LensBurstTest {

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 460
        const val CAPTION = "your rent is due on friday"
        const val BASELINE = 380f
        const val AT_X = 220
        val AT_Y = (BASELINE - 10f).toInt()
    }

    private var size = 30f

    /** The caption on a plain page — the shape of a read that is not a picture. */
    private fun frame(blur: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 246, 240))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 22)
            textSize = size
            typeface = Typeface.DEFAULT
            // Wide enough to swallow the letters: this is the glance taken
            // while the picture is moving, not a soft focus.
            if (blur) maskFilter = android.graphics.BlurMaskFilter(9f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(CAPTION, 40f, BASELINE, text)
        return bitmap
    }

    private fun read(bitmap: Bitmap): Outcome = runBlocking {
        VisionTranslator(ApplicationProvider.getApplicationContext())
            .read(bitmap, Point(AT_X, AT_Y), "en", slack = 12)
    }

    @Test
    fun aBlurredFrameLosesToASharpOne() {
        val sharp = read(frame(blur = false))
        val blurred = read(frame(blur = true))

        println("sharp=\"${sharp.source}\" blurred=\"${blurred.source}\"")

        assertTrue(
            "the sharp frame should read, but came back empty",
            sharp.source.isNotBlank(),
        )

        val agreed = agreedOutcome(listOf(blurred, sharp))
        assertTrue(
            "the frames agreed on \"${agreed.source}\", which is not the caption",
            agreed.source.lowercase().contains("rent is due"),
        )
    }

    /**
     * The same caption in the two sizes a reel shows it at — a phone's own
     * caption small in the frame, a full-screen one large — so the read does not
     * depend on the text happening to be one particular size.
     */
    @Test
    fun theCaptionIsReadAtBothSizesAReelUses() {
        val sizes = listOf(18f, 34f)
        for (each in sizes) {
            size = each
            val outcome = read(frame(blur = false))
            assertTrue(
                "nothing read at ${each}px: \"${outcome.source}\"",
                outcome.source.lowercase().contains("due on"),
            )
        }
        size = 30f
    }
}
