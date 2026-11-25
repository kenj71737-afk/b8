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
    }

    companion object {
        private const val TAG = "DubLive-Gemini"
        const val DEFAULT_MODEL = "gemini-2.0-flash-live-001"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile var connected = false
        private set

    fun connect(model: String = DEFAULT_MODEL) {
        val langHint = when (sourceLang) {
            "zh-CN" -> "Tiếng Trung giản thể"
            "yue" -> "tiếng Quảng Đông"
            "en" -> "tiếng Anh"
            "ko" -> "tiếng Hàn"
            "ja" -> "tiếng Nhật"
            else -> "ngôn ngữ gốc của video (tự nhận diện)"
        }
        val modeHint = if (wholeSentence) {
            "Chờ nghe hết một câu hoàn chỉnh rồi mới dịch cả câu, dịch đúng ngữ cảnh hơn."
        } else {
            "Dịch theo từng cụm ngắn ngay khi nghe rõ cụm đó, không chờ hết câu, để ra giọng nhanh nhất."
        }
        val instruction = "Bạn là phiên dịch viên lồng tiếng phim. Nghe $langHint trong video và " +
            "nói lại bằng tiếng Việt tự nhiên, giọng lồng tiếng truyền hình ấm áp, rõ ràng, " +
            "khớp đúng lúc nhân vật mở miệng và nói hết trong đúng thời lượng câu gốc. $modeHint " +
            "Chỉ dịch câu có nghĩa. Nhân vật chỉ kêu, rên, cười, ho, ậm ừ hoặc nhạc không lời " +
            "thì im lặng hoàn toàn, tuyệt đối không bịa thêm lời. Nghe không rõ chữ thì im, không đoán."
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$model")
                put("generation_config", JSONObject().apply {
                    put("response_modalities", org.json.JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                })
                put("system_instruction", JSONObject().apply {
                    put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", instruction)
                    ))
                })
                put("input_audio_transcription", JSONObject())
                put("output_audio_transcription", JSONObject())
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
                Log.w(TAG, "Live socket failed: ${t.message}")
                try { listener.onError(t.message ?: "WebSocket failure") } catch (_: Exception) { }
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

    /** Stream 16 kHz mono PCM to the model. */
    fun sendAudio(pcm16: ByteArray) {
        val socket = socket ?: return
        if (!connected) return
        try {
            val payload = JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("media_chunks", org.json.JSONArray().put(
                        JSONObject().apply {
                            put("mime_type", "audio/pcm;rate=16000")
                            put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
                        }
                    ))
                })
            }
            socket.send(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendAudio failed", e)
        }
    }

    /** Signal end of the current utterance so the model can flush a translation. */
    fun endTurn() {
        val socket = socket ?: return
        if (!connected) return
        try {
            socket.send(JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("audio_stream_end", true)
                })
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "endTurn failed", e)
        }
    }

    fun close() {
        try { socket?.close(1000, "done") } catch (_: Exception) { }
        socket = null
        connected = false
    }

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (root.has("setupComplete")) {
                listener.onConnected()
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
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
