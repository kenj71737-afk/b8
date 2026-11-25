package com.dublive.live

/**
 * Measures the real dub delay and adapts the video delay to it.
 *
 * LESSON FROM THE OLD APP (FIX43): delay stood still at 5s238ms because
 * samples were only taken at the start of a "speech burst" (audio arriving
 * after >= 1 s of silence). Fast Cantonese dialogue has no such gaps, so
 * there was never a burst start and never a sample.
 *
 * v1.0.8 — SLICE FIFO + PER-SENTENCE MEDIAN. Why the v1.0.7 FIFO pairing
 * still froze (user report 20:37 "độ trễ không cập nhật tự động theo
 * đoạn câu"): it waited for a NEW dubbed segment — a dub chunk arriving
 * >= 800 ms after the previous dub audio — before pairing one sample.
 * In continuous dialogue the dub voice also speaks continuously, so
 * there is never an 800 ms dub silence and the pairing event never
 * fires. Same structural bug as before, moved from the speech side to
 * the dub side. The fix removes the silence wait entirely:
 *
 *  - SLICES: the service pushes a speech-slice timestamp every 500 ms
 *    of in-speech audio (plus one at each speech start), and the dub
 *    side consumes one slice every 500 ms of dubbed audio played.
 *    Each pair gives a sample = dubTime - speechTime. Slices are
 *    capped at 40 (20 s); the oldest is dropped when full.
 *  - PER-SENTENCE MEDIAN (user request 20:40 "đo bằng câu của âm
 *    gốc"): samples are NOT applied the moment they are paired.
 *    They collect until a source-sentence boundary — VAD speech start
 *    (next sentence begins), VAD speech end, or the 4 s forced cut
 *    while speech runs on (segmentCutter, kept from v1.0.7) — and
 *    only then is the MEDIAN of that sentence's samples EMA-smoothed
 *    (alpha 0.5) into the delay and broadcast once (TYPE_HUD). So the
 *    "Trễ hình" number moves exactly ONCE per source sentence: stable
 *    per sentence (as the user asked), never frozen (v1.0.6), never
 *    jittering every 500 ms, never ballooning with utterance length
 *    (v1.0.4). A sentence with no samples keeps the old delay.
 *  - If a sample falls outside [500 ms, 20 s] the pairing has drifted
 *    (a slice was filtered or lost) — both queues are cleared so the
 *    NEXT sentence resyncs instead of pairing wrongly forever.
 *  - TTS (Groq path) keeps the old one-sample-per-read pairing: its
 *    start pops the oldest slice, then drops the rest of that
 *    sentence's slices so the next read pairs with fresh ones.
 *  - Manual "Trễ −1s / +1s" still applies immediately, and later
 *    automatic medians keep EMA-ing from the manual value.
 * All methods are @Synchronized: speech slices come from the VAD /
 * capture thread, dub slices from the Live socket thread, TTS starts
 * from the TTS listener, boundaries from the main handler.
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    initialDelayMs: Long,
    private val onDelayChanged: (Long) -> Unit
) {
    /** FIFO of speech-slice timestamps (ms), oldest first. */
    private val speechSlices = ArrayDeque<Long>()
    /** Slice-pair samples collected since the last sentence boundary. */
    private val pendingSamples = ArrayList<Long>()
    private var emaMs: Long = initialDelayMs.coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)

    val currentDelayMs: Long get() = emaMs

    /** One 500 ms slice of in-speech audio (service pushes one per
     * 500 ms of VAD audio, plus one at each speech start). */
    @Synchronized
    fun pushSpeechSlice(now: Long = System.currentTimeMillis()) {
        speechSlices.addLast(now)
        while (speechSlices.size > 40) speechSlices.removeFirst()
    }

    /** One 500 ms slice of dubbed (Live) audio played: pair it with
     * the oldest unpaired speech slice and COLLECT the sample for the
     * current sentence. No delay update happens here. */
    @Synchronized
    fun onDubSlice(now: Long = System.currentTimeMillis()) {
        pairSample(now)
    }

    /** First chunk of a dubbed segment (Live audio): same collection
     * as onDubSlice — one extra prompt sample at dub start. */
    @Synchronized
    fun noteDubSegmentStart(now: Long = System.currentTimeMillis()) {
        pairSample(now)
    }

    /** A TTS read started (Groq path): one sample per read, as before.
     * The leftover slices of this sentence are dropped so the next
     * read pairs with fresh slices instead of stale ones. */
    @Synchronized
    fun noteTtsSegmentStart(now: Long = System.currentTimeMillis()) {
        pairSample(now)
        speechSlices.clear()
    }

    /** A source sentence ended (next VAD speech start, VAD speech end,
     * or the 4 s forced cut): apply the MEDIAN of this sentence's
     * slice samples, once, via EMA. Empty -> keep the old delay. */
    @Synchronized
    fun noteSentenceBoundary() {
        if (pendingSamples.isEmpty()) return
        val sorted = pendingSamples.sorted()
        val median = sorted[sorted.size / 2]
        pendingSamples.clear()
        val clamped = median.coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
        emaMs = (0.5 * emaMs + 0.5 * clamped).toLong()
        onDelayChanged(emaMs)
    }

    /** Fresh pairing after a (re)connect: drop stale slices/samples. */
    @Synchronized
    fun clearSlices() {
        speechSlices.clear()
        pendingSamples.clear()
    }

    /** Manual nudge from the "Trễ −1s / +1s" buttons: shift the EMA by
     * the delta and push it out at once. Later automatic medians keep
     * EMA-ing from this manual value — they never pull it back. */
    @Synchronized
    fun adjustManual(deltaMs: Long) {
        emaMs = (emaMs + deltaMs).coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
        onDelayChanged(emaMs)
    }

    @Synchronized
    fun stop() {
        speechSlices.clear()
        pendingSamples.clear()
    }

    /** Pair one dub moment with the oldest unpaired speech slice. */
    private fun pairSample(now: Long) {
        val start = speechSlices.removeFirstOrNull() ?: return
        val sample = now - start
        if (sample in 500L..20_000L) {
            pendingSamples.add(sample)
            if (pendingSamples.size > 60) pendingSamples.removeAt(0)
        } else {
            // Pairing drifted (a slice was filtered/lost, or a stale
            // pre-connect marker survived): drop everything so the
            // NEXT sentence resyncs cleanly.
            speechSlices.clear()
            pendingSamples.clear()
        }
    }
}
