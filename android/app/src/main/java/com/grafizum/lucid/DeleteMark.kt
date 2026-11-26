package com.grafizum.lucid

/**
 * Where the way out sits, now that it lives on the bottom edge.
 *
 * At rest a drawer lip peeks out of the very bottom of the screen: a wide bar
 * with rounded top corners and its bottom flat on the edge — the handle of the
 * drawer the screen edge already reads as. It opens into the full mark only
 * while the lens is being carried to that edge, and it opens *above* the gesture
 * bar so the drop can actually be landed.
 *
 * Two more rules live here because they are arithmetic too: how close the lens
 * has to get before the edge answers ([nearing]), and how far it has to come
 * back up before the edge goes quiet again ([leftEdge]). Both are measured from
 * [reach], so the answer is always read from where the lens is now, never from
 * where the gesture started.
 *
 * Plain integers on purpose: the arithmetic is checked on the JVM, where the
 * graphics classes are stubs.
 */
object DeleteMark {

    /** A seat for the mark, in screen pixels (top-left of its window). */
    data class Seat(val x: Int, val y: Int)

    /**
     * The half-disc: centred, its flat side on the bottom edge, so the top half
     * of a circle is all that shows.
     */
    fun peek(screenW: Int, screenH: Int, width: Int, height: Int): Seat = Seat(
        x = (screenW - width).coerceAtLeast(0) / 2,
        y = (screenH - height).coerceAtLeast(0),
    )

    /**
     * The mark when it opens: centred, [inset] clear of the bottom edge so the
     * gesture bar does not sit over the target.
     */
    fun opened(screenW: Int, screenH: Int, width: Int, height: Int, inset: Int): Seat = Seat(
        x = (screenW - width).coerceAtLeast(0) / 2,
        y = (screenH - height - inset).coerceAtLeast(0),
    )

    /**
     * Whether the lens has come down onto the lip: the bottom of its own border
     * touching the top of the mark's window.
     *
     * That touch is what opens the way out. Waiting for the absolute bottom of
     * the screen made the hand push past the very thing it was reaching for —
     * the lip is a whole cap above the edge, so the two borders meeting is the
     * moment the eye (and the thumb) already reads as "on it".
     */
    /**
     * The gap between the lens and the lip, in pixels: zero when they touch or
     * overlap, and the larger of the two clearances when they do not.
     *
     * Both rectangles, both axes: a lens coming at the lip from the side is as
     * near it as one coming down onto it from above, and both are near it when
     * this is small.
     */
    fun gapToLip(
        lipLeft: Int,
        lipTop: Int,
        lipRight: Int,
        lipBottom: Int,
        lensX: Int,
        lensY: Int,
        size: Int,
    ): Int {
        val dx = maxOf(0, lipLeft - (lensX + size), lensX - lipRight)
        val dy = maxOf(0, lipTop - (lensY + size), lensY - lipBottom)
        return maxOf(dx, dy)
    }

    /**
     * Whether the lens's border is touching the rectangle's: the two meet on
     * both axes, so the lens is against the bar or into it, and inside the bar's
     * own span.
     *
     * This is the whole trigger, and the rectangle's own bounds are what decide
     * it — not a radius around the button. The bar's border only exists where
     * the bar is, so being near the button, floating above it, or down at the
     * bottom edge beside it is not touching it.
     */
    fun touchesLip(
        lipLeft: Int,
        lipTop: Int,
        lipRight: Int,
        lipBottom: Int,
        lensX: Int,
        lensY: Int,
        size: Int,
    ): Boolean = gapToLip(lipLeft, lipTop, lipRight, lipBottom, lensX, lensY, size) == 0

    /**
     * Whether the lens is still within [margin] of the lip, which is what keeps
     * the mark open once it has opened. Holding is not triggering: the open is
     * the border touching, and this only stops a finger that wobbles from making
     * the mark blink.
     */
    fun reachesLip(
        lipLeft: Int,
        lipTop: Int,
        lipRight: Int,
        lipBottom: Int,
        lensX: Int,
        lensY: Int,
        size: Int,
        margin: Int,
    ): Boolean =
        lensY + size >= lipTop - margin ||
            gapToLip(lipLeft, lipTop, lipRight, lipBottom, lensX, lensY, size) <= margin

    /**
     * Whether a release with the lens centred here counts as a drop on the mark
     * centred there. The radius is the disc plus a thumb's slack, and the whole
     * question is arithmetic on purpose: the distance between the bottom of the
     * screen and the mark is what decides whether reading a page's last line can
     * ever put the lens away by accident.
     */
    fun onMark(lensCx: Float, lensCy: Float, markCx: Float, markCy: Float, reach: Float): Boolean {
        if (reach <= 0f) return false
        val dx = lensCx - markCx
        val dy = lensCy - markCy
        return dx * dx + dy * dy <= reach * reach
    }

    /** How far the lens still is from sitting on the edge, in pixels. */
    fun reach(screenH: Int, lensY: Int, size: Int): Int =
        (screenH - (lensY + size)).coerceAtLeast(0)

    /**
     * The lowest seat the lens may rest in: its own bottom border on the lip's
     * top one, and not a pixel further.
     *
     * Below that line the lens is inside the lip's rectangle — drawn through the
     * bar's outline, which reads as a lens that fell into the drawer — and its
     * middle is in the phone's own gesture strip, where the next upward drag is
     * taken for Home and the lens stops following the hand. The bar's border is
     * exactly where the way out opens ([touchesLip]), so nothing is given up by
     * stopping there: carried down, the lens still meets the bar, and lifted up
     * it still reaches the cross.
     */
    fun restingFloor(lipTop: Int, size: Int): Int = (lipTop - size).coerceAtLeast(0)

    /**
     * Close enough that the edge is worth announcing with a hop: the border of
     * the lens within [distance] of the border of the rectangle, the same ring
     * all the way round it — on every side, and at the corners too, because it
     * is the rectangle's own bounds the ring is measured from.
     *
     * The ring is the hand's own distance: wherever the lens has to be for the
     * hop to feel like it answers the hand, that is where the ring is drawn,
     * and nowhere further out.
     */
    fun nearing(
        lipLeft: Int,
        lipTop: Int,
        lipRight: Int,
        lipBottom: Int,
        lensX: Int,
        lensY: Int,
        size: Int,
        distance: Int,
    ): Boolean =
        gapToLip(lipLeft, lipTop, lipRight, lipBottom, lensX, lensY, size) <= distance

}
