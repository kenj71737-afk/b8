package com.alad.app.core.util

/**
 * FIX21: decides whether a whole turn of speech is only non-verbal noise —
 * pain cries, groans, gasps, laughter, fillers — with no meaningful words.
 * Used as a safety net when a turn ends: if the source text or the
 * translated text is meaningless, the queued audio for that turn is dropped
 * and the subtitle is cleared at once instead of lingering.
 *
 * Deliberately strict: anything containing even one real word is
 * meaningful ("á, đau quá!" is meaningful — "đau quá" are real words).
 * Affirmations are real words and are NOT filtered ("ừ", "ờ", "dạ",
 * "yeah", "uh-huh", "嗯"...). The system instruction given to Gemini is
 * the main filter; this only catches the cases where the model vocalized
 * a groan anyway.
 */
object UtteranceFilter {

    // Single tokens that are pure interjections / fillers (lowercase,
    // after collapsing repeated characters, so "ááá" is checked as "á").
    private val interjections = setOf(
        // Vietnamese pain cries / gasps / fillers
        "á", "à", "ả", "ã", "ạ", "â", "a",
        "e", "è", "é", "ê",
        "o", "ô", "ồ", "ố", "ơ", "ớ",
        "u", "ư", "ừm", "hự", "hừ", "hừm",
        "ui", "ối", "ôi", "ây", "au", "ái",
        // English
        "ah", "aah", "oh", "ooh", "uh", "um", "umm", "hmm", "hm",
        "eh", "er", "ow", "oww", "ouch", "ugh", "argh", "aw", "ew",
        // Korean pain cry (아야 = "ouch")
        "아야"
    )

    // Characters that are interjections on their own (CJK / kana / hangul).
    // A token made ONLY of these is noise, e.g. "啊哦" or "あっ".
    private val interjectionChars = setOf(
        '啊', '哎', '唉', '哦', '呃', '哼', '呜', '呀', '哟', '哈', '呵',
        'あ', 'う', 'え', 'お', 'っ',
        '아', '어', '으', '앗', '흠'
    )

    // Whole-utterance phrases that are pure cries (normalized, single spaces).
    private val interjectionPhrases = setOf(
        "ui da", "ây da", "ối giời ơi", "trời ơi", "ối giời"
    )

    // Laughter: "haha", "hehe", "hihihi", "hahahehe"... (single "ha" is
    // ambiguous, so it needs at least two syllables to count as laughter).
    private val laughterRegex = Regex("^(ha|he|hi|ho|hu){2,}$")

    fun isMeaningless(text: String): Boolean {
        if (text.isBlank()) return false // nothing said: nothing to filter
        // Lowercase, drop everything that is not a letter, collapse spaces.
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return true // only punctuation, e.g. "..."
        if (normalized in interjectionPhrases) return true
        // Every whitespace-separated token must be a pure interjection.
        return normalized.split(" ").all { token ->
            val collapsed = collapseRepeats(token)
            collapsed in interjections ||
                collapsed.matches(laughterRegex) ||
                (collapsed.isNotEmpty() && collapsed.all { it in interjectionChars })
        }
    }

    /** "ááá" -> "á", "aaa" -> "a", "啊啊啊" -> "啊": a drawn-out cry is still one cry. */
    private fun collapseRepeats(token: String): String {
        val out = StringBuilder()
        var prev = '\u0000'
        for (ch in token) {
            if (ch != prev) out.append(ch)
            prev = ch
        }
        return out.toString()
    }
}
