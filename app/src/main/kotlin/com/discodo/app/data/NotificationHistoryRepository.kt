package com.discodo.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class HistoryCategory {
  DM,
  MENTION,
  EVERYONE,
  ROLE,
  CALL,
}

@Serializable
data class NotificationHistoryItem(
  val id: String,
  val timestampEpochMs: Long,
  val category: HistoryCategory,
  val authorName: String,
  val guildName: String? = null,
  val channelName: String? = null,
  val bodyText: String,
  val deepLinkUrl: String,
)

/**
 * Stores notified messages and incoming calls on disk for the last 30 days,
 * automatically pruning expired entries whenever loaded or updated.
 */
class NotificationHistoryRepository(
  private val storageFile: File,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  constructor(context: Context) : this(File(context.filesDir, HISTORY_FILE_NAME))

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val lock = Any()
  private val _items = MutableStateFlow(loadAndPruneLocked())
  val items: StateFlow<List<NotificationHistoryItem>> = _items.asStateFlow()

  fun record(item: NotificationHistoryItem) {
    synchronized(lock) {
      val now = clock()
      val cutoff = now - RETENTION_MS
      val updated =
        (listOf(item) + _items.value.filter { it.id != item.id })
          .filter { it.timestampEpochMs >= cutoff }
          .take(MAX_ITEMS)
      persistLocked(updated)
      _items.value = updated
    }
  }

  fun pruneExpired() {
    synchronized(lock) {
      val cutoff = clock() - RETENTION_MS
      val filtered = _items.value.filter { it.timestampEpochMs >= cutoff }
      if (filtered.size != _items.value.size) {
        persistLocked(filtered)
        _items.value = filtered
      }
    }
  }

  fun clearHistory() {
    synchronized(lock) {
      if (storageFile.exists()) {
        storageFile.delete()
      }
      _items.value = emptyList()
    }
  }

  private fun loadAndPruneLocked(): List<NotificationHistoryItem> {
    if (!storageFile.exists()) return emptyList()
    val parsed =
      runCatching {
          json.decodeFromString<List<NotificationHistoryItem>>(storageFile.readText())
        }
        .getOrDefault(emptyList())
    val cutoff = clock() - RETENTION_MS
    val pruned = parsed.filter { it.timestampEpochMs >= cutoff }.take(MAX_ITEMS)
    if (pruned.size != parsed.size) {
      persistLocked(pruned)
    }
    return pruned
  }

  private fun persistLocked(items: List<NotificationHistoryItem>) {
    runCatching {
      storageFile.parentFile?.mkdirs()
      storageFile.writeText(json.encodeToString(items))
    }
  }

  companion object {
    const val HISTORY_FILE_NAME = "notification_history.json"
    const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_ITEMS = 1000
  }
}
