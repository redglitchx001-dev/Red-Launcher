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
 * Rich Presence for Red Launcher Beta.
 *
 * How it works: the launcher connects to the Discord gateway with the user's
 * own Discord token (same approach as CustomRPC/Kizzy) and sends presence
 * updates:
 *  - large image  : "red" (logo, uploaded in the Discord Developer Portal)
 *  - small image  : the player's skin face, uploaded automatically as asset
 *                   "skinface" to the user's application (best effort)
 *  - party size   : players online / max, read from a live Minecraft server
 *                   status ping (when a dedicated server is used)
 *  - timestamps   : play time, from the moment the game was launched
 */

package com.movtery.redlauncherbeta.feature.discord

import android.content.Context
import android.util.Log
import com.movtery.zalithlauncher.game.version.multiplayer.pingServer
import com.movtery.zalithlauncher.game.version.multiplayer.resolve
import com.movtery.zalithlauncher.utils.network.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object DiscordManager {

    /** The user's Discord application (created in the Developer Portal). */
    const val APPLICATION_ID = "1543238280496156714"
    /** Logo uploaded as art asset "red" in the Developer Portal. */
    const val ASSET_LOGO = "red"
    /** Asset key the launcher uploads the skin face into. */
    const val ASSET_SKIN_FACE = "skinface"

    private const val TAG = "DiscordManager"
    private const val PREFS_NAME = "red_launcher_discord"
    private const val KEY_ENABLED = "discord_enabled"
    private const val KEY_TOKEN = "discord_token"
    private const val PRESENCE_MIN_INTERVAL_MS = 15_000L
    private const val REFRESH_INTERVAL_MS = 60_000L

    private var appContext: Context? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gateway: DiscordGateway? = null

    private val _connectionState = MutableStateFlow("")
    val connectionState: StateFlow<String> = _connectionState

    private enum class Mode { NONE, GAME, MENU }

    private var mode = Mode.NONE
    private var inGame = false
    private var gameStartMillis = 0L
    private var serverIp: String? = null
    private var accountName: String = "Steve"
    private var serverPlayers: Pair<Int, Int>? = null
    private var skinFaceUploaded = false
    private var lastPresenceSent = 0L
    private var refreshJob: Job? = null
    private var lastMenuPresenceSent = 0L

    // region Storage

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            if (isEnabled() && getToken().isNotEmpty()) {
                connect()
            }
        }
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("DiscordManager.init() not called")

    fun isEnabled(): Boolean = runCatching { prefs().getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        runCatching { prefs().edit().putBoolean(KEY_ENABLED, enabled).apply() }
        if (enabled) {
            connect()
        } else {
            clearPresenceAndDisconnect()
        }
    }

    fun getToken(): String = runCatching { prefs().getString(KEY_TOKEN, "").orEmpty() }.getOrDefault("")

    fun setToken(token: String) {
        runCatching { prefs().edit().putString(KEY_TOKEN, token.trim()).apply() }
    }

    // endregion

    // region Connection

    val isReady: Boolean get() = gateway?.isReady == true

    fun connect() {
        val token = getToken()
        if (token.isEmpty()) {
            setState("No token saved")
            return
        }
        if (gateway?.isConnected == true) return

        gateway?.close()
        val gw = DiscordGateway(token, object : DiscordGateway.Listener {
            override fun onStateChanged(stateText: String, ready: Boolean) {
                setState(stateText)
                if (ready) {
                    flushPendingPresence()
                }
            }
        })
        gateway = gw
        setState("Connecting...")
        gw.connect()
    }

    fun disconnect() {
        gateway?.close()
        gateway = null
        setState("Disconnected")
    }

    private fun clearPresenceAndDisconnect() {
        mode = Mode.NONE
        inGame = false
        refreshJob?.cancel()
        refreshJob = null
        gateway?.let {
            if (it.isReady) {
                it.updatePresence("online", JSONArray())
            }
        }
        disconnect()
        setState("Disabled")
    }

    private fun setState(text: String) {
        _connectionState.value = text
    }

    // endregion

    // region Game lifecycle hooks

    /** Called right before the game process is started. */
    fun onGameStart(serverIp: String?, accountName: String, skinFile: File?) {
        Log.i(TAG, "onGameStart: ip=$serverIp account=$accountName enabled=${isEnabled()}")
        if (!isEnabled()) return

        this.serverIp = serverIp
        this.accountName = accountName
        this.gameStartMillis = System.currentTimeMillis()
        this.inGame = true
        this.mode = Mode.GAME
        this.skinFaceUploaded = false
        this.serverPlayers = null

        ensureConnected()

        if (isReady) {
            startGamePresencePipeline(skinFile)
        }
        // else: flushPendingPresence() sends the game presence once READY arrives
    }

    /** Called when the game activity is destroyed. */
    fun onGameStop() {
        Log.i(TAG, "onGameStop")
        if (!inGame && mode == Mode.NONE) return
        inGame = false
        mode = Mode.MENU
        refreshJob?.cancel()
        refreshJob = null
        if (isEnabled() && isReady) {
            sendMenuPresence(force = true)
        }
    }

    /** Called when the launcher comes back to the foreground. */
    fun onAppForeground() {
        if (!isEnabled() || inGame) return
        if (!isReady) {
            mode = Mode.MENU
            return
        }
        sendMenuPresence()
    }

    // endregion

    // region Presence pipeline

    private fun ensureConnected() {
        if (isReady) return
        connect()
    }

    /** Sends whatever presence is pending (game or menu) once the gateway is ready. */
    private fun flushPendingPresence() {
        when (mode) {
            Mode.GAME -> {
                val skinFile =
                    com.movtery.zalithlauncher.game.account.AccountsManager
                        .currentAccountFlow.value?.getSkinFile()
                startGamePresencePipeline(skinFile)
            }
            Mode.MENU -> sendMenuPresence(force = true)
            Mode.NONE -> Unit
        }
    }

    private fun startGamePresencePipeline(skinFile: File?) {
        // 1) Upload the skin face as a Discord asset (best effort)
        scope.launch {
            skinFile?.let { file ->
                runCatching {
                    val face = SkinFaceUtil.extractFacePng(file)
                    if (face != null) {
                        uploadSkinFaceAsset(face)
                        skinFaceUploaded = true
                        Log.i(TAG, "Skin face asset uploaded")
                    } else {
                        Log.w(TAG, "Could not extract skin face — small image skipped")
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Skin face upload failed (small image skipped)", e)
                }
            }
            // 2) Send the first game presence right away
            sendGamePresence(force = true)
            // 3) Keep it fresh: player count + play time
            refreshJob?.cancel()
            refreshJob = scope.launch {
                while (inGame) {
                    delay(REFRESH_INTERVAL_MS)
                    if (!isReady) break
                    pingServerIfPossible()
                    sendGamePresence()
                }
            }
        }
    }

    private fun pingServerIfPossible() {
        val ip = serverIp ?: return
        scope.launch {
            try {
                val address = ServerAddress.parse(ip)
                val resolved = address.resolve()
                val result = pingServer(resolved, timeoutMillis = 5_000)
                val online = result.status.players.online
                val max = result.status.players.max
                if (online > 0 || max > 0) {
                    serverPlayers = online to max
                    Log.i(TAG, "Server $ip: $online/$max players")
                    sendGamePresence()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server ping failed: $ip", e)
            }
        }
    }

    private fun sendGamePresence(force: Boolean = false) {
        val gw = gateway ?: return
        if (!gw.isReady) return
        if (!force && System.currentTimeMillis() - lastPresenceSent < PRESENCE_MIN_INTERVAL_MS) return

        val activity = JSONObject().apply {
            put("name", "Minecraft")
            put("type", 0)
            put("application_id", APPLICATION_ID)
            put("details", "Red Launcher Beta")
            put("state", if (!serverIp.isNullOrEmpty()) "Joacă pe $serverIp" else "Singleplayer")
            put("assets", JSONObject().apply {
                put("large_image", ASSET_LOGO)
                put("large_text", "Red Launcher Beta")
                if (skinFaceUploaded) {
                    put("small_image", ASSET_SKIN_FACE)
                    put("small_text", accountName)
                }
            })
            put("timestamps", JSONObject().apply {
                put("start", gameStartMillis)
            })
            serverPlayers?.let { (online, max) ->
                put("party", JSONObject().apply {
                    put("id", "rlb-" + (serverIp ?: "single").hashCode().toUInt().toString(16))
                    put("size", JSONArray().apply {
                        put(online)
                        put(max)
                    })
                })
            }
        }

        gw.updatePresence("online", JSONArray().put(activity))
        lastPresenceSent = System.currentTimeMillis()
    }

    private fun sendMenuPresence(force: Boolean = false) {
        val gw = gateway ?: return
        if (!gw.isReady) return
        if (!force && System.currentTimeMillis() - lastMenuPresenceSent < 30_000L) return

        val activity = JSONObject().apply {
            put("name", "Red Launcher Beta")
            put("type", 0)
            put("application_id", APPLICATION_ID)
            put("details", "In Meniul Principal")
            put("assets", JSONObject().apply {
                put("large_image", ASSET_LOGO)
                put("large_text", "Red Launcher Beta")
            })
        }
        gw.updatePresence("online", JSONArray().put(activity))
        lastMenuPresenceSent = System.currentTimeMillis()
    }

    // endregion

    // region Discord asset upload (best effort)

    /**
     * Uploads the skin face as art asset [ASSET_SKIN_FACE] to the user's
     * Discord application, so the presence can reference it as small image.
     */
    private fun uploadSkinFaceAsset(faceBytes: ByteArray) {
        val token = getToken()
        if (token.isEmpty()) return

        val url = "https://discord.com/api/v10/applications/$APPLICATION_ID/assets"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", ASSET_SKIN_FACE)
            .addFormDataPart(
                "data",
                "$ASSET_SKIN_FACE.png",
                faceBytes.toRequestBody("image/png".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", token)
            .post(multipart)
            .build()

        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == 204 || response.code == 200 -> Unit
                response.code == 400 || response.code == 409 -> {
                    // asset already exists -> delete and re-upload
                    Log.i(TAG, "Asset exists, replacing: ${response.code}")
                    val deleteRequest = Request.Builder()
                        .url("$url/$ASSET_SKIN_FACE")
                        .header("Authorization", token)
                        .delete()
                        .build()
                    runCatching { httpClient.newCall(deleteRequest).execute().close() }
                    val retryRequest = request.newBuilder().post(multipart).build()
                    httpClient.newCall(retryRequest).execute().use { retryResponse ->
                        check(retryResponse.isSuccessful) {
                            "Asset re-upload failed: HTTP ${retryResponse.code}"
                        }
                    }
                }
                else -> error("Asset upload failed: HTTP ${response.code}")
            }
        }
        // Any exception here is caught by the caller — the presence just
        // runs without the small image.
    }

    // endregion
}
