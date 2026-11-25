package com.dublive.live

/**
 * Drops utterances that carry no meaning: bare cries, groans, laughter,
 * coughs, humming or music-like filler. When the model is unsure, the app
 * stays silent instead of inventing words.
 */
object SpeechFilter {
    private val noiseOnly = setOf(
        "a", "á", "à", "ạ", "ơ", "ờ", "ừ", "ừm", "ừm ừm", "hừ", "hừm",
        "ha", "haha", "ha ha", "he", "hehe", "hihi", "ồ", "ố", "ô",
        "ừ ừ", "a a", "á á", "hả", "hơ", "ừ hừ", "ừm ừm ừm"
    )
    private val laughLike = Regex("^(ha|he|hi|ho|haha|hehe|hihi|hoho|ha ha|he he)[ !.]*$", RegexOption.IGNORE_CASE)

    /** True when [text] is worth translating and speaking. */
    fun isMeaningful(text: String): Boolean {
        val clean = text.trim()
            .lowercase()
            .replace(Regex("[.,!?…~\-"'()\[\]]"), " ")
            .replace(Regex("\s+"), " ")
            .trim()
        if (clean.isEmpty()) return false
        if (clean in noiseOnly) return false
        if (laughLike.matches(clean)) return false
        // A single short interjection on its own is not a sentence.
        if (clean.length <= 1) return false
        // Must contain at least one real word character (letter / CJK).
        if (!clean.any { it.isLetter() }) return false
        return true
    }
}
