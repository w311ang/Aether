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

    private fun position(index: Int, count: Int, anchorIndex: Int, anchorY: Float) =
        anchoredTimelinePosition(index, count, anchorIndex, anchorY, top = 0f, bottom = 100f, naturalStep = 10f)

    private fun indexAt(y: Float, count: Int, anchorIndex: Int, anchorY: Float) =
        anchoredTimelineIndexForPosition(y, count, anchorIndex, anchorY, top = 0f, bottom = 100f, naturalStep = 10f)

    @Test
    fun expandedRailPutsCurrentTurnUnderThePress() {
        assertEquals(37f, position(index = 3, count = 5, anchorIndex = 3, anchorY = 37f))
        assertEquals(3, indexAt(y = 37f, count = 5, anchorIndex = 3, anchorY = 37f))
        assertEquals(80f, position(index = 0, count = 40, anchorIndex = 0, anchorY = 80f))
    }

    @Test
    fun expandedRailKeepsNaturalSpacingWhenTurnsFit() {
        assertEquals(30f, position(index = 1, count = 5, anchorIndex = 3, anchorY = 50f))
        assertEquals(60f, position(index = 4, count = 5, anchorIndex = 3, anchorY = 50f))
        assertEquals(1, indexAt(y = 31f, count = 5, anchorIndex = 3, anchorY = 50f))
        assertEquals(4, indexAt(y = 62f, count = 5, anchorIndex = 3, anchorY = 50f))
    }

    @Test
    fun expandedRailCompressesLongSidesSoEveryTurnStaysReachable() {
        // A press at 50 leaves 50px for the 20 turns above and 50px for the 9 turns below.
        assertEquals(0f, position(index = 0, count = 30, anchorIndex = 20, anchorY = 50f))
        assertEquals(100f, position(index = 29, count = 30, anchorIndex = 20, anchorY = 50f), 0.001f)
        assertEquals(0, indexAt(y = 0f, count = 30, anchorIndex = 20, anchorY = 50f))
        assertEquals(29, indexAt(y = 100f, count = 30, anchorIndex = 20, anchorY = 50f))
        assertEquals(10, indexAt(y = 25f, count = 30, anchorIndex = 20, anchorY = 50f))
    }
}
