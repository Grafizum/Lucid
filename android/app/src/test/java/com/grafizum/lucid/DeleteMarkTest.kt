package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way out lives on the bottom edge: a half-disc peeks out of it at rest,
 * the full mark opens above it when the lens is carried down, and only then is
 * a drop a delete.
 */
class DeleteMarkTest {

    private val screenW = 1080
    private val screenH = 2340
    private val width = 190
    private val peekHeight = 70
    private val openHeight = 195
    private val inset = 70

    @Test
    fun `the half-disc is centred and flat on the bottom edge`() {
        val seat = DeleteMark.peek(screenW, screenH, width, peekHeight)

        assertEquals((screenW - width) / 2, seat.x)
        assertEquals(screenH, seat.y + peekHeight)
    }

    @Test
    fun `the opened mark is centred and sits clear of the gesture bar`() {
        val seat = DeleteMark.opened(screenW, screenH, width, openHeight, inset)

        assertEquals((screenW - width) / 2, seat.x)
        assertEquals(screenH - inset, seat.y + openHeight)
    }

    @Test
    fun `the mark rises out of the edge when it opens`() {
        val peek = DeleteMark.peek(screenW, screenH, width, peekHeight)
        val open = DeleteMark.opened(screenW, screenH, width, openHeight, inset)

        assertTrue("opened at ${open.y}, peeked at ${peek.y}", open.y < peek.y)
    }

    @Test
    fun `a screen smaller than the mark still gets a seat on it`() {
        val peek = DeleteMark.peek(screenW = 120, screenH = 100, width = width, height = peekHeight)
        val open = DeleteMark.opened(120, 100, width, openHeight, inset)

        assertEquals(0, peek.x)
        // Even a short screen keeps the half-disc flush with its bottom edge.
        assertEquals(100, peek.y + peekHeight)
        // The opened mark is taller than this screen, so it pins to the top
        // instead of opening off it.
        assertEquals(0, open.x)
        assertEquals(0, open.y)
    }

    @Test
    fun `a release at the bottom of a page is a read, not a drop`() {
        // Real numbers from the S906B: density 2.625, lens 110 px, disc 46 dp,
        // label 18 dp, gap 4 dp, inset 56 dp.
        val screenH = 2400
        val lens = 110
        val disc = 121
        val openHeight = disc + 10 + 47
        val inset = 147
        val top = DeleteMark.opened(screenW = 1080, screenH, width = 189, height = openHeight, inset = inset).y
        val markCy = top + disc / 2f
        val reach = disc / 2f + 45f

        // As low as the lens will go — where the last line of a page is read.
        val clampedCy = (screenH - lens + lens / 2f)
        assertFalse(
            "a release at the very bottom must not be a drop",
            DeleteMark.onMark(540f, clampedCy, 540f, markCy, reach),
        )

        // Lifted up onto the cross: that is a deliberate delete, and the mark is
        // still open while the lift happens.
        assertTrue(DeleteMark.onMark(540f, markCy, 540f, markCy, reach))
        // The cross sits above the lip's border, so the border test alone cannot
        // be what holds the mark open up there: the cross's own reach, widened
        // for the lift, is what keeps it open.
        val lifted = (markCy - lens / 2f).toInt()
        assertTrue("the cross is above the lip border", lifted + lens < screenH - 47)
    }

    @Test
    fun `the lens is never left resting inside the way out`() {
        // Real numbers from the S906B: 2400 px tall, the resting lip 47 px high,
        // the opened mark 178 px tall sitting 147 px clear of the bottom edge.
        val screenH = 2400
        val size = 110
        val lipTop = screenH - 47
        val floor = DeleteMark.restingFloor(lipTop, size)

        // The seat the lens takes: its own border on the bar's, not through it.
        assertEquals(lipTop - size, floor)
        assertEquals(lipTop, floor + size)
        // Down there the way out still opens — the borders meet, which is the
        // whole trigger — and the lens is still off the cross, so letting go at
        // the bottom of a page is a read and not a delete.
        assertTrue(DeleteMark.touchesLip(335, lipTop, 744, screenH, 485, floor, size))
        val disc = 121
        val openTop = screenH - (disc + 10 + 47) - 147
        val markCy = openTop + disc / 2f
        val reach = disc / 2f + 45f
        assertFalse(
            "a lens on the floor must not be a drop",
            DeleteMark.onMark(540f, floor + size / 2f, 540f, markCy, reach),
        )

        // What the floor stops: the seat all the way down, which is inside the
        // bar, its middle in the phone's gesture strip.
        val bottomEdge = screenH - size
        assertTrue(bottomEdge > floor)
        assertTrue("the bottom seat runs through the bar", bottomEdge + size > lipTop)
        assertEquals("only the floor sits on the edge of the bar", lipTop, floor + size)
    }

    /**
     * The lip on the S906B at density 2.625: 409 px across, 47 px tall, flat on
     * the bottom edge, so it spans x 335..744 and y 2293..2340.
     */
    private fun near(margin: Int, lensX: Int = 485, lensY: Int, size: Int = 110): Boolean {
        val lipLeft = (screenW - 409) / 2
        val lipTop = screenH - 47
        return DeleteMark.reachesLip(
            lipLeft = lipLeft,
            lipTop = lipTop,
            lipRight = lipLeft + 409,
            lipBottom = lipTop + 47,
            lensX = lensX,
            lensY = lensY,
            size = size,
            margin = margin,
        )
    }

