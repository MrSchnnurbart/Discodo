package com.discodo.app.gateway

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Caches guild names, channel names, user profiles, DM recipients, current user guild roles,
 * and mute/suppression overrides parsed from Discord Gateway events.
 */
class GuildMuteCache {

  data class MuteEntry(
    val muted: Boolean = false,
    val muteEndEpochMillis: Long? = null,
    val suppressEveryone: Boolean = false,
    val suppressRoles: Boolean = false,
  ) {
    fun isCurrentlyMuted(nowEpochMillis: Long): Boolean {
      if (!muted) return false
      return muteEndEpochMillis == null || muteEndEpochMillis > nowEpochMillis
    }
  }

  private val guildNames = ConcurrentHashMap<String, String>()
  private val channelNames = ConcurrentHashMap<String, String>()
  private val dmLabels = ConcurrentHashMap<String, String>()
  private val users = ConcurrentHashMap<String, DiscordAuthor>()
  private val dmRecipientIds = ConcurrentHashMap<String, List<String>>()
  private val myGuildRoles = ConcurrentHashMap<String, Set<String>>()
  private val guildMutes = ConcurrentHashMap<String, MuteEntry>()
  private val channelMutes = ConcurrentHashMap<String, MuteEntry>()

  @Volatile private var orderedReadyGuildIds: List<String> = emptyList()

  fun clear() {
    guildNames.clear()
    channelNames.clear()
    dmLabels.clear()
    users.clear()
    dmRecipientIds.clear()
    myGuildRoles.clear()
    guildMutes.clear()
    channelMutes.clear()
    orderedReadyGuildIds = emptyList()
  }

  fun onReady(readyData: JsonObject, currentUserId: String? = null) {
    clear()
    readyData["users"]?.asArrayOrNull()?.forEach { element ->
      element.asObjectOrNull()?.let { parseUser(it) }?.let { rememberUser(it) }
    }

    val readyGuildIds = mutableListOf<String>()
    readyData["guilds"]?.asArrayOrNull()?.forEach { element ->
      val guildObj = element.asObjectOrNull() ?: return@forEach
      guildObj.stringOrNull("id")?.let { readyGuildIds.add(it) }
      parseGuild(guildObj, currentUserId)
    }
    orderedReadyGuildIds = readyGuildIds

    parseMergedMembers(readyData["merged_members"]?.asArrayOrNull(), readyGuildIds, currentUserId)

    readyData["private_channels"]?.asArrayOrNull()?.forEach { element ->
      element.asObjectOrNull()?.let { parsePrivateChannel(it) }
    }
    readyData["user_guild_settings"]?.let { settingsElement ->
      val entries =
        when (settingsElement) {
          is JsonArray -> settingsElement
          is JsonObject -> settingsElement["entries"]?.asArrayOrNull()
          else -> null
        }
      entries?.forEach { entry ->
        entry.asObjectOrNull()?.let { onUserGuildSettingsUpdate(it) }
      }
    }
  }

  fun onReadySupplemental(data: JsonObject, currentUserId: String? = null) {
    val guildIds = orderedReadyGuildIds
    if (guildIds.isEmpty()) return
    parseMergedMembers(data["merged_members"]?.asArrayOrNull(), guildIds, currentUserId)
  }

  fun rememberUser(author: DiscordAuthor) {
    users[author.id] = author
  }

  fun onGuildCreateOrUpdate(guildObj: JsonObject, currentUserId: String? = null) {
    parseGuild(guildObj, currentUserId)
  }

  fun onGuildMemberUpdate(memberEvent: JsonObject, currentUserId: String?) {
    if (currentUserId == null) return
    val guildId = memberEvent.stringOrNull("guild_id") ?: return
    val memberUserId =
      memberEvent["user"]?.asObjectOrNull()?.stringOrNull("id")
        ?: memberEvent.stringOrNull("user_id")
    if (memberUserId != currentUserId) return
    val roles = parseRoleIds(memberEvent["roles"]?.asArrayOrNull())
    myGuildRoles[guildId] = roles
  }

