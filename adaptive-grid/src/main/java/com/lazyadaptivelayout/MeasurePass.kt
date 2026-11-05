package com.lazyadaptivelayout

import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.lazyadaptivelayout.layout.invalidatePositionCache
import com.lazyadaptivelayout.layout.invalidatePositionCacheIfHeightsChanged
import com.lazyadaptivelayout.layout.measureFlattenedGrid
import com.lazyadaptivelayout.model.GridItemConfig
import com.lazyadaptivelayout.model.ItemPosition
import com.lazyadaptivelayout.provider.GridLayoutProvider

/**
 * Items whose height is not known up front can only be sized by composing and measuring them. To keep
 * a pass cheap the grid does that for the items in (and just around) the viewport, reuses the cached
 * height of every other item it has already seen, and discovers the heights of items it has not seen
 * yet in small steps, only as far ahead of the viewport as needed.
 *
 * Items discovered ahead of the viewport are limited per pass so that a block that is expensive to
 * compose cannot stall a frame; when more is needed the pass asks for another one.
 */

/** Most items composed purely to learn their height in a pass while the viewport is already filled. */
internal const val LOOKAHEAD_ITEMS_PER_PASS = 6

/** Most items composed in a pass while the viewport (or the scroll anchor) is not covered yet. */
internal const val VIEWPORT_ITEMS_PER_PASS = 60

/** True for the all-zero position given to items that have not been placed (not measured, or padding only). */
internal fun ItemPosition.isUnpositioned(): Boolean = x == 0 && y == 0 && width == 0 && height == 0

/**
 * Heights learned by measuring, shared by every pass of one grid.
 *
 * Only items that currently have a height are cached: an item that renders nothing is measured again on
 * every pass, so it expands as soon as its content arrives.
 */
internal class MeasuredHeightCache {
    val heights = mutableMapOf<Int, Int>()

    /**
     * Highest item index composed and measured so far. It lets the discovery step walk across a run of
     * zero-height items over several passes instead of starting from the last item with a height every time.
     */
    var reach = -1

    private var keys: List<Any> = emptyList()
    private var itemsRef: List<*>? = null

    /**
     * Keeps the heights of items whose key is unchanged at the same index (for example when a page is
     * appended to a feed) and drops the rest. Returns true when an existing item changed, which also
     * invalidates the positions computed from the old heights.
     */
    fun syncWith(items: List<EnrichedGridItem<*>>): Boolean {
        if (itemsRef === items) return false
        itemsRef = items

        val previous = keys
        val common = minOf(previous.size, items.size)
        var firstChanged = common
        for (index in 0 until common) {
            if (previous[index] != items[index].key) {
                firstChanged = index
                break
            }
        }
        keys = items.map { it.key }

        heights.keys.removeAll { it >= firstChanged }
        if (reach >= firstChanged) reach = firstChanged - 1
        // Items appended or removed at the end do not move the others; a changed key does.
        return firstChanged < common
    }

    fun clear() {
        heights.clear()
        reach = -1
        keys = emptyList()
        itemsRef = null
    }
}

/**
 * The items that intersect the viewport, plus [bufferSize] items on each side, or null when none of the
 * positioned items does. Zero-height and unpositioned items never intersect, but they are inside the
 * range when they sit between items that do.
 */
internal fun viewportItemRange(
    positions: List<ItemPosition>,
    viewportTop: Int,
    viewportBottom: Int,
    bufferSize: Int,
): IntRange? {
    var first = -1
    var last = -1
    for (index in positions.indices) {
        val position = positions[index]
        if (position.height <= 0) continue
        if (position.y + position.height > viewportTop && position.y < viewportBottom) {
            if (first < 0) first = index
            last = index
        }
    }
    if (first < 0) return null
    return maxOf(0, first - bufferSize)..minOf(positions.size - 1, last + bufferSize)
}

/**
 * The next items to compose and measure to learn their heights, or null when the budget for this pass is
 * used up. [viewportCovered] is true when positioned content already fills the viewport; discovery ahead
 * of the viewport then gets the smaller [LOOKAHEAD_ITEMS_PER_PASS] budget.
 *
 * @param measuredEnd last item composed and measured so far.
 * @param itemsDiscovered items composed by earlier calls in this pass.
 */
