package com.discodo.app.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays the user-selected incoming call ringtone and repeating phone-call vibration
 * until stopped explicitly, dismissed from the notification, or after [RING_TIMEOUT_MS].
 */
class CallAlertController(
  context: Context,
  private val onRingTimeout: (channelId: String) -> Unit = {},
) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val lock = Any()

  private var mediaPlayer: MediaPlayer? = null
  private var timeoutJob: Job? = null
  private var activeChannelId: String? = null

  fun startRinging(channelId: String, customRingtoneUri: String?) {
    stopRinging()
    synchronized(lock) {
      activeChannelId = channelId
    }

    val audioManager = appContext.getSystemService(AudioManager::class.java)
    val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
      scheduleTimeout(channelId)
      return
    }

    startCallVibration()

    if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
      startRingtonePlayer(customRingtoneUri)
    }

    scheduleTimeout(channelId)
  }

  fun stopRinging(channelId: String? = null) {
    val playerToStop: MediaPlayer?
    synchronized(lock) {
      if (channelId != null && activeChannelId != null && activeChannelId != channelId) {
        return
      }
      activeChannelId = null
      timeoutJob?.cancel()
      timeoutJob = null
      playerToStop = mediaPlayer
      mediaPlayer = null
    }

    runCatching {
      playerToStop?.stop()
    }
    runCatching {
      playerToStop?.release()
    }
    stopVibration()
  }

  private fun scheduleTimeout(channelId: String) {
    synchronized(lock) {
      timeoutJob?.cancel()
      timeoutJob =
        scope.launch {
          delay(RING_TIMEOUT_MS)
          val stillActive = synchronized(lock) { activeChannelId == channelId }
          if (stillActive) {
            stopRinging(channelId)
            onRingTimeout(channelId)
          }
        }
    }
  }

  private fun startRingtonePlayer(customUriString: String?) {
    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    val chosenUri = customUriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: defaultUri

    if (!tryStartMediaPlayer(chosenUri) && chosenUri != defaultUri && defaultUri != null) {
      tryStartMediaPlayer(defaultUri)
    }
  }

  private fun tryStartMediaPlayer(uri: Uri?): Boolean {
    if (uri == null) return false
    return runCatching {
        val player =
          MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            )
            setDataSource(appContext, uri)
            isLooping = true
            prepare()
            start()
          }
        synchronized(lock) { mediaPlayer = player }
        true
      }
      .getOrDefault(false)
  }

  private fun startCallVibration() {
    val vibrator = getVibrator() ?: return
    if (!vibrator.hasVibrator()) return

    val effect = VibrationEffect.createWaveform(CALL_VIBRATION_PATTERN, 0)
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attrs =
          VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_RINGTONE)
            .build()
        vibrator.vibrate(effect, attrs)
      } else {
        @Suppress("DEPRECATION")
        val audioAttrs =
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, audioAttrs)
      }
    }
  }

  private fun stopVibration() {
    runCatching { getVibrator()?.cancel() }
  }

  private fun getVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      appContext.getSystemService(Vibrator::class.java)
    }
  }

  companion object {
    private const val RING_TIMEOUT_MS = 45_000L
    private val CALL_VIBRATION_PATTERN = longArrayOf(0L, 1000L, 850L)
  }
}
