package com.discodo.app.notifications

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.discodo.app.DiscodoApplication

/**
 * Trampoline activity invoked when the user taps or answers an incoming call notification.
 * Stops the looping ringtone/vibration, dismisses the call notification, and launches
 * the Discord/Valenced deep link cleanly without background activity launch restrictions.
 */
class CallNotificationClickActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val app = application as DiscodoApplication
    val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
    val deepLink = intent.getStringExtra(EXTRA_DEEP_LINK) ?: "https://discord.com/channels/@me"

    if (!channelId.isNullOrBlank()) {
      app.notificationHelper.cancelCallNotification(channelId)
    } else {
      app.callAlertController.stopRinging()
    }

    val viewIntent =
      Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    runCatching { startActivity(viewIntent) }
    finish()
  }

  companion object {
    const val EXTRA_CHANNEL_ID = "extra_channel_id"
    const val EXTRA_DEEP_LINK = "extra_deep_link"
  }
}
