package com.grafizum.lucid

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The seven loops the onboarding plays, drawn in the app's own dimensions.
 *
 * Everything inside the screen is measured in the real app's dp and then
 * multiplied by one factor, [unit], which is the mock screen's width divided by
 * a 393 dp phone. So the lens is 42 dp, the slide is 320 × 58 dp, the delete lip
 * is 156 × 18 dp and the card is the full width less 16 dp — the sizes the
 * service and the layout really use, only projected smaller. That is what keeps
 * the loops honest: nothing here is a drawing of a lens, it is the lens.
 *
 * The welcome comes before this, on its own screen; by the time the tour shows,
 * the handset is already open.
 */
class OnboardingScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LAST = 6

        /** How long one turn of each loop takes, in milliseconds. */
        private val DURATIONS = longArrayOf(6400L, 7800L, 7200L, 8200L, 6600L, 9800L, 9200L)

        /** What each loop is doing, for anyone who cannot see it. */
        private val DESCRIPTIONS = arrayOf(
            "The slider is dragged across, and the lens comes up on Lucid's own screen.",
            "In a chat, the lens is rested on a line of Japanese and the English translation opens under it.",
            "A tap opens the language list.",
            "A long press fades the lens back and stops it reading; a second press wakes it.",
            "The lens is carried to the bottom edge, where Delete opens and takes it.",
            "The quick settings button turns the lens on, and a second tap turns it off again.",
            "Android's accessibility list, where Lucid's text access row is switched on.",
        )

        /** One line of the chat the tour is about: English from the reader, Japanese from Anna. */
        private class ChatLine(
            val text: String,
            val incoming: Boolean,
            val widthDp: Float,
            val time: String,
            /** The line the third loop comes to rest on. */
            val focus: Boolean = false,
        )

        /** The glyphs the shade's buttons carry. */
        private enum class ShadeGlyph { WIFI, BLUETOOTH, LUCID, TORCH }

        /** The reference phone: the screen the dimensions were designed on. */
        private const val REFERENCE_WIDTH_DP = 393f
    }

    var page: Int = 0
        set(value) {
            val next = value.coerceIn(0, LAST)
            if (field == next && animator != null) return
            field = next
            contentDescription = DESCRIPTIONS[next]
            restart()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** Pixels per real dp of the app, once [panel] is known. */
    private var unit = 1f
    private fun m(realDp: Float) = realDp * unit

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val ink = color(R.color.screen_ink)
    private val muted = color(R.color.screen_muted)
    private val faint = color(R.color.screen_faint)
    private val band = color(R.color.screen_band)
    private val paper = color(R.color.screen_canvas)
    private val cardPaper = color(R.color.screen_card)
    private val edge = color(R.color.screen_edge)
    private val button = color(R.color.screen_button)
    private val accent = color(R.color.screen_accent)
    private val accentSoft = color(R.color.screen_accent_soft)
    private val shade = color(R.color.screen_shade)
    private val dot = color(R.color.screen_dot)
    private val divider = color(R.color.screen_divider)
    private val cream = color(R.color.cream_50)

    /**
     * The lens itself, as the service composes it: [bubble_bg] at 42 dp with
     * [iris] at 18 dp on top. Drawing the two drawables rather than rebuilding
     * them is what makes the disc the colour it really is — a hand-painted
     * gradient can only approximate a four-stop theme shape, and it did not.
     */
    private val lensDisc = ContextCompat.getDrawable(context, R.drawable.bubble_bg)?.mutate()
    private val lensIris = ContextCompat.getDrawable(context, R.drawable.iris)?.mutate()

    private val cardSurface = color(R.color.card_surface)
    private val cardEdge = color(R.color.card_edge)
    private val cardInk = color(R.color.card_ink)
    private val cardMuted = color(R.color.card_muted)
    private val cardFaint = color(R.color.card_faint)
    private val cardAccent = color(R.color.card_accent)
    private val cardChipFill = color(R.color.card_chip_fill)
    private val cardChipEdge = color(R.color.card_chip_edge)
    private val cardAccentFill = color(R.color.card_chip_accent_fill)
    private val cardAccentEdge = color(R.color.card_chip_accent_edge)

    private val lipSurface = color(R.color.lens_lip_surface)
    private val lipRing = color(R.color.lens_lip_ring)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.lucid_sans)
        isSubpixelText = true
    }
    private val clip = Path()
    private val gradientRect = RectF()

    private val tileGlyph = ContextCompat.getDrawable(context, R.drawable.ic_tile_mark)?.mutate()
    private val closeGlyph = ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()

    /** The settings cog, drawn from the app's own vector: the header carries the
     *  same mark as home, so a redraw would only be an imitation of it. */
    private val gearGlyph = ContextCompat.getDrawable(context, R.drawable.ic_gear)?.mutate()

    // The shade's buttons carry the glyphs the phone's own shade carries, not
    // hand-built shapes: at 20 dp a drawn arc reads as a squiggle, while a
    // vector at the system's own weight reads as a button.
    private val wifiGlyph = ContextCompat.getDrawable(context, R.drawable.ic_qs_wifi)?.mutate()
    private val bluetoothGlyph = ContextCompat.getDrawable(context, R.drawable.ic_qs_bluetooth)?.mutate()
    private val flashlightGlyph = ContextCompat.getDrawable(context, R.drawable.ic_qs_flashlight)?.mutate()

    /** A night screen: the shade dims it rather than painting it black. */
    private val night = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The shade's own sheet: warm frosted by day, a grey step in the dark. */
    private val shadeSurface =
        if (night) color(R.color.screen_card) else blend(paper, band, 0.42f)

    /** A button at rest: a step off the sheet, so it reads as raised on both. */
    private val shadeTileSurface =
        if (night) blend(color(R.color.screen_card), ink, 0.10f) else cardPaper

    /**
     * The other app's chat, dressed for the theme it is in.
     *
     * At night it keeps the day screen's relationships instead of inverting
     * into mud: a dark warm surface, a raised bubble for the other side and a
     * deep clay for mine. Every bubble is solid — a translucent bubble over a
     * dark screen is what made it look like dirty glass.
     */
    private val chatSurface = if (night) blend(paper, band, 0.55f) else cream

    /** A message from the other side: white by day, warm charcoal at night. */
    private val chatBubble =
        if (night) blend(paper, cream, 0.11f) else color(R.color.screen_card)

    /** Mine: pale clay by day, deep clay at night. */
    private val chatMine =
        if (night) blend(paper, color(R.color.clay_500), 0.42f)
        else blend(cream, accentSoft, 0.30f)

    /** The screen being shown: a crop of the handset, not the whole handset. */
    private val panel = RectF()

    /** The one message the read loop is about, laid out as the chat is drawn. */
    private val readAt = RectF()

    /** The paper the screen last opened with, so the crop's fade matches it. */
    private var screenSurface = 0

    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = DESCRIPTIONS[0]
        setWillNotDraw(false)
    }

    /* ── the loop ──────────────────────────────────────────────────── */

    private fun restart() {
        animator?.cancel()
        animator = null
        t = 0f
        invalidate()
        if (!isAttachedToWindow) return
        if (!Capabilities.animationsOn()) {
            t = 0.55f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATIONS[page]
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        // The whole handset, not a crop of one: a 393 × 852 dp phone, as large
        // as the loop's place allows, with room at both sides for the keys and
        // below it for the shadow it casts.
        val maxW = minOf(w - dp(74f), dp(208f)).coerceAtLeast(dp(110f))
        val maxH = (h - dp(10f)).coerceAtLeast(dp(240f))
        val ratio = 852f / REFERENCE_WIDTH_DP
        var width = maxW
        var height = width * ratio
        if (height > maxH) {
            height = maxH
            width = height / ratio
        }
        val left = (w - width) / 2f
        val top = (h - height) / 2f
        panel.set(left, top, left + width, top + height)
        unit = panel.width() / REFERENCE_WIDTH_DP
        restart()
    }

    /* ── paint ─────────────────────────────────────────────────────── */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (panel.isEmpty) return

        when (page) {
            0 -> sceneStart(canvas)
            1 -> sceneRead(canvas)
            2 -> sceneTap(canvas)
            3 -> sceneHold(canvas)
            4 -> sceneDelete(canvas)
            5 -> sceneTile(canvas)
            else -> sceneAccess(canvas)
        }
    }

    /* ── the screen ────────────────────────────────────────────────── */

    /**
     * A handset screen, cropped: rounded, clipped, fading into the page.
     *
     * [surface] is the other app's own paper. A chat is not Lucid's screen, so
     * it is one step lighter than the page: the screen has to read as a screen,
     * and the cream card on it has to read as a card.
     */
    private fun openScreen(canvas: Canvas, surface: Int = paper) {
        screenSurface = surface
        drawHandset(canvas)

        val radius = m(26f)
        fill.color = surface
        canvas.drawRoundRect(panel, radius, radius, fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(edge, 0.35f)
        canvas.drawRoundRect(panel, radius, radius, line)

        clip.reset()
        clip.addRoundRect(panel, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
    }

    private fun closeScreen(canvas: Canvas) {
        // the gesture bar the lens has to stay clear of
        val barW = m(108f)
        val barH = m(3f)
        fill.color = withAlpha(ink, 0.28f)
        canvas.drawRoundRect(
            panel.centerX() - barW / 2f, panel.bottom - m(9f),
            panel.centerX() + barW / 2f, panel.bottom - m(9f) + barH,
            barH / 2f, barH / 2f, fill,
        )

        canvas.restore()
    }

    /**
     * The phone the screen sits in — the handset, not a rounded rectangle.
     *
     * It is drawn rather than stamped: the frame is a slab with thickness (lit
     * at the top-left, falling away at the foot), the screen is recessed behind
     * a rim of shadow, and the keys stand a little proud of each side, their
     * roots hidden under the body so they read as one piece of hardware.
     */
    private fun drawHandset(canvas: Canvas) {
        val bezelX = m(9f)
        val body = RectF(
            panel.left - bezelX,
            panel.top - m(12f),
            panel.right + bezelX,
            panel.bottom + m(14f),
        )
        // The frame's corners are concentric with the screen's: the bezel is
        // [bezelX] wide on the sides, so the body's radius is the screen's plus
        // that width. Equal radii pinched the bezel at every corner and left
        // the recessed rim cutting a second curve across it.
        val radius = m(26f) + bezelX

        // What it casts on the page: soft rings under the foot, never a hard
        // drop shadow — the same lift the card gets.
        for (step in 6 downTo 1) {
            val grow = m(1.3f) * step
            fill.color = withAlpha(shade, 0.040f)
            canvas.drawRoundRect(
                body.left - grow * 0.45f,
                body.top + m(5f) - grow * 0.15f,
                body.right + grow * 0.45f,
                body.bottom + m(7f) + grow,
                radius + grow,
                radius + grow,
                fill,
            )
        }

        // The body keeps its material all the way round: the light end is a
        // shade brighter than the shell, never close enough to the page to
        // dissolve into it — a frame that vanishes at one corner reads as a
        // seam between two boards rather than one machined piece.
        val shell = color(R.color.screen_shell)
        val lit = blend(shell, color(R.color.cream_50), 0.30f)
        val deep = blend(shell, color(R.color.ink_900), 0.34f)

        // the keys first, so the body covers where they leave the frame
        key(canvas, body, right = true, top = body.top + m(156f), height = m(30f), depth = m(2.2f), lit = lit, deep = deep)
        key(canvas, body, right = false, top = body.top + m(104f), height = m(13f), depth = m(1.8f), lit = lit, deep = deep)
        key(canvas, body, right = false, top = body.top + m(132f), height = m(48f), depth = m(2.2f), lit = lit, deep = deep)

        // the frame: one slab, lit from the top-left and losing its light at
        // the bottom-right, which is what makes it a body and not a border
        fill.shader = LinearGradient(
            body.left, body.top, body.right, body.bottom,
            intArrayOf(lit, shell, deep),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(body, radius, radius, fill)
        fill.shader = null

        // the silhouette: a hairline of ink all the way round, so the handset
        // holds its shape against the page however the light falls on it
        line.strokeWidth = m(0.8f)
        line.color = withAlpha(color(R.color.ink_900), 0.16f)
        canvas.drawRoundRect(
            body.left + m(0.4f), body.top + m(0.4f),
            body.right - m(0.4f), body.bottom - m(0.4f),
            radius, radius, line,
        )

        // the machined edge, brightest where the light lands: inside the
        // silhouette, so it reads as a cut on the frame and not a second wall
        line.strokeWidth = m(0.9f)
        line.color = withAlpha(color(R.color.cream_50), 0.38f)
        canvas.drawRoundRect(
            body.left + m(1.6f), body.top + m(1.6f),
            body.right - m(1.6f), body.bottom - m(1.6f),
            radius - m(1.2f), radius - m(1.2f), line,
        )

        // the screen is set into the frame: one soft rim, concentric with the
        // screen's own corner, so it never crosses the bezel's curve
        val rim = RectF(panel)
        rim.inset(-m(1.2f), -m(1.2f))
        val rimRadius = m(26f) + m(1.2f)
        line.strokeWidth = m(1.8f)
        line.color = withAlpha(deep, 0.38f)
        canvas.drawRoundRect(rim, rimRadius, rimRadius, line)
    }

    /** One side key: it stands [depth] proud of the frame and is cut off inside it. */
    private fun key(
        canvas: Canvas,
        body: RectF,
        right: Boolean,
        top: Float,
        height: Float,
        depth: Float,
        lit: Int,
        deep: Int,
    ) {
        val root = m(4f)
        val rect = if (right) {
            RectF(body.right - root, top, body.right + depth, top + height)
        } else {
            RectF(body.left - depth, top, body.left + root, top + height)
        }
        fill.color = withAlpha(deep, 0.95f)
        canvas.drawRoundRect(rect, depth, depth, fill)

        // the key's own long edge catches the light, the way the frame does
        line.strokeWidth = m(0.7f)
        line.color = withAlpha(lit, 0.55f)
        val x = if (right) rect.right - m(0.2f) else rect.left + m(0.2f)
        canvas.drawLine(x, rect.top + depth, x, rect.bottom - depth, line)
    }

    /**
     * The chat the lens is for: Anna writes in Japanese, the person holding the
     * phone writes in English. A real thread — her avatar and the call button in
     * the bar, today's divider, tails on the bubbles, a time under each one.
     */
    private fun drawChat(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return

        val bar = panel.top + m(8f)

        // the other app's bar: back, her avatar, her name and language, the call
        label(canvas, panel.left + m(12f), bar + m(19f), "‹", withAlpha(muted, alpha), m(20f))
        fill.color = withAlpha(accentSoft, 0.22f * alpha)
        canvas.drawCircle(panel.left + m(39f), bar + m(14f), m(11f), fill)
        label(
            canvas, panel.left + m(39f), bar + m(18.5f), "A",
            withAlpha(accent, 0.95f * alpha), m(11f), Paint.Align.CENTER,
        )
        label(canvas, panel.left + m(55f), bar + m(12f), "Anna", withAlpha(ink, alpha), m(13.5f))
        label(canvas, panel.left + m(55f), bar + m(24f), "Japanese", withAlpha(faint, alpha), m(9f))
        line.strokeWidth = m(1.1f)
        line.color = withAlpha(muted, 0.7f * alpha)
        canvas.drawRoundRect(
            panel.right - m(48f), bar + m(5f), panel.right - m(13f), bar + m(23f),
            m(9f), m(9f), line,
        )
        label(
            canvas, panel.right - m(30.5f), bar + m(17f), "Call",
            withAlpha(muted, 0.9f * alpha), m(8f), Paint.Align.CENTER,
        )

        fill.color = withAlpha(divider, alpha)
        canvas.drawRect(panel.left, bar + m(30f), panel.right, bar + m(31f), fill)

        // today's divider
        text.textSize = m(8.5f)
        val chipW = text.measureText("TODAY") + m(18f)
        fill.color = withAlpha(band, 0.9f * alpha)
        canvas.drawRoundRect(
            panel.centerX() - chipW / 2f, bar + m(38f),
            panel.centerX() + chipW / 2f, bar + m(52f), m(7f), m(7f), fill,
        )
        label(
            canvas, panel.centerX(), bar + m(47.5f), "TODAY",
            withAlpha(faint, alpha), m(8.5f), Paint.Align.CENTER,
        )

        val messages = listOf(
            ChatLine("今日はありがとう。", true, 150f, "20:30"),
            ChatLine("Anytime — the slides went well.", false, 214f, "20:31"),
            ChatLine("そう言ってもらえると嬉しいです。", true, 212f, "20:33"),
            ChatLine("Where do we meet tomorrow?", false, 208f, "20:36"),
            ChatLine("3階の会議室です。", true, 150f, "20:38"),
            ChatLine("Great. And what time again?", false, 206f, "20:40"),
            ChatLine("明日の会議は10時からです。", true, 206f, "20:42", focus = true),
            ChatLine("Perfect. See you at ten.", false, 186f, "20:44"),
            ChatLine("おやすみなさい！", true, 160f, "20:46"),
        )
        val lastMine = messages.indexOfLast { !it.incoming }
        val wash = seg(t, 0.14f, 0.26f) * (if (page == 1) 1f else 0f)
        // The thread hangs from the foot, the way a chat does when it is caught
        // up: the newest message sits just above the field, not stranded at the
        // top of a screen with an empty half under it.
        val widths = messages.map { m(it.widthDp) }
        val heights = messages.mapIndexed { index, message -> bubbleHeight(message.text, widths[index]) }
        var cursor = bar + m(58f)
        messages.forEachIndexed { index, message ->
            val width = widths[index]
            val bodyH = heights[index]
            val left = if (message.incoming) panel.left + m(16f) else panel.right - m(16f) - width
            if (message.focus) {
                readAt.set(left, cursor, left + width, cursor + bodyH)
                if (wash > 0.01f) {
                    fill.color = withAlpha(accentSoft, 0.20f * wash * alpha)
                    canvas.drawRoundRect(
                        readAt.left - m(4f), readAt.top - m(4f),
                        readAt.right + m(4f), readAt.bottom + m(4f),
                        m(18f), m(18f), fill,
                    )
                }
            }
            bubble(
                canvas, alpha, incoming = message.incoming, top = cursor,
                value = message.text, width = width, alignRight = !message.incoming,
                time = message.time,
                read = index == lastMine,
            )
            cursor += bodyH + m(13f)
        }

        drawInputBar(canvas, alpha)
    }

    private fun bubble(
        canvas: Canvas,
        alpha: Float,
        incoming: Boolean,
        top: Float,
        value: String,
        width: Float,
        alignRight: Boolean = false,
        time: String = "",
        read: Boolean = false,
    ) {
        val left = if (alignRight) panel.right - m(16f) - width else panel.left + m(16f)
        val bodyH = bubbleHeight(value, width)
        // mine reads as a tinted bubble, the other side's as a white one, the
        // way a thread really looks — and the other side's carries its hairline
        fill.color = withAlpha(if (alignRight) chatMine else chatBubble, alpha)
        canvas.drawRoundRect(left, top, left + width, top + bodyH, m(16f), m(16f), fill)
        if (!alignRight) {
            line.strokeWidth = m(1f)
            line.color = if (night) withAlpha(divider, 0.45f * alpha) else withAlpha(cardEdge, 0.75f * alpha)
            canvas.drawRoundRect(left, top, left + width, top + bodyH, m(16f), m(16f), line)
        }

        // the tail, on the side the bubble belongs to
        clip.reset()
        if (alignRight) {
            clip.moveTo(left + width - m(22f), top + bodyH - m(6f))
            clip.lineTo(left + width - m(4f), top + bodyH - m(6f))
            clip.lineTo(left + width - m(4f), top + bodyH + m(7f))
        } else {
            clip.moveTo(left + m(22f), top + bodyH - m(6f))
            clip.lineTo(left + m(4f), top + bodyH - m(6f))
            clip.lineTo(left + m(4f), top + bodyH + m(7f))
        }
        clip.close()
        canvas.drawPath(clip, fill)

        // the words, placed by the font's own metrics rather than by a guessed
        // offset — that is what had the text sitting high in a tall bubble
        text.textSize = m(13.5f)
        val fm = text.fontMetrics
        wrapLines(value, width - m(26f)).forEachIndexed { row, rowText ->
            label(
                canvas, left + m(13f),
                top + m(9f) - fm.ascent + row * m(17f),
                rowText, withAlpha(ink, 0.90f * alpha), m(13.5f),
            )
        }

        // the time, inside the bubble at its foot as the chat app has it, with
        // the read mark on the last of mine
        if (time.isNotEmpty()) {
            val ty = top + bodyH - m(7f)
            label(
                canvas, left + width - m(13f), ty, time,
                withAlpha(faint, 0.95f * alpha), m(8f), Paint.Align.RIGHT,
            )
            if (read) {
                text.textSize = m(8f)
                val ticksLeft = left + width - m(13f) - text.measureText(time) - m(7f)
                line.strokeWidth = m(1f)
                line.color = withAlpha(accent, 0.85f * alpha)
                canvas.drawLine(ticksLeft - m(4.6f), ty - m(2.2f), ticksLeft - m(2.6f), ty - m(0.2f), line)
                canvas.drawLine(ticksLeft - m(2.6f), ty - m(0.2f), ticksLeft + m(0.4f), ty - m(4.6f), line)
                canvas.drawLine(ticksLeft - m(0.6f), ty - m(2.2f), ticksLeft + m(1.4f), ty - m(0.2f), line)
                canvas.drawLine(ticksLeft + m(1.4f), ty - m(0.2f), ticksLeft + m(4.4f), ty - m(4.6f), line)
            }
        }
    }

    private fun bubbleHeight(value: String, width: Float): Float {
        text.textSize = m(13.5f)
        val fm = text.fontMetrics
        val lines = wrapLines(value, width - m(26f)).size.coerceAtLeast(1)
        // the words, then the row the time sits on, then the padding around them
        return m(9f) * 2 + (lines - 1) * m(17f) + (fm.descent - fm.ascent) + m(12f)
    }

    /**
     * The foot of the conversation: the field, the word a person would type,
     * and the send button. A thread with no way to answer does not read as a
     * chat, it reads as a picture of one.
     */
    private fun drawInputBar(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        val h = m(56f)
        val top = panel.bottom - m(24f) - h

        val barSurface = if (night) blend(chatSurface, cream, 0.06f) else cardSurface
        fill.color = withAlpha(barSurface, alpha)
        canvas.drawRect(panel.left, top, panel.right, top + h, fill)
        fill.color = withAlpha(divider, alpha)
        canvas.drawRect(panel.left, top, panel.right, top + m(1f), fill)

        // the field itself
        val fieldLeft = panel.left + m(12f)
        val fieldRight = panel.right - m(62f)
        val fieldTop = top + m(9f)
        val fieldBottom = top + h - m(9f)
        val fieldSurface = if (night) blend(chatSurface, cream, 0.10f) else band
        fill.color = withAlpha(fieldSurface, if (night) alpha else 0.60f * alpha)
        canvas.drawRoundRect(
            fieldLeft, fieldTop, fieldRight, fieldBottom,
            (fieldBottom - fieldTop) / 2f, (fieldBottom - fieldTop) / 2f, fill,
        )
        line.strokeWidth = m(1f)
        line.color = withAlpha(cardEdge, 0.55f * alpha)
        canvas.drawRoundRect(
            fieldLeft, fieldTop, fieldRight, fieldBottom,
            (fieldBottom - fieldTop) / 2f, (fieldBottom - fieldTop) / 2f, line,
        )
        label(
            canvas, fieldLeft + m(16f), (fieldTop + fieldBottom) / 2f + m(3.2f),
            "Message", withAlpha(faint, 0.95f * alpha), m(12f),
        )

        // the send button: the one clay surface on the thread
        val sendCx = panel.right - m(38f)
        val sendCy = (fieldTop + fieldBottom) / 2f
        val r = m(17f)
        fill.color = withAlpha(accent, alpha)
        canvas.drawCircle(sendCx, sendCy, r, fill)
        line.strokeWidth = m(1.5f)
        // the arrow has to read on the button whatever colour the theme's
        // accent is: cream on the day clay, ink on the night one
        line.color = withAlpha(if (night) color(R.color.ink_900) else cream, alpha)
        canvas.drawLine(sendCx, sendCy + m(5f), sendCx, sendCy - m(5f), line)
        canvas.drawLine(sendCx - m(3.6f), sendCy - m(1.6f), sendCx, sendCy - m(5.2f), line)
        canvas.drawLine(sendCx + m(3.6f), sendCy - m(1.6f), sendCx, sendCy - m(5.2f), line)
    }

    /* ── page 1: the slide, exactly as the main screen has it ───────── */

    private fun sceneStart(canvas: Canvas) {
        // Lucid's own screen, while the lens is off
        openScreen(canvas)
        drawLucidHome(canvas, alpha = 1f)
        drawSlide(canvas)
        closeScreen(canvas)

        // The knob lands and the lens comes up on the same screen. The slide is
        // the app's own control, so the lens starts inside Lucid — no jump into
        // another app here; the chat belongs to the next page.
        val arrive = ease(seg(t, 0.26f, 0.42f))
        val leave = 1f - seg(t, 0.92f, 1f)
        if (arrive > 0.01f) {
            val r = m(21f)
            val cx = panel.centerX()
            val cy = panel.top + panel.height() * 0.46f
            ripple(canvas, cx, cy, 0.24f, 0.46f, arrive)
            lens(canvas, cx, cy, r, arrive * leave, glow = 0.30f + 0.25f * pulse())
        }
    }

    /** The main screen as it now is: the top bar, the pitch, then the band. */
    private fun drawLucidHome(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        val left = panel.left + m(20f)
        val right = panel.right - m(20f)
        val barCy = panel.top + m(46f)

        // the top bar: the mark, the wordmark, then the gear. The theme used to
         // have a disc of its own up here; it lives in Settings now, so the bar
         // carries the one control the page actually has.
        lens(canvas, left + m(15f), barCy, m(15f), alpha)
        label(canvas, left + m(38f), barCy + m(9f), "Lucid", withAlpha(ink, alpha), m(25f))
        gearButton(canvas, right - m(36f), barCy, m(17f), alpha)

        // the pitch, in the app's own words
        wrap(
            canvas,
            "Reads whatever the lens sits over — WhatsApp, a browser, any app — and translates it in place.",
            left, panel.top + m(96f), right - left, m(17f), withAlpha(muted, alpha), m(12f),
        )

        // the band: the language row, then the setup card's rows
        val bandTop = panel.top + m(152f)
        val bandBottom = bandTop + m(150f)
        fill.color = withAlpha(band, 0.9f * alpha)
        canvas.drawRoundRect(left - m(2f), bandTop, right + m(2f), bandBottom, m(14f), m(14f), fill)
        fill.color = withAlpha(cardPaper, alpha)
        canvas.drawRoundRect(
            left + m(8f), bandTop + m(10f), right - m(8f), bandBottom - m(10f),
            m(10f), m(10f), fill,
        )

        val cardLeft = left + m(20f)
        val cardRight = right - m(20f)
        val cardTop = bandTop + m(10f)
        label(canvas, cardLeft, cardTop + m(24f), "TRANSLATE INTO", withAlpha(faint, alpha), m(9f))
        // The setting itself, never an example: a tour showing "English" over a
        // lens set to Japanese is a picture of a different app.
        label(
            canvas, cardRight, cardTop + m(26f), targetName(), withAlpha(ink, 0.85f * alpha),
            m(15f), Paint.Align.RIGHT,
        )
        fill.color = withAlpha(divider, alpha)
        canvas.drawRect(cardLeft, cardTop + m(36f), cardRight, cardTop + m(37f), fill)

        val rows = arrayOf("Float over other apps" to "Allowed", "Text access" to "Allow")
        rows.forEachIndexed { index, row ->
            val rowTop = cardTop + m(50f) + m(40f) * index
            fill.color = withAlpha(if (index == 0) color(R.color.olive_600) else dot, alpha)
            canvas.drawCircle(cardLeft + m(4f), rowTop + m(3f), m(3.5f), fill)
            label(canvas, cardLeft + m(15f), rowTop + m(8f), row.first, withAlpha(ink, alpha), m(11.5f))
            val labelW = text.measureText(row.second) + m(22f)
            fill.color = withAlpha(band, alpha)
            canvas.drawRoundRect(
                cardRight - labelW, rowTop - m(9f), cardRight, rowTop + m(17f),
                m(13f), m(13f), fill,
            )
            label(
                canvas, cardRight - labelW / 2f, rowTop + m(8f), row.second,
                withAlpha(muted, alpha), m(10f), Paint.Align.CENTER,
            )
            if (index == 0) {
                fill.color = withAlpha(divider, alpha)
                canvas.drawRect(cardLeft, rowTop + m(27f), cardRight, rowTop + m(28f), fill)
            }
        }
    }

    /**
     * The settings button in the app's bar: the 34 dp ghost chip with the 20 dp
     * cog inside it, exactly as `activity_main.xml` lays it out. The chip is drawn
     * rather than inflated so its hairline follows the screen's own scale, and the
     * cog is the app's vector so the tour shows the control home really has.
     *
     * The theme's own moon/sun disc used to sit here and is gone: a three-way
     * choice belongs on a page with room to name all three, which is Settings.
     */
    private fun gearButton(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        fill.color = withAlpha(button, alpha)
        canvas.drawCircle(cx, cy, r, fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(edge, 0.55f * alpha)
        canvas.drawCircle(cx, cy, r, line)

        // the image is 20 dp inside 34 dp with 9 dp of padding: 16 dp on screen
        gearGlyph?.let { glyph ->
            val half = r * 16f / 34f
            glyph.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt(),
            )
            glyph.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            glyph.draw(canvas)
        }
    }

    /** The one CTA: a 320 × 58 dp capsule with the well on its left seat. */
    private fun drawSlide(canvas: Canvas) {
        val trackW = m(320f)
        val trackH = m(58f)
        val left = panel.centerX() - trackW / 2f
        val bottom = panel.bottom - m(26f)
        val fade = 1f

        fill.color = withAlpha(band, fade)
        canvas.drawRoundRect(left, bottom - trackH, left + trackW, bottom, trackH / 2f, trackH / 2f, fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(shade, 0.10f * fade)
        canvas.drawRoundRect(left, bottom - trackH, left + trackW, bottom, trackH / 2f, trackH / 2f, line)

        val inset = m(8f)
        val radius = m(21f)
        val travel = trackW - 2 * inset - 2 * radius
        val p = ease(seg(t, 0.02f, 0.30f))
        val kx = left + inset + radius + travel * p
        val ky = bottom - trackH / 2f

        // the words the control carries
        label(
            canvas, left + inset + 2 * radius + m(15f), ky + m(4f),
            if (t < 0.30f) "Start the lens" else "Stop the lens",
            withAlpha(muted, fade * (1f - seg(t, 0.10f, 0.24f))), m(13.5f),
        )

        fill.color = withAlpha(shade, 0.18f * fade)
        canvas.drawCircle(kx, ky + m(2f), radius, fill)
        fill.color = withAlpha(cardPaper, fade)
        canvas.drawCircle(kx, ky, radius, fill)

        // the arrow turns into the check as the slide commits
        val check = ease(seg(t, 0.24f, 0.31f))
        val s = radius * 0.52f
        line.strokeWidth = m(1.7f)
        line.color = withAlpha(accent, fade * (1f - check))
        canvas.drawLine(kx - s, ky, kx + s, ky, line)
        canvas.drawLine(kx + s * 0.62f, ky - s * 0.42f, kx + s, ky, line)
        canvas.drawLine(kx + s * 0.62f, ky + s * 0.42f, kx + s, ky, line)
        line.color = withAlpha(accent, fade * check)
        line.strokeWidth = m(1.9f)
        canvas.drawLine(kx - s * 0.62f, ky + s * 0.06f, kx - s * 0.14f, ky + s * 0.54f, line)
        canvas.drawLine(kx - s * 0.14f, ky + s * 0.54f, kx + s * 0.70f, ky - s * 0.48f, line)

        finger(canvas, kx, ky, 1f - seg(t, 0.30f, 0.38f), pressed = true)
    }

    /* ── page 2: the tap that opens the language list ──────────────── */

    private fun sceneTap(canvas: Canvas) {
        openScreen(canvas, chatSurface)
        drawChat(canvas, 1f)
        closeScreen(canvas)

        val r = m(21f)
        val cx = panel.centerX()
        val cy = panel.top + panel.height() * 0.42f

        val leave = 1f - seg(t, 0.92f, 1f)
        lens(canvas, cx, cy, r, leave, glow = 0.22f)

        // the tap, and the list it opens. The press that pauses the lens is a
        // gesture of its own, with a loop of its own on the next page, so this
        // one is left to say only what a tap does.
        ripple(canvas, cx, cy, 0.02f, 0.20f, if (t < 0.26f) 1f else 0f)
        if (t < 0.12f) finger(canvas, cx, cy + r * 0.8f, 1f - seg(t, 0.06f, 0.12f), pressed = true)
        drawLanguageSheet(canvas, cx, cy, r)
    }

    /* ── page 3: press and hold to pause the lens ──────────────────── */

    private fun sceneHold(canvas: Canvas) {
        openScreen(canvas, chatSurface)
        drawChat(canvas, 1f)
        closeScreen(canvas)

        val r = m(21f)
        val cx = panel.centerX()
        val cy = panel.top + panel.height() * 0.42f

        val leave = 1f - seg(t, 0.96f, 1f)

        // Two holds, because the gesture is a switch: the first pauses the lens,
        // the second wakes it. The service leaves the lens its size and place
        // and takes its alpha to a third, so it is dimmed, not gone — there it
        // is to be pressed again, which is the whole point of the loop.
        val pause = ease(seg(t, 0.42f, 0.54f))
        val wake = ease(seg(t, 0.88f, 0.96f))
        val dim = 1f - 0.70f * (pause * (1f - wake))
        lens(canvas, cx, cy, r, dim * leave, glow = 0.22f * (1f - pause))

        // the ring a long press fills under the thumb, then the same again to wake
        val first = ease(seg(t, 0.14f, 0.40f))
        val second = ease(seg(t, 0.66f, 0.88f))
        // the thumb rests on the lens's lower edge, not its middle: a fingertip
        // over the disc would read as a smudge on it rather than as a press
        val thumbX = cx + r * 0.66f
        val thumbY = cy + r * 1.02f
        if (first > 0.01f && pause < 0.6f) {
            holdRing(canvas, cx, cy, r, first)
            finger(canvas, thumbX, thumbY, 1f - seg(t, 0.40f, 0.46f), pressed = true)
        } else if (second > 0.01f) {
            holdRing(canvas, cx, cy, r, second)
            finger(canvas, thumbX, thumbY, 1f - seg(t, 0.88f, 0.94f), pressed = true)
        }

        // the word the phone gives back while the lens is paused
        val toast = ease(seg(t, 0.50f, 0.58f)) * (1f - ease(seg(t, 0.84f, 0.90f)))
        drawPauseToast(canvas, toast * leave)
    }

    /** The ring a long press fills around the lens before it lands. */
    private fun holdRing(canvas: Canvas, cx: Float, cy: Float, r: Float, progress: Float) {
        line.strokeWidth = m(1.6f)
        line.color = withAlpha(accent, 0.75f)
        val hr = r * 1.7f
        canvas.drawArc(
            cx - hr, cy - hr + r * 0.8f, cx + hr, cy + hr + r * 0.8f,
            -90f, 360f * progress, false, line,
        )
    }

    /** The toast the service shows when the hold lands: paused, and how to wake it. */
    private fun drawPauseToast(canvas: Canvas, alpha: Float) =
        drawToast(canvas, "Lens paused — hold to wake it", alpha)

    /** An Android toast at the foot of the screen: a dark pill holding one line. */
    private fun drawToast(canvas: Canvas, message: String, alpha: Float) {
        if (alpha <= 0.01f) return
        text.textSize = m(10f)
        val w = text.measureText(message) + m(30f)
        val h = m(27f)
        val left = panel.centerX() - w / 2f
        val top = panel.bottom - m(116f)
        fill.color = withAlpha(blend(color(R.color.ink_900), cream, 0.08f), 0.94f * alpha)
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2f, h / 2f, fill)
        label(
            canvas, panel.centerX(), top + h / 2f + m(3.4f), message,
            withAlpha(cream, 0.94f * alpha), m(10f), Paint.Align.CENTER,
        )
    }

    /** The language the lens is set to, spelled as the app's own chip spells it. */
    private fun targetName(): String = Languages.short(Prefs.targetLanguage(context))

    /**
     * The three rows the picker loop shows: the language the lens is really set
     * to first — so the pick on screen is the pick that was made — then two
     * everyday ones it is not. A row that would run past the sheet prints the
     * English name alone instead of both names twice over.
     */
    private fun pickerRows(): List<String> {
        val chosen = Languages.canonical(Prefs.targetLanguage(context))
        val codes = listOfNotNull(chosen) + listOf("de", "es", "en").filter { it != chosen }
        return codes.distinct().take(3).map { code ->
            val native = Languages.nativeLabel(code)
            val label = Languages.label(code)
            val pair = if (native == label) label else "$native  ·  $label"
            if (pair.length > 26) Languages.short(code) else pair
        }
    }

    /** The picker the card really shows: native · label, the pick marked. */
    private fun drawLanguageSheet(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val appear = ease(seg(t, 0.10f, 0.22f))
        // the list stays up long enough to be read and picked from, now that
        // the press that pauses the lens is not sharing this loop's time
        val leave = ease(seg(t, 0.78f, 0.88f))
        val alpha = appear * (1f - leave)
        if (alpha <= 0.01f) return

        val rows = pickerRows()
        val w = m(240f)
        val rowH = m(30f)
        val pad = m(16f)
        val h = pad * 2 + m(18f) + rows.size * rowH
        val left = cx - w / 2f
        val top = (cy + r + m(28f)).coerceAtMost(panel.bottom - m(18f) - h) + (1f - appear) * m(10f)

        fill.color = withAlpha(cardSurface, alpha)
        canvas.drawRoundRect(left, top, left + w, top + h, m(19f), m(19f), fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(cardEdge, alpha)
        canvas.drawRoundRect(left, top, left + w, top + h, m(19f), m(19f), line)

        label(canvas, left + pad, top + m(24f), "TRANSLATE INTO", withAlpha(cardFaint, alpha), m(9f))

        val pick = ease(seg(t, 0.24f, 0.32f))
        rows.forEachIndexed { index, row ->
            val rowTop = top + pad + m(18f) + index * rowH
            // The first row is the language the app is really set to, so the
            // pick the loop shows is the pick the person made.
            val selected = index == 0
            if (selected && pick > 0.01f) {
                fill.color = withAlpha(accentSoft, 0.16f * pick * alpha)
                canvas.drawRoundRect(
                    left + m(10f), rowTop, left + w - m(10f), rowTop + rowH,
                    m(9f), m(9f), fill,
                )
            }
            label(
                canvas, left + pad, rowTop + m(20f), row,
                withAlpha(if (selected && pick > 0.5f) cardAccent else cardInk, alpha), m(13.5f),
            )
            if (selected && pick > 0.5f) {
                line.strokeWidth = m(1.6f)
                line.color = withAlpha(cardAccent, alpha)
                val tx = left + w - m(28f)
                val ty = rowTop + m(16f)
                canvas.drawLine(tx - m(5f), ty, tx - m(1f), ty + m(4f), line)
                canvas.drawLine(tx - m(1f), ty + m(4f), tx + m(5f), ty - m(4f), line)
            }
        }
    }

    /* ── page 3: rest on a line, the card opens ────────────────────── */

    private fun sceneRead(canvas: Canvas) {
        openScreen(canvas, chatSurface)
        drawChat(canvas, 1f)
        closeScreen(canvas)

        val leave = 1f - seg(t, 0.92f, 1f)
        val r = m(21f)
        val cx = readAt.centerX()
        val cy = readAt.centerY()

        // it comes across the screen and settles on her line, and reads once it
        // has stopped over it
        val settle = ease(seg(t, 0.02f, 0.18f))
        val hx = cx + m(58f) * (1f - settle)
        lens(
            canvas, hx, cy - m(8f) * (1f - settle), r, leave,
            glow = 0.25f * seg(t, 0.16f, 0.28f) * (1f - seg(t, 0.74f, 0.82f)),
        )

        val cardIn = ease(seg(t, 0.18f, 0.30f))
        val cardOut = ease(seg(t, 0.82f, 0.90f))
        drawCard(canvas, cx, cy + r, cardIn * (1f - cardOut) * leave)
    }

    /** The translation card, at the size and with the parts the app gives it. */
    private fun drawCard(canvas: Canvas, lensCx: Float, lensBottom: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val w = m(377f)
        val h = m(178f)
        val left = panel.centerX() - w / 2f
        val top = (lensBottom + m(10f)).coerceAtMost(panel.bottom - m(12f) - h) + (1f - alpha) * m(8f)

        // Raised, as the overlay window is. The card is paper on a paper page
        // in this drawing, so its lift has to be drawn: a stack of soft rings
        // below it, then the hairline the real card carries.
        val radius = m(19f)
        for (step in 5 downTo 1) {
            val grow = m(1.6f) * step
            fill.color = withAlpha(shade, 0.045f * alpha)
            canvas.drawRoundRect(
                left - grow, top + m(2f) - grow, left + w + grow, top + h + m(4f) + grow,
                radius + grow, radius + grow, fill,
            )
        }
        fill.color = withAlpha(cardSurface, alpha)
        canvas.drawRoundRect(left, top, left + w, top + h, radius, radius, fill)
        line.strokeWidth = m(1.2f)
        line.color = withAlpha(cardEdge, 0.9f * alpha)
        canvas.drawRoundRect(left, top, left + w, top + h, radius, radius, line)

        val pad = m(16f)
        // status line: dot, word, close
        fill.color = withAlpha(color(R.color.olive_600), alpha)
        canvas.drawCircle(left + pad + m(3f), top + m(21f), m(3f), fill)
        label(canvas, left + pad + m(12f), top + m(24f), "TRANSLATED", withAlpha(cardMuted, alpha), m(9f))
        closeGlyph?.let {
            it.setTint(withAlpha(cardFaint, alpha))
            it.setBounds(
                (left + w - pad - m(12f)).toInt(), (top + m(12f)).toInt(),
                (left + w - pad).toInt(), (top + m(24f)).toInt(),
            )
            it.draw(canvas)
        }

        // the language pair
        val chipTop = top + m(34f)
        pairChip(canvas, left + pad, chipTop, "Japanese", alpha, accent = false)
        label(canvas, left + pad + m(66f), chipTop + m(15f), "→", withAlpha(cardFaint, alpha), m(11f))
        pairChip(canvas, left + pad + m(84f), chipTop, "English", alpha, accent = true)

        // the answer
        wrap(
            canvas, "Tomorrow's meeting starts at ten.",
            left + pad, top + m(72f), w - pad * 2, m(21f),
            withAlpha(cardInk, alpha), m(16f),
        )

        // the line it was read from, kept underneath
        fill.color = withAlpha(cardEdge, 0.9f * alpha)
        canvas.drawRect(left + pad, top + m(106f), left + w - pad, top + m(107f), fill)
        wrap(
            canvas, "はい。明日の会議は10時からです。",
            left + pad, top + m(126f), w - pad * 2, m(16f),
            withAlpha(cardMuted, alpha), m(11.5f),
        )

        // the foot: how it was read, and the copy button
        label(
            canvas, left + pad, top + h - m(16f), "on-device · 0.3s",
            withAlpha(cardFaint, alpha), m(9f),
        )
        val copyW = m(56f)
        fill.color = withAlpha(accentSoft, 0.18f * alpha)
        canvas.drawRoundRect(
            left + w - pad - copyW, top + h - m(27f), left + w - pad, top + h - m(7f),
            m(14f), m(14f), fill,
        )
        label(
            canvas, left + w - pad - copyW / 2f, top + h - m(15f), "Copy",
            withAlpha(cardMuted, alpha), m(10.5f), Paint.Align.CENTER,
        )
    }

    private fun pairChip(canvas: Canvas, left: Float, top: Float, value: String, alpha: Float, accent: Boolean) {
        text.textSize = m(10f)
        val w = text.measureText(value) + m(18f)
        fill.color = withAlpha(if (accent) cardAccentFill else cardChipFill, alpha)
        canvas.drawRoundRect(left, top, left + w, top + m(22f), m(11f), m(11f), fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(if (accent) cardAccentEdge else cardChipEdge, alpha)
        canvas.drawRoundRect(left, top, left + w, top + m(22f), m(11f), m(11f), line)
        label(
            canvas, left + m(9f), top + m(15f), value,
            withAlpha(if (accent) cardAccent else cardMuted, alpha), m(10f),
        )
    }

    /* ── page 4: the edge, the lip, Delete ─────────────────────────── */

    private fun sceneDelete(canvas: Canvas) {
        openScreen(canvas, chatSurface)
        drawChat(canvas, 0.9f)
        closeScreen(canvas)

        val r = m(21f)
        val cx = panel.centerX()
        val startCy = panel.top + panel.height() * 0.32f

        // the lip: 156 × 18 at rest, grown to 176 × 24 as the lens comes near
        val near = ease(seg(t, 0.16f, 0.34f))
        val fold = ease(seg(t, 0.40f, 0.48f))
        val lipW = m(156f) + (m(176f) - m(156f)) * near
        val lipH = m(18f) + (m(24f) - m(18f)) * near
        val lipAlpha = (0.88f - 0.88f * fold) * (1f - seg(t, 0.92f, 1f))
        if (lipAlpha > 0.01f) {
            drawLip(canvas, cx, lipW, lipH, lipAlpha, outlineOnly = near > 0.5f)
        }

        // the way out opens above the edge once the lens touches the lip
        val open = ease(seg(t, 0.44f, 0.56f))
        val discCy = panel.bottom - m(56f) - m(18f) - m(4f) - m(23f)
        if (open > 0.01f) {
            drawDropMark(canvas, cx, discCy, open * (1f - seg(t, 0.92f, 1f)))
        }

        // the lens: down to the lip, then up onto the disc
        val travel = ease(seg(t, 0.04f, 0.36f))
        val toLip = startCy + (panel.bottom - lipH - r - m(2f) - startCy) * travel
        val lift = ease(seg(t, 0.48f, 0.62f))
        // onto the disc itself, not past it: the word under the mark has to
        // stay readable while the lens is sitting in it
        val cy = toLip + (discCy - toLip) * lift
        val taken = ease(seg(t, 0.64f, 0.72f))
        lens(canvas, cx, cy, r, (1f - taken) * (1f - seg(t, 0.94f, 1f)), glow = 0.25f * travel * (1f - taken))

        if (travel > 0.02f) finger(canvas, cx, cy + r * 0.75f, 1f - seg(t, 0.62f, 0.70f), pressed = true)

        // the mark answering the drop
        if (t in 0.64f..0.80f) {
            val p = ((t - 0.64f) / 0.16f).coerceIn(0f, 1f)
            line.strokeWidth = m(1.6f)
            line.color = withAlpha(accent, 0.5f * (1f - p))
            canvas.drawCircle(cx, discCy, m(23f) + m(16f) * p, line)
        }
    }

    /** The drawer lip: rounded top, flat on the screen's edge. */
    private fun drawLip(
        canvas: Canvas,
        cx: Float,
        w: Float,
        h: Float,
        alpha: Float,
        outlineOnly: Boolean,
    ) {
        val left = cx - w / 2f
        val top = panel.bottom - h
        val radius = minOf(m(9f), h / 2f)
        clip.reset()
        clip.addRoundRect(
            left, top, left + w, panel.bottom,
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        if (!outlineOnly) {
            fill.color = withAlpha(lipSurface, alpha)
            canvas.drawPath(clip, fill)
        }
        line.strokeWidth = m(1.2f)
        line.color = withAlpha(lipRing, alpha)
        canvas.drawPath(clip, line)
    }

    /** The opened mark: a 46 dp disc with the cross, and the word under it. */
    private fun drawDropMark(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val radius = m(23f)

        fill.color = withAlpha(shade, 0.14f * alpha)
        canvas.drawCircle(cx, cy + m(3f), radius, fill)
        fill.color = withAlpha(cardPaper, alpha)
        canvas.drawCircle(cx, cy, radius, fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(accent, 0.55f * alpha)
        canvas.drawCircle(cx, cy, radius, line)

        closeGlyph?.let {
            it.setTint(withAlpha(accent, alpha))
            val g = radius * 0.44f
            it.setBounds((cx - g).toInt(), (cy - g).toInt(), (cx + g).toInt(), (cy + g).toInt())
            it.draw(canvas)
        }

        // the word's pill
        text.textSize = m(10f)
        val w = text.measureText("Delete") + m(16f)
        val pillTop = cy + radius + m(4f)
        fill.color = withAlpha(cardPaper, alpha)
        canvas.drawRoundRect(cx - w / 2f, pillTop, cx + w / 2f, pillTop + m(18f), m(9f), m(9f), fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(cardEdge, alpha)
        canvas.drawRoundRect(cx - w / 2f, pillTop, cx + w / 2f, pillTop + m(18f), m(9f), m(9f), line)
        label(canvas, cx, pillTop + m(12.5f), "Delete", withAlpha(cardInk, alpha), m(10f), Paint.Align.CENTER)
    }

    /* ── page 5: the shade's button ────────────────────────────────── */

    private fun sceneTile(canvas: Canvas) {
        openScreen(canvas, chatSurface)
        drawChat(canvas, 0.85f)

        // The shade comes down twice: once to turn the lens on, once to turn it
        // off again — the second tap has to be seen to work.
        val down1 = ease(seg(t, 0.02f, 0.11f))
        val up1 = ease(seg(t, 0.26f, 0.34f))
        val down2 = ease(seg(t, 0.58f, 0.66f))
        val up2 = ease(seg(t, 0.82f, 0.92f))
        val shade = maxOf(down1 * (1f - up1), down2 * (1f - up2))

        // the button's state: on at the first tap, back off at the second
        val on = ease(seg(t, 0.14f, 0.22f)) * (1f - ease(seg(t, 0.70f, 0.78f)))

        // Four buttons across the shade's width, as the phone lays them out:
        // the row is inside the same padding on both sides.
        val gap = m(10f)
        val rowLeft = panel.left + m(16f)
        val tileSize = (panel.width() - m(32f) - gap * 3f) / 4f
        val tileTop = panel.top + m(118f)
        val lensTileCx = rowLeft + (tileSize + gap) * 2f + tileSize / 2f
        val tileCy = tileTop + tileSize / 2f

        // the lens the button starts — and the second tap takes away. It is
        // drawn under the shade, as it really sits under it.
        val appear = ease(seg(t, 0.34f, 0.44f))
        val gone = ease(seg(t, 0.86f, 0.96f))
        val lensAlpha = appear * (1f - gone)
        if (lensAlpha > 0.01f) {
            lens(
                canvas,
                panel.centerX(),
                panel.top + panel.height() * 0.42f,
                m(21f),
                lensAlpha,
                glow = 0.30f * lensAlpha * (0.5f + 0.5f * pulse()),
            )
        }

        if (shade > 0.01f) {
            drawShade(canvas, rowLeft, tileTop, tileSize, gap, shade, on)
        }
        closeScreen(canvas)

        // the thumb on the lens button, for both taps
        val tap1 = if (shade > 0.5f) 1f - seg(t, 0.06f, 0.18f) else 0f
        val tap2 = if (t > 0.62f && shade > 0.5f) 1f - seg(t, 0.70f, 0.82f) else 0f
        if (tap1 > 0.01f) finger(canvas, lensTileCx, tileCy, tap1, pressed = true)
        if (tap2 > 0.01f) finger(canvas, lensTileCx, tileCy, tap2, pressed = true)
        ripple(canvas, lensTileCx, tileCy, 0.12f, 0.30f, if (shade > 0.5f) 1f else 0f)
        ripple(canvas, lensTileCx, tileCy, 0.66f, 0.84f, if (t > 0.62f && shade > 0.5f) 0.9f else 0f)
    }

    /** The shade as the phone really draws it: clock, brightness, a row of buttons. */
    private fun drawShade(
        canvas: Canvas,
        rowLeft: Float,
        tileTop: Float,
        tileSize: Float,
        gap: Float,
        alpha: Float,
        on: Float,
    ) {
        // The shade covers what is under it without going black: on a light
        // page it is the warm frosted sheet this phone really pulls down, and
        // on a night page it is a dark grey sheet a step above the ink — the
        // app behind still reads through it as dimmed, not switched off.
        fill.color = withAlpha(shadeSurface, alpha)
        canvas.drawRect(panel, fill)

        // the clock, the date, and the two little buttons beside them
        label(canvas, panel.left + m(20f), panel.top + m(48f), "22:16", withAlpha(ink, alpha), m(26f))
        label(
            canvas, panel.left + m(20f), panel.top + m(62f), "Thu, 19 Nov",
            withAlpha(muted, 0.9f * alpha), m(9f),
        )
        shadeCircle(canvas, panel.right - m(48f), panel.top + m(38f), alpha, settings = true)
        shadeCircle(canvas, panel.right - m(20f), panel.top + m(38f), alpha, settings = false)

        // the brightness bar: a pressed-in track lit from the left, as the
        // phone draws it, with the knob at the level it sits at
        val barLeft = panel.left + m(20f)
        val barRight = panel.right - m(20f)
        val barTop = panel.top + m(74f)
        val lit = barLeft + (barRight - barLeft) * 0.62f
        fill.color = withAlpha(band, 0.90f * alpha)
        canvas.drawRoundRect(barLeft, barTop, barRight, barTop + m(22f), m(11f), m(11f), fill)
        fill.color = withAlpha(shadeTileSurface, 0.92f * alpha)
        canvas.drawRoundRect(barLeft, barTop, lit, barTop + m(22f), m(11f), m(11f), fill)
        fill.color = withAlpha(cream, alpha)
        canvas.drawCircle(lit - m(7f), barTop + m(11f), m(6f), fill)
        line.strokeWidth = m(1f)
        line.color = withAlpha(ink, 0.10f * alpha)
        canvas.drawCircle(lit - m(7f), barTop + m(11f), m(6f), line)

        // the buttons, in their row, with the lens's own third from the left
        shadeTile(canvas, rowLeft, tileTop, tileSize, "Wi-Fi", 0f, alpha, ShadeGlyph.WIFI)
        shadeTile(
            canvas, rowLeft + (tileSize + gap), tileTop, tileSize, "Bluetooth",
            0f, alpha, ShadeGlyph.BLUETOOTH,
        )
        shadeTile(
            canvas, rowLeft + (tileSize + gap) * 2f, tileTop, tileSize,
            if (on > 0.5f) "Lens on" else "Lens off", on, alpha, ShadeGlyph.LUCID,
        )
        shadeTile(
            canvas, rowLeft + (tileSize + gap) * 3f, tileTop, tileSize, "Torch",
            0f, alpha, ShadeGlyph.TORCH,
        )

        // what the tap did, said once under the row
        if (alpha > 0.5f) {
            label(
                canvas, rowLeft, tileTop + tileSize + m(40f),
                if (on > 0.5f) "The lens is on — it floats over your apps." else "The lens is off.",
                withAlpha(muted, alpha), m(10.5f),
            )
        }
    }

    /** The two round buttons the shade keeps beside the clock. */
    private fun shadeCircle(canvas: Canvas, cx: Float, cy: Float, alpha: Float, settings: Boolean) {
        val r = m(11f)
        fill.color = withAlpha(shadeTileSurface, 0.92f * alpha)
        canvas.drawCircle(cx, cy, r, fill)
        line.strokeWidth = m(1.2f)
        line.color = withAlpha(muted, 0.55f * alpha)
        if (settings) {
            canvas.drawCircle(cx, cy, m(3.4f), line)
            for (i in 0 until 6) {
                val a = i * PI / 3
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                canvas.drawLine(
                    cx + dx * m(5.2f), cy + dy * m(5.2f),
                    cx + dx * m(7f), cy + dy * m(7f), line,
                )
            }
        } else {
            canvas.drawLine(cx - m(4f), cy + m(4f), cx + m(4.5f), cy - m(4.5f), line)
            canvas.drawLine(cx + m(2.2f), cy - m(6.2f), cx + m(6f), cy - m(2.4f), line)
        }
    }

    private fun shadeTile(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        name: String,
        on: Float,
        alpha: Float,
        glyph: ShadeGlyph,
    ) {
        fill.color = blend(withAlpha(shadeTileSurface, alpha), withAlpha(accent, alpha), on)
        canvas.drawRoundRect(left, top, left + size, top + size, size * 0.42f, size * 0.42f, fill)
        line.strokeWidth = m(1f)
        line.color = blend(withAlpha(divider, alpha), withAlpha(accent, alpha), on)
        canvas.drawRoundRect(left, top, left + size, top + size, size * 0.42f, size * 0.42f, line)

        // on the clay button the glyph takes whichever of cream and ink reads:
        // the night accent is a light clay, so cream on it disappeared
        val glyphColor = blend(
            withAlpha(muted, 0.95f * alpha),
            withAlpha(if (night) color(R.color.ink_900) else cream, alpha),
            on,
        )
        val cx = left + size / 2f
        val cy = top + size / 2f
        // The system's own glyphs, tinted to the button's state: Wi-Fi is the
        // three arcs and the dot, Bluetooth the rune, the torch its cone — the
        // shapes a person already knows from their phone's shade. The lens's
        // button carries the app's own mark, as the real one does.
        val icon = when (glyph) {
            ShadeGlyph.WIFI -> wifiGlyph
            ShadeGlyph.BLUETOOTH -> bluetoothGlyph
            ShadeGlyph.TORCH -> flashlightGlyph
            ShadeGlyph.LUCID -> tileGlyph
        }
        icon?.let {
            it.setTint(glyphColor)
            // the lens keeps the app's own mark at the size the phone gives it;
            // the system glyphs sit at the proportions its shade uses, so they
            // read as buttons rather than as enlarged symbols
            val g = size * (if (glyph == ShadeGlyph.LUCID) 0.52f else 0.42f)
            it.setBounds(
                (cx - g / 2f).toInt(), (cy - g / 2f).toInt(),
                (cx + g / 2f).toInt(), (cy + g / 2f).toInt(),
            )
            it.draw(canvas)
        }

        label(
            canvas, cx, top + size + m(15f), name,
            blend(withAlpha(muted, alpha), withAlpha(cream, alpha), on),
            m(10f), Paint.Align.CENTER,
        )
    }

    /* ── page 6: the switch that lets the lens read ───────────────── */

    /**
     * Android's accessibility list, the screen the tour's last button opens.
     *
     * It is drawn as the real list: sections, rows and switches, with Lucid's
     * own row the one the loop touches. The permission is the one thing the
     * tour cannot give itself, so the last screen shows where it is given
     * rather than pretending the lens already has it.
     */
    private fun sceneAccess(canvas: Canvas) {
        openScreen(canvas, chatSurface)

        val left = panel.left + m(18f)
        val right = panel.right - m(18f)
        val header = panel.top + m(30f)

        label(canvas, panel.left + m(12f), header + m(5f), "‹", withAlpha(muted, 1f), m(18f))
        label(
            canvas, panel.centerX(), header + m(5f), "Accessibility",
            withAlpha(ink, 1f), m(14f), Paint.Align.CENTER,
        )
        fill.color = withAlpha(divider, 1f)
        canvas.drawRect(panel.left, header + m(18f), panel.right, header + m(19f), fill)

        // The switch alone says what the screen is for: it starts off, it is
        // turned on, and it is turned off again as the loop closes, so the
        // change is never a cut. No finger and no highlight — the control is
        // the whole subject of the page.
        val on = ease(seg(t, 0.30f, 0.42f)) * (1f - ease(seg(t, 0.90f, 0.98f)))
        val sections = listOf(
            "INSTALLED APPS" to listOf(
                Triple("TalkBack", "Off", false),
                Triple("Lucid", if (on > 0.5f) "Allowed" else "Not allowed", true),
                Triple("Select to Speak", "Off", false),
                Triple("Voice Access", "Off", false),
            ),
            "GENERAL" to listOf(
                Triple("Font size", "Default", false),
                Triple("Colour correction", "Off", false),
            ),
            "SERVICES" to listOf(
                Triple("Accessibility shortcuts", "Off", false),
                Triple("Text-to-speech output", "Default", false),
            ),
        )

        val rowH = m(58f)
        var y = panel.top + m(52f)
        sections.forEach { (heading, rows) ->
            label(canvas, left, y + m(8f), heading, withAlpha(faint, 1f), m(9f))
            y += m(18f)
            rows.forEach { (name, state, lucid) ->
                label(canvas, left, y + m(23f), name, withAlpha(ink, 1f), m(13.5f))
                label(
                    canvas, left, y + m(39f), state,
                    withAlpha(if (lucid && on > 0.5f) accent else faint, 1f), m(10f),
                )
                permissionSwitch(canvas, right - m(2f), y + rowH / 2f, if (lucid) on else 0f)
                fill.color = withAlpha(divider, 1f)
                canvas.drawRect(left, y + rowH, right, y + rowH + m(0.8f), fill)
                y += rowH
            }
            y += m(12f)
        }

        closeScreen(canvas)
    }

    /** One row's switch: a pill whose knob crosses to the far end as it is set. */
    private fun permissionSwitch(canvas: Canvas, trackRight: Float, cy: Float, on: Float) {
        val w = m(44f)
        val h = m(24f)
        val trackLeft = trackRight - w
        val top = cy - h / 2f
        fill.color = withAlpha(blend(blend(band, muted, 0.30f), accent, on), 1f)
        canvas.drawRoundRect(trackLeft, top, trackLeft + w, top + h, h / 2f, h / 2f, fill)
        // the knob stays light in both themes, as a real switch's does: a card
        // surface at night is darker than its track and read as a hole
        fill.color = withAlpha(if (night) cream else cardPaper, 1f)
        canvas.drawCircle(trackLeft + h / 2f + (w - h) * on, cy, h / 2f - m(3f), fill)
    }

    /* ── the lens, at its real 42 dp ───────────────────────────────── */

    private fun lens(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float, glow: Float = 0f) {
        if (alpha <= 0.005f) return

        if (glow > 0.01f) {
            fill.shader = RadialGradient(
                cx, cy, radius * 2.4f,
                intArrayOf(withAlpha(accentSoft, 0.20f * glow * alpha), withAlpha(accentSoft, 0f)),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius * 2.4f, fill)
            fill.shader = null
        }

        // the lift under the disc, then the app's own two drawables at the
        // sizes the service gives them — 42 dp disc, 18 dp iris, exact colours
        fill.color = withAlpha(shade, 0.16f * alpha)
        canvas.drawCircle(cx, cy + m(2f), radius, fill)
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        lensDisc?.let {
            it.alpha = a
            it.setBounds(
                (cx - radius).toInt(), (cy - radius).toInt(),
                (cx + radius).toInt(), (cy + radius).toInt(),
            )
            it.draw(canvas)
        }
        val g = radius * (18f / 42f)
        lensIris?.let {
            it.alpha = a
            it.setBounds(
                (cx - g).toInt(), (cy - g).toInt(),
                (cx + g).toInt(), (cy + g).toInt(),
            )
            it.draw(canvas)
        }
    }

    /* ── small drawing helpers ─────────────────────────────────────── */

    private fun finger(canvas: Canvas, x: Float, y: Float, alpha: Float, pressed: Boolean) {
        if (alpha <= 0.01f) return
        val r = dp(if (pressed) 12f else 14f)
        fill.color = withAlpha(cream, 0.60f * alpha)
        canvas.drawCircle(x, y, r, fill)
        line.strokeWidth = dp(1.2f)
        line.color = withAlpha(ink, 0.28f * alpha)
        canvas.drawCircle(x, y, r, line)
        fill.color = withAlpha(ink, 0.14f * alpha)
        canvas.drawCircle(x, y, r * 0.38f, fill)
    }

    private fun ripple(canvas: Canvas, x: Float, y: Float, from: Float, to: Float, alpha: Float) {
        if (alpha <= 0.01f || to <= from) return
        val p = ((t - from) / (to - from)).coerceIn(0f, 1f)
        line.strokeWidth = dp(1.5f)
        line.color = withAlpha(accentSoft, alpha * (1f - p))
        canvas.drawCircle(x, y, m(21f) + m(46f) * p, line)
    }

    private fun pulse(): Float = ((sin((t * 3 * PI).toFloat()) + 1f) / 2f)

    private fun label(
        canvas: Canvas,
        x: Float,
        y: Float,
        value: String,
        color: Int,
        sizePx: Float,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        text.color = color
        text.textSize = sizePx
        text.textAlign = align
        text.letterSpacing = 0f
        canvas.drawText(value, x, y, text)
    }

    private fun wrap(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        lineHeight: Float,
        color: Int,
        sizePx: Float,
    ) {
        val lines = wrapLines(value, maxWidth, sizePx)
        label(canvas, x, y, lines.firstOrNull() ?: "", color, sizePx)
        lines.drop(1).forEachIndexed { index, row ->
            label(canvas, x, y + lineHeight * (index + 1), row, color, sizePx)
        }
    }

    private fun wrapLines(value: String, maxWidth: Float, sizePx: Float = m(13.5f)): List<String> {
        text.textSize = sizePx
        val out = ArrayList<String>()
        if (text.measureText(value) <= maxWidth) return listOf(value)
        // Japanese has no spaces between its words: a line breaks between the
        // characters themselves, so there is nothing to join back up.
        if (!value.contains(' ')) {
            var line = StringBuilder()
            value.forEach { ch ->
                val candidate = line.toString() + ch
                if (text.measureText(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    out += line.toString()
                    line = StringBuilder(ch.toString())
                }
            }
            if (line.isNotEmpty()) out += line.toString()
            return out
        }
        val words = value.split(' ')
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (text.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /* ── arithmetic ────────────────────────────────────────────────── */

    private fun seg(value: Float, from: Float, to: Float): Float =
        ((value - from) / (to - from)).coerceIn(0f, 1f)

    private fun ease(value: Float): Float = value * value * (3f - 2f * value)

    /**
     * A role colour already carries an alpha of its own (the card's hairline is
     * 8% ink, its chips are 6% tints), so the loop's alpha has to multiply it
     * rather than replace it: replacing turned every translucent colour into a
     * solid one, which is what made the card's chips read as dark blobs.
     */
    private fun withAlpha(color: Int, fraction: Float): Int {
        val owner = (color ushr 24) and 0xFF
        val base = if (owner == 0) 255 else owner
        val a = ((base / 255f) * fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = ((from ushr 24) + (((to ushr 24) - (from ushr 24)) * f)).toInt()
        val r = (((from shr 16) and 0xFF) + ((((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * f)).toInt()
        val g = (((from shr 8) and 0xFF) + ((((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * f)).toInt()
        val b = ((from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * f)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
