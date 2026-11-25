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
    /** v1.0.7 — "Trễ hình cơ bản": seed delay when a session starts. */
    const val KEY_BASE_DELAY_SEC = "base_delay_sec"
    const val KEY_ORIGINAL_VOLUME = "original_volume_pct"
    const val KEY_DUB_VOLUME = "dub_volume_pct"
    /** v1.0.7 — "Giọng dịch phát qua": "call" (default) or "alarm". */
    const val KEY_DUB_OUTPUT = "dub_output"

    /** Display name to dub-output value. Alarm is louder than the call
     * stream on the user's phone, and still echo-free: capture only
     * matches USAGE_MEDIA + USAGE_GAME, never USAGE_ALARM. */
    val DUB_OUTPUTS = linkedMapOf(
        "Âm lượng cuộc gọi (mặc định, không vọng)" to "call",
        "Âm lượng báo thức (to hơn, không vọng)" to "alarm"
    )
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

    /**
     * v1.0.7 — "Trễ hình cơ bản" (Settings slider, 3–20 s, default 5 s):
     * the seed delay a session starts with, before the first FIFO sample
     * arrives. The old code hard-coded 5238 ms here and 5000 ms in the
     * WebView; both now read this one value.
     */
    fun baseDelayMs(context: Context): Long {
        val sec = prefs(context).getInt(KEY_BASE_DELAY_SEC, 5)
        return (sec.coerceIn(3, 20) * 1000).toLong()
    }

    fun setBaseDelaySec(context: Context, sec: Int) {
        prefs(context).edit()
            .putInt(KEY_BASE_DELAY_SEC, sec.coerceIn(3, 20)).apply()
    }

    fun originalVolume(context: Context): Float {
        // v1.0.6: default 80% (was 60% in v1.0.5). This is the level the
        // original audio drops to WHILE the dubbed voice is speaking —
        // and only while it is speaking (see the duck ticker in
        // DubbingService). It returns 0..1 (percent / 100), the same
        // scale DelayJs.DubLiveSetVolume expects; callers must NOT
        // send 80.
        return (originalVolumePct(context) / 100f)
    }

    fun originalVolumePct(context: Context): Int {
        val pct = prefs(context).getInt(KEY_ORIGINAL_VOLUME, 80)
        return pct.coerceIn(0, 100)
    }

    /** Dub voice volume as a 0..100% slider value. v1.0.6: this is the
     * SYSTEM call-stream (STREAM_VOICE_CALL) volume, NOT a track gain —
     * VoicePlayer.applyCallVolume maps it onto the stream (default 90%).
     * The AudioTrack/TTS gain itself is always max (1.0f). */
    fun dubVolume(context: Context): Float {
        return (dubVolumePct(context) / 100f)
    }

    fun dubVolumePct(context: Context): Int {
        val pct = prefs(context).getInt(KEY_DUB_VOLUME, 90)
        return pct.coerceIn(0, 100)
    }

    /** v1.0.7 — true when the dub voice should play on the alarm
     * stream instead of the call stream. Applies from the next dub
     * session (VoicePlayer reads it when the track is created). */
    fun dubOutputAlarm(context: Context): Boolean =
        prefs(context).getString(KEY_DUB_OUTPUT, "call").orEmpty() == "alarm"

    fun dubOutputLabel(context: Context): String {
        val value = prefs(context).getString(KEY_DUB_OUTPUT, "call").orEmpty()
        return DUB_OUTPUTS.entries.firstOrNull { it.value == value }?.key
            ?: DUB_OUTPUTS.keys.first()
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
