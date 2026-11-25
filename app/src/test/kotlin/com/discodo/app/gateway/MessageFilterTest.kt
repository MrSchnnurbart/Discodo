package com.discodo.app.gateway

import com.discodo.app.data.NotificationPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFilterTest {

  private val filter = MessageFilter()
  private val json = Json { ignoreUnknownKeys = true }
  private val myUserId = "100000000000000001"

  @Test
  fun ignoresOwnMessages() {
    val cache = GuildMuteCache()
    val event =
      MessageCreateEvent(
        id = "msg1",
        channelId = "ch1",
        guildId = null,
        author = DiscordAuthor(id = myUserId, username = "me"),
        content = "hello",
        mentionIds = emptySet(),
        mentionEveryone = false,
      )

    val result = filter.evaluate(event, myUserId, NotificationPreferences(), cache)
    assertTrue(result is FilterResult.Ignore)
  }

  @Test
  fun notifiesDirectMessageWithDeepLinkAndRespectsToggle() {
    val cache = GuildMuteCache()
    val event =
      MessageCreateEvent(
        id = "msg2",
        channelId = "999888777",
        guildId = null,
        author = DiscordAuthor(id = "222", username = "alice", globalName = "Alice", avatar = "abc123"),
        content = "Salut !",
        mentionIds = emptySet(),
        mentionEveryone = false,
      )

    val notifyResult = filter.evaluate(event, myUserId, NotificationPreferences(notifyDm = true), cache)
    assertTrue(notifyResult is FilterResult.Notify)
    val payload = (notifyResult as FilterResult.Notify).payload
    assertEquals(NotificationCategory.DIRECT_MESSAGE, payload.category)
    assertEquals("Alice", payload.authorDisplayName)
    assertEquals("https://cdn.discordapp.com/avatars/222/abc123.png?size=128", payload.authorAvatarUrl)
    assertEquals("Salut !", payload.bodyText)
    assertEquals("https://discord.com/channels/@me/999888777", payload.deepLinkUrl)

    val disabledResult =
      filter.evaluate(event, myUserId, NotificationPreferences(notifyDm = false), cache)
    assertTrue(disabledResult is FilterResult.Ignore)
  }

  @Test
  fun notifiesDirectMentionAndResolvesGuildAndChannelNames() {
    val readyJson =
      json.parseToJsonElement(
        """
        {
          "guilds": [
            {
              "id": "guild_1",
              "properties": { "name": "Graphene Club" },
              "channels": [
                { "id": "chan_1", "name": "general" }
              ]
            }
          ]
        }
        """
      ).jsonObject

    val cache = GuildMuteCache().apply { onReady(readyJson, myUserId) }
    val event =
      MessageCreateEvent(
        id = "msg3",
        channelId = "chan_1",
        guildId = "guild_1",
        author = DiscordAuthor(id = "333", username = "bob"),
        content = "<@$myUserId> check this",
        mentionIds = setOf(myUserId),
        mentionEveryone = false,
      )

    val result = filter.evaluate(event, myUserId, NotificationPreferences(), cache)
    assertTrue(result is FilterResult.Notify)
    val payload = (result as FilterResult.Notify).payload
    assertEquals(NotificationCategory.MENTION, payload.category)
    assertEquals("Graphene Club", payload.guildName)
    assertEquals("general", payload.channelName)
    assertEquals("https://discord.com/channels/guild_1/chan_1", payload.deepLinkUrl)
  }

  @Test
  fun respectsEveryoneToggleAndGuildSuppression() {
    val readyJson =
      json.parseToJsonElement(
        """
        {
          "user_guild_settings": {
            "entries": [
              {
                "guild_id": "guild_suppressed",
                "muted": false,
                "suppress_everyone": true
              }
            ]
          }
        }
        """
      ).jsonObject

    val cache = GuildMuteCache().apply { onReady(readyJson, myUserId) }
    val normalGuildEvent =
      MessageCreateEvent(
        id = "msg4",
        channelId = "c1",
        guildId = "guild_normal",
        author = DiscordAuthor(id = "444", username = "mod"),
        content = "@everyone announcement",
        mentionIds = emptySet(),
        mentionEveryone = true,
      )

    assertTrue(
      filter.evaluate(normalGuildEvent, myUserId, NotificationPreferences(notifyEveryone = false), cache)
        is FilterResult.Ignore
    )
    val everyoneResult =
      filter.evaluate(normalGuildEvent, myUserId, NotificationPreferences(notifyEveryone = true), cache)
    assertTrue(everyoneResult is FilterResult.Notify)
    assertEquals(
      NotificationCategory.EVERYONE,
      (everyoneResult as FilterResult.Notify).payload.category,
    )

    val suppressedEvent = normalGuildEvent.copy(guildId = "guild_suppressed")
    assertTrue(
      filter.evaluate(suppressedEvent, myUserId, NotificationPreferences(notifyEveryone = true), cache)
        is FilterResult.Ignore
    )
  }

  @Test
  fun notifiesRoleMentionsOnlyForUsersRolesAndRespectsSuppressionAndToggle() {
    val readyJson =
      json.parseToJsonElement(
        """
        {
          "guilds": [
            { "id": "g_roles", "properties": { "name": "Dev Server" } },
            { "id": "g_suppressed", "properties": { "name": "Muted Roles" } }
          ],
          "merged_members": [
            [{ "user_id": "$myUserId", "roles": ["role_dev", "role_ops"] }],
            [{ "user_id": "$myUserId", "roles": ["role_suppressed"] }]
          ],
          "user_guild_settings": [
            { "guild_id": "g_suppressed", "suppress_roles": true }
          ]
        }
        """
      ).jsonObject

    val cache = GuildMuteCache().apply { onReady(readyJson, myUserId) }

    val matchingRoleEvent =
      MessageCreateEvent(
        id = "msg_r1",
        channelId = "ch_dev",
        guildId = "g_roles",
        author = DiscordAuthor(id = "777", username = "lead"),
        content = "<@&role_dev> deploy ready",
        mentionIds = emptySet(),
        mentionRoles = setOf("role_dev"),
        mentionEveryone = false,
      )

    val roleNotify =
      filter.evaluate(matchingRoleEvent, myUserId, NotificationPreferences(notifyRoles = true), cache)
    assertTrue(roleNotify is FilterResult.Notify)
    assertEquals(NotificationCategory.ROLE_MENTION, (roleNotify as FilterResult.Notify).payload.category)

    // Toggle off -> ignored
    assertTrue(
      filter.evaluate(matchingRoleEvent, myUserId, NotificationPreferences(notifyRoles = false), cache)
        is FilterResult.Ignore
    )

    // Other role user does not belong to -> ignored
    val otherRoleEvent = matchingRoleEvent.copy(mentionRoles = setOf("role_marketing"))
    assertTrue(
      filter.evaluate(otherRoleEvent, myUserId, NotificationPreferences(notifyRoles = true), cache)
        is FilterResult.Ignore
    )

    // Guild with suppress_roles -> ignored
    val suppressedRoleEvent =
      matchingRoleEvent.copy(guildId = "g_suppressed", mentionRoles = setOf("role_suppressed"))
    assertTrue(
      filter.evaluate(suppressedRoleEvent, myUserId, NotificationPreferences(notifyRoles = true), cache)
        is FilterResult.Ignore
    )
  }

  @Test
  fun ignoresCallSystemMessageType3() {
    val cache = GuildMuteCache()
    val callSystemMsg =
      MessageCreateEvent(
        id = "msg_call",
        channelId = "dm_1",
        guildId = null,
        author = DiscordAuthor(id = "222", username = "alice"),
        content = "",
        mentionIds = emptySet(),
        mentionEveryone = false,
        type = MessageFilter.MESSAGE_TYPE_CALL,
      )

    assertTrue(filter.evaluate(callSystemMsg, myUserId, NotificationPreferences(), cache) is FilterResult.Ignore)
  }

  @Test
  fun respectsMutedGuildAndChannelWithTimedExpiration() {
    val readyJson =
      json.parseToJsonElement(
        """
        {
          "user_guild_settings": [
            {
              "guild_id": "guild_muted_forever",
              "muted": true,
              "mute_config": { "end_time": null }
            },
            {
              "guild_id": "guild_timed",
              "muted": true,
              "mute_config": { "end_time": "2026-01-01T12:00:00Z" },
              "channel_overrides": [
                {
                  "channel_id": "chan_muted_forever",
                  "muted": true,
                  "mute_config": { "end_time": null }
                }
              ]
            }
          ]
        }
        """
      ).jsonObject

    val cache = GuildMuteCache().apply { onReady(readyJson, myUserId) }
    val beforeExpiry = java.time.Instant.parse("2026-01-01T11:00:00Z").toEpochMilli()
    val afterExpiry = java.time.Instant.parse("2026-01-01T13:00:00Z").toEpochMilli()

    assertTrue(cache.isMuted("guild_muted_forever", "any_channel", afterExpiry))
    assertTrue(cache.isMuted("guild_timed", "other_channel", beforeExpiry))
    assertFalse(cache.isMuted("guild_timed", "other_channel", afterExpiry))
    assertTrue(cache.isMuted("guild_timed", "chan_muted_forever", afterExpiry))

    val attachmentOnlyEvent =
      MessageCreateEvent(
        id = "msg5",
        channelId = "other_channel",
        guildId = "guild_timed",
        author = DiscordAuthor(id = "555", username = "carol"),
        content = "",
        mentionIds = setOf(myUserId),
        mentionEveryone = false,
        hasAttachments = true,
      )

    assertTrue(
      filter.evaluate(
        attachmentOnlyEvent,
        myUserId,
        NotificationPreferences(respectMutedSettings = true),
        cache,
        beforeExpiry,
      ) is FilterResult.Ignore
    )
    val notified =
      filter.evaluate(
        attachmentOnlyEvent,
        myUserId,
        NotificationPreferences(respectMutedSettings = true),
        cache,
        afterExpiry,
      )
    assertTrue(notified is FilterResult.Notify)
    assertEquals("[Pièce jointe]", (notified as FilterResult.Notify).payload.bodyText)
  }
}
