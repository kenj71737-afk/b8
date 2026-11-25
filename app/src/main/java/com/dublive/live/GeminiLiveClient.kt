package com.dublive.live

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Gemini Live (BidiGenerateContent) client over WebSocket.
 * Speech-to-speech translation: PCM audio in, Vietnamese audio + transcript out.
 *
 * NOTE on model id: the live model name changes over time. The default below
 * is the documented live model at the time of writing; if the server rejects
 * it, [Listener.onError] reports the message and the service falls back to
 * the Groq Whisper pipeline instead of crashing.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val voiceName: String,
    private val sourceLang: String,
    private val wholeSentence: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onAudioChunk(pcm24k: ByteArray)
        fun onTranslatedText(text: String, isFinal: Boolean)
        fun onTurnComplete()
        fun onError(message: String)
        fun onClosed()
        /** Source-language transcript (debug only; the service logs it). */
        fun onInputText(text: String) {}
    }

    companion object {
        private const val TAG = "DubLive-Gemini"
        /**
         * v1.0.3: the model that PROVABLY works with this user's key is
         * "gemini-3.5-live-translate-preview" — the speech-to-speech
         * translation model ALDA FIX43 and the web-dub script v1.9.1 use
         * with the same key. v1.0.2's "gemini-2.5-flash-native-audio-latest"
         * was never verified against this key: the user reported
         * "Gemini live lỗi" with no translation at all, while the key test
         * passed (it only proves the key can list models). The native-audio
         * 2.5 models stay as LAST-RESORT fallbacks, each with its own
         * (different) setup — they do not accept translationConfig, and the
         * translate model does not accept a voice config at all.
         *
         * Attempt order (service): translate-preview key 1 -> key 2 ->
         * native-audio-latest key 1 -> key 2 -> native-audio-preview
         * key 1 -> key 2. Each failure names the model in the status line.
         */
        const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
        const val DEFAULT_MODEL = TRANSLATE_MODEL
        const val NATIVE_MODEL = "gemini-2.5-flash-native-audio-latest"
        const val NATIVE_FALLBACK_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        val LIVE_MODELS = listOf(TRANSLATE_MODEL, NATIVE_MODEL, NATIVE_FALLBACK_MODEL)

        /** True for the speech-to-speech translation model. */
        fun isTranslateModel(model: String) = model == TRANSLATE_MODEL

        /** Short label for status lines, e.g. "3.5-translate". */
        fun shortModelName(model: String) = when (model) {
            TRANSLATE_MODEL -> "3.5-translate"
            NATIVE_MODEL -> "2.5-native"
            NATIVE_FALLBACK_MODEL -> "2.5-native-preview"
            else -> model
        }
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile var connected = false
        private set
    /** Model used by the current (or last) connection attempt. */
    @Volatile var currentModel: String = DEFAULT_MODEL
        private set

    fun connect(model: String = DEFAULT_MODEL) {
        currentModel = model
        val isTranslate = isTranslateModel(model)
        val langHint = when (sourceLang) {
            "zh-CN" -> "Tiếng Trung giản thể"
            "yue" -> "tiếng Quảng Đông"
            "en" -> "tiếng Anh"
            "ko" -> "tiếng Hàn"
            "ja" -> "tiếng Nhật"
            else -> "ngôn ngữ gốc của video (tự nhận diện)"
        }
        val modeHint = if (wholeSentence) {
            "Begin output only after the speaker finishes the sentence, then translate the WHOLE sentence at once — never translate a half-finished sentence or a lone fragment. A complete sentence carries the full meaning; a fragment loses it."
        } else {
            "Begin output as soon as each short meaningful phrase is understood; do not wait for the full sentence or paragraph. Keep phrases short, preserve meaning and speaker intent, avoid explanations and repetition, and maintain source order with minimal pauses."
        }
        // v1.0.3 — TWO setups, one per model family. The translate-preview
        // model is a speech-to-speech translator: it takes translationConfig
        // (target "vi", echo on) and NO voice config — it preserves the
        // original speaker's voice, and sending a voice_config gets the
        // setup rejected (ALDA FIX17). The native-audio models are
        // voice-to-voice: they take speechConfig with a prebuilt voice and
        // NO translationConfig. Wire keys are camelCase exactly as in ALDA
        // (generationConfig, systemInstruction, ...): v1.0.2 sent
        // snake_case keys that the server silently ignores, so its setup never took effect.
        val instruction = if (isTranslate) {
            "You are a real-time simultaneous interpreter. " +
                (if (sourceLang == "auto") "" else "The speech you hear is in $langHint. ") +
                "Translate speech into concise, natural spoken Vietnamese. " +
                "Deliver it like a classic Vietnamese TVB drama dub: warm, expressive and clear, " +
                "with steady natural drama — never flat, never rushed, never shouting — and with " +
                "natural Vietnamese address terms (anh/em/chi/co/chu...) matching the characters' " +
                "relationship, never stiff textbook Vietnamese. Keep the speaker's own tone and " +
                "emotion. Do not imitate any real dubbing actor's voice; this is a delivery " +
                "style, not a copy of a person. Every translated line must take about the same " +
                "time to say as the original line it replaces, so the Vietnamese voice finishes " +
                "when the character's mouth stops moving. " +
                "Translate only meaningful speech. Completely ignore non-verbal sounds — " +
                "cries of pain, groans, moans, gasps, sighs, laughter, coughing, sneezing, " +
                "hiccups, and filler sounds such as uh, um, hmm, ah and oh — and stay " +
                "silent for them: do not speak, do not turn them into words. A lone cry " +
                "or groan is not a sentence and needs no translation. " +
                "If you hear only music, background noise, sound effects, mumbling, or " +
                "sounds you cannot clearly understand as words, stay completely silent — " +
                "never invent words for sounds you cannot understand. Do not guess. " +
                modeHint
        } else {
            "Bạn là phiên dịch viên lồng tiếng phim. Nghe $langHint trong video và " +
                "nói lại bằng tiếng Việt tự nhiên, giọng lồng tiếng truyền hình ấm áp, rõ ràng, " +
                "khớp đúng lúc nhân vật mở miệng và nói hết trong đúng thời lượng câu gốc. " +
                (if (wholeSentence) "Chờ nghe hết một câu hoàn chỉnh rồi mới dịch cả câu, dịch đúng ngữ cảnh hơn."
                else "Dịch theo từng cụm ngắn ngay khi nghe rõ cụm đó, không chờ hết câu, để ra giọng nhanh nhất.") +
                " Chỉ dịch câu có nghĩa. Nhân vật chỉ kêu, rên, cười, ho, ậm ừ hoặc nhạc không lời " +
                "thì im lặng hoàn toàn, tuyệt đối không bịa thêm lời. Nghe không rõ chữ thì im, không đoán."
        }
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$model")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    if (isTranslate) {
                        put("translationConfig", JSONObject().apply {
                            put("targetLanguageCode", "vi")
                            put("echoTargetLanguage", true)
                        })
                    } else {
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                    }
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", instruction)
                    ))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                if (isTranslate) {
                    put("sessionResumption", JSONObject().apply {
                        put("handle", JSONObject.NULL)
                    })
                }
            })
        }
        val request = Request.Builder().url("$ENDPOINT?key=$apiKey").build()
        try {
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    try {
                        webSocket.send(setup.toString())
                    } catch (e: Exception) {
                        Log.w(TAG, "setup send failed", e)
                        connected = false
                        listener.onError(e.message ?: "WebSocket setup failed")
                        return
                    }
                    Log.i(TAG, "Live session opened")
                }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val code = response?.code
                val msg = if (code != null) {
                    "WebSocket failure (HTTP $code): ${t.message}"
                } else {
                    t.message ?: "WebSocket failure"
                }
                Log.w(TAG, "Live socket failed: $msg")
                try { listener.onError(msg) } catch (_: Exception) { }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                try { listener.onClosed() } catch (_: Exception) { }
            }
        })
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket connect failed", e)
            connected = false
            listener.onError(e.message ?: "WebSocket connect failed")
        }
    }

    /**
     * Stream 16 kHz mono PCM to the model.
     * v1.0.3: the wire shape is realtimeInput.audio (ALDA / web script).
     * v1.0.2 sent the audio in a chunk-list field under a snake_case key
     * and the server never delivered the audio to the model.
     */
    fun sendAudio(pcm16: ByteArray) {
        val socket = socket ?: return
        if (!connected) return
        try {
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
                    })
                })
            }
            socket.send(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendAudio failed", e)
        }
    }

    /**
     * v1.0.3: intentionally a no-op. ALDA streams audio continuously and
     * never sends an end-of-stream marker — the translate model segments
     * speech itself. v1.0.2 sent such a marker after every utterance. Kept as
     * a no-op so existing callers (DubbingService.onSpeechEnd) keep working.
     */
    fun endTurn() {
        // Continuous streaming: no turn cuts.
    }

    fun close() {
        try { socket?.close(1000, "done") } catch (_: Exception) { }
        socket = null
        connected = false
    }

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            // The server can reject the session inside a message instead of
            // closing the socket, e.g. {"error": {"code": 404, "message":
            // "models/... is not found"}}. v1.0.1 ignored this and stayed
            // silent forever; surface it as an error so the service can
            // retry with the fallback model / other key.
            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val code = err?.optInt("code", 0) ?: 0
                val msg = err?.optString("message").orEmpty()
                    .ifEmpty { root.optString("error") }
                val detail = if (code != 0) "Lỗi server Live ($code): $msg" else "Lỗi server Live: $msg"
                Log.w(TAG, detail)
                try { listener.onError(detail) } catch (_: Exception) { }
                return
            }
            if (root.has("setupComplete")) {
                listener.onConnected()
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            // Source-language transcript: debug only, logged, not shown.
            val inTx = content.optJSONObject("inputTranscription")
            if (inTx != null) {
                val t = inTx.optString("text")
                if (t.isNotEmpty()) {
                    Log.i(TAG, "inputTranscription: $t")
                    try { listener.onInputText(t) } catch (_: Exception) { }
                }
            }
            val turn = content.optJSONObject("modelTurn")
            if (turn != null) {
                val parts = turn.optJSONArray("parts") ?: org.json.JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData")
                    if (inline != null && inline.optString("mime_type").startsWith("audio/pcm")) {
                        val b64 = inline.optString("data")
                        if (b64.isNotEmpty()) {
                            listener.onAudioChunk(Base64.decode(b64, Base64.DEFAULT))
                        }
                    }
                    val txt = part.optString("text")
                    if (txt.isNotEmpty()) listener.onTranslatedText(txt, false)
                }
            }
            val outTx = content.optJSONObject("outputTranscription")
            if (outTx != null) {
                val t = outTx.optString("text")
                if (t.isNotEmpty()) listener.onTranslatedText(t, false)
            }
            if (content.optBoolean("turnComplete", false)) {
                listener.onTurnComplete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad server message: ${e.message}")
        }
    }
}
