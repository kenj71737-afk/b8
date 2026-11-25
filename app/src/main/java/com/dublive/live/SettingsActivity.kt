package com.dublive.live

import android.content.Intent
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
    /** v1.0.7 — "Trễ hình cơ bản": seed delay at session start. */
    private lateinit var baseDelaySeek: SeekBar
    private lateinit var baseDelayLabel: TextView
    private lateinit var originalVolumeSeek: SeekBar
    private lateinit var originalVolumeLabel: TextView
    private lateinit var dubVolumeSeek: SeekBar
    private lateinit var dubVolumeLabel: TextView
    private lateinit var vadThresholdSeek: SeekBar
    private lateinit var vadThresholdLabel: TextView
    /** v1.0.8 — spectral gate toggle (default ON). */
    private lateinit var spectralGateCheck: CheckBox
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
        baseDelaySeek = findViewById(R.id.baseDelaySeek)
        baseDelayLabel = findViewById(R.id.baseDelayLabel)
        originalVolumeSeek = findViewById(R.id.originalVolumeSeek)
        originalVolumeLabel = findViewById(R.id.originalVolumeLabel)
        dubVolumeSeek = findViewById(R.id.dubVolumeSeek)
        dubVolumeLabel = findViewById(R.id.dubVolumeLabel)
        vadThresholdSeek = findViewById(R.id.vadThresholdSeek)
        vadThresholdLabel = findViewById(R.id.vadThresholdLabel)
        spectralGateCheck = findViewById(R.id.spectralGateCheck)
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

        // v1.0.7 — base delay seed (3–20 s, default 5 s). The tracker
        // starts here and FIFO samples move it from there; the "Trễ
        // −1s / +1s" buttons on the main screen nudge the same value
        // mid-session (and update this seed when not dubbing).
        val initialBase = (Prefs.baseDelayMs(this) / 1000).toInt()
        baseDelaySeek.progress = (initialBase - 3).coerceIn(0, 17)
        baseDelayLabel.text = "$initialBase giây"
        baseDelaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                baseDelayLabel.text = "${progress + 3} giây"
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        // v1.0.5 — three new sliders. Original volume: the level the
        // film's own sound drops to WHILE the dub voice speaks (default
        // 60%, 0–100%). Dub volume: the dubbed voice itself (default
        // 90%). VAD threshold: SeekBar progress 0–950 maps to RMS
        // 250–1200 (default 600, ALDA's value); higher = fewer noise
        // utterances from background music.
        originalVolumeSeek.progress = Prefs.originalVolumePct(this)
        originalVolumeLabel.text = "${Prefs.originalVolumePct(this)}%"
        originalVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                originalVolumeLabel.text = "$progress%"
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        dubVolumeSeek.progress = Prefs.dubVolumePct(this)
        dubVolumeLabel.text = "${Prefs.dubVolumePct(this)}%"
        dubVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                dubVolumeLabel.text = "$progress%"
                // v1.0.6 — the slider drives the SYSTEM volume of the
                // stream the voice is on, directly: save at once and
                // apply at once, so dragging it changes the dub
                // loudness immediately, no restart needed (works even
                // mid-dubbing).
                // v1.0.11 — go through the service, not the call stream
                // directly: the voice is on MEDIA in the tap modes and
                // on the call stream in capture/mic, and only the
                // service knows which one is live right now.
                if (fromUser) {
                    Prefs.prefs(this@SettingsActivity).edit()
                        .putInt(Prefs.KEY_DUB_VOLUME, progress.coerceIn(0, 100)).apply()
                    try {
                        val intent = Intent(this@SettingsActivity, DubbingService::class.java).apply {
                            action = DubbingService.ACTION_APPLY_DUB_VOLUME
                            putExtra(DubbingService.EXTRA_VOLUME_PCT, progress.coerceIn(0, 100))
                        }
                        startService(intent)
                    } catch (_: Exception) { }
                }
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        // v1.0.5: progress 0..950 maps to threshold 250..1200.
        // v1.0.8: range narrowed to 250..900 (progress 0..650) —
        // 1000–1200 always choked speech (quiet syllables fell below
        // it and the VAD cut sentences mid-way, the 20:37 report).
        // The watchdog re-reads the saved value every 10 s, so this
        // takes effect mid-session without restarting the dub.
        vadThresholdSeek.progress = (Prefs.vadThreshold(this) - 250).coerceIn(0, 650)
        vadThresholdLabel.text = "${Prefs.vadThreshold(this)}"
        vadThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                vadThresholdLabel.text = "${progress + 250}"
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        // v1.0.8 — spectral gate toggle (default ON): filter background
        // music by spectrum at utterance start. Saved below; the
        // watchdog picks it up live, like the threshold.
        spectralGateCheck.isChecked = Prefs.spectralGateEnabled(this)

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
            .putInt(Prefs.KEY_BASE_DELAY_SEC, baseDelaySeek.progress + 3)
            .putInt(Prefs.KEY_ORIGINAL_VOLUME, originalVolumeSeek.progress.coerceIn(0, 100))
            .putInt(Prefs.KEY_DUB_VOLUME, dubVolumeSeek.progress.coerceIn(0, 100))
            .putInt(Prefs.KEY_VAD_THRESHOLD, (vadThresholdSeek.progress + 250).coerceIn(250, 900))
            .putBoolean(Prefs.KEY_SPECTRAL_GATE, spectralGateCheck.isChecked)
            .apply()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * v1.0.3: tests the models in the same order the service tries them —
     * gemini-3.5-live-translate-preview first (the model ALDA uses with
     * this key), then the two native-audio fallbacks. Each attempt opens
     * a real Live WebSocket and waits for setupComplete (max 10 s), and
     * reports exactly which model failed with what error. v1.0.2 only
     * tried the two native-audio models, so it could report "Live nối
     * được" while the dub still failed — the service now uses the
     * translate model first, so the test must check it first. Only if
     * every model fails does the models-list call run, to tell a bad key
     * (401) apart from dead models.
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
        val errors = mutableListOf<String>()
        fun tryNext(index: Int) {
            if (index >= GeminiLiveClient.LIVE_MODELS.size) {
                // Every Live model failed. Distinguish a bad key from
                // dead models with the models-list call as a secondary check.
                checkModelsList(key) { listOk, listMsg ->
                    if (listOk) {
                        finish(
                            getString(R.string.key_live_bad, "cả 3 model Live đều bị từ chối " +
                                "(${errors.joinToString("; ")}). Key vẫn liệt kê được model, " +
                                "nghĩa là key tốt nhưng model Live đã đổi tên — cập nhật app.")
                        )
                    } else {
                        finish(getString(R.string.key_live_bad, listMsg))
                    }
                }
                return
            }
            testLiveModel(key, GeminiLiveClient.LIVE_MODELS[index], finish) { modelError ->
                errors.add(modelError)
                tryNext(index + 1)
            }
        }
        tryNext(0)
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

                    override fun onClosed(code: Int, reason: String) {
                        fail("$model: kết nối Live tự đóng trước khi setup xong (mã $code: ${reason.ifBlank { "không rõ lý do" }})")
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
