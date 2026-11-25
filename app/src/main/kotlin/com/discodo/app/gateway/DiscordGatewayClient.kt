package com.discodo.app.gateway

import android.util.Log
import com.discodo.app.data.GatewayStatus
import com.discodo.app.data.NotificationPreferences
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Manages a persistent OkHttp WebSocket connection to the Discord Gateway (v10),
 * handling HELLO, jittered HEARTBEAT + HEARTBEAT_ACK verification, IDENTIFY,
 * RESUME, exponential backoff reconnection, and DISPATCH message/call filtering.
 *
 * Security invariant: the Discord token is never written to logs.
 */
class DiscordGatewayClient(
  private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
  private val gatewayBaseUrl: String = DEFAULT_GATEWAY_URL,
  private val tokenProvider: () -> String?,
  private val preferencesProvider: () -> NotificationPreferences,
  private val onStatusChanged: (GatewayStatus) -> Unit,
  private val onNotification: (NotificationPayload) -> Unit,
  private val onIncomingCall: (IncomingCallPayload) -> Unit = {},
  private val onCallEnded: (String) -> Unit = {},
  private val muteCache: GuildMuteCache = GuildMuteCache(),
  private val messageFilter: MessageFilter = MessageFilter(),
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val baseReconnectDelayMs: Long = DEFAULT_BASE_RECONNECT_DELAY_MS,
  private val maxReconnectDelayMs: Long = DEFAULT_MAX_RECONNECT_DELAY_MS,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val stateLock = Any()

  @Volatile private var isRunning = false
  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var heartbeatJob: Job? = null
  @Volatile private var reconnectJob: Job? = null

  @Volatile private var currentUserId: String? = null
  @Volatile private var currentUsername: String = "Discord"
  @Volatile private var sessionId: String? = null
  @Volatile private var resumeGatewayUrl: String? = null
  @Volatile private var lastSequence: Long? = null
  @Volatile private var awaitingHeartbeatAck = false
  @Volatile private var reconnectAttempt = 0
  private val activeRingingChannels = Collections.synchronizedSet(mutableSetOf<String>())

  fun start() {
    synchronized(stateLock) {
      if (isRunning) return
      isRunning = true
      reconnectAttempt = 0
    }
    connect(useResumeUrl = false)
  }

  fun stop() {
    val socketToClose: WebSocket?
    synchronized(stateLock) {
      isRunning = false
      reconnectJob?.cancel()
      reconnectJob = null
      heartbeatJob?.cancel()
      heartbeatJob = null
      socketToClose = webSocket
      webSocket = null
      awaitingHeartbeatAck = false
    }
    endAllActiveCalls()
    socketToClose?.close(NORMAL_CLOSE_CODE, "Stopped by user")
    onStatusChanged(GatewayStatus.Disconnected)
  }

  fun shutdown() {
    stop()
    scope.cancel()
  }

  /**
   * Triggers an immediate reconnect if the client is running and currently disconnected
   * or waiting on a backoff timer (for example, when network connectivity returns).
   */
  fun reconnectNow() {
    synchronized(stateLock) {
      if (!isRunning) return
      if (webSocket != null) return
      reconnectJob?.cancel()
      reconnectJob = null
      reconnectAttempt = 0
    }
    connect(useResumeUrl = sessionId != null && lastSequence != null)
  }

  private fun endAllActiveCalls() {
    val channels =
      synchronized(activeRingingChannels) {
        activeRingingChannels.toList().also { activeRingingChannels.clear() }
      }
    channels.forEach { onCallEnded(it) }
  }

  private fun connect(useResumeUrl: Boolean) {
    val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
    if (token == null) {
      synchronized(stateLock) { isRunning = false }
      onStatusChanged(GatewayStatus.Error("Token Discord manquant", isAuthError = true))
      return
    }

    val targetUrl =
      if (useResumeUrl && !resumeGatewayUrl.isNullOrBlank()) {
        normalizeGatewayUrl(resumeGatewayUrl!!)
      } else {
        normalizeGatewayUrl(gatewayBaseUrl)
      }

    synchronized(stateLock) {
      if (!isRunning) return
      heartbeatJob?.cancel()
      heartbeatJob = null
      webSocket?.cancel()
      awaitingHeartbeatAck = false
    }

    onStatusChanged(GatewayStatus.Connecting)

    val request =
      Request.Builder()
        .url(targetUrl)
        .header("User-Agent", USER_AGENT)
        .build()

    val socket = okHttpClient.newWebSocket(request, GatewayWebSocketListener())
    synchronized(stateLock) {
      if (isRunning) {
        webSocket = socket
      } else {
        socket.close(NORMAL_CLOSE_CODE, "Stopped")
      }
    }
  }

  private inner class GatewayWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      Log.i(TAG, "WebSocket opened")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      if (webSocket !== this@DiscordGatewayClient.webSocket) return
      handleGatewayFrame(webSocket, text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
      webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (webSocket !== this@DiscordGatewayClient.webSocket) return
      handleDisconnect(code, reason.ifBlank { "Connexion fermée ($code)" })
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (webSocket !== this@DiscordGatewayClient.webSocket) return
      val msg = t.message?.take(120) ?: t.javaClass.simpleName
      Log.w(TAG, "WebSocket failure: $msg")
      handleDisconnect(INTERNAL_RECONNECT_CODE, msg)
    }
  }

  private fun handleGatewayFrame(socket: WebSocket, rawJson: String) {
    val frame =
      runCatching { json.parseToJsonElement(rawJson).jsonObject }
        .getOrElse {
          Log.w(TAG, "Failed to parse gateway frame: ${it.message}")
          return
        }

    val op = frame["op"]?.jsonPrimitive?.intOrNull ?: return
    val seq = frame["s"]?.let { if (it is JsonNull) null else it.jsonPrimitive.longOrNull }
    if (seq != null) {
      lastSequence = seq
    }

    when (op) {
      GatewayOpcodes.HELLO -> {
        val intervalMs =
          frame["d"]?.asObjectOrNull()?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull
            ?: DEFAULT_HEARTBEAT_INTERVAL_MS
        startHeartbeatLoop(socket, intervalMs)
        sendIdentifyOrResume(socket)
      }
      GatewayOpcodes.HEARTBEAT_ACK -> {
        awaitingHeartbeatAck = false
        val userId = currentUserId
        if (userId != null) {
          onStatusChanged(
            GatewayStatus.Connected(
              username = currentUsername,
              userId = userId,
              lastHeartbeatAckEpochMs = clock(),
            )
          )
        }
      }
      GatewayOpcodes.HEARTBEAT -> {
        sendHeartbeat(socket)
      }
      GatewayOpcodes.RECONNECT -> {
        Log.i(TAG, "Gateway requested RECONNECT (OP 7)")
        socket.close(INTERNAL_RECONNECT_CODE, "Gateway requested reconnect")
      }
      GatewayOpcodes.INVALID_SESSION -> {
        val resumable = frame["d"]?.jsonPrimitive?.booleanOrNull == true
        Log.w(TAG, "Gateway INVALID_SESSION (OP 9), resumable=$resumable")
        if (!resumable) {
          clearSessionState()
        }
        socket.close(INTERNAL_RECONNECT_CODE, "Invalid session")
      }
      GatewayOpcodes.DISPATCH -> {
        val eventName = frame["t"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val dataObj = frame["d"]?.asObjectOrNull() ?: return
        if (eventName != null) {
          handleDispatch(eventName, dataObj)
        }
      }
    }
  }

  private fun startHeartbeatLoop(socket: WebSocket, intervalMs: Long) {
    heartbeatJob?.cancel()
    val safeInterval = intervalMs.coerceAtLeast(100L)
    heartbeatJob =
      scope.launch {
        val jitterFactor = Random.nextDouble(0.1, 0.9)
        delay((safeInterval * jitterFactor).toLong().coerceAtLeast(50L))
        while (isActive && isRunning && webSocket === socket) {
          if (awaitingHeartbeatAck) {
            Log.w(TAG, "Missing HEARTBEAT_ACK; closing zombie connection")
            socket.close(INTERNAL_RECONNECT_CODE, "Heartbeat ACK timeout")
            return@launch
          }
          awaitingHeartbeatAck = true
          sendHeartbeat(socket)
          delay(safeInterval)
        }
      }
  }

  private fun sendHeartbeat(socket: WebSocket) {
    val seqElement = lastSequence?.let { JsonPrimitive(it) } ?: JsonNull
    val payload =
      buildJsonObject {
        put("op", JsonPrimitive(GatewayOpcodes.HEARTBEAT))
        put("d", seqElement)
      }
    socket.send(payload.toString())
  }

  private fun sendIdentifyOrResume(socket: WebSocket) {
    val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
    if (token == null) {
      stopWithAuthError("Token Discord manquant")
      socket.close(NORMAL_CLOSE_CODE, "Missing token")
      return
    }

    val existingSessionId = sessionId
    val existingSeq = lastSequence
    if (existingSessionId != null && existingSeq != null) {
      val resumePayload =
        buildJsonObject {
          put("op", JsonPrimitive(GatewayOpcodes.RESUME))
          put(
            "d",
            buildJsonObject {
              put("token", JsonPrimitive(token))
              put("session_id", JsonPrimitive(existingSessionId))
              put("seq", JsonPrimitive(existingSeq))
            },
          )
        }
      socket.send(resumePayload.toString())
      Log.i(TAG, "Sent RESUME (OP 6)")
    } else {
      val identifyPayload =
        buildJsonObject {
          put("op", JsonPrimitive(GatewayOpcodes.IDENTIFY))
          put(
            "d",
            buildJsonObject {
              put("token", JsonPrimitive(token))
              put(
                "properties",
                buildJsonObject {
                  put("os", JsonPrimitive("Android"))
                  put("browser", JsonPrimitive("Discord Android"))
                  put("device", JsonPrimitive("Android"))
                },
              )
              put("compress", JsonPrimitive(false))
            },
          )
        }
      socket.send(identifyPayload.toString())
      Log.i(TAG, "Sent IDENTIFY (OP 2)")
    }
  }

  private fun handleDispatch(eventName: String, data: JsonObject) {
    when (eventName) {
      "READY" -> {
        val userObj = data["user"]?.asObjectOrNull() ?: return
        val userId = userObj.stringOrNull("id") ?: return
        val username =
          userObj.stringOrNull("global_name") ?: userObj.stringOrNull("username") ?: "Discord"

        currentUserId = userId
        currentUsername = username
        sessionId = data.stringOrNull("session_id")
        resumeGatewayUrl = data.stringOrNull("resume_gateway_url")
        reconnectAttempt = 0

        muteCache.onReady(data, userId)
        endAllActiveCalls()
        onStatusChanged(
          GatewayStatus.Connected(
            username = username,
            userId = userId,
            lastHeartbeatAckEpochMs = clock(),
          )
        )
        Log.i(TAG, "Gateway READY for user $userId")
      }
      "READY_SUPPLEMENTAL" -> {
        muteCache.onReadySupplemental(data, currentUserId)
      }
      "RESUMED" -> {
        reconnectAttempt = 0
        val userId = currentUserId
        if (userId != null) {
          onStatusChanged(
            GatewayStatus.Connected(
              username = currentUsername,
              userId = userId,
              lastHeartbeatAckEpochMs = clock(),
            )
          )
        }
        Log.i(TAG, "Gateway session RESUMED")
      }
      "GUILD_CREATE", "GUILD_UPDATE" -> muteCache.onGuildCreateOrUpdate(data, currentUserId)
      "GUILD_MEMBER_UPDATE" -> muteCache.onGuildMemberUpdate(data, currentUserId)
      "CHANNEL_CREATE", "CHANNEL_UPDATE" -> muteCache.onChannelCreateOrUpdate(data)
      "USER_GUILD_SETTINGS_UPDATE" -> muteCache.onUserGuildSettingsUpdate(data)
      "MESSAGE_CREATE" -> handleMessageCreate(data)
      "CALL_CREATE", "CALL_UPDATE" -> handleCallCreateOrUpdate(data)
      "CALL_DELETE" -> handleCallDelete(data)
    }
  }

  private fun handleCallCreateOrUpdate(data: JsonObject) {
    val userId = currentUserId ?: return
    val channelId = data.stringOrNull("channel_id") ?: return
    val ringingIds =
      data["ringing"]
        ?.asArrayOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.toSet()
        ?: emptySet()

    if (userId in ringingIds) {
      val prefs = preferencesProvider()
      if (!prefs.notifyCalls) return
      if (prefs.respectMutedSettings && muteCache.isMuted(null, channelId, clock())) return

      if (activeRingingChannels.add(channelId)) {
        val voiceStateUserIds =
          data["voice_states"]
            ?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.stringOrNull("user_id") }
            ?: emptyList()
        val caller = muteCache.resolveCallCaller(channelId, userId, voiceStateUserIds)
        val callerName =
          caller?.displayName ?: muteCache.getChannelName(channelId) ?: "Appel Discord"
        onIncomingCall(
          IncomingCallPayload(
            channelId = channelId,
            messageId = data.stringOrNull("message_id"),
            callerName = callerName,
            callerAvatarUrl = caller?.avatarUrl(),
            isGroupCall = muteCache.isGroupDm(channelId),
          )
        )
      }
    } else if (activeRingingChannels.remove(channelId)) {
      onCallEnded(channelId)
    }
  }

  private fun handleCallDelete(data: JsonObject) {
    val channelId = data.stringOrNull("channel_id") ?: return
    if (activeRingingChannels.remove(channelId)) {
      onCallEnded(channelId)
    }
  }

  private fun handleMessageCreate(data: JsonObject) {
    val userId = currentUserId ?: return
    val message = parseMessageCreate(data) ?: return
    muteCache.rememberUser(message.author)

    when (
      val result =
        messageFilter.evaluate(
          event = message,
          currentUserId = userId,
          preferences = preferencesProvider(),
          muteCache = muteCache,
          nowEpochMillis = clock(),
        )
    ) {
      is FilterResult.Notify -> onNotification(result.payload)
      is FilterResult.Ignore -> Unit
    }
  }

  private fun parseMessageCreate(data: JsonObject): MessageCreateEvent? {
    val id = data.stringOrNull("id") ?: return null
    val channelId = data.stringOrNull("channel_id") ?: return null
    val authorObj = data["author"]?.asObjectOrNull() ?: return null
    val authorId = authorObj.stringOrNull("id") ?: return null
    val username = authorObj.stringOrNull("username") ?: "Utilisateur"
    val globalName = authorObj.stringOrNull("global_name")
    val avatar = authorObj.stringOrNull("avatar")
    val discriminator = authorObj.stringOrNull("discriminator") ?: "0"

    val mentionIds =
      data["mentions"]
        ?.asArrayOrNull()
        ?.mapNotNull { it.asObjectOrNull()?.stringOrNull("id") }
        ?.toSet()
        ?: emptySet()

    val mentionRoles =
      data["mention_roles"]
        ?.asArrayOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.toSet()
        ?: emptySet()

    val hasAttachments = data["attachments"]?.asArrayOrNull()?.isNotEmpty() == true
    val hasEmbeds = data["embeds"]?.asArrayOrNull()?.isNotEmpty() == true
    val type = runCatching { data["type"]?.jsonPrimitive?.intOrNull ?: 0 }.getOrDefault(0)

    return MessageCreateEvent(
      id = id,
      channelId = channelId,
      guildId = data.stringOrNull("guild_id"),
      author =
        DiscordAuthor(
          id = authorId,
          username = username,
          globalName = globalName,
          avatar = avatar,
          discriminator = discriminator,
        ),
      content = data.stringOrNull("content").orEmpty(),
      mentionIds = mentionIds,
      mentionRoles = mentionRoles,
      mentionEveryone = data.booleanOrDefault("mention_everyone", false),
      hasAttachments = hasAttachments,
      hasEmbeds = hasEmbeds,
      type = type,
    )
  }

  private fun handleDisconnect(closeCode: Int, reason: String) {
    val shouldReconnect: Boolean
    synchronized(stateLock) {
      heartbeatJob?.cancel()
      heartbeatJob = null
      webSocket = null
      awaitingHeartbeatAck = false
      shouldReconnect = isRunning
    }

    if (!shouldReconnect) {
      onStatusChanged(GatewayStatus.Disconnected)
      return
    }

    if (closeCode in FATAL_AUTH_CLOSE_CODES) {
      clearSessionState()
      stopWithAuthError("Authentification refusée par Discord (code $closeCode)")
      return
    }

    if (closeCode in NON_RESUMABLE_CLOSE_CODES) {
      clearSessionState()
    }

    scheduleReconnect(reason)
  }

  private fun stopWithAuthError(message: String) {
    synchronized(stateLock) {
      isRunning = false
      reconnectJob?.cancel()
      reconnectJob = null
      heartbeatJob?.cancel()
      heartbeatJob = null
      webSocket = null
    }
    onStatusChanged(GatewayStatus.Error(message = message, isAuthError = true))
  }

  private fun scheduleReconnect(reason: String) {
    synchronized(stateLock) {
      if (!isRunning) return
      reconnectJob?.cancel()

      val attempt = ++reconnectAttempt
      val shift = min(attempt - 1, 10)
      val exponentialDelay =
        min(maxReconnectDelayMs, baseReconnectDelayMs * (1L shl shift))
      val jitter = (exponentialDelay * Random.nextDouble(0.0, 0.25)).toLong()
      val delayMs = exponentialDelay + jitter
      val canResume = sessionId != null && lastSequence != null

      onStatusChanged(
        GatewayStatus.Reconnecting(
          attempt = attempt,
          delaySeconds = (delayMs / 1000L).coerceAtLeast(1L),
          reason = reason,
        )
      )

      reconnectJob =
        scope.launch {
          delay(delayMs)
          if (isActive && isRunning) {
            connect(useResumeUrl = canResume)
          }
        }
    }
  }

  private fun clearSessionState() {
    sessionId = null
    resumeGatewayUrl = null
    lastSequence = null
  }

  companion object {
    private const val TAG = "DiscodoGateway"
    const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val USER_AGENT = "Discord-Android/245015;RNA"
    private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 41_250L
    private const val DEFAULT_BASE_RECONNECT_DELAY_MS = 1_000L
    private const val DEFAULT_MAX_RECONNECT_DELAY_MS = 60_000L
    private const val NORMAL_CLOSE_CODE = 1000
    private const val INTERNAL_RECONNECT_CODE = 4000

    private val FATAL_AUTH_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)
    private val NON_RESUMABLE_CLOSE_CODES = setOf(4003, 4007, 4009)

    fun defaultOkHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    internal fun normalizeGatewayUrl(rawUrl: String): String {
      val trimmed = rawUrl.trim().trimEnd('/')
      return if (trimmed.contains("?")) {
        trimmed
      } else {
        "$trimmed/?v=10&encoding=json"
      }
    }
  }
}
