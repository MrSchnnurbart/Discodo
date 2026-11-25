package com.discodo.app.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.discodo.app.BuildConfig
import com.discodo.app.data.GatewayStatus
import com.discodo.app.data.HistoryCategory
import com.discodo.app.data.NotificationHistoryItem
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  viewModel: MainViewModel = viewModel(),
) {
  val context = LocalContext.current
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val status by viewModel.gatewayStatus.collectAsStateWithLifecycle()
  val tokenInput by viewModel.tokenInput.collectAsStateWithLifecycle()
  val tokenVisible by viewModel.tokenVisible.collectAsStateWithLifecycle()
  val feedback by viewModel.feedbackMessage.collectAsStateWithLifecycle()
  val ignoringBattery by viewModel.isIgnoringBatteryOptimizations.collectAsStateWithLifecycle()
  val lastSummary by viewModel.lastNotificationSummary.collectAsStateWithLifecycle()
  val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
  val updateState by viewModel.updateState.collectAsStateWithLifecycle()

  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  var showClearHistoryDialog by remember { mutableStateOf(false) }

  val snackbarHostState = remember { SnackbarHostState() }
  var pendingActionAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val action = pendingActionAfterPermission
      pendingActionAfterPermission = null
      if (granted) {
        action?.invoke()
      } else {
        viewModel.showFeedback(
          "Permission POST_NOTIFICATIONS refusée. Activez-la dans les paramètres Android."
        )
      }
    }

  val ringtonePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        val pickedUri =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(
              RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
              Uri::class.java,
            )
          } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
          }
        viewModel.setCallRingtoneUri(pickedUri?.toString())
      }
    }

  val ringtoneLabel =
    remember(settings.callRingtoneUri) {
      val uri =
        settings.callRingtoneUri?.let(Uri::parse)
          ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      runCatching { uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) } }
        .getOrNull()
        ?: "Sonnerie par défaut"
    }

  fun runWithNotificationPermission(action: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        pendingActionAfterPermission = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return
      }
    }
    action()
  }

  LifecycleResumeEffect(Unit) {
    viewModel.refreshBatteryOptimizationState()
    onPauseOrDispose {}
  }

  LaunchedEffect(feedback) {
    val msg = feedback ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(msg)
    viewModel.consumeFeedbackMessage()
  }

  val isServiceActive =
    status is GatewayStatus.Connecting ||
      status is GatewayStatus.Connected ||
      status is GatewayStatus.Reconnecting

  if (showClearHistoryDialog) {
    AlertDialog(
      onDismissRequest = { showClearHistoryDialog = false },
      title = { Text("Supprimer l'historique ?") },
      text = {
        Text("Toutes les notifications enregistrées des 30 derniers jours seront effacées.")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showClearHistoryDialog = false
            viewModel.clearHistory()
          }
        ) {
          Text("Supprimer", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearHistoryDialog = false }) { Text("Annuler") }
      },
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text(if (selectedTab == 0) "Discodo" else "Historique (30 jours)")
        }
      )
    },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          icon = { Icon(Icons.Default.Home, contentDescription = null) },
          label = { Text("Accueil") },
        )
        NavigationBarItem(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          icon = {
            BadgedBox(
              badge = {
                if (historyItems.isNotEmpty()) {
                  Badge { Text(historyItems.size.coerceAtMost(99).toString()) }
                }
              }
            ) {
              Icon(Icons.Default.History, contentDescription = null)
            }
          },
          label = { Text("Historique") },
        )
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    if (selectedTab == 0) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        StatusCard(
          status = status,
          isServiceActive = isServiceActive,
          hasToken = settings.hasToken,
          lastSummary = lastSummary,
          onStartClick = { runWithNotificationPermission { viewModel.startService() } },
          onStopClick = { viewModel.stopService() },
        )

        TokenCard(
          hasStoredToken = settings.hasToken,
          tokenInput = tokenInput,
          tokenVisible = tokenVisible,
          onTokenChange = viewModel::onTokenInputChanged,
          onToggleVisibility = viewModel::toggleTokenVisibility,
          onSave = viewModel::saveToken,
          onClear = viewModel::clearToken,
        )

        PreferencesCard(
          notifyDm = settings.notifications.notifyDm,
          notifyMentions = settings.notifications.notifyMentions,
          notifyEveryone = settings.notifications.notifyEveryone,
          notifyRoles = settings.notifications.notifyRoles,
          notifyCalls = settings.notifications.notifyCalls,
          ringtoneLabel = ringtoneLabel,
          respectMuted = settings.notifications.respectMutedSettings,
          startOnBoot = settings.startOnBoot,
          onNotifyDmChange = viewModel::setNotifyDm,
          onNotifyMentionsChange = viewModel::setNotifyMentions,
          onNotifyEveryoneChange = viewModel::setNotifyEveryone,
          onNotifyRolesChange = viewModel::setNotifyRoles,
          onNotifyCallsChange = viewModel::setNotifyCalls,
          onPickRingtone = {
            val existingUri =
              settings.callRingtoneUri?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val intent =
              Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choisir la sonnerie d'appel")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
              }
            runCatching { ringtonePickerLauncher.launch(intent) }
              .onFailure {
                viewModel.showFeedback("Sélecteur de sonnerie indisponible sur cet appareil.")
              }
          },
          onRespectMutedChange = viewModel::setRespectMutedSettings,
          onStartOnBootChange = viewModel::setStartOnBoot,
        )

        UpdateCard(
          currentVersion = BuildConfig.VERSION_NAME,
          autoCheckUpdates = settings.autoCheckUpdates,
          updateState = updateState,
          onAutoCheckChange = viewModel::setAutoCheckUpdates,
          onCheckUpdates = { viewModel.checkForUpdates(silent = false) },
          onDownloadAndInstall = { release -> viewModel.downloadAndInstallUpdate(release) },
          onRetryInstall = { file -> viewModel.installDownloadedApk(file) },
        )

        SystemToolsCard(
          ignoringBattery = ignoringBattery,
          onTestNotification = {
            runWithNotificationPermission { viewModel.sendTestNotification() }
          },
          onTestCall = {
            runWithNotificationPermission { viewModel.sendTestCallNotification() }
          },
          onRequestBatteryExemption = {
            val intent =
              Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
              )
            runCatching { context.startActivity(intent) }
              .onFailure {
                viewModel.showFeedback("Impossible d'ouvrir les paramètres de batterie.")
              }
          },
        )

        Spacer(modifier = Modifier.height(12.dp))
      }
    } else {
      HistoryTabContent(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        items = historyItems,
        onClearRequest = { showClearHistoryDialog = true },
        onItemClick = { item ->
          val viewIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(item.deepLinkUrl)).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
          runCatching { context.startActivity(viewIntent) }
            .onFailure { viewModel.showFeedback("Impossible d'ouvrir le lien Discord.") }
        },
      )
    }
  }
}

