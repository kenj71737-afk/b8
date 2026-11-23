package com.alad.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alad_settings")

class UserPreferencesRepository(private val context: Context) {

    companion object {
        val WS_URL = stringPreferencesKey("ws_url")
        val API_KEY = stringPreferencesKey("api_key")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val TARGET_DIALECT = stringPreferencesKey("target_dialect")
        val VOLUME_RATIO = floatPreferencesKey("volume_ratio") // Dubbing volume
        val IS_DUBBING_MUTED = booleanPreferencesKey("is_dubbing_muted")
        val ORIGINAL_VOLUME = floatPreferencesKey("original_volume")
        val IS_ORIGINAL_MUTED = booleanPreferencesKey("is_original_muted")
        val VOICE_PERSONA = stringPreferencesKey("voice_persona")
        val DUBBING_MODE = stringPreferencesKey("dubbing_mode")
        val SHOW_DUAL_SUBTITLES = booleanPreferencesKey("show_dual_subtitles")
        // FIX19: watch the video in another app (YouTube, TikTok...) instead of
        // the in-app browser. The translated voice plays the moment it arrives
        // (no hold for a delayed video that does not exist there).
        val EXTERNAL_APP_MODE = booleanPreferencesKey("external_app_mode")
        // FIX29 (cách B): in external-app mode, remove the original voice
        // (centre-panned dialogue) and keep only the background, then dub
        // over it. The other app is muted entirely (STREAM_MUSIC -> 0) and
        // the filtered background + dub play on the alarm stream.
        val FILTER_ORIGINAL_VOICE = booleanPreferencesKey("filter_original_voice")
        // FIX35: dịch nguyên câu hoàn chỉnh — bật thì dặn Gemini chờ nhân
        // vật nói hết câu rồi dịch cả câu, tắt thì dịch ngay từng cụm ngắn
        // như cũ. Mặc định tắt.
        val WHOLE_SENTENCE = booleanPreferencesKey("whole_sentence")
        // FIX27: second Gemini API key (another Google account) + which key
        // is currently active. Key 1 stays in API_KEY ("api_key") so existing
        // installs keep their key; key 2 is optional.
        val GEMINI_API_KEY_2 = stringPreferencesKey("gemini_api_key_2")
        val GEMINI_ACTIVE_KEY = intPreferencesKey("gemini_active_key")

        const val MODE_HYBRID = "HYBRID"               // Voice dubbing + Live subtitles
        const val MODE_AUDIO_ONLY = "AUDIO_ONLY"       // Voice dubbing only
        const val MODE_SUBTITLE_ONLY = "SUBTITLE_ONLY" // Live subtitles only (silent mode)

        val AVAILABLE_VOICES = listOf(
            "Puck",    // Energetic & Friendly
            "Charon",  // Deep & Resonant
            "Aoede",   // Melodic & Warm
            "Fenrir",  // Crisp & Authoritative
            "Kore"     // Gentle & Calm
        )
    }

