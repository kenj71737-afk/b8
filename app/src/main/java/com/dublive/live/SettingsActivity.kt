package com.dublive.live

import android.os.Bundle
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

    private fun testKey() {
        val key = key1Input.text.toString().trim()
            .ifEmpty { key2Input.text.toString().trim() }
        if (key.isEmpty()) {
            testKeyResult.text = getString(R.string.need_key_first)
            return
        }
        testKeyResult.text = "Đang kiểm tra…"
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key&pageSize=1")
            .get()
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { testKeyResult.text = "${getString(R.string.key_bad)}: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    runOnUiThread {
                        testKeyResult.text = if (it.isSuccessful) {
                            getString(R.string.key_ok)
                        } else {
                            "${getString(R.string.key_bad)} (HTTP ${it.code})"
                        }
                    }
                }
            }
        })
    }
}