internal fun nextDiscoveryRange(
    itemCount: Int,
    measuredEnd: Int,
    bufferSize: Int,
    itemsDiscovered: Int,
    viewportCovered: Boolean,
): IntRange? {
    val lastIndex = itemCount - 1
    if (measuredEnd >= lastIndex) return null
    val budget = if (viewportCovered) LOOKAHEAD_ITEMS_PER_PASS else VIEWPORT_ITEMS_PER_PASS
    val remaining = budget - itemsDiscovered
    if (remaining <= 0) return null
    val start = measuredEnd + 1
    return start..minOf(lastIndex, measuredEnd + bufferSize + 1, start + remaining - 1)
}

internal class MeasurePassResult(
    val placeables: List<Placeable?>,
    val itemHeights: List<Int>,
    val heightChanges: List<Pair<Int, Int>>,
    val positions: List<ItemPosition>,
    /** Highest item index composed and measured in this pass. */
    val measuredEnd: Int,
    /** True when the viewport or the look-ahead still needs items that this pass had no budget for. */
    val discoveryIncomplete: Boolean,
    /** How many times an item was composed and measured (not served from the height cache). */
    val itemsMeasured: Int,
)

/**
 * One measure pass over the grid. See the notes at the top of this file.
 *
 * 1. Items in (and [bufferSize] around) the viewport, taken from [previousPositions], are measured fresh
 *    and their placeables are kept; items already known keep their cached height.
 * 2. While the positioned content does not reach one viewport past the viewport (or the scroll anchor has
 *    no position yet), the next items are discovered, within the per-pass budget.
 * 3. Items that intersect the viewport once the new heights are applied, but were not measured fresh,
 *    are measured too, so everything that is drawn has a placeable.
 */
internal fun <T> measureWindow(
    layoutProvider: GridLayoutProvider<T>,
    enrichedItems: List<EnrichedGridItem<T>>,
    heightCache: MeasuredHeightCache,
    previousPositions: List<ItemPosition>,
    anchorIndex: Int,
    anchorScrollOffset: Int,
    constraints: Constraints,
    density: Density,
    bufferSize: Int,
    measurer: (Int, Constraints) -> List<Placeable>,
    getCachedWidth: (GridItemConfig, Int, Int) -> Int,
): MeasurePassResult {
    val itemCount = layoutProvider.itemCount
    if (itemCount == 0) {
        return MeasurePassResult(emptyList(), emptyList(), emptyList(), emptyList(), -1, false, 0)
    }
    val lastIndex = itemCount - 1
    val viewportHeight = constraints.maxHeight

    if (heightCache.syncWith(enrichedItems)) invalidatePositionCache()

    val anchorTop = previousPositions.getOrNull(anchorIndex)?.takeUnless { it.isUnpositioned() }?.y
    val viewportTopEstimate = (anchorTop ?: 0) + anchorScrollOffset
    val placeRange = (
        viewportItemRange(previousPositions, viewportTopEstimate, viewportTopEstimate + viewportHeight, bufferSize)
            ?: (maxOf(0, anchorIndex - bufferSize)..(anchorIndex + bufferSize))
        ).let { it.first.coerceAtMost(lastIndex)..it.last.coerceAtMost(lastIndex) }

    val lastMeasuredIndex = previousPositions.indexOfLast { it.height > 0 }
    val windowEnd = minOf(
        lastIndex,
        maxOf(placeRange.last, lastMeasuredIndex, heightCache.reach, anchorIndex + bufferSize),
    )

    var itemsMeasured = 0
    val freshlyMeasured = BooleanArray(itemCount)
    val placeables = MutableList<Placeable?>(itemCount) { null }
    var itemHeights: List<Int> = emptyList()
    var heightChanges: List<Pair<Int, Int>> = emptyList()

    fun measure(window: IntRange, fresh: IntRange) {
        val pass = measureGridItems(
            layoutProvider = layoutProvider,
            enrichedItems = enrichedItems,
            visibleRange = window,
            freshRange = fresh,
            measuredHeights = heightCache.heights,
            constraints = constraints,
            measurer = { index, itemConstraints ->
                itemsMeasured++
                measurer(index, itemConstraints)
            },
            bufferSize = 0,
            getCachedWidth = getCachedWidth,
        )
        for (index in fresh) {
            pass.first.getOrNull(index)?.let { placeables[index] = it }
            if (index in 0..lastIndex) freshlyMeasured[index] = true
        }
        itemHeights = pass.second
        heightChanges = heightChanges + pass.third
    }

    fun layout(): List<ItemPosition> {
        invalidatePositionCacheIfHeightsChanged(heightChanges)
        return measureFlattenedGrid(enrichedItems, itemHeights, constraints, density)
    }

    // 1. The window: everything up to windowEnd is visited, but only the items around the viewport are
    //    measured fresh; the rest are served from the cache (or measured once when they have no height yet).
    measure(window = 0..windowEnd, fresh = placeRange)
    var measuredEnd = windowEnd
    var positions = layout()

    fun viewportTopOf(positions: List<ItemPosition>): Int =
        (positions.getOrNull(anchorIndex)?.takeUnless { it.isUnpositioned() }?.y ?: 0) + anchorScrollOffset

    // 2. Discover items ahead of the viewport, as far as one more viewport.
    var itemsDiscovered = 0
    var discoveryIncomplete = false
    while (measuredEnd < lastIndex) {
        val viewportBottom = viewportTopOf(positions) + viewportHeight
        val contentBottom = positions.maxOfOrNull { it.y + it.height } ?: 0
        val anchorResolved = anchorIndex == 0 || positions.getOrNull(anchorIndex)?.isUnpositioned() == false
        val viewportCovered = anchorResolved && contentBottom >= viewportBottom
        if (viewportCovered && contentBottom >= viewportBottom + viewportHeight) break

        val range = nextDiscoveryRange(itemCount, measuredEnd, bufferSize, itemsDiscovered, viewportCovered)
        if (range == null) {
            discoveryIncomplete = true
            break
        }
        measure(window = range, fresh = range)
        itemsDiscovered += range.last - range.first + 1
        measuredEnd = range.last
        positions = layout()
    }

    // 3. Heights above the viewport may have changed what intersects it; measure what is missing.
    for (round in 0 until 2) {
        val top = viewportTopOf(positions)
        val needed = viewportItemRange(positions, top, top + viewportHeight, bufferSize) ?: break
        val missing = needed.filter { !freshlyMeasured[it] }
        if (missing.isEmpty()) break
        val range = missing.first()..missing.last()
        measure(window = range, fresh = range)
        measuredEnd = maxOf(measuredEnd, range.last)
        positions = layout()
    }

    heightCache.reach = maxOf(heightCache.reach, measuredEnd)
    return MeasurePassResult(placeables, itemHeights, heightChanges, positions, measuredEnd, discoveryIncomplete, itemsMeasured)
}

