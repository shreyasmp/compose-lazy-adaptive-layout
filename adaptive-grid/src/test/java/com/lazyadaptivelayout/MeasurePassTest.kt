package com.lazyadaptivelayout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import com.lazyadaptivelayout.layout.clearMeasurementCaches
import com.lazyadaptivelayout.model.GridItem
import com.lazyadaptivelayout.model.GridItemConfig
import com.lazyadaptivelayout.model.GridItemType
import com.lazyadaptivelayout.model.ItemPosition
import com.lazyadaptivelayout.provider.GridLayoutProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class MeasurePassTest {

    private val bufferSize = 2
    private val viewportHeight = 1000
    private val constraints = Constraints(maxWidth = 1000, maxHeight = viewportHeight)
    private val density = Density(1f)

    @Before
    fun resetGlobalCaches() {
        clearMeasurementCaches()
    }

    // ---------------------------------------------------------------- fakes

    private class FakePlaceable(desiredWidth: Int, desiredHeight: Int) : Placeable() {
        init {
            val cls = Placeable::class.java
            cls.getDeclaredField("width").apply { isAccessible = true }.setInt(this, desiredWidth)
            cls.getDeclaredField("height").apply { isAccessible = true }.setInt(this, desiredHeight)
        }

        override fun get(alignmentLine: AlignmentLine): Int = 0
        override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) = Unit
    }

    /**
     * A feed whose item heights can change between passes; counts every compose-and-measure.
     *
     * Like a real Measurable, an item may be measured only once per measure pass: a second measure() of
     * the same item throws, which crashes the app.
     */
    private class Feed(val heights: MutableList<Int>, val keys: List<Any> = heights.indices.map { "k$it" }) {
        var measured = 0
        private val measuredInThisPass = mutableSetOf<Int>()

        fun beginPass() = measuredInThisPass.clear()
        val provider: GridLayoutProvider<Int> = object : GridLayoutProvider<Int> {
            override val items: List<GridItem<Int>> = emptyList()
            override val itemCount: Int = heights.size
            override fun getItemConfig(index: Int): GridItemConfig = error("unused")
            override fun getItemKey(index: Int): String = index.toString()
            override fun getContentType(index: Int): Any? = null

            @Suppress("UNUSED_PARAMETER")
            @Composable
            override fun ItemContent(item: Int, index: Int, columnIndex: Int, columns: Int) = Unit
        }
        val enriched: List<EnrichedGridItem<Int>> = heights.indices.map {
            EnrichedGridItem(
                item = it,
                config = GridItemConfig(type = GridItemType.FullWidth),
                group = "g$it",
                groupColumns = 1,
                groupItemIndex = 0,
                key = keys[it],
                contentType = null,
            )
        }

        fun measurer(index: Int, constraints: Constraints): List<Placeable> {
            check(measuredInThisPass.add(index)) {
                "measure() may not be called multiple times on the same Measurable (item $index)"
            }
            measured++
            val h = heights[index]
            return if (h > 0) listOf(FakePlaceable(constraints.maxWidth, h)) else emptyList()
        }
    }

    private class Scroller(val feed: Feed, val cache: MeasuredHeightCache = MeasuredHeightCache()) {
        var positions: List<ItemPosition> = emptyList()
        var last: MeasurePassResult? = null

        fun pass(test: MeasurePassTest, anchor: Int = 0, offset: Int): MeasurePassResult {
            feed.beginPass()
            val result = measureWindow(
                layoutProvider = feed.provider,
                enrichedItems = feed.enriched,
                heightCache = cache,
                previousPositions = positions,
                anchorIndex = anchor,
                anchorScrollOffset = offset,
                constraints = test.constraints,
                density = test.density,
                bufferSize = test.bufferSize,
                measurer = feed::measurer,
                getCachedWidth = { _, columns, maxWidth -> maxWidth / columns },
            )
            positions = result.positions
            last = result
            return result
        }

        /** Runs passes at [offset] until nothing more is requested. */
        fun settle(test: MeasurePassTest, anchor: Int = 0, offset: Int, maxPasses: Int = 200): Int {
            var passes = 0
            do {
                val result = pass(test, anchor, offset)
                passes++
            } while ((result.discoveryIncomplete || result.heightChanges.isNotEmpty()) && passes < maxPasses)
            return passes
        }
    }

    private fun feedOf(count: Int, height: Int = 200) = Feed(MutableList(count) { height })

    private fun assertEveryItemOnScreenHasAPlaceable(result: MeasurePassResult, top: Int) {
        result.positions.forEachIndexed { index, position ->
            if (position.height > 0 && position.y + position.height > top && position.y < top + viewportHeight) {
                assertNotNull("item $index is on screen but has no placeable", result.placeables[index])
            }
        }
    }

    // ------------------------------------------------- measureWindow: cost

    @Test
    fun scrolling_composesOnlyTheViewportAndASmallLookahead_notTheMeasuredPrefix() {
        val feed = feedOf(75)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 0)

        // Scroll deep into the feed one step at a time, the way a fling does.
        var offset = 0
        var worst = 0
        repeat(120) {
            offset += 60
            feed.measured = 0
            scroller.settle(this, offset = offset)
            worst = maxOf(worst, feed.measured)
            assertEveryItemOnScreenHasAPlaceable(scroller.last!!, offset)
        }
        // 75 items: a viewport shows 5, plus 2 buffer each side, plus a bounded look-ahead.
        // The measured prefix (up to 75 items) must not be composed again on every pass.
        assertTrue("composed up to $worst items in one frame's passes", worst <= 40)
    }

    @Test
    fun steadyStatePass_composesOnlyTheItemsAroundTheViewport() {
        val feed = feedOf(75)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 3000)
        scroller.settle(this, offset = 3050) // discovery ahead of the viewport catches up

        feed.measured = 0
        val result = scroller.pass(this, offset = 3050)

        // 5-6 items intersect a 1000px viewport of 200px items; +2 buffer on each side.
        assertTrue("measured ${feed.measured}", feed.measured <= 11)
        assertFalse(result.discoveryIncomplete)
        assertTrue(result.heightChanges.isEmpty())
        assertEveryItemOnScreenHasAPlaceable(result, 3050)
    }

    // ----------------------------------- review: restored / requested scroll position

    @Test
    fun restoredOffsetPastTheFirstItems_isCoveredInOnePassAndNotThrownAway() {
        val feed = feedOf(75)
        val scroller = Scroller(feed)

        val result = scroller.pass(this, anchor = 0, offset = 3000)

        val bottom = result.positions.maxOf { it.y + it.height }
        assertTrue("content must reach the restored viewport, bottom=$bottom", bottom >= 3000 + viewportHeight)
        assertEveryItemOnScreenHasAPlaceable(result, 3000)
        assertFalse("the end of the list is still unknown", result.measuredEnd >= 74)

        val scrollBack = scrollBackToContent(
            positions = result.positions,
            anchorIndex = 0,
            anchorScrollOffset = 3000,
            scrollDelta = 0,
            viewportHeight = viewportHeight,
            contentFullyDiscovered = result.measuredEnd >= 74,
        )
        assertEquals(3000, scrollBack.firstItemScrollOffset)
        assertEquals(0, scrollBack.scrollDelta)
    }

    @Test
    fun anchorPastTheMeasuredPrefix_isPositionedInOnePass() {
        val feed = feedOf(75)
        val scroller = Scroller(feed)

        // A restored index, or scrollToItem / scrollToItemByKey, far past anything measured so far.
        val result = scroller.pass(this, anchor = 40, offset = 0)

        assertEquals(ItemPosition(0, 40 * 200, 1000, 200, 0), result.positions[40])
        assertEveryItemOnScreenHasAPlaceable(result, 40 * 200)
        assertNull("items before the viewport are measured for their height only", result.placeables[10])
    }

    @Test
    fun anchorAtTheLastItem_isPositioned() {
        val feed = feedOf(30)
        val scroller = Scroller(feed)

        val result = scroller.pass(this, anchor = 29, offset = 0)

        assertEquals(29 * 200, result.positions[29].y)
        assertTrue(result.measuredEnd >= 29)
    }

    // ---------------------------------------------- review: scroll-back clamp

    @Test
    fun scrollBack_isSkippedWhileTheEndOfTheContentIsUnknown() {
        val positions = List(10) { ItemPosition(0, it * 200, 1000, 200, 0) } // content bottom 2000
        val unknownEnd = scrollBackToContent(positions, 0, 1500, 0, viewportHeight, contentFullyDiscovered = false)
        assertEquals(1500, unknownEnd.firstItemScrollOffset)

        val knownEnd = scrollBackToContent(positions, 0, 1500, 0, viewportHeight, contentFullyDiscovered = true)
        assertEquals(1000, knownEnd.firstItemScrollOffset)
        assertEquals(500, knownEnd.scrollDelta)
    }

    @Test
    fun forwardFlingIntoUnmeasuredContent_isNotClampedBackToTheMeasuredBottom() {
        val feed = feedOf(75)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 0)
        val measuredBottom = scroller.positions.maxOf { it.y + it.height }

        // A fling reaches well past what has been measured; the pass discovers the missing content and
        // the clamp only considers a bottom that is final.
        val result = scroller.pass(this, offset = measuredBottom + 600)
        val scrollBack = scrollBackToContent(
            result.positions, 0, measuredBottom + 600, 0, viewportHeight, contentFullyDiscovered = result.measuredEnd >= 74,
        )
        assertEquals(measuredBottom + 600, scrollBack.firstItemScrollOffset)
        assertEveryItemOnScreenHasAPlaceable(result, measuredBottom + 600)
    }

    @Test
    fun scrollBack_clampsToTheRealEndOnceEverythingIsDiscovered() {
        val feed = feedOf(8) // 1600px of content
        val scroller = Scroller(feed)
        val result = scroller.pass(this, offset = 1500)
        assertTrue(result.measuredEnd >= 7)

        val scrollBack = scrollBackToContent(result.positions, 0, 1500, 0, viewportHeight, contentFullyDiscovered = true)
        assertEquals(600, scrollBack.firstItemScrollOffset)
    }

    // ---------------------------------------- discovery across zero-height runs

    @Test
    fun zeroHeightRun_isWalkedAcrossPasses_withBoundedWorkPerPass() {
        val heights = MutableList(120) { if (it < 10 || it >= 110) 200 else 0 }
        val feed = Feed(heights)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 0)

        var passes = 0
        var previousEnd = -1
        var offset = 2000
        while (scroller.last!!.measuredEnd < 110 && passes < 60) {
            val result = scroller.pass(this, offset = offset)
            passes++
            assertTrue("discovery must make progress", result.measuredEnd >= previousEnd)
            previousEnd = result.measuredEnd
            if (!result.discoveryIncomplete) offset += 100
        }
        assertTrue("never reached the content below the run", scroller.last!!.measuredEnd >= 110)
        assertTrue("took $passes passes", passes <= 40)
    }

    @Test
    fun trailingAnchors_areVisitedWhenTheViewportApproachesTheEnd() {
        val heights = MutableList(13) { if (it < 10) 200 else 0 } // 3 trailing zero-height anchors
        val feed = Feed(heights)
        val scroller = Scroller(feed)

        // The first pass spends its budget filling the viewport; the next one reaches the anchors.
        var result = scroller.pass(this, offset = 1000)
        var passes = 1
        while (result.discoveryIncomplete && passes < 5) {
            result = scroller.pass(this, offset = 1000)
            passes++
        }

        assertEquals(12, result.measuredEnd)
        assertTrue("took $passes passes", passes <= 3)
    }

    // ------------------------------------- each item is measured once per pass

    @Test
    fun scrollingToTheEndOfAFeedWithTrailingAnchors_neverMeasuresAnItemTwiceInAPass() {
        // 25 items of content followed by anchors (pagination / secondary fetch) that have no height.
        val feed = Feed(MutableList(40) { if (it < 25) 200 else 0 })
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 0)

        var offset = 0
        repeat(120) {
            offset += 60
            scroller.settle(this, offset = offset)
        }
        assertEquals(39, scroller.last!!.measuredEnd)
    }

    @Test
    fun blockThatCollapsesInTheViewport_pullsInItemsBehindEmptyBlocks_withoutMeasuringThemTwice() {
        // Items 15..18 are empty blocks (no height yet) in front of sized items.
        val heights = MutableList(40) { if (it in 15..18) 0 else 200 }
        val feed = Feed(heights)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 1900)

        // A visible block goes back to loading and collapses. Everything below moves up, so item 19 now
        // reaches the viewport. The empty blocks in front of it were already visited by the window of
        // this pass (they have no cached height); they must not be measured again to place item 19.
        feed.heights[10] = 0
        val result = scroller.pass(this, offset = 1900)

        assertEveryItemOnScreenHasAPlaceable(result, 1900)
        assertNotNull("item 19 is on screen", result.placeables[19])
    }

    @Test
    fun measureWindow_neverMeasuresAnItemTwiceInAPass_whateverTheScrollAndHeightChanges() {
        // The Feed fake throws on a second measure() of the same item in one pass, like a real Measurable.
        // Random feeds mix empty blocks with sized ones; blocks load and unload and the scroll moves
        // forward, backward and jumps, so every combination of window, discovery and verification occurs.
        repeat(300) { seed ->
            val random = java.util.Random(seed.toLong())
            val count = 20 + random.nextInt(120)
            val feed = Feed(MutableList(count) { if (random.nextInt(4) == 0) 0 else 40 + random.nextInt(700) })
            val scroller = Scroller(feed)
            var offset = 0
            repeat(60) { step ->
                when (random.nextInt(6)) {
                    0 -> offset = maxOf(0, offset - random.nextInt(1500))
                    1 -> offset += random.nextInt(8000)
                    else -> offset += random.nextInt(400)
                }
                if (random.nextInt(3) == 0) {
                    val changed = random.nextInt(count)
                    feed.heights[changed] = if (feed.heights[changed] > 0) 0 else 40 + random.nextInt(700)
                }
                val anchor = if (random.nextInt(8) == 0) random.nextInt(count) else 0
                try {
                    scroller.pass(this, anchor = anchor, offset = if (anchor == 0) offset else random.nextInt(300))
                } catch (e: IllegalStateException) {
                    throw AssertionError("seed $seed step $step anchor $anchor offset $offset: ${e.message}", e)
                }
            }
        }
    }

    // ---------------------------------------------------- height changes

    @Test
    fun blockThatLoadsAboveTheViewport_leavesNoUnplacedItemOnScreen() {
        val feed = feedOf(40)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 2000)

        // A block above the viewport changes height in the same pass: the items that are now on screen
        // were not around the viewport before, and must still be measured.
        feed.heights[2] = 900
        val result = scroller.pass(this, offset = 2000)
        assertEveryItemOnScreenHasAPlaceable(result, 2000)
    }

    // -------------------------------------------------- height cache

    @Test
    fun appendedItems_keepTheHeightsAlreadyKnown() {
        val feed = feedOf(20)
        val scroller = Scroller(feed)
        scroller.settle(this, offset = 0)
        val known = scroller.cache.heights.toMap()
        assertTrue(known.isNotEmpty())

        val grown = Feed(MutableList(40) { 200 }) // same keys for 0..19, 20 new items
        val next = Scroller(grown, scroller.cache)
        next.positions = scroller.positions
        grown.measured = 0
        next.pass(this, offset = 0)

        known.forEach { (index, height) -> assertEquals(height, scroller.cache.heights[index]) }
        assertTrue("re-measured ${grown.measured} items", grown.measured <= 12)
    }

    @Test
    fun changedKey_dropsTheHeightsFromThatIndexOn() {
        val cache = MeasuredHeightCache()
        val original = Feed(MutableList(10) { 100 })
        assertFalse(cache.syncWith(original.enriched))
        (0 until 10).forEach { cache.heights[it] = 100 }
        cache.reach = 9

        val reordered = Feed(MutableList(10) { 100 }, keys = List(10) { if (it < 4) "k$it" else "other$it" })
        assertTrue(cache.syncWith(reordered.enriched))

        assertEquals(setOf(0, 1, 2, 3), cache.heights.keys)
        assertEquals(3, cache.reach)
    }

    @Test
    fun removedItems_dropTheirHeights() {
        val cache = MeasuredHeightCache()
        cache.syncWith(Feed(MutableList(10) { 100 }).enriched)
        (0 until 10).forEach { cache.heights[it] = 100 }
        cache.reach = 9

        assertFalse(cache.syncWith(Feed(MutableList(6) { 100 }).enriched))

        assertEquals((0 until 6).toSet(), cache.heights.keys)
        assertEquals(5, cache.reach)
    }

    // ------------------------------------------------- pure helpers

    private fun position(index: Int, height: Int = 100) = ItemPosition(0, index * 100, 1000, height, 0)

    @Test
    fun viewportItemRange_includesIntersectingItemsAndTheBuffer() {
        val positions = List(20) { position(it) }
        // viewport 450..750 intersects items 4..7
        assertEquals(2..9, viewportItemRange(positions, 450, 750, bufferSize = 2))
    }

    @Test
    fun viewportItemRange_clampsToTheList() {
        val positions = List(5) { position(it) }
        assertEquals(0..4, viewportItemRange(positions, 0, 300, bufferSize = 4))
    }

    @Test
    fun viewportItemRange_isNullWhenNothingIsPositionedInTheViewport() {
        assertNull(viewportItemRange(List(5) { ItemPosition(0, 0, 0, 0, 0) }, 0, 500, 2))
        assertNull(viewportItemRange(List(5) { position(it) }, 2000, 2500, 2))
        assertNull(viewportItemRange(emptyList(), 0, 500, 2))
    }

    @Test
    fun viewportItemRange_keepsZeroHeightItemsThatSitInsideTheRange() {
        val positions = listOf(position(0), ItemPosition(0, 100, 1000, 0, 0), position(2))
        assertEquals(0..2, viewportItemRange(positions, 0, 300, bufferSize = 0))
    }

    @Test
    fun nextDiscoveryRange_stepsByTheBufferAndStopsAtTheListEnd() {
        assertEquals(10..12, nextDiscoveryRange(100, measuredEnd = 9, bufferSize = 2, itemsDiscovered = 0, viewportCovered = false))
        assertEquals(99..99, nextDiscoveryRange(100, measuredEnd = 98, bufferSize = 2, itemsDiscovered = 0, viewportCovered = false))
        assertNull(nextDiscoveryRange(100, measuredEnd = 99, bufferSize = 2, itemsDiscovered = 0, viewportCovered = false))
    }

    @Test
    fun nextDiscoveryRange_hasASmallerBudgetOnceTheViewportIsCovered() {
        assertNotNull(nextDiscoveryRange(500, 9, 2, itemsDiscovered = LOOKAHEAD_ITEMS_PER_PASS - 1, viewportCovered = true))
        assertNull(nextDiscoveryRange(500, 9, 2, itemsDiscovered = LOOKAHEAD_ITEMS_PER_PASS, viewportCovered = true))
        assertNotNull(nextDiscoveryRange(500, 9, 2, itemsDiscovered = LOOKAHEAD_ITEMS_PER_PASS, viewportCovered = false))
        assertNull(nextDiscoveryRange(500, 9, 2, itemsDiscovered = VIEWPORT_ITEMS_PER_PASS, viewportCovered = false))
    }

    @Test
    fun nextDiscoveryRange_neverExceedsTheRemainingBudget() {
        val range = nextDiscoveryRange(500, 9, bufferSize = 10, itemsDiscovered = LOOKAHEAD_ITEMS_PER_PASS - 2, viewportCovered = true)
        assertEquals(10..11, range)
    }
}
