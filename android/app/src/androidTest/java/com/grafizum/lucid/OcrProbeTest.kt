package com.grafizum.lucid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Temporary probe: what the lens's own OCR makes of Japanese and of small text. */
@RunWith(AndroidJUnit4::class)
class OcrProbeTest {

    private val report = StringBuilder()

    private fun lines(bitmap: Bitmap): List<String> = runBlocking {
        val result = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bitmap, 0))
            .await()
        result.textBlocks.flatMap { b -> b.lines }.map { it.text }
    }

    private fun note(name: String, value: Any?) {
        report.append(name).append(" -> ").append(value).append('\n')
    }

    private fun white(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        return bitmap to canvas
    }

    private fun upscale(bitmap: Bitmap, factor: Float): Bitmap =
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt(),
            (bitmap.height * factor).toInt(),
            true,
        )

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Japanese, the language the tour translates.
        val (ja, jaCanvas) = white(1080, 600)
        val jpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
            textSize = 44f
        }
        jaCanvas.drawText("キーホルダーです。わたしのです。", 40f, 120f, jpPaint)
        jaCanvas.drawText("オスカーはキーホルダーは自分のだと言っています。", 40f, 220f, jpPaint)
        note("latin on japanese", lines(ja))

        // 2. Small Latin text, as a caption in a Reel or a UI label.
        val (small, smallCanvas) = white(1080, 400)
        smallCanvas.drawColor(Color.WHITE)
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
            textSize = 18f
        }
        smallCanvas.drawText("Oscar says the keyholder is his own.", 30f, 60f, smallPaint)
        smallCanvas.drawText("She left it on the table by the window.", 30f, 90f, smallPaint)
        note("latin small raw", lines(small))
        note("latin small 2x", lines(upscale(small, 2f)))

        // 3. Small Latin text over a busy, video-like background.
        val (busy, busyCanvas) = white(1080, 400)
        val sky = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 1080f, 400f,
                intArrayOf(Color.rgb(40, 60, 90), Color.rgb(200, 120, 60), Color.rgb(20, 30, 40)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        busyCanvas.drawRect(0f, 0f, 1080f, 400f, sky)
        val noise = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
        var x = 0f
        while (x < 1080f) {
            busyCanvas.drawCircle(x, 80f + (x % 200), 26f, noise)
            x += 70f
        }
        val busyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 26f
        }
        busyCanvas.drawText("Oscar says the keyholder is his own.", 40f, 200f, busyPaint)
        note("busy raw", lines(busy))
        note("busy 2x", lines(upscale(busy, 2f)))

        File(context.filesDir, "ocr-probe.txt").writeText(report.toString())
        android.util.Log.i("LucidProbe", report.toString())
    }
}
