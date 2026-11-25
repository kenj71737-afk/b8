package com.dublive.live

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * v1.0.8 — spectral gate for the VAD speech START.
 *
 * The energy threshold alone cannot tell loud music from loud speech:
 * turn it up and syllables get clipped (choppy dub, the 20:37 report);
 * turn it down and background music opens utterances and gets
 * translated as words. This gate looks at the SHAPE of the spectrum
 * of each 20 ms frame (320 samples at 16 kHz, zero-padded to a
 * 512-point FFT) and only lets speech-like frames start an utterance:
 *
 *  - voice-band ratio: energy in 300–3400 Hz (bins 10..108 at
 *    31.25 Hz/bin) over total energy must be >= 0.55. Speech
 *    concentrates its energy in that band; music/noise spread wider.
 *  - spectral flatness: geometric mean / arithmetic mean of the power
 *    spectrum must be < 0.5. Speech is peaky (harmonics + formants);
 *    noise is flat (flatness near 1), tonal music sits in between.
 *
 * The gate is ONLY checked while starting an utterance. Once inside
 * one, only the low (hysteresis end) energy threshold applies, so a
 * sentence is never cut mid-way when music swells underneath the
 * dialogue. Runs only on frames already above the energy threshold,
 * so the FFT cost (~50/s, trivial) is paid only for loud frames.
 * A tonal solo instrument sitting inside the voice band can still
 * pass — accepted limitation; the Live instruction + SpeechFilter
 * remain the last line of defence.
 */
class SpectralGate {
    companion object {
        private const val FFT_SIZE = 512
        /** Voice band 300–3400 Hz -> bins 10..108 at 31.25 Hz/bin. */
        private const val BAND_LOW_BIN = 10
        private const val BAND_HIGH_BIN = 108
        private const val MIN_BAND_RATIO = 0.55
        private const val MAX_FLATNESS = 0.5
    }

    /** True when this 20 ms frame looks like speech, not music/noise. */
    fun isSpeechLike(frame: ShortArray): Boolean {
        if (frame.isEmpty()) return false
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val n = minOf(frame.size, FFT_SIZE)
        for (i in 0 until n) re[i] = frame[i] / 32768.0
        fft(re, im)
        // Power spectrum, bins 1..255 (skip DC; Nyquist at bin 256).
        var total = 0.0
        var band = 0.0
        var logSum = 0.0
        var count = 0
        for (k in 1 until FFT_SIZE / 2) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12
            total += p
            if (k in BAND_LOW_BIN..BAND_HIGH_BIN) band += p
            logSum += ln(p)
            count++
        }
        if (total <= 0.0 || count == 0) return false
        val ratio = band / total
        val arith = total / count
        val geo = exp(logSum / count)
        val flatness = geo / arith
        return ratio >= MIN_BAND_RATIO && flatness < MAX_FLATNESS
    }

    /** Iterative radix-2 Cooley-Tukey FFT, in place. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curWr = 1.0
                var curWi = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curWr - im[i + k + len / 2] * curWi
                    val vIm = re[i + k + len / 2] * curWi + im[i + k + len / 2] * curWr
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
