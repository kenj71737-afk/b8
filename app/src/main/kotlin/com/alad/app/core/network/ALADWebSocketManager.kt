package com.alad.app.core.network

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.alad.app.core.audio.PendingAudioQueue
import com.alad.app.core.audio.SpeechSendGate
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class ALADWebSocketManager(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    var onBinaryMessageReceived: ((ByteArray) -> Unit)? = null
    var onTextMessageReceived: ((String) -> Unit)? = null
    var onSourceTextReceived: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    // FIX27: fired when Gemini reports a key/quota error (429, quota
    // exhausted, invalid key) — the service switches to the other Gemini
    // key and reconnects instead of stopping the dub.
    var onKeyError: ((String) -> Unit)? = null
    // FIX28: fired when the Live socket closes or fails for any reason
    // OTHER than a key error (server closed the session, network drop...).
    // The service reconnects on its own — alternating keys — so the user
    // never has to stop/start the dub by hand. Deliberate disconnect()
    // calls never reach this: disconnect() nulls the socket first, so the
    // stale socket's callbacks return early on the identity guard below.
    var onDisconnected: (() -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // FIX27: quota / rate-limit / invalid-key signals in a Gemini error
        // message (same set as the web dubbing script v1.6).
        private val KEY_ERROR_REGEX =
            Regex("quota|rate.?limit|resource_exhausted|exhausted|api key not valid|api_key_invalid|permission denied",
                RegexOption.IGNORE_CASE)

        fun isKeyError(message: String?, code: Int = 0): Boolean {
            if (code == 429) return true
            return message != null && KEY_ERROR_REGEX.containsMatchIn(message)
        }
    }

    private var isSetupComplete = false
    private var currentVoicePersona = "Puck"
    private var currentDubbingMode = "HYBRID"
    private var currentTargetDialect = ""
    private var currentWholeSentence = false // FIX35: chờ hết câu rồi dịch cả câu
    // FIX44: lọc nhạc nền bằng phổ tần số (port từ DubLive v1.0.8).
    private var currentSpectralMusicFilter = true
    // FIX30: VAD send gate — music, sound effects and room noise never
    // reach Gemini, so the model cannot invent translations for them.
    private val sendGate = SpeechSendGate()

    /** FIX32: start of the most recent utterance the gate has seen, or -1. */
    fun lastUtteranceStartMs(): Long = sendGate.lastUtteranceStartMs()

    /** FIX38: start of the most recent sentence (for whole-sentence mode). */
    fun lastSentenceStartMs(): Long = sendGate.lastSentenceStartMs()

    /** FIX34: time of the most recent speech frame the gate has seen, or -1. */
    fun lastSpeechAtMs(): Long = sendGate.lastSpeechAtMs()

    fun connect(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        voicePersona: String = "Puck",
        dubbingMode: String = "HYBRID",
        targetDialect: String = "",
        wholeSentence: Boolean = false,
        spectralMusicFilter: Boolean = true
    ) {
        currentVoicePersona = voicePersona
        currentDubbingMode = dubbingMode
        currentTargetDialect = targetDialect
        currentWholeSentence = wholeSentence
        currentSpectralMusicFilter = spectralMusicFilter
        val finalUrl = "$GEMINI_WS_URL?key=$apiKey"
        val request = Request.Builder().url(finalUrl).build()
        isSetupComplete = false
        sendGate.reset() // FIX30: fresh VAD state for every session
        // FIX44: spectral music filter for this session (session-start
        // setting, like wholeSentence).
        sendGate.setSpectralGateEnabled(spectralMusicFilter)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                Log.d(TAG, "Connected to Gemini Live API")
                onStatusChanged?.invoke("Connected")
                sendGeminiSetup(targetLang, currentVoicePersona, currentDubbingMode, currentTargetDialect, currentWholeSentence)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                Log.d(TAG, "Raw message: $text")
                try {
                    val json = JSONObject(text)

                    if (json.has("serverContent") || json.has("server_content")) {
                        val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")
                        if (serverContent != null) {
                            // 1. Output transcription: translated text for live subtitles
                            val outputTranscription = serverContent.optJSONObject("outputTranscription")
                                ?: serverContent.optJSONObject("output_transcription")
                            if (outputTranscription != null) {
                                val translatedText = outputTranscription.optString("text", "")
                                if (translatedText.isNotEmpty()) {
                                    Log.d(TAG, "Live subtitle output: $translatedText")
                                    onTextMessageReceived?.invoke(translatedText)
                                }
                            }

                            // 2. Input transcription: source speech transcript
                            val inputTranscription = serverContent.optJSONObject("inputTranscription")
                                ?: serverContent.optJSONObject("input_transcription")
                            if (inputTranscription != null) {
                                val sourceText = inputTranscription.optString("text", "")
                                if (sourceText.isNotEmpty()) {
                                    Log.d(TAG, "Source speech input: $sourceText")
                                    onSourceTextReceived?.invoke(sourceText)
                                }
                            }

                            // 3. Model turn parts: audio PCM chunks & optional text parts
                            val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                            val parts = modelTurn?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)

                                    if (part.has("text")) {
                                        val textDelta = part.optString("text", "")
                                        if (textDelta.isNotEmpty()) {
                                            Log.d(TAG, "Part text: $textDelta")
                                            onTextMessageReceived?.invoke(textDelta)
                                        }
                                    }

                                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                                    if (inlineData != null) {
                                        val base64Data = inlineData.getString("data")
                                        val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        onBinaryMessageReceived?.invoke(audioBytes)
                                    }
                                }
                            }

                            // 4. Check for turn completion
                            val turnComplete = serverContent.optBoolean("turnComplete", false)
                                || serverContent.optBoolean("turn_complete", false)
                            if (turnComplete) {
                                Log.d(TAG, "Turn complete from Gemini Live API")
                                onTurnComplete?.invoke()
                            }
                        }
                    } else if (json.has("setupComplete") || json.has("setup_complete")) {
                        isSetupComplete = true
                        Log.d(TAG, "Gemini Setup Complete - Ready for audio streaming")
                        onStatusChanged?.invoke("Gemini Ready")
                        // FIX36: no flush here — the backlog drains through
                        // sendAudioData (oldest first, 2 blocks per input
                        // tick). A full flush would flood the socket queue
                        // past the 64 KB congestion drop and lose audio.
                    } else if (json.has("error")) {
                        val errObj = json.getJSONObject("error")
                        val errMessage = errObj.optString("message", "Unknown error")
                        val errCode = errObj.optInt("code", 0)
                        Log.e(TAG, "API Error: $errMessage")
                        // FIX27: key/quota errors go to the failover handler
                        // (switch key + reconnect); other errors keep the old
                        // status-only path.
                        if (isKeyError(errMessage, errCode)) {
                            onKeyError?.invoke(errMessage)
                        } else {
                            onStatusChanged?.invoke("Error: $errMessage")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                val text = bytes.utf8()
                onMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.d(TAG, "Closing: $code $reason")
                onStatusChanged?.invoke("Disconnected: $reason")
                onDisconnected?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.e(TAG, "WebSocket error", t)
                // FIX27: a 429 on the HTTP upgrade fails the socket before
                // any error message arrives — treat it as a key error so the
                // service can fail over to the other key.
                if (response?.code == 429 || isKeyError(t.message)) {
                    onKeyError?.invoke(t.message ?: "HTTP 429")
                } else {
                    onStatusChanged?.invoke("Error: ${t.message}")
                    onDisconnected?.invoke()
                }
            }
        })
    }

    private fun sendGeminiSetup(targetLang: String, voicePersona: String, dubbingMode: String, targetDialect: String, wholeSentence: Boolean) {
        val targetLangCode = targetLang.split("-")[0]
        val targetLangName = languageDisplayName(targetLangCode)

        val dialectInstruction = com.alad.app.presentation.main.supportedDialects[targetLangCode]
            ?.find { it.code.equals(targetDialect, ignoreCase = true) }
            ?.promptInstruction ?: if (targetDialect.isNotEmpty()) "Speak and translate in $targetDialect dialect" else ""

        // FIX17: gemini-3.5-live-translate-preview is a speech-to-speech model.
        // Its only output modality is AUDIO (24 kHz PCM); the old setup asked for
        // TEXT, which this model does not produce, so the session returned no
        // translation at all. No voiceConfig is sent either: translation mode
        // preserves the original speaker's voice, and the local voice persona is
        // applied by AudioPlayerManager's DSP instead.
        //
        // System instructions in translation mode may only guide translation and
        // must match targetLanguageCode (Google docs, Nov 2026). Formatting
        // requests such as [[TVB:...]] speaker markers are unsupported and are
        // ignored — or degrade the output — so they are no longer requested.
        // The instruction below is built from the actual target language, so it
        // can never conflict with translationConfig again.
        val styleInstruction = if (targetLangCode == "vi") {
            "Deliver it like a classic Vietnamese TVB drama dub: warm, expressive and clear, " +
            "with steady natural drama — never flat, never rushed, never shouting — and with " +
            "natural Vietnamese address terms (anh/em/chi/co/chu...) matching the characters' " +
            "relationship, never stiff textbook Vietnamese. Keep the speaker's own tone and " +
            "emotion. Do not imitate any real dubbing actor's voice; this is a delivery " +
            "style, not a copy of a person. Every translated line must take about the same " +
            "time to say as the original line it replaces, so the Vietnamese voice finishes " +
            "when the character's mouth stops moving. "
        } else {
            ""
        }

        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))

                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLangCode)
                        put("echoTargetLanguage", true)
                    })
                })

                // Always request incremental simultaneous interpretation, including default settings.
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        val dialect = if (dialectInstruction.isNotEmpty()) "$dialectInstruction. " else ""
                        put("text", "You are a real-time simultaneous interpreter. $dialect" +
                            "Translate speech into concise, natural spoken $targetLangName. " +
                            // FIX21: translate only meaningful speech. Pain cries,
                            // groans and other non-verbal sounds must stay silent
                            // instead of being vocalized by the dub voice.
                            "Translate only meaningful speech. Completely ignore non-verbal sounds — " +
                            "cries of pain, groans, moans, gasps, sighs, laughter, coughing, sneezing, " +
                            "hiccups, and filler sounds such as uh, um, hmm, ah and oh — and stay " +
                            "silent for them: do not speak, do not turn them into words. A lone cry " +
                            "or groan is not a sentence and needs no translation. " +
                            // FIX30: never invent words for music, noise or
                            // unclear audio — stay silent instead.
                            "If you hear only music, background noise, sound effects, mumbling, or " +
                            "sounds you cannot clearly understand as words, stay completely silent — " +
                            "never invent words for sounds you cannot understand. Do not guess. " +
                            styleInstruction +
                            // FIX35: ô chọn "Dịch nguyên câu hoàn chỉnh" —
                            // bật thì dặn model chờ nhân vật nói hết câu rồi
                            // dịch cả câu một lượt, tắt thì dịch ngay từng
                            // cụm ngắn như cũ.
                            (if (wholeSentence)
                                "Begin output only after the speaker finishes the sentence, then " +
                                "translate the WHOLE sentence at once — never translate a " +
                                "half-finished sentence or a lone fragment. A complete sentence " +
                                "carries the full meaning; a fragment loses it. "
                            else
                                "Begin output as soon as each short meaningful phrase is understood; " +
                                "do not wait for the full sentence or paragraph. Keep phrases short, ") +
                            "preserve meaning and speaker intent, avoid explanations and repetition, " +
                            "and maintain source order with minimal pauses.")
                    }))
                })

                // Direct children of BidiGenerateContentSetup according to Google Gemini Live API specification
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("sessionResumption", JSONObject().apply {
                    put("handle", JSONObject.NULL)
                })
            })
        }
        Log.d(TAG, "Setup message payload: $setupPayload")
        Log.d(TAG, "Translation target=$targetLangCode voicePersona=$voicePersona (persona is local DSP only, not sent to Gemini)")
        webSocket?.send(setupPayload.toString())
    }

    /** Display names look like "🇻🇳 Vietnamese": strip the flag emoji for the prompt. */
    private fun languageDisplayName(code: String): String {
        val display = com.alad.app.presentation.main.supportedLanguages
            .find { it.first.equals(code, ignoreCase = true) }?.second
        return display?.substringAfter(" ")?.takeIf { it.isNotBlank() } ?: code
    }

    // FIX36: ordered send queue for source audio (6 s). Before, only 2
    // packets (20 ms) were kept while a new session set up, so every key
    // rotation discarded 1-3 s of speech. The queue drains oldest-first
    // through sendAudioData, 2 blocks per input tick.
    private val pendingAudio = PendingAudioQueue()

    @Synchronized
    fun sendAudioData(pcmData: ByteArray) {
        // FIX30: VAD send gate — hold each block until real speech
        // (150 ms above the speech threshold) has been observed; music,
        // sound effects and noise are dropped here and never reach Gemini.
        // The gate flushes the pre-roll with the sentence's first frames,
        // so the start of a sentence is never cut.
        val toSend = sendGate.feed(pcmData, SystemClock.elapsedRealtime())
        for (block in toSend) {
            pendingAudio.add(Base64.encodeToString(block, Base64.NO_WRAP))
        }
        // FIX36: drain the queue (oldest first) once the session is up.
        // While the session is still setting up, blocks just accumulate
        // in the queue instead of being discarded.
        if (isSetupComplete) {
            pendingAudio.drain { sendAudioNow(it) }
        }
    }

    /** Sends one block. Returns false when the socket is congested (the
     * caller keeps the block queued instead of dropping it). */
    private fun sendAudioNow(base64Audio: String): Boolean {
        val socket = webSocket ?: return false
        // Drop stale packets if network congestion is building; do not let latency grow.
        if (socket.queueSize() > 64 * 1024L) {
            Log.w(TAG, "Dropping stale input audio: WebSocket queue is congested")
            return false
        }
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }
        socket.send(inputPayload.toString())
        return true
    }

    @Synchronized
    fun disconnect(resetGate: Boolean = true) {
        isSetupComplete = false
        pendingAudio.clear()
        // FIX36: a reconnect (new socket, same dubbing session) keeps the
        // VAD gate state — its pre-roll (up to 500 ms of sentence start)
        // and utterance timestamps survive. Only a full stop resets it.
        if (resetGate) sendGate.reset() // FIX30: drop any buffered pre-roll
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "User requested stop")
        onStatusChanged?.invoke("Disconnected")
    }
}
