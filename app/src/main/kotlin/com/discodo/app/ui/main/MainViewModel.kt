package com.discodo.app.ui.main

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.discodo.app.BuildConfig
import com.discodo.app.DiscodoApplication
import com.discodo.app.data.AppSettings
import com.discodo.app.data.GatewayStatus
import com.discodo.app.data.NotificationHistoryItem
import com.discodo.app.service.DiscordGatewayService
import com.discodo.app.updater.InstallLaunchResult
import com.discodo.app.updater.ReleaseInfo
import com.discodo.app.updater.UpdateCheckResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
  data object Idle : UpdateUiState

  data object Checking : UpdateUiState

  data class UpToDate(val version: String) : UpdateUiState

  data class Available(val release: ReleaseInfo) : UpdateUiState

  data class Downloading(val release: ReleaseInfo, val progressPct: Int) : UpdateUiState

  data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateUiState

  data class Error(val message: String) : UpdateUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as DiscodoApplication

  val settings: StateFlow<AppSettings> = app.appPreferences.settings
  val gatewayStatus: StateFlow<GatewayStatus> = app.stateHolder.status
  val lastNotificationSummary: StateFlow<String?> = app.stateHolder.lastNotificationSummary
  val historyItems: StateFlow<List<NotificationHistoryItem>> = app.historyRepository.items

  private val _tokenInput = MutableStateFlow("")
  val tokenInput: StateFlow<String> = _tokenInput.asStateFlow()

  private val _tokenVisible = MutableStateFlow(false)
  val tokenVisible: StateFlow<Boolean> = _tokenVisible.asStateFlow()

  private val _feedbackMessage = MutableStateFlow<String?>(null)
  val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

  private val _isIgnoringBatteryOptimizations = MutableStateFlow(checkBatteryExemption())
  val isIgnoringBatteryOptimizations: StateFlow<Boolean> =
    _isIgnoringBatteryOptimizations.asStateFlow()

  private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
  val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

  init {
    app.historyRepository.pruneExpired()
    if (settings.value.autoCheckUpdates) {
      checkForUpdates(silent = true)
    }
  }

  fun onTokenInputChanged(value: String) {
    _tokenInput.value = value
  }

  fun toggleTokenVisibility() {
    _tokenVisible.value = !_tokenVisible.value
  }

  fun saveToken() {
    val raw = _tokenInput.value.trim()
    if (raw.isEmpty()) {
      _feedbackMessage.value = "Collez un token Discord valide avant d'enregistrer."
      return
    }
    app.appPreferences.saveToken(raw)
    _tokenInput.value = ""
    _tokenVisible.value = false
    _feedbackMessage.value = "Token chiffré avec Android Keystore et enregistré."
  }

  fun clearToken() {
    stopService()
    app.appPreferences.clearToken()
    _tokenInput.value = ""
    _feedbackMessage.value = "Token supprimé."
  }

  fun startService() {
    if (!settings.value.hasToken) {
      _feedbackMessage.value = "Enregistrez d'abord votre token Discord."
      return
    }
    DiscordGatewayService.start(app)
  }

  fun stopService() {
    app.appPreferences.setServiceEnabled(false)
    runCatching { DiscordGatewayService.stop(app) }
  }

  fun setNotifyDm(enabled: Boolean) = app.appPreferences.setNotifyDm(enabled)

  fun setNotifyMentions(enabled: Boolean) = app.appPreferences.setNotifyMentions(enabled)

  fun setNotifyEveryone(enabled: Boolean) = app.appPreferences.setNotifyEveryone(enabled)

  fun setNotifyRoles(enabled: Boolean) = app.appPreferences.setNotifyRoles(enabled)

  fun setNotifyCalls(enabled: Boolean) = app.appPreferences.setNotifyCalls(enabled)

  fun setCallRingtoneUri(uri: String?) {
    app.appPreferences.setCallRingtoneUri(uri)
    _feedbackMessage.value = "Sonnerie d'appel mise à jour."
  }

  fun setRespectMutedSettings(enabled: Boolean) =
    app.appPreferences.setRespectMutedSettings(enabled)

  fun setStartOnBoot(enabled: Boolean) = app.appPreferences.setStartOnBoot(enabled)

  fun setAutoCheckUpdates(enabled: Boolean) = app.appPreferences.setAutoCheckUpdates(enabled)

  fun sendTestNotification() {
    val sent = app.notificationHelper.showTestNotification()
    _feedbackMessage.value =
      if (sent) {
        "Notification de test envoyée."
      } else {
        "Permission de notifications requise pour afficher le test."
      }
  }

  fun sendTestCallNotification() {
    val sent = app.notificationHelper.showTestCallNotification(settings.value.callRingtoneUri)
    _feedbackMessage.value =
      if (sent) {
        "Test d'appel lancé (touchez Ignorer sur la notification pour couper)."
      } else {
        "Permission de notifications requise pour afficher le test."
      }
  }

  fun clearHistory() {
    app.historyRepository.clearHistory()
    _feedbackMessage.value = "Historique des notifications effacé."
  }

  fun checkForUpdates(silent: Boolean = false) {
    if (_updateState.value is UpdateUiState.Checking || _updateState.value is UpdateUiState.Downloading) {
      return
    }
    viewModelScope.launch {
      if (!silent) {
        _updateState.value = UpdateUiState.Checking
      }
      when (val result = app.appUpdater.checkForUpdate(BuildConfig.VERSION_NAME)) {
        is UpdateCheckResult.UpdateAvailable -> {
          _updateState.value = UpdateUiState.Available(result.release)
        }
        is UpdateCheckResult.UpToDate -> {
          if (!silent) {
            _updateState.value = UpdateUiState.UpToDate(result.currentVersion)
          }
        }
        is UpdateCheckResult.Failure -> {
          if (!silent) {
            _updateState.value = UpdateUiState.Error(result.message)
          }
        }
      }
    }
  }

  fun downloadAndInstallUpdate(release: ReleaseInfo) {
    if (_updateState.value is UpdateUiState.Downloading) return
    viewModelScope.launch {
      _updateState.value = UpdateUiState.Downloading(release, 0)
      val downloadResult =
        app.appUpdater.downloadApk(release) { pct ->
          _updateState.value = UpdateUiState.Downloading(release, pct)
        }
      downloadResult
        .onSuccess { file ->
          _updateState.value = UpdateUiState.ReadyToInstall(release, file)
          installDownloadedApk(file)
        }
        .onFailure { err ->
          _updateState.value =
            UpdateUiState.Error(err.message ?: "Échec du téléchargement de la mise à jour.")
        }
    }
  }

  fun installDownloadedApk(file: File) {
    when (val res = app.appUpdater.launchApkInstaller(file)) {
      InstallLaunchResult.Launched -> Unit
      InstallLaunchResult.NeedsInstallPermission -> {
        _feedbackMessage.value =
          "Autorisez l'installation d'apps inconnues pour Discodo, puis appuyez sur Installer."
      }
      is InstallLaunchResult.Error -> {
        _feedbackMessage.value = res.message
      }
    }
  }

  fun refreshBatteryOptimizationState() {
    _isIgnoringBatteryOptimizations.value = checkBatteryExemption()
  }

  fun consumeFeedbackMessage() {
    _feedbackMessage.value = null
  }

  fun showFeedback(message: String) {
    _feedbackMessage.value = message
  }

  private fun checkBatteryExemption(): Boolean {
    val pm = app.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(app.packageName)
  }
}
