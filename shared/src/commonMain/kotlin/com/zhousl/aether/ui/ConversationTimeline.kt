package com.zhousl.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhousl.aether.platform.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class ConversationTimelineEntry(
    val key: String,
    val userPreview: String,
    val assistantPreview: String,
)

private val TimelineMotionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private val TimelineRailWidth = 58.dp
private val TimelineBarHeight = 3.dp
private val TimelineBarGap = 8.dp
private val TimelineVerticalPadding = 10.dp
private val TimelineCompactTouchHeight = 56.dp
private val TimelineMovementThreshold = 18.dp
private val TimelineExitTolerance = 28.dp

// Expanded turns keep a fixed pitch and never compress; a ladder taller than the window scrolls
// when the finger rests near either edge, faster the closer it gets.
private val TimelineMaxWindowHeight = 420.dp
private val TimelineEdgeScrollZone = 40.dp
private val TimelineEdgeScrollMaxSpeed = 600.dp // per second
private val TimelineFadeHeight = 24.dp

@Composable
fun ConversationTimeline(
    entries: List<ConversationTimelineEntry>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit,
) {
    if (entries.size < 2) return

    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    val safeCurrentIndex = currentIndex.coerceIn(entries.indices)
    var navigating by remember(entries) { mutableStateOf(false) }
    var selectedIndex by remember(entries) { mutableIntStateOf(safeCurrentIndex) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var finalPointer by remember { mutableStateOf<Offset?>(null) }
    var railSize by remember { mutableStateOf(IntSize.Zero) }
    var previewHeightPx by remember { mutableIntStateOf(0) }
    // Expanded layout: turn i sits at firstTurnY + i * pitch (root coordinates).
    var firstTurnY by remember { mutableFloatStateOf(0f) }
    var pressedIndex by remember(entries) { mutableIntStateOf(safeCurrentIndex) }
    var pointerYInRoot by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(safeCurrentIndex, navigating) {
        if (!navigating) selectedIndex = safeCurrentIndex
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Expanded, the rail spans the whole area so every turn can be placed relative to the press.
        val targetRailHeight = if (navigating) maxHeight else TimelineCompactTouchHeight
        val animatedRailHeight by animateDpAsState(
            targetValue = targetRailHeight,
            animationSpec = tween(
                durationMillis = if (reduceMotion) 0 else 280,
                easing = TimelineMotionEasing,
            ),
            label = "conversation_timeline_height",
        )
        val expansionFraction by animateFloatAsState(
            targetValue = if (navigating) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (reduceMotion) 0 else 260,
                easing = TimelineMotionEasing,
            ),
            label = "conversation_timeline_expansion",
        )
        val selectionPosition = remember(entries) { Animatable(safeCurrentIndex.toFloat()) }
        val compactWindowPosition = remember(entries) {
            Animatable(compactTimelineWindowStart(entries.size, safeCurrentIndex).toFloat())
        }
        val selectionTarget = if (navigating) selectedIndex else safeCurrentIndex
        LaunchedEffect(selectionTarget, reduceMotion) {
            if (reduceMotion) {
                selectionPosition.snapTo(selectionTarget.toFloat())
            } else {
                selectionPosition.animateTo(
                    targetValue = selectionTarget.toFloat(),
                    animationSpec = tween(durationMillis = 110, easing = TimelineMotionEasing),
                )
            }
        }
        val compactWindowTarget = compactTimelineWindowStart(entries.size, safeCurrentIndex)
        LaunchedEffect(compactWindowTarget, reduceMotion) {
            if (reduceMotion) {
                compactWindowPosition.snapTo(compactWindowTarget.toFloat())
            } else {
                compactWindowPosition.animateTo(
                    targetValue = compactWindowTarget.toFloat(),
                    animationSpec = tween(durationMillis = 190, easing = TimelineMotionEasing),
                )
            }
        }

        val movementThresholdPx = with(density) { TimelineMovementThreshold.toPx() }
        val exitTolerancePx = with(density) { TimelineExitTolerance.toPx() }
        val verticalPaddingPx = with(density) { TimelineVerticalPadding.toPx() }
        val barHeightPx = with(density) { TimelineBarHeight.toPx() }
        val rootHeightPx = constraints.maxHeight.toFloat()
        val pitchPx = with(density) { (TimelineBarHeight + TimelineBarGap).toPx() }
        val windowHeightPx = minOf(
            rootHeightPx - verticalPaddingPx * 2,
            with(density) { TimelineMaxWindowHeight.toPx() },
        ).coerceAtLeast(0f)
        val windowTopPx = (rootHeightPx - windowHeightPx) / 2f
        val windowBottomPx = windowTopPx + windowHeightPx
        val firstTurnRange = timelineFirstTurnRange(
            count = entries.size,
            pitch = pitchPx,
            top = windowTopPx + verticalPaddingPx,
            bottom = windowBottomPx - verticalPaddingPx,
        )
        val edgeZonePx = with(density) { TimelineEdgeScrollZone.toPx() }
        val edgeSpeedPx = with(density) { TimelineEdgeScrollMaxSpeed.toPx() }
        val fadeHeightPx = with(density) { TimelineFadeHeight.toPx() }

        fun expandedCenterInRoot(index: Int): Float = firstTurnY + index * pitchPx

        fun indexAtRootY(positionY: Float): Int =
            timelineIndexAt(positionY, firstTurnY, pitchPx, entries.size)

        // Keeps scrolling while the finger rests near an edge, not only when it moves.
        LaunchedEffect(navigating, rootHeightPx, entries.size) {
            if (!navigating) return@LaunchedEffect
            var previousNanos = withFrameNanos { it }
            while (true) {
                val nowNanos = withFrameNanos { it }
                val seconds = (nowNanos - previousNanos) / 1_000_000_000f
                previousNanos = nowNanos
                val speed = timelineEdgeScrollSpeed(
                    positionY = pointerYInRoot,
                    top = windowTopPx,
                    bottom = windowBottomPx,
                    edgeZone = edgeZonePx,
                    maxSpeed = edgeSpeedPx,
                )
                if (speed == 0f) continue
                val next = (firstTurnY - speed * seconds).coerceIn(firstTurnRange)
                if (next != firstTurnY) {
                    firstTurnY = next
                    selectedIndex = indexAtRootY(pointerYInRoot)
                }
            }
        }

        // Centered vertically, so the rail grows evenly up and down from the pressed spot.
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(TimelineRailWidth)
                .height(animatedRailHeight)
                .onSizeChanged { railSize = it }
                .pointerInput(entries.size, safeCurrentIndex, constraints.maxHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var pointerInRoot = Offset(
                            x = down.position.x,
                            y = down.position.y + (constraints.maxHeight - railSize.height) / 2f,
                        )
                        // The current turn expands to where the finger pressed (shifted only as
                        // far as needed to keep the ladder's ends inside the window).
                        pressedIndex = safeCurrentIndex
                        pointerYInRoot = pointerInRoot.y
                        firstTurnY = (pointerInRoot.y - safeCurrentIndex * pitchPx).coerceIn(firstTurnRange)
                        navigating = true
                        selectedIndex = safeCurrentIndex
                        dragDistancePx = 0f
                        dragOffset = Offset.Zero
                        finalPointer = down.position

                        val pointerId = down.id
                        var cancelled = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            val nextPointerInRoot = Offset(
                                x = change.position.x,
                                y = change.position.y + (constraints.maxHeight - railSize.height) / 2f,
                            )
                            val dragAmount = nextPointerInRoot - pointerInRoot
                            pointerInRoot = nextPointerInRoot
                            dragOffset += dragAmount
                            dragDistancePx = maxOf(dragDistancePx, dragOffset.getDistance())
                            finalPointer = change.position
                            // The press starts on the current turn, so selection can follow the
                            // finger right away instead of waiting out the tap threshold.
                            pointerYInRoot = pointerInRoot.y
                            if (railSize.height > 0) {
                                selectedIndex = indexAtRootY(pointerInRoot.y)
                            }
                            pressed = change.pressed
                            change.consume()
                        }

                        val pointer = finalPointer
                        val stayedNearRail = pointer != null &&
                            pointer.x >= -exitTolerancePx &&
                            pointer.x <= railSize.width + exitTolerancePx &&
                            pointer.y >= -exitTolerancePx &&
                            pointer.y <= railSize.height + exitTolerancePx
                        val destination = selectedIndex
                        // A plain tap stays put; moving to another turn navigates even when the
                        // drag is shorter than the tap threshold (turns are only ~11dp apart).
                        val shouldNavigate = !cancelled &&
                            stayedNearRail &&
                            (destination != pressedIndex || dragDistancePx >= movementThresholdPx)
                        navigating = false
                        selectedIndex = safeCurrentIndex
                        if (shouldNavigate) latestOnNavigate(destination)
                    }
                },
        ) {
            val compactCount = entries.size.coerceAtMost(4)
            val compactBlockHeightPx = with(density) {
                (TimelineBarHeight * compactCount + TimelineBarGap * (compactCount - 1)).toPx()
            }
            // Keep the collapsed bars at the rail's center so they don't slide while it expands.
            val compactTop = (size.height - compactBlockHeightPx) / 2f
            val compactStep = barHeightPx + with(density) { TimelineBarGap.toPx() }
            val canvasTopInRoot = (rootHeightPx - size.height) / 2f
            val endX = size.width - with(density) { 11.dp.toPx() }
            // Fade an end only while more turns are hidden past it.
            val moreAbove = firstTurnY < firstTurnRange.endInclusive - 0.5f
            val moreBelow = firstTurnY > firstTurnRange.start + 0.5f

            entries.indices.forEach { index ->
                val collapsedSlot = index - compactWindowPosition.value
                val collapsedY = compactTop + collapsedSlot * compactStep + barHeightPx / 2f
                val expandedY = expandedCenterInRoot(index) - canvasTopInRoot
                val y = lerp(
                    Offset(0f, collapsedY),
                    Offset(0f, expandedY),
                    expansionFraction,
                ).y
                val distance = abs(index - selectionPosition.value)
                val compactWidth = compactTimelineBarWidth(distance)
                val navigationWidth = navigationTimelineBarWidth(distance)
                val widthPx = with(density) {
                    (compactWidth + (navigationWidth - compactWidth) * expansionFraction).dp.toPx()
                }
                val compactColor = compactTimelineBarColor(distance)
                val navigationColor = navigationTimelineBarColor(distance, isDarkTheme)
                val color = lerp(compactColor, navigationColor, expansionFraction)
                val collapsedAlpha = compactTimelineSlotAlpha(collapsedSlot, compactCount)
                val expandedAlpha = timelineWindowAlpha(
                    positionY = expandedY + canvasTopInRoot,
                    top = windowTopPx,
                    bottom = windowBottomPx,
                    fadeHeight = fadeHeightPx,
                    fadeTop = moreAbove,
                    fadeBottom = moreBelow,
                )
                val alpha = collapsedAlpha + (expandedAlpha - collapsedAlpha) * expansionFraction

                drawLine(
                    color = color.copy(alpha = color.alpha * alpha),
                    start = Offset(endX - widthPx, y),
                    end = Offset(endX, y),
                    strokeWidth = barHeightPx,
                    cap = StrokeCap.Round,
                )
            }
        }

        val previewEntry = entries[selectedIndex.coerceIn(entries.indices)]
        val selectedCenterInRoot = expandedCenterInRoot(selectedIndex.coerceIn(entries.indices))
        val previewTopPx = (selectedCenterInRoot - previewHeightPx / 2f)
            .coerceIn(0f, (rootHeightPx - previewHeightPx).coerceAtLeast(0f))
        val previewBackground = if (isDarkTheme) Color(0xFF2C2C2E) else Color(0xFFF0F0F2)
        val previewUserColor = if (isDarkTheme) Color.White else Color(0xFF1C1C1E)
        val previewAssistantColor = if (isDarkTheme) Color(0xFF9A9A9E) else Color(0xFF68686C)

        AnimatedVisibility(
            visible = navigating,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = TimelineRailWidth + 4.dp)
                .offset(y = with(density) { previewTopPx.toDp() }),
            enter = fadeIn(tween(if (reduceMotion) 0 else 150)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(if (reduceMotion) 0 else 190, easing = TimelineMotionEasing),
            ),
            exit = fadeOut(tween(if (reduceMotion) 0 else 110)) + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(if (reduceMotion) 0 else 130, easing = TimelineMotionEasing),
            ),
        ) {
            Column(
                modifier = Modifier
                    .onSizeChanged { previewHeightPx = it.height }
                    .widthIn(min = 180.dp, max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(previewBackground.copy(alpha = 0.98f))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(
                    text = previewEntry.userPreview.ifBlank { " " },
                    color = previewUserColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (previewEntry.assistantPreview.isNotBlank()) {
                    Text(
                        text = previewEntry.assistantPreview,
                        color = previewAssistantColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
    }
}

internal fun compactTimelineIndices(total: Int, currentIndex: Int): List<Int> {
    if (total <= 0) return emptyList()
    val visibleCount = total.coerceAtMost(4)
    val start = compactTimelineWindowStart(total, currentIndex)
    return (start until start + visibleCount).toList()
}

internal fun compactTimelineWindowStart(total: Int, currentIndex: Int): Int {
    if (total <= 0) return 0
    val visibleCount = total.coerceAtMost(4)
    val safeCurrent = currentIndex.coerceIn(0, total - 1)
    return (safeCurrent - 1).coerceIn(0, total - visibleCount)
}

private fun compactTimelineSlotAlpha(slot: Float, visibleCount: Int): Float {
    if (visibleCount <= 0) return 0f
    val lastSlot = visibleCount - 1f
    return when {
        slot < -1f || slot > lastSlot + 1f -> 0f
        slot < 0f -> slot + 1f
        slot <= lastSlot -> 1f
        else -> lastSlot + 1f - slot
    }.coerceIn(0f, 1f)
}

/**
 * Allowed positions of the first turn when turns sit [pitch] apart between [top] and [bottom]:
 * a ladder that fits stays entirely inside, a taller one may scroll until either end reaches
 * its edge.
 */
internal fun timelineFirstTurnRange(
    count: Int,
    pitch: Float,
    top: Float,
    bottom: Float,
): ClosedFloatingPointRange<Float> {
    val ladderHeight = (count - 1).coerceAtLeast(0) * pitch
    val lastAtBottom = bottom - ladderHeight
    return minOf(top, lastAtBottom)..maxOf(top, lastAtBottom)
}

/** The turn whose mark is nearest to [positionY]. */
internal fun timelineIndexAt(
    positionY: Float,
    firstTurnY: Float,
    pitch: Float,
    count: Int,
): Int {
    if (count <= 1 || pitch <= 0f) return 0
    return ((positionY - firstTurnY) / pitch).roundToInt().coerceIn(0, count - 1)
}

/**
 * Edge scrolling speed (px/s, negative = towards earlier turns) for a finger at [positionY]:
 * zero away from the edges, ramping to [maxSpeed] at the window edge and beyond.
 */
internal fun timelineEdgeScrollSpeed(
    positionY: Float,
    top: Float,
    bottom: Float,
    edgeZone: Float,
    maxSpeed: Float,
): Float {
    if (edgeZone <= 0f) return 0f
    return when {
        positionY < top + edgeZone -> -maxSpeed * ((top + edgeZone - positionY) / edgeZone).coerceIn(0f, 1f)
        positionY > bottom - edgeZone -> maxSpeed * ((positionY - (bottom - edgeZone)) / edgeZone).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Opacity of an expanded mark: hidden outside the window, faded near an end that can scroll. */
internal fun timelineWindowAlpha(
    positionY: Float,
    top: Float,
    bottom: Float,
    fadeHeight: Float,
    fadeTop: Boolean,
    fadeBottom: Boolean,
): Float {
    if (positionY < top || positionY > bottom) return 0f
    var alpha = 1f
    if (fadeTop && fadeHeight > 0f) alpha = minOf(alpha, (positionY - top) / fadeHeight)
    if (fadeBottom && fadeHeight > 0f) alpha = minOf(alpha, (bottom - positionY) / fadeHeight)
    return alpha.coerceIn(0f, 1f)
}

private fun compactTimelineBarWidth(distance: Float): Float = when {
    distance < 1f -> 31f - 8f * distance
    distance < 2f -> 23f - 5f * (distance - 1f)
    else -> (18f - 3f * (distance - 2f)).coerceAtLeast(12f)
}

private fun navigationTimelineBarWidth(distance: Float): Float = when {
    distance < 1f -> 36f - 11f * distance
    distance < 2f -> 25f - 9f * (distance - 1f)
    else -> 16f
}

private fun compactTimelineBarColor(distance: Float): Color {
    if (distance < 0.5f) return Color(0xFF777777)
    val alpha = when (floor(distance).toInt()) {
        0, 1 -> 0.52f
        2 -> 0.36f
        else -> 0.24f
    }
    return Color(0xFFB8B8B8).copy(alpha = alpha)
}

private fun navigationTimelineBarColor(distance: Float, isDarkTheme: Boolean): Color {
    val selectedAmount = (1f - distance).coerceIn(0f, 1f)
    val inactive = if (isDarkTheme) Color(0xFF464646) else Color(0xFFC5C5C7)
    val selected = if (isDarkTheme) Color.White else Color(0xFF1C1C1E)
    return lerp(inactive, selected, selectedAmount)
}
