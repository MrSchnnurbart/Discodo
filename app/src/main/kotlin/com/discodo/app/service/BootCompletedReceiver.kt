package com.discodo.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.discodo.app.data.AppPreferences

class BootCompletedReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (
      action != Intent.ACTION_BOOT_COMPLETED &&
        action != Intent.ACTION_MY_PACKAGE_REPLACED &&
        action != ACTION_QUICKBOOT_POWERON
    ) {
      return
    }

    val prefs = AppPreferences(context)
    val settings = prefs.settings.value
    if (settings.startOnBoot && settings.serviceEnabled && settings.hasToken) {
      Log.i(TAG, "Auto-starting DiscordGatewayService after $action")
      runCatching { DiscordGatewayService.start(context) }
        .onFailure { Log.e(TAG, "Failed to auto-start service: ${it.message}") }
    }
  }

  companion object {
    private const val TAG = "BootCompletedReceiver"
    private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
  }
}
