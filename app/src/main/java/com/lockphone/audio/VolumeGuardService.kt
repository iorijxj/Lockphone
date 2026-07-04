package com.lockphone.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.lockphone.data.SettingsRepository
import com.lockphone.admin.LockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VolumeGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var audio: AudioManager
    private lateinit var lock: LockController
    private lateinit var repo: SettingsRepository
    private var volumeLocked = true

    private val handler = Handler(Looper.getMainLooper())

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = applyPolicy()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = applyPolicy()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = applyPolicy()
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lock = LockController(applicationContext)
        repo = SettingsRepository(applicationContext)
        startForeground(NOTIF_ID, buildNotification())
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        scope.launch {
            repo.volumeLocked.collect {
                volumeLocked = it
                applyPolicy()
            }
        }
    }

    private fun isBluetoothA2dpConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

    private fun applyPolicy() {
        when {
            !volumeLocked -> lock.setVolumeAdjustRestricted(false)
            isBluetoothA2dpConnected() -> {
                lock.setVolumeAdjustRestricted(false)
                clampMediaVolume()
            }
            else -> lock.setVolumeAdjustRestricted(true)
        }
    }

    private fun clampMediaVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = (max * MIN_FRACTION).roundToInt().coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur < min) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "音量守护", NotificationManager.IMPORTANCE_MIN,
        )
        mgr.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("音量守护运行中")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(volumeObserver)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "volume_guard"
        private const val NOTIF_ID = 1001
        private const val MIN_FRACTION = 0.5
    }
}
