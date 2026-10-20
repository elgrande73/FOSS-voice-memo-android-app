package io.github.elgrande73.voicememo.detection

import kotlin.math.cos
import kotlin.math.sin

/**
 * Fast Fourier Transform implementation for spectral feature extraction and audio energy analysis.
 */
class FFT(private val n: Int) {

    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, received: $n" }
        for (i in 0 until n / 2) {
            cosTable[i] = cos(-2.0 * Math.PI * i / n)
            sinTable[i] = sin(-2.0 * Math.PI * i / n)
        }
    }

    /**
     * Computes the in-place Radix-2 Cooley-Tukey FFT.
     */
    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var l = 2
        while (l <= n) {
            val halfL = l shr 1
            val step = n / l
            for (i in 0 until n step l) {
                var tableIndex = 0
                for (k in 0 until halfL) {
                    val c = cosTable[tableIndex]
                    val s = sinTable[tableIndex]
                    val pos = i + k + halfL
                    val tr = c * real[pos] - s * imag[pos]
                    val ti = s * real[pos] + c * imag[pos]
                    real[pos] = real[i + k] - tr
                    imag[pos] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti
                    tableIndex += step
                }
            }
            l = l shl 1
        }
    }

    /**
     * Computes the power spectrum magnitude for the given real input frame.
     */
    fun computePowerSpectrum(input: DoubleArray): DoubleArray {
        val real = input.copyOf(n)
        val imag = DoubleArray(n)
        transform(real, imag)

        val power = DoubleArray(n / 2 + 1)
        for (i in 0..n / 2) {
            power[i] = real[i] * real[i] + imag[i] * imag[i]
        }
        return power
    }
}
