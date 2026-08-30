/*
 * Red Launcher
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
 * Rich Presence for Red Launcher.
 *
 * How it works: the launcher connects to the Discord gateway with the user's
 * own Discord token (same approach as CustomRPC/Kizzy) and sends presence
 * updates:
 *  - large image  : the launcher logo, either from the public URL LOGO_URL
 *                   (default, works for everyone) or from the "red" art
 *                   asset uploaded to the Discord Developer Portal
 *                   (see docs/discord/README.md)
 *  - small image  : the player's skin face, fetched as an external URL from
 *                   https://mc-heads.net/avatar/{uuid} — no Discord asset
 *                   upload is involved, so it works for every user
 *  - party size   : players online / max, read from a live Minecraft server
 *                   status ping (when a dedicated server is used)
 *  - timestamps   : play time, from the moment the game was launched
 *
 * The presence is cleared when the launcher goes to the background (unless a
 * game is running), so it never stays "stuck" on the user's profile.
 *
 * The user's Discord token is stored with EncryptedSharedPreferences and is
 * never written to logs.
 */

package com.movtery.redlauncherbeta.feature.discord

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import org.json.JSONArray
import org.json.JSONObject

object DiscordManager {

    /** The user's Discord application (created in the Developer Portal). */
    const val APPLICATION_ID = "1543238280496156714"
    /** Logo uploaded as art asset "red" in the Developer Portal (see docs/discord/README.md). */
    const val ASSET_LOGO = "red"
    /**
     * Public URL of the launcher logo. Discord accepts external https URLs for
     * presence images, so the logo shows up for every user even when the "red"
     * art asset has not been uploaded to the application yet.
     */
    const val LOGO_URL = "https://raw.githubusercontent.com/redglitchx001-dev/Red-Launcher/main/RedLauncherBeta/src/main/res/drawable/img_launcher.png"
    /** External skin-face avatar URL template ({uuid} is replaced at runtime). */
    const val SKIN_FACE_URL_TEMPLATE = "https://mc-heads.net/avatar/{uuid}"

    private const val TAG = "DiscordManager"
    private const val PREFS_NAME = "red_launcher_discord_v2"
    private const val KEY_ENABLED = "discord_enabled"
    private const val KEY_TOKEN = "discord_token"
    private const val KEY_USE_URL_ASSETS = "discord_use_url_assets"
    private const val PRESENCE_MIN_INTERVAL_MS = 15_000L
    private const val REFRESH_INTERVAL_MS = 60_000L
    private const val MENU_PRESENCE_MIN_INTERVAL_MS = 30_000L

    private var appContext: Context? = null
    private var cachedPrefs: SharedPreferences? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gateway: DiscordGateway? = null

    private val _connectionState = MutableStateFlow("")
    val connectionState: StateFlow<String> = _connectionState

    private enum class Mode { NONE, GAME, MENU }

    private var mode = Mode.NONE
    private var inGame = false
    private var appInForeground = false
    private var gameStartMillis = 0L
    private var serverIp: String? = null
    private var accountName: String = "Steve"
    private var accountUuid: String? = null
    private var serverPlayers: Pair<Int, Int>? = null
    private var lastPresenceSent = 0L
    private var refreshJob: Job? = null
    private var lastMenuPresenceSent = 0L

    // region Storage

