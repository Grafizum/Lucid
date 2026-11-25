package com.grafizum.lucid

/**
 * Sections: the groups of lines that belong together inside a picture.
 *
 * A frame read from pixels comes back as loose lines, and a loose line is enough while the
 * lens is over the words themselves. It stops being enough the moment there is
 * more than one block of text in the picture — a photo of a menu, a poster, a
 * slide — because then the nearest line on screen and the line that belongs to
 * what the lens is on are two different things.
 *
 * A section is a run of lines that share a column and follow each other at
 * normal leading. Two paragraphs separated by a blank gap, two columns of a
 * menu, a caption under a picture and a price at the other edge of the card all
 * end up as separate sections, which is what lets the lens answer for the
 * block it is actually resting on.
 *
 * Plain integers, like [UnderLens]: this is checked on the JVM, where the
 * graphics classes are stubs.
 */
object Sections {

    /** One OCR line: its words and the box they were drawn in. */
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height.toLong()
    }

    /** A block of lines that read together, top to bottom. */
    data class Section(val lines: List<Line>) {

        /** The section as the reader sees it: one line per line, in order. */
        val text: String get() = lines.joinToString("\n") { it.text }

        val left: Int get() = lines.minOf { it.left }
        val top: Int get() = lines.minOf { it.top }
        val right: Int get() = lines.maxOf { it.right }
        val bottom: Int get() = lines.maxOf { it.bottom }
        val width: Int get() = right - left
        val area: Long get() = (right - left).toLong() * (bottom - top).toLong()

        fun box(): UnderLens.Box = UnderLens.Box(left, top, right, bottom)

        /** True when [line] is one of the lines this section was built from. */
        fun holds(line: Line): Boolean = lines.any {
            it.text == line.text &&
                it.left == line.left && it.top == line.top &&
                it.right == line.right && it.bottom == line.bottom
        }
    }

    /**
     * Group loose OCR [lines] into sections.
     *
     * [leading] is how much empty space between two lines still counts as the
     * same block, as a fraction of the height of the line above. Paragraph
     * leading leaves around a third of a line empty, and a heading leaves more
     * than that but less than the blank line that opens a new block, so the
     * threshold has to tolerate a heading without swallowing a paragraph break.
     */
    fun of(lines: List<Line>, leading: Float = 1.2f): List<Section> {
        val usable = lines.filter { it.text.isNotBlank() && it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Line>>()

        for (line in usable.sortedWith(compareBy({ it.top }, { it.left }))) {
            val home = groups
                .filter { sharesColumn(it, line) }
                .map { group ->
                    val above = group.maxByOrNull { it.bottom }!!
                    val gap = line.top - above.bottom
                    val tolerance = maxOf(4f, above.height * leading)
                    Triple(group, gap, tolerance)
                }
                .filter { (_, gap, tolerance) -> gap.toFloat() in -tolerance..tolerance }
                .minByOrNull { (_, gap, _) -> kotlin.math.abs(gap) }
                ?.first

            if (home == null) groups += mutableListOf(line) else home += line
        }

        return groups
            .map { group -> Section(group.sortedWith(compareBy({ it.top }, { it.left }))) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** The section the point is on — the smallest one, when they nest. */
    fun under(sections: List<Section>, x: Int, y: Int, slack: Int = 0): Section? =
        sections.filter { UnderLens.holds(it.box(), x, y, slack) }.minByOrNull { it.area }

    /**
     * The sections that read as one block with [chosen]: the chosen one, plus
     * every neighbour in the same column that sits within [reach] pixels of the
     * text found so far, walking outwards until nothing else is close enough.
     *
     * A list needs this. Every name on a poster is its own section, because the
     * space between two rows is wider than [of]'s leading allows — so the lens
     * used to answer with the one name it was resting on. [reach] is how far a
     * row of a list can sit from the one above it and still be the same read.
     */
    fun blockOf(sections: List<Section>, chosen: Section, reach: Int): List<Section> {
        if (sections.size < 2) return listOf(chosen)

        val taken = mutableListOf(chosen)
        var left = chosen.left
        var right = chosen.right
        var top = chosen.top
        var bottom = chosen.bottom

        var grew = true
        while (grew) {
            grew = false
            for (section in sections) {
                if (section in taken) continue

                // Same column: the two sit over a stretch of the page together.
                // The other column of a menu shares none of it, so it stays out.
                val overlap = minOf(right, section.right) - maxOf(left, section.left)
                if (overlap <= 0) continue
                val narrower = minOf(right - left, section.width)
                if (overlap < 0.4f * narrower) continue

                val gap = when {
                    section.top >= bottom -> section.top - bottom
                    section.bottom <= top -> top - section.bottom
                    else -> 0
                }
                if (gap > reach) continue

                taken += section
                left = minOf(left, section.left)
                right = maxOf(right, section.right)
                top = minOf(top, section.top)
                bottom = maxOf(bottom, section.bottom)
                grew = true
            }
        }

        return taken.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * A line belongs to a group when the two sit over the same stretch of the
     * page. A heading is narrower than the paragraph under it and still shares
     * its column; the other column of a menu shares none of it.
     */
    private fun sharesColumn(group: List<Line>, line: Line): Boolean {
        val left = group.minOf { it.left }
        val right = group.maxOf { it.right }
        val overlap = minOf(right, line.right) - maxOf(left, line.left)
        if (overlap <= 0) return false
        val narrower = minOf(right - left, line.width)
        return overlap >= 0.4f * narrower
    }

}
