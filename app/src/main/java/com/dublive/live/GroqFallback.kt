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
 * Groq Whisper (speech-to-text) -> Gemini text API (translation) -> TTS voice.
 * The Groq key is optional; without it the service reports the Live error
 * and keeps retrying instead of silently inventing speech.
 */
class GroqFallback(
    private val groqKey: String,
    private val geminiKey: String,
    private val sourceLang: String
) {
    interface Listener {
        fun onTranslated(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "DubLive-Groq"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Transcribe one finished utterance (16 kHz mono PCM) then translate it. */
    fun transcribeAndTranslate(pcm16k: ByteArray, listener: Listener) {
        if (groqKey.isEmpty()) {
            listener.onError("Gemini Live lỗi và chưa có key Groq dự phòng")
            return
        }
        val wav = pcmToWav(pcm16k, 16000)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "utterance.wav",
                wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3")
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
                listener.onError("Whisper lỗi: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        listener.onError("Whisper HTTP ${it.code}")
                        return
                    }
                    val text = JSONObject(it.body?.string().orEmpty())
                        .optString("text").trim()
                    if (text.isEmpty() || !SpeechFilter.isMeaningful(text)) return
                    translate(text, listener)
                }
            }
        })
    }

    private fun translate(sourceText: String, listener: Listener) {
        if (geminiKey.isEmpty()) {
            listener.onError("Thiếu key Gemini để dịch text")
            return
        }
        val langHint = when (sourceLang) {
            "zh-CN" -> "tiếng Trung giản thể"
            "yue" -> "tiếng Quảng Đông"
            "en" -> "tiếng Anh"
            "ko" -> "tiếng Hàn"
            "ja" -> "tiếng Nhật"
            else -> "ngôn ngữ gốc"
        }
        val prompt = "Dịch đoạn $langHint sau sang tiếng Việt tự nhiên, giọng lồng tiếng " +
            "truyền hình, chỉ trả về bản dịch, không giải thích thêm: " + sourceText
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
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                listener.onError("Dịch text lỗi: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        listener.onError("Dịch text HTTP ${it.code}")
                        return
                    }
                    try {
                        val out = JSONObject(it.body?.string().orEmpty())
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text").trim()
                        if (SpeechFilter.isMeaningful(out)) listener.onTranslated(out)
                    } catch (e: Exception) {
                        listener.onError("Không đọc được bản dịch: ${e.message}")
                    }
                }
            }
        })
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
