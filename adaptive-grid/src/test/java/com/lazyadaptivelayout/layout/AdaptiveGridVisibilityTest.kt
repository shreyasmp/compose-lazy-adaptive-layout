package com.lazyadaptivelayout.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveGridVisibilityTest {

    @Test
    fun trailingCollapsedIndices_returnsEmpty_whenMeasureWindowHasNotReachedEnd() {
        // Last real item visible, two zero-height anchors below, but window has not reached the end.
        val result = trailingCollapsedIndices(
            lastVisibleIndex = 3,
            itemsCount = 6,
            measureWindowReachedEnd = false,
            effectiveHeightAt = { 0 },
        )

        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun trailingCollapsedIndices_includesTrailingZeroHeightAnchors_whenWindowReachedEnd() {
        // Items 4 and 5 are zero-height trailing anchors after the last visible real item (index 3).
        val heights = intArrayOf(100, 100, 100, 100, 0, 0)
        val result = trailingCollapsedIndices(
            lastVisibleIndex = 3,
            itemsCount = 6,
            measureWindowReachedEnd = true,
            effectiveHeightAt = { heights[it] },
        )

        assertEquals(listOf(4, 5), result)
    }

    @Test
    fun trailingCollapsedIndices_stopsAtFirstRealItem_belowLastVisible() {
        // index 4 is a zero anchor, index 5 is a real (not-yet-visible) item: must not be reported.
        val heights = intArrayOf(100, 100, 100, 100, 0, 100)
        val result = trailingCollapsedIndices(
            lastVisibleIndex = 3,
            itemsCount = 6,
            measureWindowReachedEnd = true,
            effectiveHeightAt = { heights[it] },
        )

        assertEquals(listOf(4), result)
    }

    @Test
    fun trailingCollapsedIndices_returnsEmpty_whenLastVisibleIsAlreadyLastItem() {
        val result = trailingCollapsedIndices(
            lastVisibleIndex = 5,
            itemsCount = 6,
            measureWindowReachedEnd = true,
            effectiveHeightAt = { 0 },
        )

        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun trailingCollapsedIndices_returnsEmpty_whenNothingVisible() {
        val result = trailingCollapsedIndices(
            lastVisibleIndex = -1,
            itemsCount = 6,
            measureWindowReachedEnd = true,
            effectiveHeightAt = { 0 },
        )

        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun trailingCollapsedIndices_returnsEmpty_whenGridIsEmpty() {
        val result = trailingCollapsedIndices(
            lastVisibleIndex = -1,
            itemsCount = 0,
            measureWindowReachedEnd = true,
            effectiveHeightAt = { 0 },
        )

        assertEquals(emptyList<Int>(), result)
    }
}
