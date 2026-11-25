package com.discodo.app.gateway

import com.discodo.app.data.NotificationPreferences

sealed interface FilterResult {
  data class Notify(val payload: NotificationPayload) : FilterResult

  data class Ignore(val reason: String) : FilterResult
}

/**
 * Evaluates incoming Discord MESSAGE_CREATE events against user preferences, user identity,
 * user roles, and guild/channel mute settings.
 */
class MessageFilter {

  fun evaluate(
    event: MessageCreateEvent,
    currentUserId: String,
    preferences: NotificationPreferences,
    muteCache: GuildMuteCache,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ): FilterResult {
    if (event.author.id == currentUserId) {
      return FilterResult.Ignore("Message authored by current user")
    }

    if (event.type == MESSAGE_TYPE_CALL) {
      return FilterResult.Ignore("Call system message handled by CALL_CREATE/CALL_UPDATE")
    }

    if (
      preferences.respectMutedSettings &&
        muteCache.isMuted(event.guildId, event.channelId, nowEpochMillis)
    ) {
      return FilterResult.Ignore("Channel or guild is muted")
    }

    val category =
      when {
        event.guildId == null -> {
          if (!preferences.notifyDm) {
            return FilterResult.Ignore("DM notifications disabled")
          }
          NotificationCategory.DIRECT_MESSAGE
        }
        currentUserId in event.mentionIds -> {
          if (!preferences.notifyMentions) {
            return FilterResult.Ignore("Mention notifications disabled")
          }
          NotificationCategory.MENTION
        }
        event.mentionEveryone -> {
          if (
            preferences.respectMutedSettings && muteCache.isEveryoneSuppressed(event.guildId)
          ) {
            return FilterResult.Ignore("@everyone suppressed for guild")
          }
          if (!preferences.notifyEveryone) {
            return FilterResult.Ignore("@everyone notifications disabled")
          }
          NotificationCategory.EVERYONE
        }
        muteCache.isUserInAnyRole(event.guildId, event.mentionRoles) -> {
          if (preferences.respectMutedSettings && muteCache.isRolesSuppressed(event.guildId)) {
            return FilterResult.Ignore("Role mentions suppressed for guild")
          }
          if (!preferences.notifyRoles) {
            return FilterResult.Ignore("Role mention notifications disabled")
          }
          NotificationCategory.ROLE_MENTION
        }
        else -> return FilterResult.Ignore("Not a DM or relevant mention")
      }

    val bodyText =
      when {
        event.content.isNotBlank() -> event.content.trim()
        event.hasAttachments -> "[Pièce jointe]"
        event.hasEmbeds -> "[Contenu intégré]"
        else -> "[Nouveau message]"
      }

    return FilterResult.Notify(
      NotificationPayload(
        messageId = event.id,
        channelId = event.channelId,
        guildId = event.guildId,
        authorDisplayName = event.author.displayName,
        authorAvatarUrl = event.author.avatarUrl(),
        channelName = muteCache.getChannelName(event.channelId),
        guildName = muteCache.getGuildName(event.guildId),
        bodyText = bodyText,
        category = category,
      )
    )
  }

  companion object {
    const val MESSAGE_TYPE_CALL = 3
  }
}
