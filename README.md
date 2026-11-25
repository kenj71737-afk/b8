# DubLive — Live video dubbing to Vietnamese

Fresh Android app (Kotlin, minSdk 26, targetSdk 34) that dubs live video
into Vietnamese. Written from scratch; it shares no code with the old
ALDA/ALAD app, only the lessons learned from its failures.

## Sources (all three)
1. **Web films** — open any film site in the in-app WebView.
2. **YouTube** — open youtube.com in the same WebView.
3. **Local files** — pick a video on the device, played with ExoPlayer.

## How it works
- **Audio capture:** Android 10+ AudioPlaybackCapture via MediaProjection
  (system audio, no WebAudio tap — the old WebAudio approach went silent
  on cross-origin video without CORS). Mic fallback with an on-screen note.
- **Translation:** Gemini Live speech-to-speech (Vietnamese). Watchdog:
  silent-key death after ~5 min is detected (no translated output for 60 s)
  and the session reconnects (max 5 times); keys rotate every 5 minutes.
  Fallback: Groq Whisper -> Gemini text translation -> TTS.
- **Video delay (the core):** the dubbed voice lags the picture by 2-5 s,
  so the picture is delayed to wait for the voice (canvas frame queue in
  the WebView; frame queue over ExoPlayer for local files). Delay is
  measured per utterance AND sampled every 2 s while a translation is in
  flight (periodic sampling — the old app only sampled at burst starts and
  its delay froze at 5.238 s on fast Cantonese dialogue). EMA 30%,
  clamped to 3-20 s. Current delay is shown on a one-line HUD.
- **Filtering:** VAD only sends real speech (>= 150 ms, 500 ms pre-roll);
  cries, groans, laughter, coughs and music-only lines are dropped, and
  the app stays silent rather than inventing words.
- **Voice + subtitles:** Gemini voice (Aoede/Puck/Charon) or Vietnamese
  TTS; reading speeds up (1.2x past 2 s lag, 1.35x past 4 s). Vietnamese
  subtitles overlay the video, shown as the voice starts. Original audio
  ducked to 15% while dubbing.

## Settings
Gemini Key 1 / Key 2 (typed by the user, stored only on the device, never
in the repo), optional Groq key, ElevenLabs key slot (placeholder, unused
in 1.0.0 — no account yet), source language, whole-sentence vs phrase
mode (phrase mode is the default: fast Cantonese dialogue), voice,
max delay slider, key test button.

## Build
GitHub Actions builds the APK (`./gradlew assembleDebug assembleRelease`).
This machine cannot run Gradle (sandbox blocks Java TCP), so the APK is
always built by Actions. See BUILD_NOTES.md.