  fun onChannelCreateOrUpdate(channelObj: JsonObject) {
    val id = channelObj.stringOrNull("id") ?: return
    val name = channelObj.stringOrNull("name")
    if (!name.isNullOrBlank()) {
      channelNames[id] = name
    }
    if (channelObj.containsKey("recipients") || channelObj.containsKey("recipient_ids")) {
      parsePrivateChannel(channelObj)
    }
  }

  fun onUserGuildSettingsUpdate(entry: JsonObject) {
    val guildId = entry.stringOrNull("guild_id")
    val muted = entry.booleanOrDefault("muted", false)
    val muteEnd = parseMuteEndTime(entry["mute_config"]?.asObjectOrNull())
    val suppressEveryone = entry.booleanOrDefault("suppress_everyone", false)
    val suppressRoles = entry.booleanOrDefault("suppress_roles", false)

    if (guildId != null) {
      guildMutes[guildId] =
        MuteEntry(
          muted = muted,
          muteEndEpochMillis = muteEnd,
          suppressEveryone = suppressEveryone,
          suppressRoles = suppressRoles,
        )
    }

    entry["channel_overrides"]?.asArrayOrNull()?.forEach { overrideElement ->
      val overrideObj = overrideElement.asObjectOrNull() ?: return@forEach
      val channelId = overrideObj.stringOrNull("channel_id") ?: return@forEach
      val channelMuted = overrideObj.booleanOrDefault("muted", false)
      val channelMuteEnd = parseMuteEndTime(overrideObj["mute_config"]?.asObjectOrNull())
      channelMutes[channelId] =
        MuteEntry(
          muted = channelMuted,
          muteEndEpochMillis = channelMuteEnd,
        )
    }
  }

  fun getGuildName(guildId: String?): String? = guildId?.let { guildNames[it] }

  fun getChannelName(channelId: String): String? = channelNames[channelId] ?: dmLabels[channelId]

  fun isUserInAnyRole(guildId: String?, mentionedRoleIds: Set<String>): Boolean {
    if (guildId == null || mentionedRoleIds.isEmpty()) return false
    val userRoles = myGuildRoles[guildId] ?: return false
    return mentionedRoleIds.any { it in userRoles }
  }

  fun resolveCallCaller(
    channelId: String,
    currentUserId: String,
    voiceStateUserIds: List<String>,
  ): DiscordAuthor? {
    val candidateId =
      voiceStateUserIds.firstOrNull { it != currentUserId }
        ?: dmRecipientIds[channelId]?.firstOrNull { it != currentUserId }
    return candidateId?.let { users[it] }
  }

  fun isGroupDm(channelId: String): Boolean {
    return (dmRecipientIds[channelId]?.size ?: 0) > 1
  }

  fun isMuted(
    guildId: String?,
    channelId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ): Boolean {
    if (channelMutes[channelId]?.isCurrentlyMuted(nowEpochMillis) == true) {
      return true
    }
    if (guildId != null && guildMutes[guildId]?.isCurrentlyMuted(nowEpochMillis) == true) {
      return true
    }
    return false
  }

  fun isEveryoneSuppressed(guildId: String?): Boolean {
    if (guildId == null) return false
    return guildMutes[guildId]?.suppressEveryone == true
  }

  fun isRolesSuppressed(guildId: String?): Boolean {
    if (guildId == null) return false
    return guildMutes[guildId]?.suppressRoles == true
  }