@Composable
private fun HistoryTabContent(
  modifier: Modifier,
  items: List<NotificationHistoryItem>,
  onClearRequest: () -> Unit,
  onItemClick: (NotificationHistoryItem) -> Unit,
) {
  Column(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "${items.size} notification(s) • 30 derniers jours",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton(
        onClick = onClearRequest,
        enabled = items.isNotEmpty(),
      ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Supprimer")
      }
    }

    if (items.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "Aucune notification enregistrée sur les 30 derniers jours.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      val dateFormatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(items, key = { it.id }) { item ->
          HistoryItemCard(
            item = item,
            formattedTime = dateFormatter.format(Date(item.timestampEpochMs)),
            onClick = { onItemClick(item) },
          )
        }
      }
    }
  }
}

@Composable
private fun HistoryItemCard(
  item: NotificationHistoryItem,
  formattedTime: String,
  onClick: () -> Unit,
) {
  val (badgeText, badgeColor) =
    when (item.category) {
      HistoryCategory.DM -> "DM" to Color(0xFF5865F2)
      HistoryCategory.MENTION -> "Mention" to Color(0xFF1E8E3E)
      HistoryCategory.EVERYONE -> "@everyone" to Color(0xFF795548)
      HistoryCategory.ROLE -> "Rôle" to Color(0xFF6A1B9A)
      HistoryCategory.CALL -> "Appel" to Color(0xFFD93025)
    }

  val contextLabel =
    listOfNotNull(
        item.channelName?.let { "#$it" },
        item.guildName,
      )
      .joinToString(" • ")

  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            color = badgeColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = badgeText,
              color = badgeColor,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
          Text(
            text = item.authorName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (contextLabel.isNotBlank()) {
        Text(
          text = contextLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Text(
        text = item.bodyText,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StatusCard(
  status: GatewayStatus,
  isServiceActive: Boolean,
  hasToken: Boolean,
  lastSummary: String?,
  onStartClick: () -> Unit,
  onStopClick: () -> Unit,
) {
  val (dotColor, title, subtitle) =
    when (status) {
      GatewayStatus.Disconnected ->
        Triple(Color(0xFF8E918F), "Déconnecté", "Le service Gateway est arrêté.")
      GatewayStatus.Connecting ->
        Triple(Color(0xFFF9AB00), "Connexion…", "Handshake avec wss://gateway.discord.gg")
      is GatewayStatus.Connected -> {
        val ackInfo =
          status.lastHeartbeatAckEpochMs?.let { ts ->
            val timeStr = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ts))
            " • Dernier heartbeat ACK : $timeStr"
          }.orEmpty()
        Triple(
          Color(0xFF1E8E3E),
          "Connecté (${status.username})",
          "Session active$ackInfo",
        )
      }
      is GatewayStatus.Reconnecting ->
        Triple(
          Color(0xFFF9AB00),
          "Reconnexion (tentative #${status.attempt})",
          "Nouvel essai dans ${status.delaySeconds}s — ${status.reason}",
        )
      is GatewayStatus.Error ->
        Triple(Color(0xFFD93025), "Erreur", status.message)
    }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(dotColor))
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
      }

      if (!lastSummary.isNullOrBlank()) {
        Text(
          text = "Dernière notif : $lastSummary",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (isServiceActive) {
        Button(
          onClick = onStopClick,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Icon(Icons.Default.Stop, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Arrêter le service")
        }
      } else {
        Button(
          onClick = onStartClick,
          enabled = hasToken,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Démarrer le service")
        }
      }
    }
  }
}

@Composable
private fun TokenCard(
  hasStoredToken: Boolean,
  tokenInput: String,
  tokenVisible: Boolean,
  onTokenChange: (String) -> Unit,
  onToggleVisibility: () -> Unit,
  onSave: () -> Unit,
  onClear: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Token Discord",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Text(
        text =
          if (hasStoredToken) {
            "Un token chiffré (Android Keystore) est enregistré localement."
          } else {
            "Aucun token enregistré. Collez votre token pour démarrer."
          },
        style = MaterialTheme.typography.bodySmall,
        color =
          if (hasStoredToken) {
            Color(0xFF1E8E3E)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )

      OutlinedTextField(
        value = tokenInput,
        onValueChange = onTokenChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = {
          Text(if (hasStoredToken) "Remplacer le token Discord" else "Coller votre token Discord")
        },
        visualTransformation =
          if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
          IconButton(onClick = onToggleVisibility) {
            Icon(
              imageVector =
                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (tokenVisible) "Masquer" else "Afficher",
            )
          }
        },
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onSave,
          enabled = tokenInput.isNotBlank(),
          modifier = Modifier.weight(1f),
        ) {
          Text("Enregistrer")
        }
        if (hasStoredToken) {
          OutlinedButton(onClick = onClear) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer le token")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Effacer")
          }
        }
      }
    }
  }
}

