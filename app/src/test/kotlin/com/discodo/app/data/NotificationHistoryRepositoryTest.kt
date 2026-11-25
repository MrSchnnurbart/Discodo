package com.discodo.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationHistoryRepositoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun recordsPrunesOlderThan30DaysAndClearsHistory() {
    val file = File(tempFolder.root, "history.json")
    var nowMs = 1_700_000_000_000L
    val repo = NotificationHistoryRepository(file) { nowMs }

    val oldMs = nowMs - NotificationHistoryRepository.RETENTION_MS - 10_000L
    val recentMs = nowMs - 60_000L

    repo.record(
      NotificationHistoryItem(
        id = "old_1",
        timestampEpochMs = oldMs,
        category = HistoryCategory.DM,
        authorName = "OldUser",
        bodyText = "Expired message",
        deepLinkUrl = "https://discord.com/channels/@me/1",
      )
    )
    repo.record(
      NotificationHistoryItem(
        id = "recent_1",
        timestampEpochMs = recentMs,
        category = HistoryCategory.ROLE,
        authorName = "Alice",
        guildName = "Server",
        channelName = "dev",
        bodyText = "<@&role> check",
        deepLinkUrl = "https://discord.com/channels/10/20",
      )
    )

    assertEquals(1, repo.items.value.size)
    assertEquals("recent_1", repo.items.value.first().id)

    // Reload from disk with a new instance -> recent item persists
    val reloaded = NotificationHistoryRepository(file) { nowMs }
    assertEquals(1, reloaded.items.value.size)
    assertEquals("Alice", reloaded.items.value.first().authorName)

    // Advance clock past 30 days -> pruned automatically
    nowMs = recentMs + NotificationHistoryRepository.RETENTION_MS + 1_000L
    reloaded.pruneExpired()
    assertTrue(reloaded.items.value.isEmpty())

    // Record another and clear explicitly
    reloaded.record(
      NotificationHistoryItem(
        id = "new_1",
        timestampEpochMs = nowMs,
        category = HistoryCategory.CALL,
        authorName = "Bob",
        bodyText = "Appel vocal Discord",
        deepLinkUrl = "https://discord.com/channels/@me/2",
      )
    )
    assertEquals(1, reloaded.items.value.size)
    reloaded.clearHistory()
    assertTrue(reloaded.items.value.isEmpty())
  }
}
