package com.discodo.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface GatewayStatus {
  data object Disconnected : GatewayStatus

  data object Connecting : GatewayStatus

  data class Connected(
    val username: String,
    val userId: String,
    val lastHeartbeatAckEpochMs: Long? = null,
  ) : GatewayStatus

  data class Reconnecting(
    val attempt: Int,
    val delaySeconds: Long,
    val reason: String,
  ) : GatewayStatus

  data class Error(
    val message: String,
    val isAuthError: Boolean = false,
  ) : GatewayStatus
}

/**
 * Process-wide holder for live Discord Gateway connection status observed by both the
 * Foreground Service notification and the Jetpack Compose UI.
 */
class ServiceStateHolder {
  private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Disconnected)
  val status: StateFlow<GatewayStatus> = _status.asStateFlow()

  private val _lastNotificationSummary = MutableStateFlow<String?>(null)
  val lastNotificationSummary: StateFlow<String?> = _lastNotificationSummary.asStateFlow()

  fun updateStatus(newStatus: GatewayStatus) {
    _status.value = newStatus
  }

  fun recordNotifiedMessage(summary: String) {
    _lastNotificationSummary.value = summary
  }
}
