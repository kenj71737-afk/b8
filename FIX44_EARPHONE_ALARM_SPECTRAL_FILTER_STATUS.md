# FIX44 — Earphone routing for alarm audio + spectral music filter (v1.2.23, code 28)

## What the user asked (26 Nov 04:14, from the FIX40 build)
1. When listening with earphones, the audio that goes through the alarm
   channel must play through the earphones, not out of the phone speaker.
2. Filter background music by frequency spectrum, like DubLive.

Built on the FIX43 tree (v1.2.22/code 27). The user referenced the FIX40
APK in the message, but FIX43 already contains everything in FIX40 plus
FIX41–FIX43, so FIX44 is built on the current head — the user gets all
previous fixes plus these two.

## 1. Alarm audio through earphones

### Problem
In alarm mode (FIX29/FIX37/FIX41) the dub voice (AudioPlayerManager,
USAGE_ALARM) and the delayed background (BackgroundAudioPlayer,
USAGE_ALARM) play through the phone speaker even when earphones are
connected. Android routes USAGE_ALARM to the speaker by default — an
alarm must wake you even with earphones in — so the dub + background
leak out of the phone while the user listens on earphones.

### Fix — new `core/audio/HeadphoneRouter.kt`
- `findHeadphones(audioManager)`: first connected earphone-type output
  device, priority wired headset > wired headphones > USB headset >
  USB device > Bluetooth A2DP.
- `routeTrack(track, audioManager)`: pins the track to that device via
  `AudioTrack.setPreferredDevice` (API 31+; no-op below API 31 or with
  no earphones — system default routing applies, unchanged behaviour).
- `registerCallback / unregisterCallback`: an `AudioDeviceCallback`
  (main-thread handler) that re-routes the current track when output
  devices are added/removed, so plugging/unplugging earphones
  mid-session takes effect without restarting the dub.

### Wiring
- `AudioPlayerManager`: routes after the track is built in `start()`
  and again after the track is rebuilt in `switchToAlarmStream()` (a
  new track has no preferred device); callback registered in `start()`,
  unregistered in `stop()`.
- `BackgroundAudioPlayer`: same — routes after the track is built in
  `start()` (now holds an `AudioManager` from the context), callback
  unregistered in `stop()`.

### Known limits
- `setPreferredDevice` is the correct API for this, but the system may
  still override it for the alarm usage on some devices/ROMs. The user
  must verify on their phone: with earphones in, in alarm mode (external
  app or a CORS-blocking site), the dub voice and the background must
  come through the earphones only.
- Below Android 12 (API 31) there is no per-track routing API, so the
  old speaker behaviour remains on those devices.
- The non-alarm dub track (USAGE_ASSISTANT) already routes to earphones
  by the system default; the router is applied to it too (harmless).

## 2. Spectral music filter (port from DubLive v1.0.8)

### Problem
The energy threshold alone cannot tell loud music from loud speech:
turn it up and syllables get clipped; turn it down and background
music opens utterances and gets translated as words. DubLive v1.0.8
solved this with a spectral gate on the VAD speech start; ALDA's
SpeechSendGate (FIX30) had only the energy threshold.

### Fix — new `core/audio/SpectralGate.kt` (verbatim port of DubLive's)
512-point FFT; a 20 ms frame is speech-like only when:
- voice-band ratio (energy in 300–3400 Hz over total) >= 0.55, and
- spectral flatness (geometric / arithmetic mean of the power
  spectrum) < 0.5.
Pure Kotlin, no Android dependencies.

### Wiring into `SpeechSendGate`
- New `spectralGateEnabled` flag (default true) + `setSpectralGateEnabled()`.
- In `feed()`: a loud block is checked against the gate ONLY while the
  utterance has not started sending yet. A block that fails is dropped
  (not added to the pre-roll, does not advance the utterance); the
  utterance stays open on its earlier passing blocks, and the 450 ms
  silence timeout closes it if no more speech comes. Once the utterance
  is sending, the gate is not checked — music under dialogue never cuts
  a sentence. A single speech frame that fails the gate only delays the
  utterance start by one block.
- The gate runs on the block's first 20 ms frame (320 samples); shorter
  blocks are used whole (the gate zero-pads).
- Accepted limitation (same as DubLive): a tonal solo instrument sitting
  inside the voice band can still pass; the Live instruction +
  UtteranceFilter remain the last line of defence.

### Settings (same pattern as the FIX35 whole-sentence switch)
- New DataStore key `spectral_music_filter` (default true), Flow +
  `updateSpectralMusicFilter()` in UserPreferencesRepository.
- `spectralMusicFilter` StateFlow + updater in MainViewModel.
- MainScreen switch "Lọc nhạc nền bằng phổ tần số" (MusicNote icon),
  next to the whole-sentence card. Takes effect on the next dub start
  (the service reads it once at session start, like wholeSentence).
- The service passes the flag to all three `connect()` call sites
  (session start, key-rotation/stall reconnect, language change);
  `ALADWebSocketManager.connect(..., spectralMusicFilter = true)` stores
  it and calls `sendGate.setSpectralGateEnabled()` after `sendGate.reset()`.

## Files changed (vs FIX43)
- NEW `app/.../core/audio/SpectralGate.kt`
- NEW `app/.../core/audio/HeadphoneRouter.kt`
- `app/.../core/audio/SpeechSendGate.kt` (gate wiring)
- `app/.../core/network/ALADWebSocketManager.kt` (connect param)
- `app/.../core/service/AudioDubbingForegroundService.kt` (3 call sites)
- `app/.../data/repository/UserPreferencesRepository.kt` (pref)
- `app/.../presentation/main/MainViewModel.kt` (StateFlow)
- `app/.../presentation/main/MainScreen.kt` (switch)
- `app/.../core/audio/AudioPlayerManager.kt` (routing)
- `app/.../core/audio/BackgroundAudioPlayer.kt` (routing)
- `app/build.gradle.kts` (versionCode 28, versionName 1.2.23)
- This status doc.

## Verification
- Harness: `SpectralGateCheckMain` (speech-like harmonic passes; bass
  music + white noise + silence fail; pure tone passes — documented
  limitation) and the updated `NoiseFilterCheckMain` (speech blocks are
  now harmonic so they pass the gate; RMS-gate behaviour unchanged).
  Run in `~/workspace/alda-build-check/`.
- Real build via GitHub Actions (same pipeline as FIX39–FIX43).
- User verification needed:
  1. Earphones in, alarm mode (external app, or a CORS-blocking site):
     dub voice + background through the earphones only, nothing from
     the phone speaker. Plug/unplug mid-session: routing follows.
  2. A film with loud intro music: no translated words appear for the
     music; real dialogue still translates normally. Toggle the switch
     off to compare.
