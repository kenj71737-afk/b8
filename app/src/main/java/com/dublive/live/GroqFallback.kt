package com.dublive.live

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Fallback pipeline used only when Gemini Live fails:
 * Groq Whisper (speech-to-text) -> translation -> TTS voice.
 *
 * v1.0.3 lessons, from real user screenshots:
 * - The Whisper step WORKS with "whisper-large-v3": the user's error came
 *   AFTER it, at the translate step ("Dịch text HTTP 404"), so v3 stays
 *   primary. Turbo ("whisper-large-v3-turbo", Groq's current recommended
 *   transcription model) is only a retry if Whisper answers 400/404 for
 *   the model id.
 * - The translate step used to call Gemini generateContent with
 *   gemini-2.5-flash and died with HTTP 404 — that model id is not found
 *   with this key, the same failure mode as the dead Live 2.0 model.
 *   Translation now goes through GROQ first (the Groq key is present and
 *   Whisper just proved it works): chat completions with
 *   openai/gpt-oss-120b, then llama-3.3-70b-versatile. Only if both fail
 *   does it fall back to Gemini text models, in order gemini-2.5-flash ->
 *   gemini-2.0-flash -> gemini-1.5-flash, never stopping at the first 404.
 * - Every step reports its failures (step + HTTP code + model tried) via
 *   onError (terminal) or onProgress (retrying with the next model), so a
 *   failure is never silent.
 * - Keys are mutable: the service refreshes them (updateKeys) right
 *   before each call, so a Groq key typed while the service is running
 *   takes effect without a full restart.
 */
