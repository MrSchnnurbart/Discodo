package com.discodo.app.gateway

object GatewayOpcodes {
  const val DISPATCH = 0
  const val HEARTBEAT = 1
  const val IDENTIFY = 2
  const val RESUME = 6
  const val RECONNECT = 7
  const val INVALID_SESSION = 9
  const val HELLO = 10
  const val HEARTBEAT_ACK = 11
}

data class DiscordAuthor(
  val id: String,
  val username: String,
  val globalName: String? = null,
  val avatar: String? = null,
  val discriminator: String = "0",
) {
  val displayName: String
    get() = globalName?.takeIf { it.isNotBlank() } ?: username

  fun avatarUrl(size: Int = 128): String {
    val avatarHash = avatar?.takeIf { it.isNotBlank() }
    if (avatarHash != null) {
      return "https://cdn.discordapp.com/avatars/$id/$avatarHash.png?size=$size"
    }
    val defaultIndex =
      if (discriminator == "0" || discriminator.isBlank()) {
        val snowflake = id.toLongOrNull() ?: 0L
        ((snowflake ushr 22) % 6L).toInt().coerceIn(0, 5)
      } else {
        (discriminator.toIntOrNull() ?: 0) % 5
      }
    return "https://cdn.discordapp.com/embed/avatars/$defaultIndex.png"
  }
}

data class MessageCreateEvent(
  val id: String,
  val channelId: String,
  val guildId: String?,
  val author: DiscordAuthor,
  val content: String,
  val mentionIds: Set<String>,
  val mentionRoles: Set<String> = emptySet(),
  val mentionEveryone: Boolean,
  val hasAttachments: Boolean = false,
  val hasEmbeds: Boolean = false,
  val type: Int = 0,
)

enum class NotificationCategory {
  DIRECT_MESSAGE,
  MENTION,
  EVERYONE,
  ROLE_MENTION,
}

data class NotificationPayload(
  val messageId: String,
  val channelId: String,
  val guildId: String?,
  val authorDisplayName: String,
  val authorAvatarUrl: String? = null,
  val channelName: String?,
  val guildName: String?,
  val bodyText: String,
  val category: NotificationCategory,
) {
  val deepLinkUrl: String
    get() =
      if (guildId == null) {
        "https://discord.com/channels/@me/$channelId"
      } else {
        "https://discord.com/channels/$guildId/$channelId"
      }
}

data class IncomingCallPayload(
  val channelId: String,
  val messageId: String?,
  val callerName: String,
  val callerAvatarUrl: String? = null,
  val isGroupCall: Boolean = false,
) {
  val deepLinkUrl: String
    get() = "https://discord.com/channels/@me/$channelId"
}
