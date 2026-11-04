package com.lazyadaptivelayout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.scrollableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import kotlin.math.abs
import kotlin.math.sign
import com.lazyadaptivelayout.layout.clearMeasurementCaches
import com.lazyadaptivelayout.layout.invalidatePositionCacheIfHeightsChanged
import com.lazyadaptivelayout.layout.measureFlattenedGrid
import com.lazyadaptivelayout.layout.notifyDataVersion
import com.lazyadaptivelayout.layout.collapsedZeroHeightVisibleIndices
import com.lazyadaptivelayout.model.AdaptiveGridItemInfo
import com.lazyadaptivelayout.model.GridItem
import com.lazyadaptivelayout.model.GridItemConfig
import com.lazyadaptivelayout.model.GridItemType
import com.lazyadaptivelayout.model.ItemPosition
import com.lazyadaptivelayout.placement.LazySimplePlacementScope
import com.lazyadaptivelayout.provider.GridLayoutProvider
import com.lazyadaptivelayout.state.AdaptiveGridMeasureResult
import com.lazyadaptivelayout.state.AdaptiveGridState

val LocalGridItemConfig = staticCompositionLocalOf<GridItemConfig> {
    error("LocalGridItemConfig not provided")
}

internal val LocalAdaptiveGridMeasureInvalidator =
    staticCompositionLocalOf<com.lazyadaptivelayout.layout.ObservableScopeInvalidator?> { null }

@Composable
/** Returns a remembered [AdaptiveGridState] suitable for use with LazyAdaptiveGrid. */
fun rememberAdaptiveGridState(): AdaptiveGridState {
    return rememberSaveable(saver = AdaptiveGridState.Saver) { AdaptiveGridState() }
}


@Composable
/** Builds and remembers the unified list of groups defined in the [AdaptiveGridScope] content. */
private fun <T> rememberUnifiedGroups(content: AdaptiveGridScope<T>.() -> Unit): List<UnifiedGroup<T>> {
    return remember(content) {
        val scope = AdaptiveGridScopeImpl<T>()
        scope.content()
        scope.unifiedGroups
    }
}

internal data class EnrichedGridItem<T>(
    val item: T,
    val config: GridItemConfig,
    val group: Any,
    val groupColumns: Int,
    val groupItemIndex: Int,
    val key: Any,
    val contentType: Any?
)

@Composable
/** Flattens groups into per-item metadata used by measurement and composition. */
private fun <T> rememberEnrichedItems(
    unifiedGroups: List<UnifiedGroup<T>>,
    density: Density
): List<EnrichedGridItem<T>> {
    return remember(unifiedGroups, density) {
        val enrichedItems = mutableListOf<EnrichedGridItem<T>>()
        for (unifiedGroup in unifiedGroups) {
            when (unifiedGroup) {
                is UnifiedGroup.Regular -> {
                    val group = unifiedGroup.group
                    for ((itemIndex, item) in group.items.withIndex()) {
                        val calculatedHeight = with(density) { group.height.invoke(item, itemIndex).toPx().toInt() }
                        val config = GridItemConfig(
                            type = group.type,
                            height = if (calculatedHeight > 0) calculatedHeight else null,
                            edgeSpacing = group.edgeSpacing,
                            contentPadding = group.contentPadding
                        )
                        enrichedItems.add(
                            EnrichedGridItem(
                                item = item,
                                config = config,
                                group = group,
                                groupColumns = group.columns,
                                groupItemIndex = itemIndex,
                                key = group.key.invoke(item, itemIndex),
                                contentType = group.contentType.invoke(item, itemIndex)
                            )
                        )
                    }
                }
                is UnifiedGroup.Custom -> {
                    val customGroup = unifiedGroup.group
                    for ((itemIndex, customItem) in customGroup.customItems.withIndex()) {
                        val calculatedHeight = if (customItem.height != null) {
                            with(density) { customItem.height.toPx().toInt() }
                        } else {
                            with(density) { customGroup.height.invoke(customItem.item, itemIndex).toPx().toInt() }
                        }
                        val config = GridItemConfig(
                            type = GridItemType.Custom(customItem.span),
                            height = if (calculatedHeight > 0) calculatedHeight else null,
                            edgeSpacing = customGroup.edgeSpacing,
                            contentPadding = customGroup.contentPadding
                        )
                        enrichedItems.add(
                            EnrichedGridItem(
                                item = customItem.item,
                                config = config,
                                group = customGroup,
                                groupColumns = customGroup.columns,
                                groupItemIndex = itemIndex,
                                key = customGroup.key.invoke(customItem.item, itemIndex),
                                contentType = customGroup.contentType.invoke(customItem.item, itemIndex)
                            )
                        )
                    }
                }
            }
        }
        enrichedItems
    }
}

