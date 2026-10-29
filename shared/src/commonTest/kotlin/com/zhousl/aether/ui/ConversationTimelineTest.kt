package com.zhousl.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTimelineTest {
    @Test
    fun compactWindowKeepsFiveTurnSequenceAnchoredLikeMinimap() {
        assertEquals(listOf(0, 1, 2, 3), compactTimelineIndices(total = 5, currentIndex = 0))
        assertEquals(listOf(0, 1, 2, 3), compactTimelineIndices(total = 5, currentIndex = 1))
        assertEquals(listOf(1, 2, 3, 4), compactTimelineIndices(total = 5, currentIndex = 2))
        assertEquals(listOf(1, 2, 3, 4), compactTimelineIndices(total = 5, currentIndex = 3))
        assertEquals(listOf(1, 2, 3, 4), compactTimelineIndices(total = 5, currentIndex = 4))
    }

    @Test
    fun compactWindowAdvancesOneSlotAtATimeForLongConversations() {
        assertEquals(8, compactTimelineWindowStart(total = 18, currentIndex = 9))
        assertEquals(9, compactTimelineWindowStart(total = 18, currentIndex = 10))
        assertEquals(10, compactTimelineWindowStart(total = 18, currentIndex = 11))
        assertEquals(14, compactTimelineWindowStart(total = 18, currentIndex = 17))
    }

    @Test
    fun shortLadderStaysInsideTheWindow() {
        // 5 turns 10px apart are 40px tall: the first turn may sit anywhere from 0 to 60.
        assertEquals(0f..60f, timelineFirstTurnRange(count = 5, pitch = 10f, top = 0f, bottom = 100f))
    }

    @Test
    fun tallLadderScrollsUntilEitherEndReachesTheWindowEdge() {
        // 30 turns are 290px tall in a 100px window: from the last turn at 100 to the first at 0.
        assertEquals(-190f..0f, timelineFirstTurnRange(count = 30, pitch = 10f, top = 0f, bottom = 100f))
    }

    @Test
    fun marksKeepAFixedPitch() {
        assertEquals(0, timelineIndexAt(positionY = 20f, firstTurnY = 20f, pitch = 10f, count = 30))
        assertEquals(1, timelineIndexAt(positionY = 31f, firstTurnY = 20f, pitch = 10f, count = 30))
        assertEquals(12, timelineIndexAt(positionY = 138f, firstTurnY = 20f, pitch = 10f, count = 30))
        assertEquals(29, timelineIndexAt(positionY = 9999f, firstTurnY = 20f, pitch = 10f, count = 30))
        assertEquals(0, timelineIndexAt(positionY = -9999f, firstTurnY = 20f, pitch = 10f, count = 30))
    }

    @Test
    fun edgeScrollingRampsUpTowardsTheEdges() {
        val speed = { y: Float -> timelineEdgeScrollSpeed(y, top = 0f, bottom = 400f, edgeZone = 40f, maxSpeed = 600f) }
        assertEquals(0f, speed(200f))
        assertEquals(-300f, speed(20f))
        assertEquals(-600f, speed(-50f))
        assertEquals(300f, speed(380f))
        assertEquals(600f, speed(450f))
    }

    @Test
    fun marksFadeOnlyAtEndsThatCanScroll() {
        val alpha = { y: Float, fadeTop: Boolean, fadeBottom: Boolean ->
            timelineWindowAlpha(y, top = 0f, bottom = 100f, fadeHeight = 20f, fadeTop = fadeTop, fadeBottom = fadeBottom)
        }
        assertEquals(0f, alpha(-1f, false, false))
        assertEquals(1f, alpha(5f, false, false))
        assertEquals(0.25f, alpha(5f, true, false))
        assertEquals(0.5f, alpha(90f, false, true))
        assertEquals(1f, alpha(50f, true, true))
    }
}