  private fun parseGuild(guildObj: JsonObject, currentUserId: String?) {
    val guildId = guildObj.stringOrNull("id") ?: return
    val name =
      guildObj.stringOrNull("name")
        ?: guildObj["properties"]?.asObjectOrNull()?.stringOrNull("name")
    if (!name.isNullOrBlank()) {
      guildNames[guildId] = name
    }

    guildObj["channels"]?.asArrayOrNull()?.forEach { channelElement ->
      val channelObj = channelElement.asObjectOrNull() ?: return@forEach
      val channelId = channelObj.stringOrNull("id") ?: return@forEach
      val channelName = channelObj.stringOrNull("name")
      if (!channelName.isNullOrBlank()) {
        channelNames[channelId] = channelName
      }
    }

    if (currentUserId != null) {
      guildObj["members"]?.asArrayOrNull()?.forEach { memberElement ->
        val memberObj = memberElement.asObjectOrNull() ?: return@forEach
        val memberId =
          memberObj["user"]?.asObjectOrNull()?.stringOrNull("id")
            ?: memberObj.stringOrNull("user_id")
        if (memberId == currentUserId) {
          myGuildRoles[guildId] = parseRoleIds(memberObj["roles"]?.asArrayOrNull())
        }
      }
    }
  }

  private fun parseMergedMembers(
    mergedMembers: JsonArray?,
    orderedGuildIds: List<String>,
    currentUserId: String?,
  ) {
    if (mergedMembers == null || currentUserId == null) return
    val count = minOf(mergedMembers.size, orderedGuildIds.size)
    for (i in 0 until count) {
      val guildId = orderedGuildIds[i]
      val guildMembers = mergedMembers[i].asArrayOrNull() ?: continue
      for (memberElement in guildMembers) {
        val memberObj = memberElement.asObjectOrNull() ?: continue
        val memberId =
          memberObj.stringOrNull("user_id")
            ?: memberObj["user"]?.asObjectOrNull()?.stringOrNull("id")
        if (memberId == currentUserId) {
          myGuildRoles[guildId] = parseRoleIds(memberObj["roles"]?.asArrayOrNull())
          break
        }
      }
    }
  }

  private fun parseRoleIds(rolesArray: JsonArray?): Set<String> {
    if (rolesArray == null) return emptySet()
    return rolesArray.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.toSet()
  }

  private fun parsePrivateChannel(channelObj: JsonObject) {
    val id = channelObj.stringOrNull("id") ?: return
    val recipientIds = mutableListOf<String>()

    channelObj["recipients"]?.asArrayOrNull()?.forEach { recipient ->
      val user = recipient.asObjectOrNull()?.let { parseUser(it) } ?: return@forEach
      rememberUser(user)
      recipientIds.add(user.id)
    }

    if (recipientIds.isEmpty()) {
      channelObj["recipient_ids"]?.asArrayOrNull()?.forEach { rawId ->
        val uid = runCatching { rawId.jsonPrimitive.content }.getOrNull()
        if (!uid.isNullOrBlank()) recipientIds.add(uid)
      }
    }

    if (recipientIds.isNotEmpty()) {
      dmRecipientIds[id] = recipientIds
    }

    val explicitName = channelObj.stringOrNull("name")
    if (!explicitName.isNullOrBlank()) {
      dmLabels[id] = explicitName
      return
    }
    if (recipientIds.size > 1) {
      val names = recipientIds.mapNotNull { users[it]?.displayName }
      if (names.isNotEmpty()) {
        dmLabels[id] = names.joinToString(", ")
      }
    }
  }

  private fun parseUser(userObj: JsonObject): DiscordAuthor? {
    val id = userObj.stringOrNull("id") ?: return null
    val username = userObj.stringOrNull("username") ?: "Utilisateur"
    return DiscordAuthor(
      id = id,
      username = username,
      globalName = userObj.stringOrNull("global_name"),
      avatar = userObj.stringOrNull("avatar"),
      discriminator = userObj.stringOrNull("discriminator") ?: "0",
    )
  }

  private fun parseMuteEndTime(muteConfig: JsonObject?): Long? {
    val endTimeIso = muteConfig?.stringOrNull("end_time") ?: return null
    return runCatching { Instant.parse(endTimeIso).toEpochMilli() }.getOrNull()
  }
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject.stringOrNull(key: String): String? =
  runCatching {
    val primitive = this[key]?.jsonPrimitive ?: return null
    if (!primitive.isString) return null
    primitive.content
  }.getOrNull()

internal fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
  runCatching { this[key]?.jsonPrimitive?.booleanOrNull ?: default }.getOrDefault(default)