    @Test
    fun `the way out opens on the borders meeting, and nothing else`() {
        val lipTop = screenH - 47
        val lipLeft = (screenW - 409) / 2 // 335
        val size = 110

        fun touching(lensX: Int, lensY: Int) = DeleteMark.touchesLip(
            lipLeft, lipTop, lipLeft + 409, lipTop + 47, lensX, lensY, size,
        )

        // The borders met, and a hand pushing a little into the bar: open.
        assertTrue(touching(485, lipTop - size))
        assertTrue(touching(485, lipTop - size + 20))
        // Anywhere along the bar's own span, right to its ends.
        assertTrue(touching(lipLeft - size + 1, lipTop - size))
        assertTrue(touching(lipLeft + 409 - 1, lipTop - size))
        // A pixel short of the border: no radius behind it, so nothing opens.
        assertFalse(touching(485, lipTop - size - 1))
        // Down at the bottom edge but beside the bar, outside its span: the
        // bar's border is not there, so nothing opens.
        assertFalse(touching(lipLeft - size - 1, screenH - size))
        assertFalse(touching(lipLeft + 409 + 1, screenH - size))
        // Well up the page: nothing opens.
        assertFalse(touching(485, 900))
    }

    @Test
    fun `the gap to the lip is zero on contact and measured when clear`() {
        // Overlapping the bar counts as on it, not as a negative gap.
        assertEquals(0, DeleteMark.gapToLip(335, 2293, 744, 2340, 400, 2290, 110))
        assertEquals(0, DeleteMark.gapToLip(335, 2293, 744, 2340, 485, 2293 - 110, 110))
        // 30 px above its top border and 40 px off its right end.
        assertEquals(40, DeleteMark.gapToLip(335, 2293, 744, 2340, 784, 2293 - 140, 110))
        // Straight down the page, far above it.
        assertEquals(400, DeleteMark.gapToLip(335, 2293, 744, 2340, 485, 2293 - 510, 110))
    }

    @Test
    fun `the mark is held a little way off the lip and folds beyond that`() {
        val lipTop = screenH - 47
        val size = 110
        val hold = 126 // 48 dp: how far it stays open, not how near it opens

        // On the bar, and a hand's width above it: held, so a finger that
        // wobbles does not make the mark blink.
        assertTrue(near(hold, lensY = lipTop - size))
        assertTrue(near(hold, lensY = lipTop - size - 100))
        // Carried away: the mark folds and the rectangle shows again.
        assertFalse(near(hold, lensY = lipTop - size - 140))
        assertFalse(near(hold, lensY = 900))
    }

    @Test
    fun `the edge announces itself in one ring the same distance all round`() {
        val size = 110
        val lipTop = screenH - 47
        val lipLeft = (screenW - 409) / 2 // 335
        val ring = 107 // 41 dp: the distance the hand picked

        fun hop(lensX: Int, lensY: Int) = DeleteMark.nearing(
            lipLeft, lipTop, lipLeft + 409, lipTop + 47, lensX, lensY, size, ring,
        )

        // Well up the page: nothing to say.
        assertFalse(hop(485, 900))
        // Just outside the ring on each side: still nothing.
        assertFalse(hop(485, lipTop - size - ring - 1))
        assertFalse(hop(lipLeft - size - ring - 1, lipTop))
        assertFalse(hop(lipLeft + 409 + ring + 1, lipTop))
        // On the ring from above, from the left and from the right: the same
        // distance every way, because it is the rectangle that is measured.
        assertTrue(hop(485, lipTop - size - ring))
        assertTrue(hop(lipLeft - size - ring, lipTop))
        assertTrue(hop(lipLeft + 409 + ring, lipTop))
        // The corner is inside the ring at that same distance on both axes.
        assertTrue(hop(lipLeft - size - ring, lipTop - size - ring))
        // And the lens's own border on the rectangle's border is inside it.
        assertTrue(hop(485, lipTop - size))
        assertEquals(0, DeleteMark.reach(screenH, lensY = screenH - size, size = size))
    }

    @Test
    fun `nothing on the way down falls back out of the open state`() {
        val size = 110
        val lipTop = screenH - 47 // the resting lip is 18 dp tall
        val lipLeft = (screenW - 409) / 2

        // From the border to as low as the window allows, every height is on
        // the rectangle — so however far the hand pushes past the border it
        // aimed at, nothing falls out.
        for (lensY in (lipTop - size)..(screenH - size)) {
            assertTrue(
                "lensY=$lensY",
                DeleteMark.touchesLip(lipLeft, lipTop, lipLeft + 409, lipTop + 47, 485, lensY, size),
            )
        }
        // And it opens above the bottom, so the hand never has to push past it.
        assertTrue("opens above the bottom", lipTop < screenH)
    }

    @Test
    fun `the lip that has hopped opens on its own border, not the resting one`() {
        val size = 110
        val restTop = screenH - 47
        val heldTop = screenH - 63 // the held lip is 24 dp tall
        val lensY = heldTop - size

        // The service reads the border off the lip in the state it is in, so a
        // lens on the hopped lip is on the lip: nothing is measured short.
        val restOpen = DeleteMark.touchesLip(335, restTop, 744, screenH, 485, lensY, size)
        val heldOpen = DeleteMark.touchesLip(335, heldTop, 744, screenH, 485, lensY, size)
        assertFalse(restOpen)
        assertTrue(heldOpen)
    }
}
