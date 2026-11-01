package com.lazyadaptivelayout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroHeightRunProbeTest {

    private val bufferSize = 2

    @Test
    fun nextProbe_tailHasHeight_returnsNull() {
        val heights = listOf(100, 100, 0, 0, 100, 0, 0, 0, 0, 0)
        // Window ends at index 5; the item at index 4 has height, so the window is not stalled.
        assertNull(nextZeroRunProbeRange(heights, measuredEnd = 5, bufferSize = bufferSize, extraItemsProbed = 0))
    }

    @Test
    fun nextProbe_tailAllZero_probesNextStep() {
        val heights = listOf(100, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100)
        val range = nextZeroRunProbeRange(heights, measuredEnd = 5, bufferSize = bufferSize, extraItemsProbed = 0)
        assertEquals(6..8, range)
    }

    @Test
    fun nextProbe_windowAlreadyAtListEnd_returnsNull() {
        val heights = listOf(100, 0, 0, 0, 0)
        assertNull(nextZeroRunProbeRange(heights, measuredEnd = 4, bufferSize = bufferSize, extraItemsProbed = 0))
    }

    @Test
    fun nextProbe_clampsToListEnd() {
        val heights = listOf(100, 0, 0, 0, 0, 0)
        val range = nextZeroRunProbeRange(heights, measuredEnd = 4, bufferSize = bufferSize, extraItemsProbed = 0)
        assertEquals(5..5, range)
    }

    @Test
    fun nextProbe_stopsAtLookaheadCap() {
        val heights = List(200) { if (it == 0) 100 else 0 }
        assertNull(
            nextZeroRunProbeRange(
                heights,
                measuredEnd = 20,
                bufferSize = bufferSize,
                extraItemsProbed = ZERO_HEIGHT_RUN_MAX_LOOKAHEAD,
            ),
        )
        val last = nextZeroRunProbeRange(
            heights,
            measuredEnd = 20,
            bufferSize = bufferSize,
            extraItemsProbed = ZERO_HEIGHT_RUN_MAX_LOOKAHEAD - 1,
        )
        assertEquals(21..21, last)
    }

    @Test
    fun nextProbe_emptyOrNegativeEnd_returnsNull() {
        assertNull(nextZeroRunProbeRange(emptyList(), measuredEnd = -1, bufferSize = bufferSize, extraItemsProbed = 0))
        assertNull(nextZeroRunProbeRange(listOf(0, 0), measuredEnd = -1, bufferSize = bufferSize, extraItemsProbed = 0))
    }

    /**
     * Minimal model of the grid's measure passes: heights are learned for the items inside the
     * measured window; zero-height items stay unknown (never cached), and a pass only chains into
     * another one while some height changed (remeasureNeeded).
     */
    private class MeasureModel(private val trueHeights: List<Int>, private val bufferSize: Int) {
        val known = MutableList(trueHeights.size) { 0 }

        private fun measure(range: IntRange): Boolean {
            var changed = false
            for (index in range) {
                if (known[index] != trueHeights[index]) {
                    known[index] = trueHeights[index]
                    changed = true
                }
            }
            return changed
        }

        /** Runs one measure pass and returns whether it requested a remeasure. */
        fun pass(stepOverZeroRuns: Boolean): Boolean {
            val itemCount = trueHeights.size
            val lastMeasured = known.indexOfLast { it > 0 }
            val window = calculateVisibleRangeFromRelativePosition(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                itemCount = itemCount,
                bufferSize = bufferSize,
                viewportHeight = 2856,
                lastMeasuredIndex = lastMeasured,
            )
            var measuredEnd = minOf(itemCount - 1, window.last + bufferSize)
            var changed = measure(0..measuredEnd)
            if (stepOverZeroRuns) {
                var extra = 0
                while (true) {
                    val probe = nextZeroRunProbeRange(known, measuredEnd, bufferSize, extra) ?: break
                    changed = measure(probe) || changed
                    extra += probe.last - probe.first + 1
                    measuredEnd = probe.last
                }
            }
            return changed
        }

        fun settle(stepOverZeroRuns: Boolean) {
            var guard = 0
            while (pass(stepOverZeroRuns) && guard++ < 100) Unit
            // Extra user-driven passes (e.g. scrolling at the bottom) change nothing once stalled.
            repeat(5) { pass(stepOverZeroRuns) }
        }
    }

    // 3 real items, a run of 15 zero-height items (indices 3..17), then 10 real items (18..27).
    private val feed = List(3) { 300 } + List(15) { 0 } + List(10) { 300 }

    @Test
    fun progressiveWindowAlone_stallsOnLongZeroHeightRun() {
        val model = MeasureModel(feed, bufferSize)
        model.settle(stepOverZeroRuns = false)
        val firstRealItemAfterRun = 18
        assertEquals(
            "without stepping, content below the zero-height run is never measured",
            0,
            model.known[firstRealItemAfterRun],
        )
    }

    @Test
    fun steppingOverZeroRuns_reachesContentBelowTheRun() {
        val model = MeasureModel(feed, bufferSize)
        model.settle(stepOverZeroRuns = true)
        assertEquals(feed.indices.map { feed[it] }, model.known)
    }

    @Test
    fun steppingOverZeroRuns_doesNotMeasureAheadWhenNothingIsCollapsed() {
        val allReal = List(40) { 300 }
        val model = MeasureModel(allReal, bufferSize)
        // A single pass must stay progressive (a few items), not jump the whole list.
        model.pass(stepOverZeroRuns = true)
        assertTrue(model.known.count { it > 0 } <= 1 + 2 * bufferSize + 10)
        model.settle(stepOverZeroRuns = true)
        assertEquals(allReal, model.known)
    }
}