@Composable
private fun PreferencesCard(
  notifyDm: Boolean,
  notifyMentions: Boolean,
  notifyEveryone: Boolean,
  notifyRoles: Boolean,
  notifyCalls: Boolean,
  ringtoneLabel: String,
  respectMuted: Boolean,
  startOnBoot: Boolean,
  onNotifyDmChange: (Boolean) -> Unit,
  onNotifyMentionsChange: (Boolean) -> Unit,
  onNotifyEveryoneChange: (Boolean) -> Unit,
  onNotifyRolesChange: (Boolean) -> Unit,
  onNotifyCallsChange: (Boolean) -> Unit,
  onPickRingtone: () -> Unit,
  onRespectMutedChange: (Boolean) -> Unit,
  onStartOnBootChange: (Boolean) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "Filtres & Démarrage",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
      )

      PreferenceSwitchRow(
        title = "Notifier les DM",
        subtitle = "Messages privés avec photo de profil",
        checked = notifyDm,
        onCheckedChange = onNotifyDmChange,
      )
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Notifier les appels DM",
        subtitle = "Notification d'appel avec vibreur et sonnerie",
        checked = notifyCalls,
        onCheckedChange = onNotifyCallsChange,
      )
      if (notifyCalls) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.MusicNote,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(text = "Sonnerie d'appel", style = MaterialTheme.typography.bodyMedium)
              Text(
                text = ringtoneLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          OutlinedButton(onClick = onPickRingtone) { Text("Choisir") }
        }
      }
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Notifier les mentions",
        subtitle = "Lorsque votre identifiant est mentionné",
        checked = notifyMentions,
        onCheckedChange = onNotifyMentionsChange,
      )
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Notifier @everyone / @here",
        subtitle = "Priorité modérée et vibration plus discrète",
        checked = notifyEveryone,
        onCheckedChange = onNotifyEveryoneChange,
      )
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Notifier les mentions de rôles",
        subtitle = "Vos rôles mentionnés (priorité modérée et vibration discrète)",
        checked = notifyRoles,
        onCheckedChange = onNotifyRolesChange,
      )
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Respecter les sourdines",
        subtitle = "Ignore serveurs et salons mis en sourdine",
        checked = respectMuted,
        onCheckedChange = onRespectMutedChange,
      )
      HorizontalDivider()
      PreferenceSwitchRow(
        title = "Démarrer au boot",
        subtitle = "Relance le service au démarrage du téléphone",
        checked = startOnBoot,
        onCheckedChange = onStartOnBootChange,
      )
    }
  }
}