/** The scroll position after keeping the viewport inside the content. */
internal data class ScrollBackResult(val firstItemScrollOffset: Int, val scrollDelta: Int)

/**
 * Pulls the scroll position back when the viewport would extend past the end of the content.
 *
 * Only done once [contentFullyDiscovered] (every item has been visited): before that the content's end is
 * unknown, so a short content height is not a limit. Clamping on it would throw away a restored or
 * requested scroll offset and cut a fling short before the rest of the list is measured.
 */
internal fun scrollBackToContent(
    positions: List<ItemPosition>,
    anchorIndex: Int,
    anchorScrollOffset: Int,
    scrollDelta: Int,
    viewportHeight: Int,
    contentFullyDiscovered: Boolean,
): ScrollBackResult {
    var offset = anchorScrollOffset
    var delta = scrollDelta
    val totalContentHeight = positions.maxOfOrNull { it.y + it.height } ?: 0
    if (!contentFullyDiscovered || totalContentHeight <= 0) return ScrollBackResult(offset, delta)
    val anchor = positions.getOrNull(anchorIndex) ?: return ScrollBackResult(offset, delta)

    val viewportEnd = anchor.y + offset + viewportHeight
    if (viewportEnd > totalContentHeight) {
        val toScrollBack = viewportEnd - totalContentHeight
        offset -= toScrollBack
        delta += toScrollBack
        if (offset < 0) {
            delta += offset
            offset = 0
        }
    }

    val contentExtentBelowStart = totalContentHeight - (anchor.y + offset)
    if (contentExtentBelowStart < viewportHeight) {
        val toScrollBack = viewportHeight - contentExtentBelowStart
        offset -= toScrollBack
        delta += toScrollBack
        if (offset < 0) {
            delta += offset
            offset = 0
        }
    }
    return ScrollBackResult(offset, delta)
}
