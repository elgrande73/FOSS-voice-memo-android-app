package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.ui.PlayingMemoScroll
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayingMemoScrollTest {

    @Test
    fun aFullyVisibleCardNeverMoves() {
        assertEquals(0, PlayingMemoScroll.delta(itemTop = 0, itemSize = 300, viewportStart = 0, viewportEnd = 800, margin = 20))
        assertEquals(0, PlayingMemoScroll.delta(itemTop = 500, itemSize = 300, viewportStart = 0, viewportEnd = 800, margin = 20))
    }

    @Test
    fun aCardCutOffAtTheBottomScrollsUpJustEnough() {
        // Phone (800 px list) and tablet (2000 px list): only the hidden part plus the margin is scrolled
        assertEquals(170, PlayingMemoScroll.delta(650, 300, 0, 800, 20))
        assertEquals(170, PlayingMemoScroll.delta(1850, 300, 0, 2000, 20))
    }

    @Test
    fun aCardCutOffAtTheTopScrollsDown() {
        assertEquals(-120, PlayingMemoScroll.delta(-100, 300, 0, 800, 20))
    }

    @Test
    fun aCardTallerThanTheListShowsItsBottomWithThePlayButton() {
        // Small landscape screen: 300 px list, 500 px card (long transcript)
        val delta = PlayingMemoScroll.delta(100, 500, 0, 300, 20)
        assertEquals(100 + 500 - 280, delta)
        assertEquals(300 - 20, 100 + 500 - delta) // card bottom now 20 px above the list's bottom edge
    }

    @Test
    fun contentPaddingOffsetsAreRespected() {
        assertEquals(0, PlayingMemoScroll.delta(-40, 200, -48, 752, 8))
        assertEquals(-16, PlayingMemoScroll.delta(-56, 200, -48, 752, 8))
    }
}
