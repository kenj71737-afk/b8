package com.dublive.live

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings: Gemini keys (typed by the user, stored only on the device),
 * Groq fallback key, source language, whole-sentence vs phrase mode,
 * voice, max video delay, and a key test button.
 * The ElevenLabs field is present but disabled: no account / no Visa yet,
 * it is only a placeholder for a future version.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var key1Input: EditText
    private lateinit var key2Input: EditText
    private lateinit var groqKeyInput: EditText
    private lateinit var sourceLangSpinner: Spinner
    private lateinit var wholeSentenceCheck: CheckBox
    private lateinit var voiceSpinner: Spinner
    private lateinit var maxDelaySeek: SeekBar
    private lateinit var maxDelayLabel: TextView
    private lateinit var testKeyResult: TextView

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        key1Input = findViewById(R.id.key1Input)
        key2Input = findViewById(R.id.key2Input)
        groqKeyInput = findViewById(R.id.groqKeyInput)
        sourceLangSpinner = findViewById(R.id.sourceLangSpinner)
        wholeSentenceCheck = findViewById(R.id.wholeSentenceCheck)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        maxDelaySeek = findViewById(R.id.maxDelaySeek)
        maxDelayLabel = findViewById(R.id.maxDelayLabel)
        testKeyResult = findViewById(R.id.testKeyResult)

        val prefs = Prefs.prefs(this)
        key1Input.setText(prefs.getString(Prefs.KEY_GEMINI_1, ""))
        key2Input.setText(prefs.getString(Prefs.KEY_GEMINI_2, ""))
        groqKeyInput.setText(prefs.getString(Prefs.KEY_GROQ, ""))
        wholeSentenceCheck.isChecked = Prefs.wholeSentence(this)

        sourceLangSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            Prefs.SOURCE_LANGS.keys.toList()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sourceLangSpinner.setSelection(
            Prefs.SOURCE_LANGS.keys.indexOf(Prefs.sourceLangLabel(this)).coerceAtLeast(0)
        )

        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            Prefs.VOICES.keys.toList()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val voiceLabel = Prefs.VOICES.entries
            .firstOrNull { it.value == Prefs.voiceName(this) }?.key
            ?: Prefs.VOICES.keys.first()
        voiceSpinner.setSelection(Prefs.VOICES.keys.indexOf(voiceLabel).coerceAtLeast(0))

        val initialDelay = (Prefs.maxDelayMs(this) / 1000).toInt()
        maxDelaySeek.progress = (initialDelay - 3).coerceIn(0, 17)
        maxDelayLabel.text = "$initialDelay giây"
        maxDelaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                maxDelayLabel.text = "${progress + 3} giây"
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        findViewById<Button>(R.id.testKeyButton).setOnClickListener { testKey() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun save() {
        val langLabel = sourceLangSpinner.selectedItem as String
        val voiceLabel = voiceSpinner.selectedItem as String
        Prefs.prefs(this).edit()
            .putString(Prefs.KEY_GEMINI_1, key1Input.text.toString().trim())
            .putString(Prefs.KEY_GEMINI_2, key2Input.text.toString().trim())
            .putString(Prefs.KEY_GROQ, groqKeyInput.text.toString().trim())
            .putString(Prefs.KEY_SOURCE_LANG, Prefs.SOURCE_LANGS[langLabel] ?: "auto")
            .putBoolean(Prefs.KEY_WHOLE_SENTENCE, wholeSentenceCheck.isChecked)
            .putString(Prefs.KEY_VOICE, Prefs.VOICES[voiceLabel] ?: "Aoede")
            .putInt(Prefs.KEY_MAX_DELAY_SEC, maxDelaySeek.progress + 3)
            .apply()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * v1.0.2: the old test only did GET v1beta/models — a key passes that
     * even when the Live model itself is dead, so the user thought the key
     * was fine while dubbing stayed silent (exactly what happened with
     * gemini-2.0-flash-live-001, HTTP 1008 / not found). The real test
     * opens a Live WebSocket with the current model and waits for
     * setupComplete (max 10 s). If the primary model is rejected, it
     * tries the fallback model once. Only if the socket cannot open at
     * all does it fall back to the models-list check, to tell a bad key
     * (401) apart from a dead model.
     */
    private fun testKey() {
        val key = key1Input.text.toString().trim()
            .ifEmpty { key2Input.text.toString().trim() }
        if (key.isEmpty()) {
            testKeyResult.text = getString(R.string.need_key_first)
            return
        }
        testKeyResult.text = getString(R.string.key_testing_live)
        val done = AtomicBoolean(false)
        val uiHandler = Handler(Looper.getMainLooper())
        val finish: (String) -> Unit = { msg ->
            if (done.compareAndSet(false, true)) {
                uiHandler.post { testKeyResult.text = msg }
            }
        }
        testLiveModel(key, GeminiLiveClient.DEFAULT_MODEL, finish) { modelError ->
            // Primary model rejected — try the fallback model once.
            testLiveModel(key, GeminiLiveClient.FALLBACK_MODEL, finish) { fallbackError ->
                // Both Live models failed. Distinguish a bad key from a
                // dead model with the models-list call as a secondary check.
                checkModelsList(key) { listOk, listMsg ->
                    if (listOk) {
                        finish(
                            getString(R.string.key_live_bad, "cả 2 model Live đều bị từ chối " +
                                "($modelError; $fallbackError). Key vẫn liệt kê được model, " +
                                "nghĩa là key tốt nhưng model Live đã đổi tên — cập nhật app.")
                        )
                    } else {
                        finish(getString(R.string.key_live_bad, listMsg))
                    }
                }
            }
        }
    }

    /** Open one Live session with [model]; calls [onResult] with the
     * success text on setupComplete, or [onErrorText] with the error. */
    private fun testLiveModel(
        key: String,
        model: String,
        onResult: (String) -> Unit,
        onErrorText: (String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val uiHandler = Handler(Looper.getMainLooper())
        val fail: (String) -> Unit = { msg ->
            if (finished.compareAndSet(false, true)) onErrorText(msg)
        }
        val clientRef = arrayOfNulls<GeminiLiveClient>(1)
        uiHandler.postDelayed({
            try { clientRef[0]?.close() } catch (_: Exception) { }
            fail("hết 10s chờ setupComplete ($model)")
        }, 10_000L)
        try {
            val client = GeminiLiveClient(
                apiKey = key,
                voiceName = Prefs.voiceName(this),
                sourceLang = Prefs.sourceLang(this),
                wholeSentence = false,
                listener = object : GeminiLiveClient.Listener {
                    override fun onConnected() {
                        try { clientRef[0]?.close() } catch (_: Exception) { }
                        if (finished.compareAndSet(false, true)) {
                            onResult(getString(R.string.key_live_ok, model))
                        }
                    }

                    override fun onAudioChunk(pcm24k: ByteArray) {}
                    override fun onTranslatedText(text: String, isFinal: Boolean) {}
                    override fun onTurnComplete() {}
                    override fun onError(message: String) {
                        try { clientRef[0]?.close() } catch (_: Exception) { }
                        fail("$model: $message")
                    }

                    override fun onClosed() {
                        fail("$model: kết nối Live tự đóng trước khi setup xong")
                    }
                }
            )
            clientRef[0] = client
            client.connect(model)
        } catch (e: Exception) {
            fail("$model: ${e.message}")
        }
    }

    /** Secondary check: can this key list models at all? */
    private fun checkModelsList(key: String, cb: (Boolean, String) -> Unit) {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key&pageSize=1")
            .get()
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb(false, "không gọi được API Gemini: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) cb(true, "")
                    else cb(false, "key lỗi (HTTP ${it.code})")
                }
            }
        })
    }
}
