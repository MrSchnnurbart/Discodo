package com.discodo.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.discodo.app.MainActivity
import com.discodo.app.R
import com.discodo.app.data.GatewayStatus
import com.discodo.app.gateway.IncomingCallPayload
import com.discodo.app.gateway.NotificationCategory
import com.discodo.app.gateway.NotificationPayload
import com.discodo.app.service.DiscordGatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationHelper(
  private val context: Context,
  private val avatarLoader: AvatarLoader = AvatarLoader(),
) {

  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  val callAlertController: CallAlertController by lazy {
    CallAlertController(context) { timedOutChannelId ->
      NotificationManagerCompat.from(context).cancel(callNotificationId(timedOutChannelId))
    }
  }

  fun ensureChannelsCreated() {
    val serviceChannel =
      NotificationChannel(
          CHANNEL_SERVICE_STATUS,
          context.getString(R.string.channel_service_name),
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = context.getString(R.string.channel_service_desc)
          setShowBadge(false)
          enableLights(false)
          enableVibration(false)
          setSound(null, null)
        }

    val dmChannel =
      NotificationChannel(
          CHANNEL_DM,
          context.getString(R.string.channel_dm_name),
          NotificationManager.IMPORTANCE_HIGH,
        )
        .apply {
          description = context.getString(R.string.channel_dm_desc)
          enableVibration(true)
          vibrationPattern = STRONG_VIBRATION_PATTERN
        }

    val mentionsChannel =
      NotificationChannel(
          CHANNEL_MENTIONS,
          context.getString(R.string.channel_mentions_name),
          NotificationManager.IMPORTANCE_HIGH,
        )
        .apply {
          description = context.getString(R.string.channel_mentions_desc)
          enableVibration(true)
          vibrationPattern = STRONG_VIBRATION_PATTERN
        }

    val everyoneChannel =
      NotificationChannel(
          CHANNEL_EVERYONE,
          context.getString(R.string.channel_everyone_name),
          NotificationManager.IMPORTANCE_DEFAULT,
        )
        .apply {
          description = context.getString(R.string.channel_everyone_desc)
          enableVibration(true)
          vibrationPattern = SOFT_VIBRATION_PATTERN
        }

    // Channel sound/vibration are silenced because CallAlertController plays the user's
    // selected looping ringtone and call vibration pattern directly.
    val callsChannel =
      NotificationChannel(
          CHANNEL_CALLS,
          context.getString(R.string.channel_calls_name),
          NotificationManager.IMPORTANCE_HIGH,
        )
        .apply {
          description = context.getString(R.string.channel_calls_desc)
          setSound(null, null)
          enableVibration(false)
        }

    notificationManager.createNotificationChannels(
      listOf(serviceChannel, dmChannel, mentionsChannel, everyoneChannel, callsChannel)
    )
  }

  fun buildServiceNotification(status: GatewayStatus): Notification {
    ensureChannelsCreated()

    val openAppIntent =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      Intent(context, DiscordGatewayService::class.java).apply {
        action = DiscordGatewayService.ACTION_STOP
      }
    val stopPendingIntent =
      PendingIntent.getService(
        context,
        REQUEST_STOP_SERVICE,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val statusText =
      when (status) {
        GatewayStatus.Disconnected -> "Déconnecté"
        GatewayStatus.Connecting -> "Connexion au Gateway Discord…"
        is GatewayStatus.Connected -> "Connecté en tant que ${status.username}"
        is GatewayStatus.Reconnecting ->
          "Reconnexion (#${status.attempt}, ${status.delaySeconds}s)…"
        is GatewayStatus.Error -> "Erreur : ${status.message}"
      }

    return NotificationCompat.Builder(context, CHANNEL_SERVICE_STATUS)
      .setSmallIcon(R.drawable.ic_stat_notidisc)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(statusText)
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openAppPendingIntent)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, context.getString(R.string.action_stop), stopPendingIntent)
      .build()
  }

  @SuppressLint("MissingPermission")
  fun updateServiceNotification(status: GatewayStatus) {
    if (!hasNotificationPermission()) return
    runCatching {
      NotificationManagerCompat.from(context)
        .notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(status))
    }
  }

  fun showMessageNotification(payload: NotificationPayload) {
    if (!hasNotificationPermission()) return
    ensureChannelsCreated()

    if (payload.category == NotificationCategory.DIRECT_MESSAGE) {
      ioScope.launch {
        val avatar = avatarLoader.loadCircularAvatar(payload.authorAvatarUrl)
        postMessageNotification(payload, avatar)
      }
    } else {
      postMessageNotification(payload, avatarBitmap = null)
    }
  }

  @SuppressLint("MissingPermission")
  private fun postMessageNotification(payload: NotificationPayload, avatarBitmap: Bitmap?) {
    if (!hasNotificationPermission()) return

    val channelId =
      when (payload.category) {
        NotificationCategory.DIRECT_MESSAGE -> CHANNEL_DM
        NotificationCategory.MENTION -> CHANNEL_MENTIONS
        NotificationCategory.EVERYONE,
        NotificationCategory.ROLE_MENTION -> CHANNEL_EVERYONE
      }

    val priority =
      when (payload.category) {
        NotificationCategory.DIRECT_MESSAGE,
        NotificationCategory.MENTION -> NotificationCompat.PRIORITY_HIGH
        NotificationCategory.EVERYONE,
        NotificationCategory.ROLE_MENTION -> NotificationCompat.PRIORITY_DEFAULT
      }

    val viewIntent =
      Intent(Intent.ACTION_VIEW, Uri.parse(payload.deepLinkUrl)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }

    val notificationId = payload.messageId.hashCode()
    val pendingIntent =
      PendingIntent.getActivity(
        context,
        notificationId,
        viewIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_notidisc)
        .setPriority(priority)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setGroup("discodo_${payload.channelId}")

    if (payload.category == NotificationCategory.DIRECT_MESSAGE) {
      val senderBuilder =
        Person.Builder()
          .setName(payload.authorDisplayName)
          .setKey(payload.authorDisplayName)
      if (avatarBitmap != null) {
        senderBuilder.setIcon(IconCompat.createWithBitmap(avatarBitmap))
        builder.setLargeIcon(avatarBitmap)
      }
      val sender = senderBuilder.build()

      val messagingStyle =
        NotificationCompat.MessagingStyle(sender)
          .addMessage(payload.bodyText, System.currentTimeMillis(), sender)

      val groupName = payload.channelName?.takeIf { it.isNotBlank() }
      if (groupName != null) {
        messagingStyle.setGroupConversation(true).setConversationTitle(groupName)
      }

      builder
        .setContentTitle(payload.authorDisplayName)
        .setContentText(payload.bodyText)
        .setSubText("Message privé")
        .setStyle(messagingStyle)
    } else {
      val channelPart = payload.channelName?.let { "#$it" } ?: "salon"
      val title = "${payload.authorDisplayName} • $channelPart"
      val subText =
        when (payload.category) {
          NotificationCategory.EVERYONE ->
            payload.guildName?.let { "$it (@everyone)" } ?: "@everyone"
          NotificationCategory.ROLE_MENTION ->
            payload.guildName?.let { "$it (Rôle)" } ?: "Mention de rôle"
          NotificationCategory.MENTION -> payload.guildName ?: "Mention"
          NotificationCategory.DIRECT_MESSAGE -> "Message privé"
        }

      builder
        .setContentTitle(title)
        .setContentText(payload.bodyText)
        .setSubText(subText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(payload.bodyText))
    }

    runCatching {
      NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
  }

  fun showIncomingCallNotification(call: IncomingCallPayload, ringtoneUri: String?) {
    if (!hasNotificationPermission()) return
    ensureChannelsCreated()

    callAlertController.startRinging(call.channelId, ringtoneUri)

    ioScope.launch {
      val avatarBitmap = avatarLoader.loadCircularAvatar(call.callerAvatarUrl)
      postIncomingCallNotification(call, avatarBitmap)
    }
  }

  @SuppressLint("MissingPermission")
  private fun postIncomingCallNotification(call: IncomingCallPayload, avatarBitmap: Bitmap?) {
    if (!hasNotificationPermission()) return

    val notificationId = callNotificationId(call.channelId)

    val answerIntent =
      Intent(context, CallNotificationClickActivity::class.java).apply {
        putExtra(CallNotificationClickActivity.EXTRA_CHANNEL_ID, call.channelId)
        putExtra(CallNotificationClickActivity.EXTRA_DEEP_LINK, call.deepLinkUrl)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val answerPendingIntent =
      PendingIntent.getActivity(
        context,
        notificationId,
        answerIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val declineIntent =
      Intent(context, DiscordGatewayService::class.java).apply {
        action = DiscordGatewayService.ACTION_DISMISS_CALL
        putExtra(DiscordGatewayService.EXTRA_CALL_CHANNEL_ID, call.channelId)
      }
    val declinePendingIntent =
      PendingIntent.getService(
        context,
        notificationId + 1,
        declineIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val callerPerson =
      Person.Builder()
        .setName(call.callerName)
        .setImportant(true)
        .apply {
          if (avatarBitmap != null) {
            setIcon(IconCompat.createWithBitmap(avatarBitmap))
          }
        }
        .build()

    val builder =
      NotificationCompat.Builder(context, CHANNEL_CALLS)
        .setSmallIcon(R.drawable.ic_stat_notidisc)
        .setContentTitle(call.callerName)
        .setContentText(
          if (call.isGroupCall) "Appel de groupe Discord entrant" else "Appel vocal Discord entrant"
        )
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setAutoCancel(true)
        .setSilent(true)
        .setContentIntent(answerPendingIntent)
        .addPerson(callerPerson)
        .setStyle(
          NotificationCompat.CallStyle.forIncomingCall(
            callerPerson,
            declinePendingIntent,
            answerPendingIntent,
          )
        )

    if (avatarBitmap != null) {
      builder.setLargeIcon(avatarBitmap)
    }

    runCatching {
      NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
  }

  fun cancelCallNotification(channelId: String) {
    callAlertController.stopRinging(channelId)
    NotificationManagerCompat.from(context).cancel(callNotificationId(channelId))
  }

  @SuppressLint("MissingPermission")
  fun showTestNotification(): Boolean {
    if (!hasNotificationPermission()) return false
    ensureChannelsCreated()

    val viewIntent =
      Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/channels/@me")).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        context,
        TEST_NOTIFICATION_ID,
        viewIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(context, CHANNEL_DM)
        .setSmallIcon(R.drawable.ic_stat_notidisc)
        .setContentTitle("Test Discodo")
        .setContentText("Les notifications locales fonctionnent. Touchez pour ouvrir Discord.")
        .setSubText("Test")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    return runCatching {
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
      }
      .isSuccess
  }

  fun showTestCallNotification(ringtoneUri: String?): Boolean {
    if (!hasNotificationPermission()) return false
    showIncomingCallNotification(
      IncomingCallPayload(
        channelId = "test_call_channel",
        messageId = null,
        callerName = "Test Appel Discodo",
        callerAvatarUrl = null,
        isGroupCall = false,
      ),
      ringtoneUri = ringtoneUri,
    )
    return true
  }

  fun hasNotificationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  private fun callNotificationId(channelId: String): Int =
    CALL_NOTIFICATION_BASE_ID + (channelId.hashCode() and 0x0FFF_FFFF)

  companion object {
    const val CHANNEL_SERVICE_STATUS = "discodo_service_status"
    const val CHANNEL_DM = "discodo_dm"
    const val CHANNEL_MENTIONS = "discodo_mentions"
    const val CHANNEL_EVERYONE = "discodo_everyone"
    const val CHANNEL_CALLS = "discodo_calls"

    const val SERVICE_NOTIFICATION_ID = 1001
    private const val TEST_NOTIFICATION_ID = 1002
    private const val CALL_NOTIFICATION_BASE_ID = 2000
    private const val REQUEST_OPEN_APP = 100
    private const val REQUEST_STOP_SERVICE = 101

    private val STRONG_VIBRATION_PATTERN = longArrayOf(0L, 250L, 120L, 250L)
    private val SOFT_VIBRATION_PATTERN = longArrayOf(0L, 100L)
  }
}
