package com.discodo.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationPreferences(
  val notifyDm: Boolean = true,
  val notifyMentions: Boolean = true,
  val notifyEveryone: Boolean = false,
  val notifyRoles: Boolean = true,
  val notifyCalls: Boolean = true,
  val respectMutedSettings: Boolean = true,
)

data class AppSettings(
  val hasToken: Boolean = false,
  val notifications: NotificationPreferences = NotificationPreferences(),
  val callRingtoneUri: String? = null,
  val startOnBoot: Boolean = true,
  val serviceEnabled: Boolean = false,
  val autoCheckUpdates: Boolean = true,
)

/**
 * Persists user preferences in private SharedPreferences and stores the Discord token
 * encrypted via [TokenCryptoManager]. The token is never logged or exposed in UI state flows.
 */
class AppPreferences(
  context: Context,
  private val crypto: TokenCryptoManager = TokenCryptoManager(),
) {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  fun saveToken(rawToken: String) {
    val sanitized = rawToken.trim().removeSurrounding("\"")
    if (sanitized.isEmpty()) {
      clearToken()
      return
    }
    val encrypted = crypto.encrypt(sanitized)
    prefs.edit().putString(KEY_ENCRYPTED_TOKEN, encrypted).apply()
    refresh()
  }

  fun clearToken() {
    prefs.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
    refresh()
  }

  fun getToken(): String? {
    val encrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
    return crypto.decrypt(encrypted)?.takeIf { it.isNotBlank() }
  }

  fun setNotifyDm(enabled: Boolean) = updateBool(KEY_NOTIFY_DM, enabled)

  fun setNotifyMentions(enabled: Boolean) = updateBool(KEY_NOTIFY_MENTIONS, enabled)

  fun setNotifyEveryone(enabled: Boolean) = updateBool(KEY_NOTIFY_EVERYONE, enabled)

  fun setNotifyRoles(enabled: Boolean) = updateBool(KEY_NOTIFY_ROLES, enabled)

  fun setNotifyCalls(enabled: Boolean) = updateBool(KEY_NOTIFY_CALLS, enabled)

  fun setCallRingtoneUri(uri: String?) {
    if (uri.isNullOrBlank()) {
      prefs.edit().remove(KEY_CALL_RINGTONE_URI).apply()
    } else {
      prefs.edit().putString(KEY_CALL_RINGTONE_URI, uri).apply()
    }
    refresh()
  }

  fun setRespectMutedSettings(enabled: Boolean) = updateBool(KEY_RESPECT_MUTED, enabled)

  fun setStartOnBoot(enabled: Boolean) = updateBool(KEY_START_ON_BOOT, enabled)

  fun setServiceEnabled(enabled: Boolean) = updateBool(KEY_SERVICE_ENABLED, enabled)

  fun setAutoCheckUpdates(enabled: Boolean) = updateBool(KEY_AUTO_CHECK_UPDATES, enabled)

  private fun updateBool(key: String, value: Boolean) {
    prefs.edit().putBoolean(key, value).apply()
    refresh()
  }

  private fun refresh() {
    _settings.value = loadSettings()
  }

  private fun loadSettings(): AppSettings {
    val hasValidToken = !prefs.getString(KEY_ENCRYPTED_TOKEN, null).isNullOrBlank()
    return AppSettings(
      hasToken = hasValidToken,
      notifications =
        NotificationPreferences(
          notifyDm = prefs.getBoolean(KEY_NOTIFY_DM, true),
          notifyMentions = prefs.getBoolean(KEY_NOTIFY_MENTIONS, true),
          notifyEveryone = prefs.getBoolean(KEY_NOTIFY_EVERYONE, false),
          notifyRoles = prefs.getBoolean(KEY_NOTIFY_ROLES, true),
          notifyCalls = prefs.getBoolean(KEY_NOTIFY_CALLS, true),
          respectMutedSettings = prefs.getBoolean(KEY_RESPECT_MUTED, true),
        ),
      callRingtoneUri = prefs.getString(KEY_CALL_RINGTONE_URI, null),
      startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true),
      serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false),
      autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
    )
  }

  private companion object {
    const val PREFS_NAME = "discodo_prefs"
    const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
    const val KEY_NOTIFY_DM = "notify_dm"
    const val KEY_NOTIFY_MENTIONS = "notify_mentions"
    const val KEY_NOTIFY_EVERYONE = "notify_everyone"
    const val KEY_NOTIFY_ROLES = "notify_roles"
    const val KEY_NOTIFY_CALLS = "notify_calls"
    const val KEY_CALL_RINGTONE_URI = "call_ringtone_uri"
    const val KEY_RESPECT_MUTED = "respect_muted_settings"
    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
  }
}