@Composable
private fun UpdateCard(
  currentVersion: String,
  autoCheckUpdates: Boolean,
  updateState: UpdateUiState,
  onAutoCheckChange: (Boolean) -> Unit,
  onCheckUpdates: () -> Unit,
  onDownloadAndInstall: (com.discodo.app.updater.ReleaseInfo) -> Unit,
  onRetryInstall: (java.io.File) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Mises à jour (v$currentVersion)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }

      PreferenceSwitchRow(
        title = "Vérifier au démarrage",
        subtitle = "Recherche les nouvelles releases sur GitHub",
        checked = autoCheckUpdates,
        onCheckedChange = onAutoCheckChange,
      )

      when (updateState) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Vérification sur GitHub…", style = MaterialTheme.typography.bodySmall)
          }
        }
        is UpdateUiState.UpToDate -> {
          Text(
            text = "Discodo v${updateState.version} est à jour.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1E8E3E),
          )
        }
        is UpdateUiState.Available -> {
          Text(
            text = "Nouvelle version disponible : ${updateState.release.tagName}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
          if (updateState.release.releaseNotes.isNotBlank()) {
            Text(
              text = updateState.release.releaseNotes,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Button(
            onClick = { onDownloadAndInstall(updateState.release) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Télécharger et installer (${updateState.release.tagName})")
          }
        }
        is UpdateUiState.Downloading -> {
          Text(
            text = "Téléchargement de ${updateState.release.tagName} : ${updateState.progressPct}%",
            style = MaterialTheme.typography.bodySmall,
          )
          LinearProgressIndicator(
            progress = { updateState.progressPct / 100f },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        is UpdateUiState.ReadyToInstall -> {
          Button(
            onClick = { onRetryInstall(updateState.apkFile) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Installer ${updateState.release.tagName}")
          }
        }
        is UpdateUiState.Error -> {
          Text(
            text = updateState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      OutlinedButton(
        onClick = onCheckUpdates,
        enabled =
          updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Vérifier les mises à jour")
      }
    }
  }
}

@Composable
private fun PreferenceSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun SystemToolsCard(
  ignoringBattery: Boolean,
  onTestNotification: () -> Unit,
  onTestCall: () -> Unit,
  onRequestBatteryExemption: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = "Vérification & GrapheneOS",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )

      FilledTonalButton(
        onClick = onTestNotification,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.NotificationsActive, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Tester la notification")
      }

      FilledTonalButton(
        onClick = onTestCall,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.Call, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Tester la notification d'appel")
      }

      OutlinedButton(
        onClick = onRequestBatteryExemption,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          if (ignoringBattery) {
            "Optimisation batterie désactivée ✓"
          } else {
            "Désactiver l'optimisation batterie"
          }
        )
      }
    }
  }
}
