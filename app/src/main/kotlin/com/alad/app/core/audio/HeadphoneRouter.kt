package com.alad.app.core.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * FIX44 — route the alarm-stream tracks (dub voice + delayed background)
 * to connected earphones instead of the phone speaker.
 *
 * Android routes USAGE_ALARM to the speaker by default (an alarm must
 * wake you even with earphones in), so in alarm mode the dub voice and
 * the delayed background come out of the phone speaker while the user
 * listens on earphones. [AudioTrack.setPreferredDevice] (API 31+) lets
 * a track pin itself to a specific output device; this helper finds the
 * connected earphones (wired headset/headphones, USB headset, then
 * Bluetooth A2DP) and pins the track to them. When no earphones are
 * connected it is a no-op and the system default routing applies.
 * Re-apply on every track rebuild (switchToAlarmStream) and on device
 * changes via [registerCallback], so plugging/unplugging earphones
 * mid-session takes effect without restarting the dub.
 */
object HeadphoneRouter {
    private const val TAG = "HeadphoneRouter"

    /** Earphone device types, in preference order. */
    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    )

    /** First connected earphone-type output device, or null. */
    fun findHeadphones(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (e: Exception) {
            Log.w(TAG, "getDevices failed", e)
            return null
        }
        for (type in PRIORITY) {
            val found = devices.firstOrNull { it.type == type }
            if (found != null) return found
        }
        return null
    }

    /**
     * Pin [track] to the connected earphones, if any (API 31+). Returns
     * true when the track was pinned; false when there are no earphones,
     * the API is unavailable, or the pin failed (the track then plays on
     * the system default route, unchanged behaviour).
     */
    fun routeTrack(track: AudioTrack?, audioManager: AudioManager): Boolean {
        if (track == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = findHeadphones(audioManager) ?: return false
        return try {
            val ok = track.setPreferredDevice(device)
            Log.i(TAG, "Routed track to ${device.productName} (type=${device.type}): $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed", e)
            false
        }
    }

    /**
     * Watch for earphones being plugged/unplugged; [onChange] runs on
     * the main thread. Returns the callback to pass to
     * [unregisterCallback], or null when the API is unavailable.
     */
    fun registerCallback(audioManager: AudioManager, onChange: () -> Unit): AudioDeviceCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onChange()
            }
        }
        return try {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            callback
        } catch (e: Exception) {
            Log.w(TAG, "registerAudioDeviceCallback failed", e)
            null
        }
    }

    fun unregisterCallback(audioManager: AudioManager?, callback: AudioDeviceCallback?) {
        if (audioManager == null || callback == null) return
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (_: Exception) {
        }
    }
}
