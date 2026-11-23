package com.alad.app.core.audio

/**
 * FIX29 (cách B) — "bỏ âm tiếng gốc, chỉ giữ âm nền": pure DSP for the
 * voice-filter mode. No Android dependencies, so it can be unit-tested on
 * plain kotlinc.
 *
 * The idea: in stereo film audio the dialogue is mixed centre-panned
 * (identical in the left and right channels) while music and effects have
 * stereo width. Subtracting one channel from the other removes the
 * centre-panned voice and keeps the background. It is the classic
 * karaoke / OOPS (Out Of Phase Stereo) trick — real-time, a few lines of
 * arithmetic, no ML model, no network.
 *
 * Honest limits (told to the user at delivery):
 * - It removes ~everything panned dead centre, including centred music
 *   elements (lead vocal in songs, bass, kick). The background that
 *   survives is the stereo part of the mix.
 * - It cannot remove voice from MONO audio (L == R makes the difference
 *   zero). [VocalRemover] detects that and passes the original through
 *   at reduced gain instead of playing silence.
 * - It does not make the translated voice any faster: the dub still lags
 *   the picture by Gemini's ~1-2 s translation time. It only stops the
 *   original dialogue from talking over the dub.
 */
object VoiceFilterDsp {

    /**
     * 48 kHz stereo -> 16 kHz mono: downmix (L+R)/2 plus a 3:1 box-filter
     * decimation (each output sample averages 3 stereo frames, a crude
     * anti-alias filter). Input/output are little-endian PCM16 byte arrays.
     * `length` is floored to a whole number of stereo frames, then to a
     * whole number of 3-frame groups; any trailing partial group is
     * dropped (at most 2 frames = 42 µs).
     */
    fun stereo48kToMono16k(stereo: ByteArray, length: Int): ByteArray {
        val frames = (length.coerceAtMost(stereo.size) / 4).coerceAtLeast(0)
        val outSamples = frames / 3
        val out = ByteArray(outSamples * 2)
        var o = 0
        var i = 0
        while (i + 2 < frames) {
            var sum = 0
            for (f in 0..2) {
                val b = (i + f) * 4
                val l = ((stereo[b + 1].toInt() shl 8) or (stereo[b].toInt() and 0xFF)).toShort().toInt()
                val r = ((stereo[b + 3].toInt() shl 8) or (stereo[b + 2].toInt() and 0xFF)).toShort().toInt()
                sum += l + r
            }
            val mono = (sum / 6).coerceIn(-32768, 32767)
            out[o++] = (mono and 0xFF).toByte()
            out[o++] = ((mono shr 8) and 0xFF).toByte()
            i += 3
        }
        return out
    }
}

/**
 * Centre-channel vocal remover for one stereo chunk at a time.
 *
 * Output is the (L-R) difference on the left channel and (R-L) on the
 * right, at 0.7 gain — the standard wide karaoke image. Centre-panned
 * content cancels; stereo content survives.
 *
 * Mono guard: when the difference energy of a chunk is tiny compared to
 * the input energy (the content is mono or near-mono), the remover flips
 * to passthrough (original at 0.6 gain) instead of outputting silence.
 * Hysteresis (enter below 0.03, leave above 0.08) keeps the mode from
 * flapping, and a 5 ms crossfade on every flip keeps it from clicking.
 */
class VocalRemover {

    companion object {
        private const val DIFF_GAIN = 0.7f
        private const val PASSTHROUGH_GAIN = 0.6f
        private const val ENTER_PASSTHROUGH = 0.03f
        private const val LEAVE_PASSTHROUGH = 0.08f
        private const val RAMP_FRAMES = 240 // 5 ms at 48 kHz
    }

    /** True while the input is (near-)mono and the original is being passed
     * through instead of filtered. The service reports this to the user. */
    var isPassthrough: Boolean = false
        private set

    private var rampLeft = 0
    private var rampFromFilter = false

    /**
     * Filter one chunk of interleaved stereo PCM. `input` and `output`
     * must be equal-length ShortArrays with an even size.
     * Returns true when the passthrough state changed on this call.
     */
    fun process(input: ShortArray, output: ShortArray): Boolean {
        require(input.size == output.size && input.size % 2 == 0) {
            "VocalRemover needs equal-length even ShortArrays"
        }
        val frames = input.size / 2

        // Energy ratio for this chunk: |L-R| vs total input energy.
        var sumDiff = 0L
        var sumIn = 0L
        for (i in 0 until frames) {
            val l = input[i * 2].toInt()
            val r = input[i * 2 + 1].toInt()
            sumDiff += kotlin.math.abs(l - r)
            sumIn += kotlin.math.abs(l) + kotlin.math.abs(r)
        }
        // Silence keeps the current mode (no decision from no signal).
        var changed = false
        if (sumIn > 0L) {
            val ratio = sumDiff.toFloat() / sumIn.toFloat()
            if (!isPassthrough && ratio < ENTER_PASSTHROUGH) {
                isPassthrough = true
                changed = true
                rampLeft = RAMP_FRAMES
                rampFromFilter = true
            } else if (isPassthrough && ratio > LEAVE_PASSTHROUGH) {
                isPassthrough = false
                changed = true
                rampLeft = RAMP_FRAMES
                rampFromFilter = false
            }
        }

        for (i in 0 until frames) {
            val l = input[i * 2].toInt()
            val r = input[i * 2 + 1].toInt()
            val diff = l - r
            var outL: Int
            var outR: Int
            if (isPassthrough) {
                outL = (l * PASSTHROUGH_GAIN).toInt().coerceIn(-32768, 32767)
                outR = (r * PASSTHROUGH_GAIN).toInt().coerceIn(-32768, 32767)
            } else {
                outL = (diff * DIFF_GAIN).toInt().coerceIn(-32768, 32767)
                outR = (-diff * DIFF_GAIN).toInt().coerceIn(-32768, 32767)
            }
            if (rampLeft > 0) {
                // Crossfade from the OLD mode's output to the NEW mode's
                // output so a mode flip never clicks.
                val t = 1f - rampLeft.toFloat() / RAMP_FRAMES
                val oldL: Int
                val oldR: Int
                if (rampFromFilter) {
                    oldL = (diff * DIFF_GAIN).toInt().coerceIn(-32768, 32767)
                    oldR = (-diff * DIFF_GAIN).toInt().coerceIn(-32768, 32767)
                } else {
                    oldL = (l * PASSTHROUGH_GAIN).toInt().coerceIn(-32768, 32767)
                    oldR = (r * PASSTHROUGH_GAIN).toInt().coerceIn(-32768, 32767)
                }
                outL = (oldL + (outL - oldL) * t).toInt()
                outR = (oldR + (outR - oldR) * t).toInt()
                rampLeft--
            }
            output[i * 2] = outL.toShort()
            output[i * 2 + 1] = outR.toShort()
        }
        return changed
    }
}
