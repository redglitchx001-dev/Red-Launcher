/*
 * Red Launcher Beta
 * Copyright (C) 2026 redglitchx001-dev and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3.0 or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 *
 * Gateway client inspired by CustomRPC (khoirulaksara, GPL-3.0).
 */

package com.movtery.redlauncherbeta.feature.discord

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal Discord Gateway (v10) client.
 *
 * Connects with a user token, sends presence updates (opcode 3) and keeps the
 * session alive with heartbeats. Only sending our own presence — no gateway
 * intents are requested, so it is lightweight on data and battery.
 */
class DiscordGateway(
    private val token: String,
    private val listener: Listener
) : WebSocketListener() {

    interface Listener {
        /** @param ready true once the READY dispatch was received. */
        fun onStateChanged(stateText: String, ready: Boolean)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatInterval = 41_250L
    private var sequence: Int? = null
    private var heartbeatAckReceived = true
    private var lastHeartbeatTime = 0L
    private var heartbeatThread: Thread? = null
    private var reconnectAttempts = 0
    private var closedByUser = false

    private val ready = AtomicBoolean(false)
    private val open = AtomicBoolean(false)

    val isReady: Boolean get() = ready.get()
    val isConnected: Boolean get() = open.get()

    fun connect() {
        if (open.get() || closedByUser) return
        closedByUser = false
        val request = Request.Builder()
            .url("wss://gateway.discord.gg/?v=10&encoding=json")
            .build()
        Log.i(TAG, "Connecting to Discord gateway (attempt ${reconnectAttempts + 1})")
        webSocket = client.newWebSocket(request, this)
    }

    /**
     * Send a presence update (opcode 3).
     *
     * @param activities JSON array of activity objects (empty array clears the activity).
     */
    fun updatePresence(status: String, activities: JSONArray) {
        val socket = webSocket ?: return
        if (!open.get()) return

        val payload = JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("since", if (status == "idle") System.currentTimeMillis() else JSONObject.NULL)
                put("activities", activities)
                put("status", status)
                put("afk", status == "idle")
            })
        }
        socket.send(payload.toString())
    }

    fun close() {
        closedByUser = true
        ready.set(false)
        open.set(false)
        stopHeartbeat()
        webSocket?.let {
            runCatching { it.close(1000, "User closed") }
            it.cancel()
        }
        webSocket = null
    }

    // region WebSocketListener

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (this.webSocket != null && this.webSocket !== webSocket) {
            runCatching { webSocket.close(1000, "Stale connection") }
            webSocket.cancel()
            return
        }
        open.set(true)
        listener.onStateChanged("Socket open, waiting for HELLO...", false)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (this.webSocket !== webSocket) return

        val json = runCatching { JSONObject(text) }.getOrElse {
            Log.e(TAG, "Failed to parse gateway message: $text")
            return
        }
        val op = json.optInt("op", -1)

        if (!json.isNull("s")) {
            sequence = json.optInt("s")
        }

        when (op) {
            10 -> { // HELLO
                val d = json.optJSONObject("d")
                heartbeatInterval = d?.optLong("heartbeat_interval") ?: 41_250L
                startHeartbeat()
                sendIdentify()
                listener.onStateChanged("Identifying...", false)
            }
            0 -> { // DISPATCH
                when (json.optString("t")) {
                    "READY" -> {
                        ready.set(true)
                        reconnectAttempts = 0
                        listener.onStateChanged("Connected", true)
                    }
                    else -> Unit
                }
            }
            1 -> sendHeartbeat() // heartbeat request from server
            7 -> { // RECONNECT
                Log.w(TAG, "Server requested reconnect")
                val socket = this.webSocket
                this.webSocket = null
                open.set(false)
                ready.set(false)
                stopHeartbeat()
                runCatching { socket?.close(1012, "Reconnect requested") }
                reconnectAttempts++
                if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS || closedByUser) {
                    giveUp("Reconnect requested too many times")
                    return
                }
                delayAndConnect()
            }
            9 -> { // INVALID SESSION
                Log.e(TAG, "Invalid session — token invalid or expired")
                open.set(false)
                ready.set(false)
                stopHeartbeat()
                closedByUser = true
                listener.onStateChanged("Token invalid — check your Discord token", false)
                runCatching { webSocket.close(4004, "Invalid session") }
                this.webSocket = null
            }
            11 -> { // ACK
                heartbeatAckReceived = true
                val ping = System.currentTimeMillis() - lastHeartbeatTime
                listener.onStateChanged("Connected • ${ping}ms", true)
            }
            else -> Log.w(TAG, "Unhandled opcode $op")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        if (this.webSocket !== webSocket) return
        open.set(false)
        ready.set(false)
        stopHeartbeat()
        this.webSocket = null
        listener.onStateChanged("Disconnected: $reason ($code)", false)
        if (!closedByUser && reconnectAttempts <= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            delayAndConnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (this.webSocket !== webSocket) return
        open.set(false)
        ready.set(false)
        stopHeartbeat()
        this.webSocket = null
        Log.e(TAG, "Gateway failure", t)
        if (closedByUser) return
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            giveUp(t.message ?: "Connection failed")
            return
        }
        reconnectAttempts++
        listener.onStateChanged("Connection lost, reconnecting...", false)
        delayAndConnect()
    }

    // endregion

    private fun giveUp(reason: String) {
        closedByUser = true
        listener.onStateChanged("Disconnected: $reason", false)
    }

    private fun delayAndConnect() {
        Thread {
            try {
                val delay = (1_500L * (1 shl minOf(reconnectAttempts, 4))).coerceAtMost(15_000L)
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closedByUser) connect()
        }.start()
    }

    private fun sendIdentify() {
        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", token)
                put("properties", JSONObject().apply {
                    put("\$os", "android")
                    put("\$browser", "Red Launcher Beta")
                    put("\$device", "Red Launcher Beta")
                })
                put("intents", 0)
                put("presence", JSONObject().apply {
                    put("status", "online")
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray())
                    put("afk", false)
                })
            })
        }
        runCatching { webSocket?.send(identify.toString()) }
            .onFailure { Log.e(TAG, "Failed to send identify", it) }
    }

    private fun sendHeartbeat() {
        val socket = webSocket ?: return
        if (!open.get()) return
        lastHeartbeatTime = System.currentTimeMillis()
        val heartbeat = JSONObject().apply {
            put("op", 1)
            put("d", sequence ?: JSONObject.NULL)
        }
        runCatching { socket.send(heartbeat.toString()) }
            .onFailure { Log.e(TAG, "Failed to send heartbeat", it) }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatAckReceived = true
        heartbeatThread = Thread {
            while (open.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(heartbeatInterval)
                } catch (_: InterruptedException) {
                    break
                }
                if (!heartbeatAckReceived) {
                    Log.e(TAG, "Heartbeat ACK timeout — reconnecting")
                    open.set(false)
                    ready.set(false)
                    stopHeartbeat()
                    webSocket?.let {
                        runCatching { it.close(1008, "Heartbeat timeout") }
                        it.cancel()
                    }
                    webSocket = null
                    if (!closedByUser && reconnectAttempts <= MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        listener.onStateChanged("Heartbeat timeout, reconnecting...", false)
                        delayAndConnect()
                    }
                    break
                }
                heartbeatAckReceived = false
                sendHeartbeat()
            }
        }.also { it.start() }
    }

    private fun stopHeartbeat() {
        heartbeatThread?.let {
            runCatching { it.interrupt() }
        }
        heartbeatThread = null
    }

    private companion object {
        const val TAG = "DiscordGateway"
        const val MAX_RECONNECT_ATTEMPTS = 8
    }
}
