package com.discodo.app

import android.app.Application
import com.discodo.app.data.AppPreferences
import com.discodo.app.data.NotificationHistoryRepository
import com.discodo.app.data.ServiceStateHolder
import com.discodo.app.notifications.CallAlertController
import com.discodo.app.notifications.NotificationHelper
import com.discodo.app.updater.AppUpdater

class DiscodoApplication : Application() {
  lateinit var appPreferences: AppPreferences
    private set

  lateinit var stateHolder: ServiceStateHolder
    private set

  lateinit var historyRepository: NotificationHistoryRepository
    private set

  lateinit var notificationHelper: NotificationHelper
    private set

  lateinit var appUpdater: AppUpdater
    private set

  val callAlertController: CallAlertController
    get() = notificationHelper.callAlertController

  override fun onCreate() {
    super.onCreate()
    appPreferences = AppPreferences(this)
    stateHolder = ServiceStateHolder()
    historyRepository = NotificationHistoryRepository(this)
    notificationHelper = NotificationHelper(this)
    notificationHelper.ensureChannelsCreated()
    appUpdater = AppUpdater(this)
  }
}
