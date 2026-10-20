package io.github.elgrande73.voicememo.audio

/**
 * Re-chunks PCM audio delivered in arbitrary buffer sizes (AudioRecord reads, file buffers) into fixed-size frames,
 * so the detection pipeline always sees the same frame boundaries regardless of how the audio arrived. Partial reads
 * are never padded with stale samples.
 */
class PcmFrameAssembler(private val frameSize: Int = DEFAULT_FRAME_SIZE) {

    private val pending = ShortArray(frameSize)
    private var filled = 0

    /** Appends the first [length] samples of [data] and emits every completed frame (as a new array) in order. */
    fun push(data: ShortArray, length: Int = data.size, onFrame: (ShortArray) -> Unit) {
        require(length in 0..data.size) { "length $length outside 0..${data.size}" }
        var offset = 0
        while (offset < length) {
            val count = minOf(frameSize - filled, length - offset)
            System.arraycopy(data, offset, pending, filled, count)
            filled += count
            offset += count
            if (filled == frameSize) {
                onFrame(pending.copyOf())
                filled = 0
            }
        }
    }

    /** Discards an incomplete frame (e.g. when the capture stream restarts). */
    fun reset() {
        filled = 0
    }

    companion object {
        /** 50 ms at 16 kHz, the detection pipeline's frame size. */
        const val DEFAULT_FRAME_SIZE = 800

        /** Splits a complete recording into frames; a trailing partial frame is dropped. */
        fun frames(samples: ShortArray, frameSize: Int = DEFAULT_FRAME_SIZE): List<ShortArray> {
            val out = ArrayList<ShortArray>(samples.size / frameSize)
            PcmFrameAssembler(frameSize).push(samples) { out += it }
            return out
        }
    }
}
