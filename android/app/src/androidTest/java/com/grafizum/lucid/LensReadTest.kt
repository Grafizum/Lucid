package com.grafizum.lucid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the lens reads, through the shipped path — a frame in, the words on it
 * out.
 *
 * The two failures people report are a picture in a script the app was not
 * looking for (a Japanese poster, read as nothing at all) and small type over a
 * picture (a Reel's caption, read with letters missing). Both are drawn here at
 * the sizes they really are, and both go through [VisionTranslator.read], which
 * is the function the lens itself calls.
 */
@RunWith(AndroidJUnit4::class)
class LensReadTest {

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 600

        /** A Reel's caption: about 10sp on a 2.75x screen. */
        const val CAPTION_PX = 27f

        /** A printed poster's headline, and the line under it. */
        const val POSTER_PX = 64f
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun blank(color: Int = Color.WHITE): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        return bitmap to canvas
    }

    /** The caption of a Reel: small, white, over a picture. */
    private fun caption(): Bitmap {
        val (bitmap, canvas) = blank(Color.rgb(38, 34, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = CAPTION_PX
        }
        canvas.drawText("the keyholder is under the mat", 60f, 300f, paint)
        canvas.drawText("ask the front desk for it", 60f, 360f, paint)
        return bitmap
    }

    /** A poster in a script the Latin model cannot read. */
    private fun poster(vararg lines: String): Bitmap {
        val (bitmap, canvas) = blank()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
            textSize = POSTER_PX
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 60f, 200f + i * 140f, paint) }
        return bitmap
    }

    /** The read the lens makes, aimed at the middle of the drawn text. */
    private fun read(bitmap: Bitmap, target: String, at: Point = Point(300, 292)): Outcome =
        runBlocking {
        val translator = VisionTranslator(context)
        try {
            withTimeout(120_000) {
                translator.read(bitmap, at, target, slack = 8)
            }
        } finally {
            translator.close()
        }
    }

    @Test
    fun aSmallCaptionComesBackWhole() {
        val outcome = read(caption(), target = "en")
        val source = outcome.source
        assertTrue("the caption read as nothing: $outcome", source.isNotBlank())

        // Every word, not the run between the letters the reader dropped.
        for (word in listOf("keyholder", "under", "the", "mat")) {
            assertTrue("'$word' missing from the caption read: '$source'", source.contains(word))
        }
    }

    @Test
    fun aTinyCaptionKeepsItsLetters() {
        // 20px: smaller than anything the app draws, which is where a read used
        // to come back as "the keyho der is under the ma".
        val (bitmap, canvas) = blank(Color.rgb(38, 34, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 20f
        }
        canvas.drawText("the keyholder is under the mat", 60f, 300f, paint)

        val outcome = read(bitmap, target = "en")
        val source = outcome.source
        assertTrue("the caption read as nothing: $outcome", source.isNotBlank())
        assertTrue("letters were lost: '$source'", source.contains("keyholder"))
    }

    @Test
    fun aJapanesePosterIsReadAtAll() {
        val outcome = read(poster("本日のおすすめ", "焼き魚定食 1200円"), target = "ja")
        val source = outcome.source
        assertTrue("the poster read as nothing: $outcome", source.isNotBlank())

        assertTrue("the headline is missing from '$source'", source.contains("おすすめ"))
        assertTrue("the dish is missing from '$source'", source.contains("定食"))
        assertTrue("the price is missing from '$source'", source.contains("1200"))
    }

    /** Chinese shares its characters with Japanese and none of its kana. */
    @Test
    fun aChineseSignIsReadAsChinese() {
        val outcome = read(poster("本日推荐", "烧鱼定食 1200元"), target = "zh")
        val source = outcome.source
        val flat = source.replace("\n", " | ")
        assertTrue("the sign read as nothing: $outcome", source.isNotBlank())

        assertTrue("the date is missing from '$flat'", source.contains("本日"))
        assertTrue("the price is missing from '$flat'", source.contains("1200"))
        // The characters the two CJK scripts do not share: a Chinese model read
        // 烧 and 荐, a Japanese one cannot.
        assertTrue("the dish is missing from '$flat'", source.contains("烧"))
    }

    @Test
    fun aKoreanSignIsReadAtAll() {
        val outcome = read(poster("오늘의 추천", "생선 정식 1200원"), target = "ko")
        val source = outcome.source
        assertTrue("the sign read as nothing: $outcome", source.isNotBlank())

        assertTrue("the headline is missing from '$source'", source.contains("추천"))
        assertTrue("the price is missing from '$source'", source.contains("1200"))
    }
}