    /**
     * Saves the Discord token and settings with EncryptedSharedPreferences, so
     * the token is never stored in plain text on disk. A dedicated preference
     * file name is used, so plain-text values from previous builds are never
     * read or corrupted — the user just enters the token once more.
     */
    private fun prefs(): SharedPreferences {
        cachedPrefs?.let { return it }
        val context = appContext ?: throw IllegalStateException("DiscordManager.init() not called")
        val prefs = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrDefault(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        cachedPrefs = prefs
        return prefs
    }

    /**
     * Must be called early (e.g. from the main activity's onCreate).
     *
     * Note: this does NOT connect to Discord. The presence is (re)established
     * on the next foreground (MainActivity.onResume) or when a game starts, so
     * a stale state from before the app was backgrounded is never resurrected
     * on cold start.
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

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

    /**
     * Whether presence images are fetched from public URLs (default) instead of
     * the Discord art assets. URL assets work for every user; art assets only
     * work once they have been uploaded to the application in the Developer
     * Portal (see docs/discord/README.md).
     */
    fun useUrlAssets(): Boolean =
        runCatching { prefs().getBoolean(KEY_USE_URL_ASSETS, true) }.getOrDefault(true)

    fun setUseUrlAssets(use: Boolean) {
        runCatching { prefs().edit().putBoolean(KEY_USE_URL_ASSETS, use).apply() }
    }

    private fun largeImage(): String = if (useUrlAssets()) LOGO_URL else ASSET_LOGO

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

    // region App & game lifecycle hooks

    /** Called right before the game process is started. */
    fun onGameStart(serverIp: String?, accountName: String, accountUuid: String?) {
        Log.i(TAG, "onGameStart: ip=$serverIp account=$accountName enabled=${isEnabled()}")
        if (!isEnabled()) return

        this.serverIp = serverIp
        this.accountName = accountName
        this.accountUuid = accountUuid
        this.gameStartMillis = System.currentTimeMillis()
        this.inGame = true
        this.mode = Mode.GAME
        this.serverPlayers = null

        ensureConnected()

        if (isReady) {
            startGamePresencePipeline()
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
        if (!isEnabled()) return
        if (appInForeground && isReady) {
            sendMenuPresence(force = true)
        } else {
            // The launcher is not on screen (e.g. the game just closed and the
            // app stays in the background) — drop the presence instead of
            // leaving "In the main menu" on the user's profile.
            clearPresenceAndDisconnect()
        }
    }

    /** Called when the launcher comes back to the foreground. */
    fun onAppForeground() {
        appInForeground = true
        if (!isEnabled() || inGame) return
        if (!isReady) {
            mode = Mode.MENU
            connect()
            return
        }
        sendMenuPresence()
    }

    /**
     * Called when the launcher goes to the background.
     *
     * The menu presence is cleared, so it does not stay "stuck" on the user's
     * Discord profile after leaving the app. While a game is running nothing
     * is done — the game keeps playing and the game presence stays up.
     */
    fun onAppBackground() {
        appInForeground = false
        if (!isEnabled() || inGame) return
        if (mode == Mode.MENU) {
            clearPresenceAndDisconnect()
        } else {
            mode = Mode.NONE
        }
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
            Mode.GAME -> startGamePresencePipeline()
            Mode.MENU -> sendMenuPresence(force = true)
            Mode.NONE -> Unit
        }
    }

    private fun startGamePresencePipeline() {
        // 1) Send the first game presence right away (the skin face small
        //    image is an external URL, see sendGamePresence)
        sendGamePresence(force = true)
        // 2) Keep it fresh: player count + play time
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
            put("details", "Red Launcher")
            put("state", if (!serverIp.isNullOrEmpty()) "In multiplayer: $serverIp" else "Singleplayer")
            put("assets", JSONObject().apply {
                put("large_image", largeImage())
                put("large_text", "Red Launcher")
                accountUuid?.takeIf { it.isNotBlank() }?.let { uuid ->
                    put("small_image", SKIN_FACE_URL_TEMPLATE.replace("{uuid}", uuid))
                    put("small_text", accountName)
                }
            })
            put("timestamps", JSONObject().apply {
                put("start", gameStartMillis)
            })
            serverPlayers?.let { (online, max) ->
                put("party", JSONObject().apply {
                    put("id", "rl-" + (serverIp ?: "single").hashCode().toUInt().toString(16))
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
        if (!force && System.currentTimeMillis() - lastMenuPresenceSent < MENU_PRESENCE_MIN_INTERVAL_MS) return

        val activity = JSONObject().apply {
            put("name", "Red Launcher")
            put("type", 0)
            put("application_id", APPLICATION_ID)
            put("details", "In the main menu")
            put("assets", JSONObject().apply {
                put("large_image", largeImage())
                put("large_text", "Red Launcher")
            })
        }
        gw.updatePresence("online", JSONArray().put(activity))
        lastMenuPresenceSent = System.currentTimeMillis()
    }

    // endregion
}
