package com.dublive.live

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal Gemini Live (BidiGenerateContent) client over WebSocket.
 * Speech-to-speech translation: PCM audio in, Vietnamese audio + transcript out.
 *
 * NOTE on model id: the live model name changes over time. The default below
 * is the documented live model at the time of writing; if the server rejects
 * it, [Listener.onError] reports the message and the service falls back to
 * the Groq Whisper pipeline instead of crashing.
 *
 * v1.0.4 — ALDA ALIGNMENT. A control test on 25/11/2026 ~19:06 proved the
 * fault is 100% on DubLive's side: the user opened ALDA with the SAME
 * Gemini key and the SAME NetShort video ("hồng diện sát") and ALDA
 * translated normally (dual subtitles visible), so the key, the quota,
 * the gemini-3.5-live-translate-preview model and Google's server were all
 * fine at that moment. DubLive was therefore diffed line-by-line against
 * ALDA FIX43's ALADWebSocketManager and every remaining difference was
 * closed:
 *  - OkHttp: ALDA uses pingInterval(15 s) + retryOnConnectionFailure(true)
 *    with default timeouts; DubLive had no ping and readTimeout(0).
 *  - Audio send: ALDA queues source audio (PendingAudioQueue, ~6 s max)
 *    and drains it 2 blocks per input tick (2x real-time), dropping a
 *    block when socket.queueSize() > 64 KB. DubLive flushed its whole
 *    ~10 s pre-connect buffer in one tight loop the moment setupComplete
 *    arrived — exactly what ALDA's FIX36 comment warns floods the socket
 *    queue past the 64 KB congestion drop. A flood like that is a
 *    plausible trigger for the server closing the session right after
 *    connect ("tự ngắt kết nối").
 *  - Setup: ALDA never tells the model the source language (it
 *    auto-detects); DubLive inserted a "The speech you hear is in ..."
 *    hint that can be wrong when the setting mismatches the video.
 *    Instruction block order and the whole-sentence tail are now ALDA's.
 *  - Parsing: ALDA accepts snake_case AND camelCase keys, handles binary
 *    onMessage and onClosing; DubLive only did camelCase text messages.
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
        /**
         * v1.0.4: carries the close code + reason so the service can tell a
         * server close (1000/1006/1011) apart from a race. The identity
         * guard below already drops closes from a stale socket.
         */
        fun onClosed(code: Int, reason: String)
        /** Source-language transcript (debug only; the service logs it). */
        fun onInputText(text: String) {}
        /**
         * v1.0.21 — output (translated) transcription, as its OWN
         * callback. It used to be folded into [onTranslatedText]
         * together with the model turn's text parts, so the service
         * could not tell a real translated sentence (which the tx
         * anchor needs, per burst) apart from the model-turn text.
         * The service falls back to the model-turn text groups when
         * output transcription never arrives.
         */
        fun onOutputTranscription(text: String) {}
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

        /**
         * v1.0.4 — ALDA FIX27 key/quota signals (same set as the web dub
         * script). A 429 or a quota/invalid-key message means "try the
         * OTHER key on the same model first"; any other error may be the
         * model itself. The service uses this to label the error history.
         */
        private val KEY_ERROR_REGEX =
            Regex("quota|rate.?limit|resource_exhausted|exhausted|api key not valid|api_key_invalid|permission denied",
                RegexOption.IGNORE_CASE)

        fun isKeyError(message: String?, code: Int = 0): Boolean {
            if (code == 429) return true
            return message != null && KEY_ERROR_REGEX.containsMatchIn(message)
        }

        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /**
         * v1.0.4 — generation counter. Every GeminiLiveClient instance gets
         * a unique generation; the service also keeps its own counter and
         * drops callbacks whose generation is not the current one. This is
         * the ALDA identity guard (`if (webSocket !== currentWebSocket)
         * return`) that DubLive was missing: OkHttp close() is async, so
         * the OLD socket's onClosed could arrive AFTER reconnectLive() had
         * already opened a NEW socket — the stale onClosed was mistaken
         * for "server closed the connection", which advanced the model/key
         * and closed the brand-new socket, whose own onClosed then fired
         * the same chain again. That loop burned all 6 (model, key) combos
         * in seconds and produced exactly the v1.0.3 screenshot error
         * "Lỗi Live [2.5-native]: Gemini Live tự ngắt kết nối".
         */
        private val NEXT_GENERATION = AtomicInteger(1)
    }

    /** Unique id of this connection attempt; the service uses it to drop
     * callbacks from a socket that has already been replaced. */
    val generation: Int = NEXT_GENERATION.getAndIncrement()

    // v1.0.4 — ALDA's exact OkHttp setup: a 15 s WebSocket ping keeps the
    // session alive through proxies/NAT, and a failed connect is retried
    // once by OkHttp itself. DubLive v1.0.3 had no ping at all and an
    // infinite read timeout.
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    @Volatile var connected = false
        private set
    /**
     * v1.0.4 — true only after the server's setupComplete, like ALDA's
     * isSetupComplete. Audio is drained to the socket only in this state;
     * [connected] alone (socket open, setup not yet acked) is not enough.
     */
    @Volatile var isSetupComplete = false
        private set
    /** Model used by the current (or last) connection attempt. */
    @Volatile var currentModel: String = DEFAULT_MODEL
        private set

    // v1.0.4 — ordered send queue, ALDA FIX36 (PendingAudioQueue) semantics,
    // written fresh for DubLive: source audio arriving while the session is
    // still setting up is KEPT (up to ~6 s) instead of discarded, then
    // drains oldest-first at 2 blocks per input tick (2x real-time) so a
    // reconnect backlog clears in half its length WITHOUT flooding the
    // socket queue. A full flush would trip the 64 KB congestion drop and
    // lose audio — that is what DubLive v1.0.3 did on every setupComplete.
    // DubLive blocks are ~100 ms (ALDA's are 10 ms), so the cap is counted
    // in bytes: 6 s x 16 kHz x 2 bytes = 192_000.
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var pendingAudioBytes = 0
    private val maxPendingBytes = 192_000
    private val drainPerTick = 2

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
            "Begin output only after the speaker finishes the sentence, then translate the WHOLE sentence at once — never translate a half-finished sentence or a lone fragment. A complete sentence carries the full meaning; a fragment loses it. "
        } else {
            "Begin output as soon as each short meaningful phrase is understood; do not wait for the full sentence or paragraph. Keep phrases short, "
        }
        // v1.0.4 — setup instruction rewritten to ALDA FIX43's exact
        // structure and wording (ALADWebSocketManager.sendGeminiSetup):
        // interpreter intro -> translate-only-meaningful-speech -> music/
        // noise silence -> TVB style -> mode hint -> preserve-meaning tail
        // (ALDA appends the tail in BOTH modes; DubLive's whole-sentence
        // mode used to drop it). Two deliberate removals vs v1.0.3:
        //  - NO source-language hint ("The speech you hear is in ..."):
        //    ALDA never sends it — the model auto-detects, and a hint
        //    that mismatches the video (setting zh-CN, video in Cantonese)
        //    can only hurt.
        //  - NO voice config for the translate model (ALDA FIX17), no
        //    translationConfig for the native models. Unchanged.
        val instruction = if (isTranslate) {
            "You are a real-time simultaneous interpreter. " +
                "Translate speech into concise, natural spoken Vietnamese. " +
                "Translate only meaningful speech. Completely ignore non-verbal sounds — " +
                "cries of pain, groans, moans, gasps, sighs, laughter, coughing, sneezing, " +
                "hiccups, and filler sounds such as uh, um, hmm, ah and oh — and stay " +
                "silent for them: do not speak, do not turn them into words. A lone cry " +
                "or groan is not a sentence and needs no translation. " +
                "If you hear only music, background noise, sound effects, mumbling, or " +
                "sounds you cannot clearly understand as words, stay completely silent — " +
                "never invent words for sounds you cannot understand. Do not guess. " +
                "Deliver it like a classic Vietnamese TVB drama dub: warm, expressive and clear, " +
                "with steady natural drama — never flat, never rushed, never shouting — and with " +
                "natural Vietnamese address terms (anh/em/chi/co/chu...) matching the characters' " +
                "relationship, never stiff textbook Vietnamese. Keep the speaker's own tone and " +
                "emotion. Do not imitate any real dubbing actor's voice; this is a delivery " +
                "style, not a copy of a person. Every translated line must take about the same " +
                "time to say as the original line it replaces, so the Vietnamese voice finishes " +
                "when the character's mouth stops moving. " +
                modeHint +
                "preserve meaning and speaker intent, avoid explanations and repetition, " +
                "and maintain source order with minimal pauses."
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
        isSetupComplete = false
        try {
            socket = http.newWebSocket(request, object : WebSocketListener() {
                /**
                 * v1.0.4 identity guard (ALDA pattern): a callback belongs
                 * to THIS socket only. OkHttp close() is async — after the
                 * service replaces this client with a new one, the old
                 * socket's late onClosed/onFailure/onMessage must be
                 * ignored, never treated as "the server dropped us".
                 * close() nulls [socket] first, so a late callback from
                 * the old socket always fails this check.
                 */
                private fun isCurrent(webSocket: WebSocket): Boolean {
                    val current = socket
                    if (current == null) return false
                    return webSocket === current
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket)) return
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
                    if (!isCurrent(webSocket)) return
                    handleServerMessage(text)
                }

                // v1.0.4 — ALDA also handles binary frames (delegated to
                // the text handler). The server sends text, but a binary
                // frame must not be silently dropped.
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isCurrent(webSocket)) return
                    handleServerMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrent(webSocket)) return
                    connected = false
                    isSetupComplete = false
                    val code = response?.code
                    val msg = if (code != null) {
                        // v1.0.4: name the HTTP code (and reason phrase) so the
                        // error history can tell 401/403/404 apart from a
                        // bare transport failure.
                        "WebSocket failure (HTTP $code ${response.message}): ${t.message}"
                    } else {
                        "WebSocket failure (không có phản hồi HTTP): ${t.message ?: "lỗi mạng không rõ nguyên nhân"}"
                    }
                    Log.w(TAG, "Live socket failed: $msg")
                    try { listener.onError(msg) } catch (_: Exception) { }
                }

                // v1.0.4 — ALDA fires its disconnect path in onClosing,
                // not only in onClosed: the peer closing first is the
                // earliest signal. The service's reconnectPending guard
                // makes the following onClosed a no-op, so this cannot
                // double-handle.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket)) return
                    connected = false
                    isSetupComplete = false
                    Log.i(TAG, "Live socket closing: $code $reason")
                    try { listener.onClosed(code, reason) } catch (_: Exception) { }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket)) return
                    connected = false
                    isSetupComplete = false
                    // v1.0.4: pass code + reason through so the service can
                    // show "Live tự ngắt (mã 1006: …)" instead of a bare
                    // "tự ngắt kết nối".
                    try { listener.onClosed(code, reason) } catch (_: Exception) { }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket connect failed", e)
            connected = false
            isSetupComplete = false
            listener.onError(e.message ?: "WebSocket connect failed")
        }
    }

    /**
     * Queue one PCM block for sending (ALDA FIX36). Blocks are drained
     * oldest-first at [drainPerTick] per input tick; the queue holds at
     * most ~6 s so a long outage cannot build an unbounded backlog.
     */
    @Synchronized
    fun queueAudio(pcm16: ByteArray) {
        pendingAudio.addLast(pcm16)
        pendingAudioBytes += pcm16.size
        while (pendingAudioBytes > maxPendingBytes && pendingAudio.isNotEmpty()) {
            pendingAudioBytes -= pendingAudio.removeFirst().size
        }
    }

    /** Drain up to [maxBlocks] queued blocks, oldest first. A congested
     * socket stops the drain and the unsent blocks stay queued for the
     * next tick instead of being dropped. Returns blocks sent. */
    @Synchronized
    fun drainPending(maxBlocks: Int = drainPerTick): Int {
        if (!isSetupComplete) return 0
        var sent = 0
        while (sent < maxBlocks && pendingAudio.isNotEmpty()) {
            if (!sendAudioNow(pendingAudio.first())) break
            pendingAudioBytes -= pendingAudio.removeFirst().size
            sent++
        }
        return sent
    }

    @Synchronized
    fun clearPending() {
        pendingAudio.clear()
        pendingAudioBytes = 0
    }

    /**
     * Stream 16 kHz mono PCM to the model.
     * v1.0.4 — ALDA send path: the block is queued and the queue drains
     * 2 blocks per call (2x real-time) instead of blasting every chunk
     * straight onto the socket. v1.0.3 sent every chunk immediately and
     * flushed ~10 s at once on setupComplete, flooding the socket queue.
     * The wire shape stays realtimeInput.audio (ALDA / web script).
     */
    @Synchronized
    fun sendAudio(pcm16: ByteArray) {
        queueAudio(pcm16)
        drainPending()
    }

    /** Sends one block right now. Returns false when the socket is
     * congested (the caller keeps the block queued instead of dropping
     * it) or the session is not set up yet. */
    private fun sendAudioNow(pcm16: ByteArray): Boolean {
        val socket = socket ?: return false
        if (!isSetupComplete) return false
        // ALDA: drop stale packets when the socket queue is congested;
        // do not let latency grow without bound.
        if (socket.queueSize() > 64 * 1024L) {
            Log.w(TAG, "Dropping stale input audio: WebSocket queue is congested")
            return false
        }
        return try {
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
            false
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
        isSetupComplete = false
        clearPending()
        val oldSocket = socket
        socket = null
        connected = false
        try { oldSocket?.close(1000, "done") } catch (_: Exception) { }
    }

    // v1.0.4 — ALDA accepts snake_case AND camelCase keys for every field
    // (the server normally sends camelCase, but a variant must not break
    // parsing). Helpers keep the checks readable.
    private fun optObj(o: JSONObject, camel: String, snake: String): JSONObject? =
        o.optJSONObject(camel) ?: o.optJSONObject(snake)

    private fun hasKey(o: JSONObject, camel: String, snake: String): Boolean =
        o.has(camel) || o.has(snake)

    private fun optBool(o: JSONObject, camel: String, snake: String): Boolean =
        if (o.has(camel)) o.optBoolean(camel, false) else o.optBoolean(snake, false)

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            // ALDA's check order: serverContent -> setupComplete -> error.
            val content = optObj(root, "serverContent", "server_content")
            if (content != null) {
                // Source-language transcript: debug only, logged, not shown.
                val inTx = optObj(content, "inputTranscription", "input_transcription")
                if (inTx != null) {
                    val t = inTx.optString("text")
                    if (t.isNotEmpty()) {
                        Log.i(TAG, "inputTranscription: $t")
                        try { listener.onInputText(t) } catch (_: Exception) { }
                    }
                }
                val turn = optObj(content, "modelTurn", "model_turn")
                if (turn != null) {
                    val parts = turn.optJSONArray("parts") ?: org.json.JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inline = optObj(part, "inlineData", "inline_data")
                        if (inline != null) {
                            val mime = inline.optString("mime_type")
                                .ifEmpty { inline.optString("mimeType") }
                            if (mime.startsWith("audio/pcm")) {
                                val b64 = inline.optString("data")
                                if (b64.isNotEmpty()) {
                                    listener.onAudioChunk(Base64.decode(b64, Base64.DEFAULT))
                                }
                            }
                        }
                        val txt = part.optString("text")
                        if (txt.isNotEmpty()) listener.onTranslatedText(txt, false)
                    }
                }
                val outTx = optObj(content, "outputTranscription", "output_transcription")
                if (outTx != null) {
                    val t = outTx.optString("text")
                    // v1.0.21 — own callback (see Listener): the
                    // service needs the translated sentence per
                    // burst for the tx anchor; it must not be mixed
                    // with the model turn's text parts.
                    if (t.isNotEmpty()) listener.onOutputTranscription(t)
                }
                if (optBool(content, "turnComplete", "turn_complete")) {
                    listener.onTurnComplete()
                }
                return
            }
            if (hasKey(root, "setupComplete", "setup_complete")) {
                isSetupComplete = true
                listener.onConnected()
                return
            }
            if (root.has("error")) {
                // The server can reject the session inside a message instead of
                // closing the socket, e.g. {"error": {"code": 404, "message":
                // "models/... is not found"}}. v1.0.1 ignored this and stayed
                // silent forever; surface it as an error so the service can
                // retry with the fallback model / other key.
                val err = root.optJSONObject("error")
                val code = err?.optInt("code", 0) ?: 0
                val msg = err?.optString("message").orEmpty()
                    .ifEmpty { root.optString("error") }
                val detail = if (code != 0) "Lỗi server Live ($code): $msg" else "Lỗi server Live: $msg"
                Log.w(TAG, detail)
                try { listener.onError(detail) } catch (_: Exception) { }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad server message: ${e.message}")
        }
    }
}
