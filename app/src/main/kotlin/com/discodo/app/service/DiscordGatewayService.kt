package com.discodo.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.discodo.app.DiscodoApplication
import com.discodo.app.data.GatewayStatus
import com.discodo.app.data.HistoryCategory
import com.discodo.app.data.NotificationHistoryItem
import com.discodo.app.gateway.DiscordGatewayClient
import com.discodo.app.gateway.NotificationCategory
import com.discodo.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DiscordGatewayService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private var gatewayClient: DiscordGatewayClient? = null
  private var connectivityManager: ConnectivityManager? = null
  private var networkCallbackRegistered = false

  private val app: DiscodoApplication
    get() = application as DiscodoApplication

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        gatewayClient?.reconnectNow()
      }
    }

  override fun onCreate() {
    super.onCreate()
    promoteToForeground(app.stateHolder.status.value)

    serviceScope.launch {
      app.stateHolder.status.collectLatest { status ->
        app.notificationHelper.updateServiceNotification(status)
        if (status is GatewayStatus.Error && status.isAuthError) {
          app.appPreferences.setServiceEnabled(false)
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
      }
    }

    registerNetworkMonitor()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISMISS_CALL) {
      val callChannelId = intent.getStringExtra(EXTRA_CALL_CHANNEL_ID)
      if (!callChannelId.isNullOrBlank()) {
        app.notificationHelper.cancelCallNotification(callChannelId)
      } else {
        app.callAlertController.stopRinging()
      }
      return if (app.appPreferences.settings.value.serviceEnabled) START_STICKY else START_NOT_STICKY
    }

    if (intent?.action == ACTION_STOP) {
      Log.i(TAG, "Received ACTION_STOP")
      app.appPreferences.setServiceEnabled(false)
      app.callAlertController.stopRinging()
      gatewayClient?.stop()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    promoteToForeground(app.stateHolder.status.value)

    val token = app.appPreferences.getToken()
    if (token.isNullOrBlank()) {
      app.appPreferences.setServiceEnabled(false)
      app.stateHolder.updateStatus(
        GatewayStatus.Error("Veuillez enregistrer votre token Discord.", isAuthError = true)
      )
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    app.appPreferences.setServiceEnabled(true)

    if (gatewayClient == null) {
      gatewayClient =
        DiscordGatewayClient(
          tokenProvider = { app.appPreferences.getToken() },
          preferencesProvider = { app.appPreferences.settings.value.notifications },
          onStatusChanged = { status -> app.stateHolder.updateStatus(status) },
          onNotification = { payload ->
            app.notificationHelper.showMessageNotification(payload)
            app.stateHolder.recordNotifiedMessage(
              "${payload.authorDisplayName}: ${payload.bodyText.take(60)}"
            )
            val historyCat =
              when (payload.category) {
                NotificationCategory.DIRECT_MESSAGE -> HistoryCategory.DM
                NotificationCategory.MENTION -> HistoryCategory.MENTION
                NotificationCategory.EVERYONE -> HistoryCategory.EVERYONE
                NotificationCategory.ROLE_MENTION -> HistoryCategory.ROLE
              }
            app.historyRepository.record(
              NotificationHistoryItem(
                id = payload.messageId,
                timestampEpochMs = System.currentTimeMillis(),
                category = historyCat,
                authorName = payload.authorDisplayName,
                guildName = payload.guildName,
                channelName = payload.channelName,
                bodyText = payload.bodyText,
                deepLinkUrl = payload.deepLinkUrl,
              )
            )
          },
          onIncomingCall = { call ->
            app.notificationHelper.showIncomingCallNotification(
              call = call,
              ringtoneUri = app.appPreferences.settings.value.callRingtoneUri,
            )
            app.stateHolder.recordNotifiedMessage("Appel entrant : ${call.callerName}")
            val now = System.currentTimeMillis()
            app.historyRepository.record(
              NotificationHistoryItem(
                id = "call_${call.channelId}_${call.messageId ?: now}",
                timestampEpochMs = now,
                category = HistoryCategory.CALL,
                authorName = call.callerName,
                guildName = null,
                channelName = null,
                bodyText =
                  if (call.isGroupCall) "Appel de groupe Discord" else "Appel vocal Discord",
                deepLinkUrl = call.deepLinkUrl,
              )
            )
          },
          onCallEnded = { channelId ->
            app.notificationHelper.cancelCallNotification(channelId)
          },
        )
    }

    gatewayClient?.start()
    return START_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "Foreground service timeout reached; stopping gracefully")
    gatewayClient?.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    unregisterNetworkMonitor()
    gatewayClient?.shutdown()
    gatewayClient = null
    if (app.stateHolder.status.value !is GatewayStatus.Error) {
      app.stateHolder.updateStatus(GatewayStatus.Disconnected)
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun promoteToForeground(status: GatewayStatus) {
    val notification = app.notificationHelper.buildServiceNotification(status)
    val fgsType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }
    ServiceCompat.startForeground(
      this,
      NotificationHelper.SERVICE_NOTIFICATION_ID,
      notification,
      fgsType,
    )
  }

  private fun registerNetworkMonitor() {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    connectivityManager = cm
    val request =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    runCatching {
      cm.registerNetworkCallback(request, networkCallback)
      networkCallbackRegistered = true
    }
  }

  private fun unregisterNetworkMonitor() {
    if (!networkCallbackRegistered) return
    runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
    networkCallbackRegistered = false
  }

  companion object {
    private const val TAG = "DiscordGatewayService"
    const val ACTION_START = "com.discodo.app.action.START"
    const val ACTION_STOP = "com.discodo.app.action.STOP"
    const val ACTION_DISMISS_CALL = "com.discodo.app.action.DISMISS_CALL"
    const val EXTRA_CALL_CHANNEL_ID = "extra_call_channel_id"

    fun start(context: Context) {
      val intent = Intent(context, DiscordGatewayService::class.java).setAction(ACTION_START)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, DiscordGatewayService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}