@Composable
/** Creates a [GridLayoutProvider] backed by the current enriched items. */
private fun <T> rememberLayoutProvider(
    enrichedItems: List<EnrichedGridItem<T>>
): GridLayoutProvider<T> {
    return remember(enrichedItems) {
        createLayoutProviderFromEnrichedItems(enrichedItems)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * A high-performance, scrollable adaptive grid.
 *
 * @param modifier Optional [Modifier] for the container.
 * @param state Scroll and visibility state for this grid.
 * @param bufferSize Number of offscreen items to measure on each side of the viewport.
 * @param content Grid content defined via [AdaptiveGridScope].
 */
fun <T> LazyAdaptiveGrid(
    modifier: Modifier = Modifier,
    state: AdaptiveGridState = rememberAdaptiveGridState(),
    bufferSize: Int = 2,
    reverseDirection: Boolean = false,
    scrollEnable: Boolean = true,
    content: AdaptiveGridScope<T>.() -> Unit
) {

    val density = LocalDensity.current

    val unifiedGroups = rememberUnifiedGroups(content)
    val enrichedItems = rememberEnrichedItems(unifiedGroups, density)

    val layoutProvider = rememberLayoutProvider(enrichedItems)

    val dataVersion = remember(enrichedItems) {
        var acc = 1
        for (e in enrichedItems) {
            acc = 31 * acc + (e.key.hashCode() ?: 0)
        }
        31 * acc + enrichedItems.size
    }

    val measuredHeights = remember(dataVersion) {
        mutableMapOf<Int, Int>()
    }

    val positionsState = remember {
        mutableStateOf<List<ItemPosition>>(emptyList())
    }

    val lastDataVersion = remember {
        mutableStateOf(dataVersion)
    }

    val widthCache = remember {
        mutableMapOf<String, Int>()
    }

    val positionCache = remember {
        mutableMapOf<String, List<ItemPosition>>()
    }

    val getCachedWidth = remember {
        { config: GridItemConfig, groupColumns: Int, maxWidth: Int ->
            val safeColumns = groupColumns.coerceAtLeast(1)
            val key = "${config.type}_${safeColumns}_${maxWidth}"
            widthCache.getOrPut(key) {
                when (val gridType = config.type) {
                    is GridItemType.FullWidth -> maxWidth
                    is GridItemType.Custom -> {
                        val spans = minOf(gridType.spans, safeColumns)
                        (maxWidth / safeColumns) * spans
                    }
                    else -> maxWidth / safeColumns
                }
            }
        }
    }

    val lazyLayoutItemProvider = remember(layoutProvider, enrichedItems) {
        object : LazyLayoutItemProvider {
            override val itemCount: Int = layoutProvider.itemCount

            @Composable
            override fun Item(index: Int, key: Any) {
                val config = enrichedItems[index].config
                val columnIndex = positionsState.value.getOrNull(index)?.columnIndex ?: 0
                CompositionLocalProvider(LocalGridItemConfig provides config) {
                    layoutProvider.ItemContent(
                        item = enrichedItems[index].item,
                        index = index,
                        columnIndex = columnIndex,
                        columns = enrichedItems[index].groupColumns
                    )
                }
            }
            override fun getKey(index: Int): Any = enrichedItems[index].key
            override fun getContentType(index: Int): Any? = enrichedItems[index].contentType
        }
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(dataVersion) {
        if (dataVersion != lastDataVersion.value) {
            notifyDataVersion(dataVersion)
            lastDataVersion.value = dataVersion
            measuredHeights.clear()
        }
    }

    val measurePolicy = remember<LazyLayoutMeasureScope.(Constraints) -> MeasureResult>(
        state,
        layoutProvider,
        enrichedItems,
        dataVersion,
        density,
        bufferSize,
        getCachedWidth,
        coroutineScope,
    ) {
        { constraints ->
            state.measurementScopeInvalidator.attachToScope()
            state.viewportHeight = constraints.maxHeight

            val hasLookaheadOccurred = state.hasLookaheadOccurred || isLookingAhead
            val itemProvider = layoutProvider
            state.layoutProvider = layoutProvider

            val firstVisibleItemIndex: Int
            val firstVisibleItemScrollOffset: Int
            Snapshot.withoutReadObservation {
                firstVisibleItemIndex =
                    state.updateScrollPositionIfTheFirstItemWasMoved(
                        itemProvider,
                        state.firstVisibleItemIndex,
                    )
                firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
            }

            val scrollToBeConsumed =
                if (isLookingAhead || !hasLookaheadOccurred) {
                    state.scrollToBeConsumed
                } else {
                    state.scrollDeltaBetweenPasses
                }

            val itemsCount = itemProvider.itemCount

            var currentFirstItemIndex = firstVisibleItemIndex
            var currentFirstItemScrollOffset = firstVisibleItemScrollOffset
            
            if (currentFirstItemIndex >= itemsCount) {
                currentFirstItemIndex = (itemsCount - 1).coerceAtLeast(0)
                currentFirstItemScrollOffset = 0
            }

            // scrollToBeConsumed: negative = forward/down, positive = backward/up
            var scrollDelta = scrollToBeConsumed.fastRoundToInt()
            
            if (currentFirstItemIndex == 0 && scrollDelta > 0) {
                val maxBackwardScroll = currentFirstItemScrollOffset
                if (scrollDelta > maxBackwardScroll) {
                    scrollDelta = maxBackwardScroll
                }
            }
            
            currentFirstItemScrollOffset -= scrollDelta
            if (currentFirstItemScrollOffset < 0) {
                scrollDelta += currentFirstItemScrollOffset
                currentFirstItemScrollOffset = 0
            }

            val lastMeasuredIndex = state._itemPositions.indexOfLast { it.height > 0 }
            val rangeStartIndex = if (lastMeasuredIndex >= 0) {
                minOf(currentFirstItemIndex, lastMeasuredIndex)
            } else {
                currentFirstItemIndex
            }
            val visibleRange = calculateVisibleRangeFromRelativePosition(
                firstVisibleItemIndex = rangeStartIndex,
                firstVisibleItemScrollOffset = currentFirstItemScrollOffset,
                itemCount = itemsCount,
                bufferSize = bufferSize,
                viewportHeight = constraints.maxHeight,
                lastMeasuredIndex = lastMeasuredIndex,
            )

            val measurer: (Int, Constraints) -> List<Placeable> =
                { index, constraints -> compose(index).map { it.measure(constraints) } }
            val firstPass = measureGridItems(
                layoutProvider = layoutProvider,
                enrichedItems = enrichedItems,
                visibleRange = visibleRange,
                measuredHeights = measuredHeights,
                constraints = constraints,
                measurer = measurer,
                bufferSize = bufferSize,
                getCachedWidth = getCachedWidth
            )
            var placeables = firstPass.first
            var itemHeights = firstPass.second
            var heightChanges = firstPass.third

            // Step the measure window over zero-height runs (anchors, empty blocks) so content
            // below them is still reached; see nextZeroRunProbeRange.
            var measuredEnd = minOf(itemsCount - 1, visibleRange.last + bufferSize)
            var extraItemsProbed = 0
            while (true) {
                val probeRange = nextZeroRunProbeRange(
                    itemHeights = itemHeights,
                    measuredEnd = measuredEnd,
                    bufferSize = bufferSize,
                    extraItemsProbed = extraItemsProbed,
                ) ?: break
                val probe = measureGridItems(
                    layoutProvider = layoutProvider,
                    enrichedItems = enrichedItems,
                    visibleRange = probeRange,
                    measuredHeights = measuredHeights,
                    constraints = constraints,
                    measurer = measurer,
                    bufferSize = 0,
                    getCachedWidth = getCachedWidth
                )
                val mergedPlaceables = placeables.toMutableList()
                for (index in probeRange) {
                    probe.first.getOrNull(index)?.let { mergedPlaceables[index] = it }
                }
                placeables = mergedPlaceables
                itemHeights = probe.second
                heightChanges = heightChanges + probe.third
                extraItemsProbed += probeRange.last - probeRange.first + 1
                measuredEnd = probeRange.last
            }

            invalidatePositionCacheIfHeightsChanged(heightChanges)
            val currentPositions = measureFlattenedGrid(enrichedItems, itemHeights, constraints, density)
            positionsState.value = currentPositions
            state._itemPositions = currentPositions

            val totalContentHeight = currentPositions.maxOfOrNull { it.y + it.height } ?: 0
            val preScrollBackScrollDelta = scrollDelta

            if (totalContentHeight > 0 && itemsCount > 0) {
                val scrollAnchorPosition = currentPositions.getOrNull(currentFirstItemIndex)
                if (scrollAnchorPosition != null) {
                    var viewportEnd =
                        scrollAnchorPosition.y + currentFirstItemScrollOffset + constraints.maxHeight
                    if (viewportEnd > totalContentHeight) {
                        val toScrollBack = viewportEnd - totalContentHeight
                        currentFirstItemScrollOffset -= toScrollBack
                        scrollDelta += toScrollBack
                        if (currentFirstItemScrollOffset < 0) {
                            scrollDelta += currentFirstItemScrollOffset
                            currentFirstItemScrollOffset = 0
                        }
                    }

                    val lastMeasuredIndex = currentPositions.indexOfLast { it.height > 0 }
                    val allItemsMeasured = lastMeasuredIndex == itemsCount - 1
                    if (allItemsMeasured) {
                        val viewportStart =
                            scrollAnchorPosition.y + currentFirstItemScrollOffset
                        val contentExtentBelowStart = totalContentHeight - viewportStart
                        val maxOffset = constraints.maxHeight
                        if (contentExtentBelowStart < maxOffset) {
                            val toScrollBack = maxOffset - contentExtentBelowStart
                            currentFirstItemScrollOffset -= toScrollBack
                            scrollDelta += toScrollBack
                            if (currentFirstItemScrollOffset < 0) {
                                scrollDelta += currentFirstItemScrollOffset
                                currentFirstItemScrollOffset = 0
                            }
                        }
                    }
                }
            }

            val firstItemPosition = currentPositions.getOrNull(currentFirstItemIndex)
            val viewportStart = if (firstItemPosition != null) {
                firstItemPosition.y + currentFirstItemScrollOffset
            } else {
                0
            }
            val viewportEnd = viewportStart + constraints.maxHeight
            val visibleItemsInfo = mutableListOf<AdaptiveGridItemInfo>()

            currentPositions.forEachIndexed { index, position ->
                if (position.x == 0 && position.y == 0 && position.width == 0 && position.height == 0) return@forEachIndexed
                val normalizedSize = normalizedItemSize(index, position, measuredHeights)
                val itemTop = position.y
                val itemBottom = position.y + normalizedSize

                if (itemBottom >= viewportStart && itemTop <= viewportEnd) {
                    visibleItemsInfo.add(
                        buildAdaptiveGridItemInfo(
                            index = index,
                            position = position,
                            normalizedSize = normalizedSize,
                            viewportStart = viewportStart,
                            enrichedItems = enrichedItems,
                        )
                    )
                }
            }

            val firstVisibleItem =
                if (currentFirstItemIndex in 0 until itemsCount) {
                    currentPositions.getOrNull(currentFirstItemIndex)?.let { firstItemPosition ->
                        buildAdaptiveGridItemInfo(
                            index = currentFirstItemIndex,
                            position = firstItemPosition,
                            normalizedSize = normalizedItemSize(
                                currentFirstItemIndex,
                                firstItemPosition,
                                measuredHeights,
                            ),
                            viewportStart = viewportStart,
                            enrichedItems = enrichedItems,
                        )
                    }
                } else {
                    null
                }

            val mergedVisibleItemsInfo =
                if (firstVisibleItem != null) {
                    val merged = visibleItemsInfo.toMutableList()
                    val existingIndex = merged.indexOfFirst { it.index == firstVisibleItem.index }
                    if (existingIndex >= 0) {
                        merged[existingIndex] = firstVisibleItem
                    } else {
                        merged.add(firstVisibleItem)
                    }
                    merged.sortedBy { it.index }
                } else {
                    visibleItemsInfo
                }

            val finalFirstVisibleItemScrollOffset = currentFirstItemScrollOffset

            val consumedScroll =
                if (
                    scrollToBeConsumed.fastRoundToInt().sign == scrollDelta.sign &&
                    abs(scrollToBeConsumed.fastRoundToInt()) >= abs(scrollDelta)
                ) {
                    scrollDelta.toFloat()
                } else {
                    scrollToBeConsumed
                }
            val unconsumedScroll = scrollToBeConsumed - consumedScroll
            val scrollBackAmount =
                if (isLookingAhead && scrollDelta > preScrollBackScrollDelta && unconsumedScroll <= 0f) {
                    (scrollDelta - preScrollBackScrollDelta + unconsumedScroll).coerceAtLeast(0f)
                } else {
                    0f
                }

            // Items beyond the measured window are unknown, not empty: with progressive measurement
            // the viewport can reach the end of the measured content while more items remain.
            // Reporting false there makes AdaptiveGridState.onScroll drop forward drags, so no pass
            // ever measures the rest. Only the geometric answer is trusted once the window has
            // reached the end of the list (trailing zero-height anchors then do not count).
            val hasUnmeasuredItemsBeyondWindow = measuredEnd < itemsCount - 1
            val canScrollForward = if (itemsCount > 0) {
                val lastMeasuredIndex = currentPositions.indexOfLast { it.height > 0 }
                if (lastMeasuredIndex < 0) {
                    hasUnmeasuredItemsBeyondWindow
                } else {
                    val lastItem = currentPositions[lastMeasuredIndex]
                    val lastItemBottom =
                        lastItem.y +
                            normalizedItemSize(lastMeasuredIndex, lastItem, measuredHeights)
                    lastItemBottom > viewportEnd || hasUnmeasuredItemsBeyondWindow
                }
            } else {
                false
            }

            // Zero-height anchors (secondary fetch, pagination) never intersect the viewport but must
            // be reported in visibleItemsInfo so Rex can trigger fetches. In-window anchors are
            // included within the progressive measure window; trailing anchors when the window
            // reaches the list end.
            val finalVisibleItemsInfo =
                collapsedZeroHeightVisibleIndices(
                    lastGeometricVisibleIndex = mergedVisibleItemsInfo.lastOrNull()?.index ?: -1,
                    measureWindowEnd = visibleRange.last,
                    itemsCount = itemsCount,
                    measureWindowReachedEnd = visibleRange.last >= itemsCount - 1,
                    effectiveHeightAt = { index ->
                        val position = currentPositions.getOrNull(index)
                        if (position == null) Int.MAX_VALUE
                        else normalizedItemSize(index, position, measuredHeights)
                    },
                ).let { collapsedIndices ->
                    if (collapsedIndices.isEmpty()) {
                        mergedVisibleItemsInfo
                    } else {
                        val augmented = mergedVisibleItemsInfo.toMutableList()
                        for (index in collapsedIndices) {
                            val position = currentPositions.getOrNull(index) ?: continue
                            augmented.add(
                                buildAdaptiveGridItemInfo(
                                    index = index,
                                    position = position,
                                    normalizedSize = 0,
                                    viewportStart = viewportStart,
                                    enrichedItems = enrichedItems,
                                )
                            )
                        }
                        augmented
                    }
                }

            val measureResult = layout(constraints.maxWidth, constraints.maxHeight) {
                LazySimplePlacementScope(
                    allPlaceables = placeables,
                    positions = currentPositions,
                    parentLayoutDirection = LayoutDirection.Ltr,
                    parentWidth = constraints.maxWidth,
                    visibleRange = (firstVisibleItem?.index ?: 0)..(mergedVisibleItemsInfo.lastOrNull()?.index ?: 0),
                    scrollOffset = finalFirstVisibleItemScrollOffset,
                    parentHeight = constraints.maxHeight
                ).placeChildren()
            }

            val adaptiveGridMeasureResult = AdaptiveGridMeasureResult(
                firstVisibleItem = firstVisibleItem,
                firstVisibleItemScrollOffset = finalFirstVisibleItemScrollOffset,
                canScrollForward = canScrollForward,
                consumedScroll = consumedScroll,
                measureResult = measureResult,
                scrollBackAmount = scrollBackAmount,
                remeasureNeeded = heightChanges.isNotEmpty(),
                coroutineScope = coroutineScope,
                density = density,
                childConstraints = Constraints.fixedWidth(constraints.maxWidth),
                visibleItemsInfo = finalVisibleItemsInfo,
                viewportStartOffset = viewportStart,
                viewportEndOffset = viewportEnd,
                totalItemsCount = itemsCount,
                viewportSize = constraints.maxHeight,
                orientation = Orientation.Vertical
            )
            state.applyMeasureResult(adaptiveGridMeasureResult, isLookingAhead)

            measureResult
        }
    }

    CompositionLocalProvider(
        LocalAdaptiveGridMeasureInvalidator provides state.measurementScopeInvalidator,
    ) {
        LazyLayout(
            itemProvider = { lazyLayoutItemProvider },
            measurePolicy = measurePolicy,
            prefetchState = state.prefetchState,
            modifier = modifier
                .clipToBounds()
                .then(state.remeasurementModifier)
                .then(state.awaitLayoutModifier)
                .scrollableArea(
                    state = state,
                    orientation = Orientation.Vertical,
                    enabled = scrollEnable,
                    reverseScrolling = reverseDirection,
                    interactionSource = state.internalInteractionSource,
                ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            widthCache.clear()
            positionCache.clear()
            measuredHeights.clear()
            positionsState.value = emptyList()
            state.layoutProvider = null

            clearMeasurementCaches()
        }
    }
}

/** Uses the latest measured height when layout position height is not yet available. */
private fun normalizedItemSize(
    index: Int,
    position: ItemPosition,
    measuredHeights: Map<Int, Int>,
): Int = maxOf(position.height, measuredHeights[index] ?: 0)

private fun <T> buildAdaptiveGridItemInfo(
    index: Int,
    position: ItemPosition,
    normalizedSize: Int,
    viewportStart: Int,
    enrichedItems: List<EnrichedGridItem<T>>,
): AdaptiveGridItemInfo {
    val normalizedPosition =
        if (normalizedSize != position.height) {
            position.copy(height = normalizedSize)
        } else {
            position
        }
    return AdaptiveGridItemInfo(
        index = index,
        key = enrichedItems.getOrNull(index)?.key ?: index,
        offset = normalizedPosition.y - viewportStart,
        size = normalizedSize,
        position = normalizedPosition,
        layoutType = enrichedItems.getOrNull(index)?.config?.type ?: GridItemType.Staggered,
    )
}

/**
 * Returns a conservative index range to measure around the current scroll position.
 *
 * Measurement expands progressively: at most one item beyond [lastMeasuredIndex] is composed
 * per pass (plus [bufferSize]). This keeps the grid compact while async blocks (e.g. experience
 * services) load, instead of eagerly measuring far ahead and reserving white space for
 * placeholders. The window always reaches [lastMeasuredIndex] + 1 + [bufferSize], so long feeds
 * are measured to the end one step per pass.
 */
internal fun calculateVisibleRangeFromRelativePosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    itemCount: Int,
    bufferSize: Int,
    viewportHeight: Int,
    lastMeasuredIndex: Int = -1,
): IntRange {
    if (itemCount == 0) return IntRange.EMPTY

    val start = maxOf(0, firstVisibleItemIndex - bufferSize)

    val estimatedItemsForViewport = (viewportHeight / 100).coerceAtLeast(20)
    val forwardBuffer = maxOf(
        bufferSize * 10,
        estimatedItemsForViewport * 3,
    )
    val scrollBasedEnd = firstVisibleItemIndex + forwardBuffer

    val end =
        if (lastMeasuredIndex < 0) {
            // Nothing measured yet — probe a small initial window only.
            minOf(itemCount - 1, maxOf(bufferSize, minOf(10, scrollBasedEnd)))
        } else {
            // Extend at most one item past the last measured item (plus bufferSize) per pass until
            // it reports height. firstVisibleItemIndex is only the scroll anchor (it stays 0 while the
            // scroll offset grows), so a window derived from it ([scrollBasedEnd]) must never cap the
            // window below the measured content: it stopped measurement at ~forwardBuffer items and
            // left every item after that (the end blocks of long feeds) unmeasured.
            minOf(
                itemCount - 1,
                maxOf(lastMeasuredIndex, lastMeasuredIndex + 1 + bufferSize),
            )
        }

    return start..end
}

/** Upper bound on extra items probed past a zero-height run within a single measure pass. */
internal const val ZERO_HEIGHT_RUN_MAX_LOOKAHEAD = 60

/**
 * Progressive measurement ([calculateVisibleRangeFromRelativePosition]) only reaches a few items
 * past the last item that reported a height. When every item in that window is zero-height (e.g.
 * pagination / secondary-fetch anchors, or blocks that render nothing) no height changes, so no
 * remeasure is requested and the window never moves: the real content below the run is never
 * composed and the grid cannot scroll to it.
 *
 * Returns the next range of indices to probe in the same measure pass, or null when the measured
 * window does not end in a zero-height run (or the list end / [ZERO_HEIGHT_RUN_MAX_LOOKAHEAD] was
 * reached). Zero-height items are transparent: probing continues past them, one
 * `bufferSize + 1` step at a time, until something reports a height.
 *
 * @param itemHeights measured height per item index so far (0 = collapsed or not measured).
 * @param measuredEnd last index composed and measured so far in this pass.
 * @param extraItemsProbed items already probed by earlier calls for this pass.
 */
internal fun nextZeroRunProbeRange(
    itemHeights: List<Int>,
    measuredEnd: Int,
    bufferSize: Int,
    extraItemsProbed: Int,
): IntRange? {
    val lastIndex = itemHeights.size - 1
    if (measuredEnd < 0 || measuredEnd >= lastIndex) return null
    if (extraItemsProbed >= ZERO_HEIGHT_RUN_MAX_LOOKAHEAD) return null

    val step = bufferSize + 1
    val tailStart = maxOf(0, measuredEnd - bufferSize)
    for (index in tailStart..measuredEnd) {
        if (itemHeights[index] > 0) return null
    }

    val start = measuredEnd + 1
    val end = minOf(
        lastIndex,
        measuredEnd + step,
        start + (ZERO_HEIGHT_RUN_MAX_LOOKAHEAD - extraItemsProbed) - 1,
    )
    return start..end
}

@OptIn(ExperimentalFoundationApi::class)
/** Measures items in the visible window (plus buffer) and returns placeables and heights. */
internal fun <T> measureGridItems(
    layoutProvider: GridLayoutProvider<T>,
    enrichedItems: List<EnrichedGridItem<T>>,
    visibleRange: IntRange,
    measuredHeights: MutableMap<Int, Int>,
    constraints: Constraints,
    measurer: (Int, Constraints) -> List<Placeable>,
    bufferSize: Int,
    getCachedWidth: (GridItemConfig, Int, Int) -> Int,
): Triple<List<Placeable?>, List<Int>, List<Pair<Int, Int>>> {

    val itemCount = layoutProvider.itemCount
    val placeables = MutableList<Placeable?>(itemCount) { null }
    val itemHeights = MutableList(itemCount) { 0 }
    val heightChanges = mutableListOf<Pair<Int, Int>>()

    if (measuredHeights.isNotEmpty()) {
        val heightEntries = measuredHeights.entries
        for ((idx, h) in heightEntries) {
            if (idx in 0 until itemCount && h > 0) {
                itemHeights[idx] = h
            }
        }
    }

    if (itemCount == 0) {
        return Triple(emptyList(), emptyList(), emptyList())
    }

    val maxIndex = itemCount - 1
    val safeStart = maxOf(0, visibleRange.first - bufferSize).coerceAtMost(maxIndex)
    val safeEnd = minOf(maxIndex, visibleRange.last + bufferSize)

    val constraintsCache = mutableMapOf<Pair<GridItemConfig, Int>, Constraints>()
    
    for (index in safeStart..safeEnd) {
        val config = enrichedItems[index].config
        val groupColumns = enrichedItems[index].groupColumns
        
        val isVisible = index in visibleRange

        val itemWidth = getCachedWidth(config, groupColumns, constraints.maxWidth)

        val constraintKey = config to itemWidth
        val heightConstraint = constraintsCache.getOrPut(constraintKey) {
            Constraints.fixedWidth(itemWidth)
        }

        val cachedHeight = measuredHeights[index]
        val itemHeight = if (cachedHeight != null && cachedHeight > 0 && !isVisible) {
            cachedHeight
        } else {
            val placeable = measurer(index, heightConstraint).firstOrNull()
            if (isVisible && placeable != null) {
                placeables[index] = placeable
            }
            // A fresh measurement is authoritative. A block that now renders nothing (no nodes, or a
            // zero-height node while it loads again) collapses instead of keeping its previous height
            // as a blank gap; once it reports a height again it is shown in place.
            val newHeight = placeable?.height ?: 0
            val oldHeight = measuredHeights[index] ?: 0
            if (newHeight > 0) {
                measuredHeights[index] = newHeight
            } else {
                measuredHeights.remove(index)
            }
            if (newHeight != oldHeight) {
                heightChanges.add(index to (newHeight - oldHeight))
            }
            newHeight
        }

        itemHeights[index] = itemHeight
    }
    
    return Triple(placeables, itemHeights, heightChanges)
}

private fun <T> createLayoutProviderFromEnrichedItems(
    enrichedItems: List<EnrichedGridItem<T>>
): GridLayoutProvider<T> {
    return object : GridLayoutProvider<T> {
        override val items = enrichedItems.map { GridItem(it.item, it.config) }

        override fun getItemConfig(index: Int): GridItemConfig = enrichedItems[index].config

        override fun getItemKey(index: Int): String = enrichedItems[index].key.toString()

        override fun getContentType(index: Int): Any? = enrichedItems[index].contentType

        @Composable
        override fun ItemContent(item: T, index: Int, columnIndex: Int, columns: Int) {
            when (val group = enrichedItems[index].group) {
                is GridGroup<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (group as GridGroup<T>).content.invoke(item, index, columnIndex, columns)
                }
                is CustomGridGroup<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val customGroup = group as CustomGridGroup<T>
                    val groupItemIndex = enrichedItems[index].groupItemIndex
                    val customItem = customGroup.customItems[groupItemIndex]
                    customItem.content.invoke(item, index, columnIndex, columns)
                }
            }
        }
    }
}
