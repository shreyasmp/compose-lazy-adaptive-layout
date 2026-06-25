package com.lazyadaptivelayout.layout

/**
 * Returns the indices of trailing items that should be reported as visible even though they
 * collapse to zero height and therefore never geometrically intersect the viewport.
 *
 * Items that render nothing — e.g. pagination or secondary-fetch anchors — have a height of 0 and
 * are positioned at the very end of the content. Because they occupy no vertical space they are
 * excluded from the geometric visible set, which causes [com.lazyadaptivelayout.state.AdaptiveGridState.lastVisibleItemIndex]
 * to undershoot the true last index. Index-based pagination (`lastVisible >= totalCount - 1 - buffer`)
 * can then stall — most notably with a zero buffer, where the last index is never reached.
 *
 * To stay correct, trailing items are only treated as visible once the measure window has actually
 * reached the end ([measureWindowReachedEnd]). At that point every item up to the end has been
 * composed, so any item that is still zero-height is genuinely empty (an anchor) rather than a real
 * item that simply has not been measured yet. Only the contiguous run of zero-height items
 * immediately following [lastVisibleIndex] is included; a real item below the last visible one stops
 * the scan so it must still be scrolled into view normally.
 *
 * @param lastVisibleIndex index of the last geometrically visible item, or -1 if none.
 * @param itemsCount total number of items in the grid.
 * @param measureWindowReachedEnd whether the current measure pass attempted to measure the last item.
 * @param effectiveHeightAt effective (normalized) height of the item at a given index.
 */
internal fun trailingCollapsedIndices(
    lastVisibleIndex: Int,
    itemsCount: Int,
    measureWindowReachedEnd: Boolean,
    effectiveHeightAt: (Int) -> Int,
): List<Int> {
    if (!measureWindowReachedEnd || itemsCount == 0) return emptyList()
    if (lastVisibleIndex < 0 || lastVisibleIndex >= itemsCount - 1) return emptyList()

    val result = mutableListOf<Int>()
    for (index in (lastVisibleIndex + 1) until itemsCount) {
        if (effectiveHeightAt(index) > 0) break
        result.add(index)
    }
    return result
}