    val wsUrlFlow: Flow<String> = context.dataStore.data.map { it[WS_URL] ?: "ws://192.168.1.100:8000/ws/dub" }.distinctUntilChanged()
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }.distinctUntilChanged()
    val sourceLangFlow: Flow<String> = context.dataStore.data.map { it[SOURCE_LANG] ?: "en" }.distinctUntilChanged()
    val targetLangFlow: Flow<String> = context.dataStore.data.map { it[TARGET_LANG] ?: "vi" }.distinctUntilChanged()
    val targetDialectFlow: Flow<String> = context.dataStore.data.map { it[TARGET_DIALECT] ?: "" }.distinctUntilChanged()
    val volumeRatioFlow: Flow<Float> = context.dataStore.data.map { it[VOLUME_RATIO] ?: 1.0f }.distinctUntilChanged()
    val isDubbingMutedFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_DUBBING_MUTED] ?: false }.distinctUntilChanged()
    val originalVolumeFlow: Flow<Float> = context.dataStore.data.map { it[ORIGINAL_VOLUME] ?: 0.30f }.distinctUntilChanged()
    val isOriginalMutedFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_ORIGINAL_MUTED] ?: false }.distinctUntilChanged()
    val voicePersonaFlow: Flow<String> = context.dataStore.data.map { it[VOICE_PERSONA] ?: "Puck" }.distinctUntilChanged()
    val dubbingModeFlow: Flow<String> = context.dataStore.data.map { it[DUBBING_MODE] ?: MODE_HYBRID }.distinctUntilChanged()
    val showDualSubtitlesFlow: Flow<Boolean> = context.dataStore.data.map { it[SHOW_DUAL_SUBTITLES] ?: true }.distinctUntilChanged()
    val externalAppModeFlow: Flow<Boolean> = context.dataStore.data.map { it[EXTERNAL_APP_MODE] ?: false }.distinctUntilChanged()
    val filterOriginalVoiceFlow: Flow<Boolean> = context.dataStore.data.map { it[FILTER_ORIGINAL_VOICE] ?: false }.distinctUntilChanged()
    val wholeSentenceFlow: Flow<Boolean> = context.dataStore.data.map { it[WHOLE_SENTENCE] ?: false }.distinctUntilChanged()
    val geminiApiKey2Flow: Flow<String> = context.dataStore.data.map { it[GEMINI_API_KEY_2] ?: "" }.distinctUntilChanged()
    val geminiActiveKeyFlow: Flow<Int> = context.dataStore.data.map { it[GEMINI_ACTIVE_KEY] ?: 0 }.distinctUntilChanged()

    suspend fun updateWsUrl(url: String) { context.dataStore.edit { it[WS_URL] = url } }
    suspend fun updateApiKey(key: String) { context.dataStore.edit { it[API_KEY] = key } }
    suspend fun updateSourceLang(lang: String) { context.dataStore.edit { it[SOURCE_LANG] = lang } }
    suspend fun updateTargetLang(lang: String) { context.dataStore.edit { it[TARGET_LANG] = lang } }
    suspend fun updateTargetDialect(dialect: String) { context.dataStore.edit { it[TARGET_DIALECT] = dialect } }
    suspend fun updateVolumeRatio(ratio: Float) { context.dataStore.edit { it[VOLUME_RATIO] = ratio } }
    suspend fun updateDubbingMuted(muted: Boolean) { context.dataStore.edit { it[IS_DUBBING_MUTED] = muted } }
    suspend fun updateOriginalVolume(volume: Float) { context.dataStore.edit { it[ORIGINAL_VOLUME] = volume } }
    suspend fun updateOriginalMuted(muted: Boolean) { context.dataStore.edit { it[IS_ORIGINAL_MUTED] = muted } }
    suspend fun updateVoicePersona(voice: String) { context.dataStore.edit { it[VOICE_PERSONA] = voice } }
    suspend fun updateDubbingMode(mode: String) { context.dataStore.edit { it[DUBBING_MODE] = mode } }
    suspend fun updateShowDualSubtitles(show: Boolean) { context.dataStore.edit { it[SHOW_DUAL_SUBTITLES] = show } }
    suspend fun updateExternalAppMode(enabled: Boolean) { context.dataStore.edit { it[EXTERNAL_APP_MODE] = enabled } }
    suspend fun updateFilterOriginalVoice(enabled: Boolean) { context.dataStore.edit { it[FILTER_ORIGINAL_VOICE] = enabled } }
    suspend fun updateWholeSentence(enabled: Boolean) { context.dataStore.edit { it[WHOLE_SENTENCE] = enabled } }
    suspend fun updateGeminiApiKey2(key: String) { context.dataStore.edit { it[GEMINI_API_KEY_2] = key } }
    suspend fun updateGeminiActiveKey(index: Int) { context.dataStore.edit { it[GEMINI_ACTIVE_KEY] = index.coerceIn(0, 1) } }
}
