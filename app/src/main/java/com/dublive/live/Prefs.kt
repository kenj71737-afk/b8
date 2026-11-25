package com.dublive.live

import android.content.Context
import android.content.SharedPreferences

/**
 * All user settings live in one SharedPreferences file on the device.
 * API keys are NEVER hard-coded anywhere in this project; they are only
 * typed by the user in Settings and stored locally.
 */
object Prefs {
    private const val FILE = "dublive_prefs"
    const val KEY_GEMINI_1 = "gemini_key_1"
    const val KEY_GEMINI_2 = "gemini_key_2"
    const val KEY_GROQ = "groq_key"
    const val KEY_ELEVENLABS = "elevenlabs_key" // placeholder for a future version
    const val KEY_SOURCE_LANG = "source_lang"
    const val KEY_WHOLE_SENTENCE = "whole_sentence"
    const val KEY_VOICE = "voice"
    const val KEY_MAX_DELAY_SEC = "max_delay_sec"
    const val KEY_ORIGINAL_VOLUME = "original_volume_pct"
    const val KEY_DUB_VOLUME = "dub_volume_pct"
    const val KEY_VAD_THRESHOLD = "vad_threshold"
    const val KEY_SHARE_TIP_SHOWN = "share_tip_shown"

    /** Display name to Gemini Live voice name. */
    val VOICES = linkedMapOf(
        "Aoede (nữ, mặc định)" to "Aoede",
        "Puck (nam)" to "Puck",
        "Charon (nam trầm)" to "Charon"
    )

    /** Display name to language hint sent to the model. */
    val SOURCE_LANGS = linkedMapOf(
        "Tự động" to "auto",
        "Trung giản thể" to "zh-CN",
        "Quảng Đông" to "yue",
        "Anh" to "en",
        "Hàn" to "ko",
        "Nhật" to "ja"
    )

    const val DEFAULT_MAX_DELAY_SEC = 20
    const val MIN_DELAY_MS = 3000L

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun geminiKeys(context: Context): List<String> {
        val p = prefs(context)
        return listOf(
            p.getString(KEY_GEMINI_1, "").orEmpty().trim(),
            p.getString(KEY_GEMINI_2, "").orEmpty().trim()
        ).filter { it.isNotEmpty() }
    }

    fun groqKey(context: Context): String =
        prefs(context).getString(KEY_GROQ, "").orEmpty().trim()

    fun sourceLang(context: Context): String =
        prefs(context).getString(KEY_SOURCE_LANG, "auto").orEmpty().ifEmpty { "auto" }

    fun sourceLangLabel(context: Context): String =
        SOURCE_LANGS.entries.firstOrNull { it.value == sourceLang(context) }?.key ?: "Tự động"

    fun wholeSentence(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WHOLE_SENTENCE, false)

    fun voiceName(context: Context): String =
        prefs(context).getString(KEY_VOICE, "Aoede").orEmpty().ifEmpty { "Aoede" }

    fun maxDelayMs(context: Context): Long {
        val sec = prefs(context).getInt(KEY_MAX_DELAY_SEC, DEFAULT_MAX_DELAY_SEC)
        return (sec.coerceIn(3, 20) * 1000).toLong()
    }

    fun originalVolume(context: Context): Float {
        // v1.0.5: default 60% (was 50%). This is the level the original
        // audio drops to WHILE the dubbed voice is speaking — and only
        // while it is speaking (see the duck ticker in DubbingService).
        // It returns 0..1 (percent / 100), the same scale
        // DelayJs.DubLiveSetVolume expects; callers must NOT send 60.
        return (originalVolumePct(context) / 100f)
    }

    fun originalVolumePct(context: Context): Int {
        val pct = prefs(context).getInt(KEY_ORIGINAL_VOLUME, 60)
        return pct.coerceIn(0, 100)
    }

    /** Dub voice volume, 0..1. Default 90%. */
    fun dubVolume(context: Context): Float {
        return (dubVolumePct(context) / 100f)
    }

    fun dubVolumePct(context: Context): Int {
        val pct = prefs(context).getInt(KEY_DUB_VOLUME, 90)
        return pct.coerceIn(0, 100)
    }

    /**
     * v1.0.5 — VAD energy (RMS) threshold. Default 600, same as ALDA.
     * v1.0.4 used a hard-coded 250, far below the real RMS of a loud
     * scene (user screenshot: RMS 1632 with music + dialogue), so the
     * VAD fired on background music/noise all the time and the noise
     * was sent to Live and translated. Higher = less sensitive, less
     * noise; lower = catches quiet speech but also noise. Range 250–1200.
     */
    fun vadThreshold(context: Context): Int {
        val t = prefs(context).getInt(KEY_VAD_THRESHOLD, 600)
        return t.coerceIn(250, 1200)
    }

    /** True once the "pick whole screen, not a single app" tip was shown. */
    fun shareTipShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHARE_TIP_SHOWN, false)

    fun setShareTipShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHARE_TIP_SHOWN, true).apply()
    }
}
