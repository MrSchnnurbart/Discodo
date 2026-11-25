package com.discodo.app.gateway

import com.discodo.app.data.GatewayStatus
import com.discodo.app.data.NotificationPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscordGatewayClientTest {

  private lateinit var server: MockWebServer
  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun fullGatewayLifecycle_helloIdentifyHeartbeatReadyDispatchAndResume() {
    val identifyReceived = CountDownLatch(1)
    val heartbeatReceived = CountDownLatch(1)
    val resumeReceived = CountDownLatch(1)
    val notificationReceived = CountDownLatch(1)

    val sentPayloads = CopyOnWriteArrayList<String>()
    val notifications = CopyOnWriteArrayList<NotificationPayload>()
    val statuses = CopyOnWriteArrayList<GatewayStatus>()

    val baseWsUrl = "ws://${server.hostName}:${server.port}"

    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":10,"d":{"heartbeat_interval":120}}""")
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            sentPayloads.add(text)
            val obj = json.parseToJsonElement(text).jsonObject
            when (obj["op"]?.jsonPrimitive?.int) {
              GatewayOpcodes.IDENTIFY -> {
                identifyReceived.countDown()
                webSocket.send(
                  """
                  {
                    "op": 0,
                    "s": 1,
                    "t": "READY",
                    "d": {
                      "user": { "id": "user_1", "username": "valenced_user" },
                      "session_id": "sess_123",
                      "resume_gateway_url": "$baseWsUrl"
                    }
                  }
                  """.trimIndent()
                )
                webSocket.send(
                  """
                  {
                    "op": 0,
                    "s": 2,
                    "t": "MESSAGE_CREATE",
                    "d": {
                      "id": "m_99",
                      "channel_id": "dm_55",
                      "guild_id": null,
                      "author": { "id": "other_2", "username": "bob", "global_name": "Bob" },
                      "content": "Ping depuis Gateway!",
                      "mentions": [],
                      "mention_everyone": false
                    }
                  }
                  """.trimIndent()
                )
              }
              GatewayOpcodes.HEARTBEAT -> {
                heartbeatReceived.countDown()
                webSocket.send("""{"op":11}""")
                webSocket.close(1001, "Simulated drop")
              }
            }
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
          }
        }
      )
    )

    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":10,"d":{"heartbeat_interval":5000}}""")
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            sentPayloads.add(text)
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["op"]?.jsonPrimitive?.int == GatewayOpcodes.RESUME) {
              val d = obj["d"]?.jsonObject
              if (
                d?.get("session_id")?.jsonPrimitive?.content == "sess_123" &&
                  d["seq"]?.jsonPrimitive?.content == "2"
              ) {
                resumeReceived.countDown()
              }
              webSocket.send("""{"op":0,"s":3,"t":"RESUMED","d":{}}""")
            }
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
          }
        }
      )
    )

    val okHttpClient = DiscordGatewayClient.defaultOkHttpClient()
    val client =
      DiscordGatewayClient(
        okHttpClient = okHttpClient,
        gatewayBaseUrl = "$baseWsUrl/?v=10&encoding=json",
        tokenProvider = { "test_user_token" },
        preferencesProvider = { NotificationPreferences() },
        onStatusChanged = { status -> statuses.add(status) },
        onNotification = { payload ->
          notifications.add(payload)
          notificationReceived.countDown()
        },
        baseReconnectDelayMs = 50L,
        maxReconnectDelayMs = 200L,
      )

    try {
      client.start()

      assertTrue("OP 2 IDENTIFY not sent", identifyReceived.await(5, TimeUnit.SECONDS))
      assertTrue("OP 1 HEARTBEAT not sent", heartbeatReceived.await(5, TimeUnit.SECONDS))
      assertTrue("Notification not dispatched", notificationReceived.await(5, TimeUnit.SECONDS))
      assertTrue("OP 6 RESUME not sent after disconnect", resumeReceived.await(5, TimeUnit.SECONDS))

      val notif = notifications.first()
      assertEquals("Bob", notif.authorDisplayName)
      assertEquals("Ping depuis Gateway!", notif.bodyText)
      assertEquals("https://discord.com/channels/@me/dm_55", notif.deepLinkUrl)

      val identifyFrame =
        sentPayloads
          .map { json.parseToJsonElement(it).jsonObject }
          .first { it["op"]?.jsonPrimitive?.int == GatewayOpcodes.IDENTIFY }
      val props = identifyFrame["d"]?.jsonObject?.get("properties")?.jsonObject
      assertEquals("Android", props?.get("os")?.jsonPrimitive?.content)
      assertEquals("Discord Android", props?.get("browser")?.jsonPrimitive?.content)
    } finally {
      client.shutdown()
      okHttpClient.dispatcher.executorService.shutdown()
      okHttpClient.connectionPool.evictAll()
    }
  }

  @Test
  fun fatalAuthCloseCode4004_stopsAndReportsAuthError() {
    val errorLatch = CountDownLatch(1)
    val statuses = CopyOnWriteArrayList<GatewayStatus>()

    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":10,"d":{"heartbeat_interval":5000}}""")
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["op"]?.jsonPrimitive?.int == GatewayOpcodes.IDENTIFY) {
              webSocket.close(4004, "Authentication failed")
            }
          }
        }
      )
    )

    val client =
      DiscordGatewayClient(
        gatewayBaseUrl = "ws://${server.hostName}:${server.port}/?v=10&encoding=json",
        tokenProvider = { "bad_token" },
        preferencesProvider = { NotificationPreferences() },
        onStatusChanged = { status ->
          statuses.add(status)
          if (status is GatewayStatus.Error && status.isAuthError) {
            errorLatch.countDown()
          }
        },
        onNotification = {},
      )

    try {
      client.start()
      assertTrue("Fatal auth error not emitted", errorLatch.await(5, TimeUnit.SECONDS))
      val lastError = statuses.filterIsInstance<GatewayStatus.Error>().last()
      assertTrue(lastError.isAuthError)
      assertTrue(lastError.message.contains("4004"))
    } finally {
      client.shutdown()
    }
  }

  @Test
  fun incomingCallCreateAndCallUpdate_dispatchesCallerAndEndsCall() {
    val incomingCallLatch = CountDownLatch(1)
    val callEndedLatch = CountDownLatch(1)
    val incomingCalls = CopyOnWriteArrayList<IncomingCallPayload>()
    val endedChannels = CopyOnWriteArrayList<String>()

    server.enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":10,"d":{"heartbeat_interval":5000}}""")
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["op"]?.jsonPrimitive?.int == GatewayOpcodes.IDENTIFY) {
              webSocket.send(
                """
                {
                  "op": 0,
                  "s": 1,
                  "t": "READY",
                  "d": {
                    "user": { "id": "me_1", "username": "valenced_user" },
                    "session_id": "sess_call",
                    "private_channels": [
                      {
                        "id": "dm_call_1",
                        "recipients": [
                          { "id": "caller_9", "username": "charlie", "global_name": "Charlie", "avatar": "av_hash" }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent()
              )
              webSocket.send(
                """
                {
                  "op": 0,
                  "s": 2,
                  "t": "CALL_CREATE",
                  "d": {
                    "channel_id": "dm_call_1",
                    "message_id": "msg_c1",
                    "ringing": ["me_1"],
                    "voice_states": [{ "user_id": "caller_9" }]
                  }
                }
                """.trimIndent()
              )
              webSocket.send(
                """
                {
                  "op": 0,
                  "s": 3,
                  "t": "CALL_UPDATE",
                  "d": {
                    "channel_id": "dm_call_1",
                    "message_id": "msg_c1",
                    "ringing": []
                  }
                }
                """.trimIndent()
              )
            }
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
          }
        }
      )
    )

    val okHttpClient = DiscordGatewayClient.defaultOkHttpClient()
    val client =
      DiscordGatewayClient(
        okHttpClient = okHttpClient,
        gatewayBaseUrl = "ws://${server.hostName}:${server.port}/?v=10&encoding=json",
        tokenProvider = { "test_token" },
        preferencesProvider = { NotificationPreferences(notifyCalls = true) },
        onStatusChanged = {},
        onNotification = {},
        onIncomingCall = { call ->
          incomingCalls.add(call)
          incomingCallLatch.countDown()
        },
        onCallEnded = { channelId ->
          endedChannels.add(channelId)
          callEndedLatch.countDown()
        },
      )

    try {
      client.start()
      assertTrue("Incoming call not dispatched", incomingCallLatch.await(5, TimeUnit.SECONDS))
      assertTrue("Call end not dispatched", callEndedLatch.await(5, TimeUnit.SECONDS))

      val call = incomingCalls.first()
      assertEquals("Charlie", call.callerName)
      assertEquals(
        "https://cdn.discordapp.com/avatars/caller_9/av_hash.png?size=128",
        call.callerAvatarUrl,
      )
      assertEquals("https://discord.com/channels/@me/dm_call_1", call.deepLinkUrl)
      assertEquals("dm_call_1", endedChannels.first())
    } finally {
      client.shutdown()
      okHttpClient.dispatcher.executorService.shutdown()
      okHttpClient.connectionPool.evictAll()
    }
  }
}