class GroqFallback(
    private var groqKey: String,
    private var geminiKey: String,
    private val sourceLang: String
) {
    interface Listener {
        fun onTranslated(text: String)
        fun onError(message: String)
        /** Non-fatal status update (e.g. retrying with the next model). */
        fun onProgress(message: String) {}
    }

    companion object {
        private const val TAG = "DubLive-Groq"
        private val WHISPER_MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo")
        private val GROQ_CHAT_MODELS = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile")
        private val GEMINI_TEXT_MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Refresh keys from Settings (called before each use). */
    fun updateKeys(groqKey: String, geminiKey: String) {
        this.groqKey = groqKey
        this.geminiKey = geminiKey
    }

    /** Transcribe one finished utterance (16 kHz mono PCM) then translate it. */
    fun transcribeAndTranslate(pcm16k: ByteArray, listener: Listener) {
        if (groqKey.isEmpty()) {
            listener.onError("Chưa có key Groq — nhập key Groq trong Cài đặt rồi Dừng lồng tiếng hẳn và Bắt đầu lại để key mới có tác dụng")
            return
        }
        transcribeWithModel(pcm16k, listener, 0)
    }

    private fun transcribeWithModel(pcm16k: ByteArray, listener: Listener, modelIndex: Int) {
        val model = WHISPER_MODELS[modelIndex]
        val wav = pcmToWav(pcm16k, 16000)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "utterance.wav",
                wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .build()
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $groqKey")
            .post(body)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Whisper failed: ${e.message}")
                listener.onError("Whisper lỗi ($model): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val code = it.code
                        // Model id rejected (400/404): try the Turbo model once.
                        if ((code == 400 || code == 404) && modelIndex + 1 < WHISPER_MODELS.size) {
                            listener.onProgress("Whisper $model HTTP $code, thử ${WHISPER_MODELS[modelIndex + 1]}…")
                            transcribeWithModel(pcm16k, listener, modelIndex + 1)
                            return
                        }
                        listener.onError("Whisper HTTP $code (model $model)")
                        return
                    }
                    val text = JSONObject(it.body?.string().orEmpty())
                        .optString("text").trim()
                    if (text.isEmpty()) {
                        listener.onError("Whisper trả về rỗng (model $model) — không nghe rõ lời")
                        return
                    }
                    if (!SpeechFilter.isMeaningful(text)) {
                        listener.onError("Groq nghe được nhưng câu không có nghĩa nên bỏ qua (model $model): \"${text.take(60)}\"")
                        return
                    }
                    translate(text, listener)
                }
            }
        })
    }

    /** Translate via Groq chat first, then Gemini text models. */
    private fun translate(sourceText: String, listener: Listener) {
        translateViaGroq(sourceText, listener, 0, mutableListOf())
    }

    private fun translateViaGroq(
        sourceText: String,
        listener: Listener,
        modelIndex: Int,
        errors: MutableList<String>
    ) {
        if (modelIndex >= GROQ_CHAT_MODELS.size) {
            translateViaGemini(sourceText, listener, 0, errors)
            return
        }
        val model = GROQ_CHAT_MODELS[modelIndex]
        val prompt = "Dịch đoạn ${langHint()} sau sang tiếng Việt tự nhiên, giọng lồng tiếng " +
            "truyền hình, chỉ trả về bản dịch, không giải thích thêm: $sourceText"
        val json = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            ))
            put("temperature", 0.3)
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $groqKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errors.add("Dịch Groq $model lỗi mạng: ${e.message}")
                listener.onProgress("Dịch Groq $model lỗi mạng, thử ${nextGroqOrGemini(modelIndex)}…")
                translateViaGroq(sourceText, listener, modelIndex + 1, errors)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        errors.add("Dịch Groq $model HTTP ${it.code}")
                        listener.onProgress("Dịch Groq $model HTTP ${it.code}, thử ${nextGroqOrGemini(modelIndex)}…")
                        translateViaGroq(sourceText, listener, modelIndex + 1, errors)
                        return
                    }
                    try {
                        val out = JSONObject(it.body?.string().orEmpty())
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                        if (out.isEmpty() || !SpeechFilter.isMeaningful(out)) {
                            errors.add("Dịch Groq $model trả về rỗng/không có nghĩa")
                            listener.onProgress("Dịch Groq $model trả về rỗng, thử ${nextGroqOrGemini(modelIndex)}…")
                            translateViaGroq(sourceText, listener, modelIndex + 1, errors)
                            return
                        }
                        listener.onTranslated(out)
                    } catch (e: Exception) {
                        errors.add("Dịch Groq $model không đọc được bản dịch: ${e.message}")
                        listener.onProgress("Dịch Groq $model lỗi đọc kết quả, thử ${nextGroqOrGemini(modelIndex)}…")
                        translateViaGroq(sourceText, listener, modelIndex + 1, errors)
                    }
                }
            }
        })
    }

    private fun nextGroqOrGemini(groqIndex: Int): String =
        if (groqIndex + 1 < GROQ_CHAT_MODELS.size) "Groq ${GROQ_CHAT_MODELS[groqIndex + 1]}"
        else "Gemini ${GEMINI_TEXT_MODELS[0]}"

    private fun translateViaGemini(
        sourceText: String,
        listener: Listener,
        modelIndex: Int,
        groqErrors: MutableList<String>
    ) {
        if (geminiKey.isEmpty()) {
            listener.onError(
                (groqErrors + "Thiếu key Gemini để dịch text").joinToString("; ")
            )
            return
        }
        if (modelIndex >= GEMINI_TEXT_MODELS.size) {
            listener.onError(
                (groqErrors + "Dịch Gemini lỗi cả 3 model (2.5-flash, 2.0-flash, 1.5-flash)").joinToString("; ")
            )
            return
        }
        val model = GEMINI_TEXT_MODELS[modelIndex]
        val prompt = "Dịch đoạn ${langHint()} sau sang tiếng Việt tự nhiên, giọng lồng tiếng " +
            "truyền hình, chỉ trả về bản dịch, không giải thích thêm: $sourceText"
        val json = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().apply {
                    put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                }
            ))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$geminiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                listener.onProgress("Dịch Gemini $model lỗi mạng, thử ${GEMINI_TEXT_MODELS.getOrNull(modelIndex + 1) ?: "hết model"}…")
                translateViaGemini(sourceText, listener, modelIndex + 1, groqErrors)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        listener.onProgress("Dịch text $model HTTP ${it.code}, thử ${GEMINI_TEXT_MODELS.getOrNull(modelIndex + 1) ?: "hết model"}…")
                        translateViaGemini(sourceText, listener, modelIndex + 1, groqErrors)
                        return
                    }
                    try {
                        val out = JSONObject(it.body?.string().orEmpty())
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text").trim()
                        if (SpeechFilter.isMeaningful(out)) {
                            listener.onTranslated(out)
                        } else {
                            listener.onError("Dịch Gemini $model trả về rỗng/không có nghĩa; " + groqErrors.joinToString("; "))
                        }
                    } catch (e: Exception) {
                        listener.onError("Không đọc được bản dịch Gemini $model: ${e.message}; " + groqErrors.joinToString("; "))
                    }
                }
            }
        })
    }

    private fun langHint(): String = when (sourceLang) {
        "zh-CN" -> "tiếng Trung giản thể"
        "yue" -> "tiếng Quảng Đông"
        "en" -> "tiếng Anh"
        "ko" -> "tiếng Hàn"
        "ja" -> "tiếng Nhật"
        else -> "ngôn ngữ gốc"
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2) // block align
        header.putShort(16) // bits
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
