package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameAssemblerTest {

    @Test
    fun partialReadsAreJoinedWithoutPaddingOrStaleSamples() {
        val assembler = PcmFrameAssembler(frameSize = 4)
        val frames = mutableListOf<ShortArray>()
        val buffer = ShortArray(4)
        // A reused read buffer where only the first `length` samples are valid
        for ((values, length) in listOf(shortArrayOf(1, 2, 3, 99) to 3, shortArrayOf(4, 5, 99, 99) to 2, shortArrayOf(6, 7, 8, 9) to 4)) {
            values.copyInto(buffer)
            assembler.push(buffer, length) { frames += it }
        }
        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), frames[1])
    }

    @Test
    fun resetDropsAnIncompleteFrame() {
        val assembler = PcmFrameAssembler(frameSize = 4)
        val frames = mutableListOf<ShortArray>()
        assembler.push(shortArrayOf(1, 2)) { frames += it }
        assembler.reset()
        assembler.push(shortArrayOf(3, 4, 5, 6)) { frames += it }
        assertEquals(listOf(listOf<Short>(3, 4, 5, 6)), frames.map { it.toList() })
    }

    @Test
    fun emittedFramesAreIndependentCopies() {
        val frames = PcmFrameAssembler.frames(ShortArray(1700) { it.toShort() })
        assertEquals(2, frames.size) // trailing 100 samples dropped
        frames[0][0] = 42
        assertEquals(800.toShort(), frames[1][0])
    }
}
