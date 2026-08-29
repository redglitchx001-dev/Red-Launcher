package com.movtery.redlauncherbeta.feature.discord

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isAuthServerAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Discord Rich Presence 管理器（Red Launcher Beta）
 *
 * 登录方式：OAuth2（PKCE，公共客户端，不需要 client_secret）。
 * 用户在 Discord 应用/浏览器里点击一次 Authorize 即可，无需在启动器中输入密码。
 *
 * 登录后通过 Discord REST API（PUT /users/@me/activities）显示 Rich Presence：
 * - 名称：Red Launcher Beta（需要在 Discord Developer Portal 创建同名应用）
 * - 大图标：AllSettings.discordLargeImage（Discord 应用资源 key，默认 red_launcher）
 * - 小图标（圆形）：当前玩家的皮肤 URL（尽力而为，Discord 不支持时自动降级）
 * - 文本：Playing {服务器IP} / Playing Minecraft / In the main menu
 */
object DiscordManager {
    private const val TAG = "DiscordManager"

    private const val PREFS_NAME = "redlauncher_discord"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_VERIFIER = "code_verifier"

    private const val DISCORD_BASE = "https://discord.com"
    private const val REDIRECT_URI = "redlauncher://discord"
    private const val SCOPE = "identify"

    private const val ACTIVITY_NAME = "Red Launcher Beta"
    private const val STATE_IN_MENU = "In the main menu"
    private const val STATE_PLAYING_MC = "Playing Minecraft"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var prefs: SharedPreferences? = null

    /** 当前展示的账号信息（用于刷新存在状态） */
    private var cachedAccount: Account? = null
    private var lastServerIp: String? = null
    private var gameStartTime: Long = 0L

    private val _state = MutableStateFlow<State>(
        if (BuildKeys.DISCORD_CLIENT_ID.isNullOrBlank()) State.NotConfigured else State.Disconnected
    )
    val state = _state.asStateFlow()

    sealed interface State {
        /** 没有配置 Discord Client ID */
        data object NotConfigured : State

        /** 未连接 */
        data object Disconnected : State

        /** 已连接 */
        data class Connected(val username: String) : State
    }

    fun isConnected(): Boolean = state.value is State.Connected

    /**
     * 应用启动时初始化（由 ZLApplication 调用）
     */
    fun initialize(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        if (!BuildKeys.DISCORD_CLIENT_ID.isNullOrBlank() && p.getString(KEY_TOKEN, null) != null) {
            _state.value = State.Connected(p.getString(KEY_USERNAME, null) ?: "Discord")
        }
    }

    /**
     * 发起登录：打开 OAuth2 授权页（优先在 Discord 应用内打开，用户只需点击一次 Authorize）
     */
    fun startLogin(context: Context) {
        if (state.value is State.NotConfigured) {
            Logger.warning(TAG, "Discord client id not configured, login skipped")
            return
        }
        val verifier = newPkceVerifier()
        prefs?.edit()?.putString(KEY_VERIFIER, verifier)?.apply()
        val challenge = pkceChallenge(verifier)

        val url = buildString {
            append(DISCORD_BASE).append("/oauth2/authorize?")
            append("client_id=").append(BuildKeys.DISCORD_CLIENT_ID)
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
            append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&prompt=consent")
        }

        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            // 优先使用 Discord 应用内的浏览器打开（已登录 Discord 的用户只需点一次 Authorize）
            context.packageManager.getPackageInfo("com.discord", 0)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.discord")
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching { context.startActivity(fallback) }
                .onFailure { e -> Logger.error(TAG, "Failed to open Discord login page", e) }
        }
    }

    /**
     * 处理 redlauncher://discord 回调（携带 OAuth2 code）
     * 由 MainActivity 的 onCreate/onNewIntent 调用
     */
    fun handleAuthRedirect(uri: Uri?) {
        val code = uri?.getQueryParameter("code")
        if (code.isNullOrEmpty()) {
            Logger.warning(TAG, "Discord redirect without code, uri=$uri")
            return
        }
        val verifier = prefs?.getString(KEY_VERIFIER, null)
        if (verifier.isNullOrEmpty()) {
            Logger.warning(TAG, "Discord code_verifier missing")
            return
        }
        scope.launch {
            runCatching {
                Logger.info(TAG, "Exchanging Discord auth code")
                val token = exchangeCodeForToken(code, verifier)
                val username = fetchDiscordUsername(token) ?: "Discord"
                prefs?.edit()?.putString(KEY_TOKEN, token)?.putString(KEY_USERNAME, username)?.apply()
                _state.value = State.Connected(username)
                Logger.info(TAG, "Discord connected as $username")
                // 立即刷新一次存在状态
                updatePresence()
            }.onFailure { e ->
                if (e !is CancellationException) {
                    Logger.error(TAG, "Discord login failed", e)
                }
                prefs?.edit()?.remove(KEY_VERIFIER)?.apply()
            }
        }
    }

    /**
     * 断开连接（删除本地 token）
     */
    fun disconnect() {
        prefs?.edit()?.remove(KEY_TOKEN)?.remove(KEY_USERNAME)?.remove(KEY_VERIFIER)?.apply()
        _state.value = State.Disconnected
        clearPresence()
        Logger.info(TAG, "Discord disconnected")
    }

    /**
     * 清除 Discord 上的存在状态（不删除本地 token）
     */
    fun clearPresence() {
        val token = prefs?.getString(KEY_TOKEN, null)
        lastServerIp = null
        gameStartTime = 0L
        if (token.isNullOrEmpty()) return
        scope.launch {
            runCatching {
                GLOBAL_CLIENT.request("$DISCORD_BASE/api/v10/users/@me/activities") {
                    method = HttpMethod.Put
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("[]")
                }
            }.onFailure { e ->
                Logger.debug(TAG, "Failed to clear Discord presence", e)
            }
        }
    }

    /**
     * 游戏启动时调用：显示 "Playing {服务器IP}" / "Playing Minecraft"
     */
    fun notifyGameStart(account: Account?, serverIp: String?) {
        cachedAccount = account
        lastServerIp = serverIp
        gameStartTime = System.currentTimeMillis()
        updatePresence()
    }

    /**
     * 回到启动器主界面时调用：显示 "In the main menu"
     */
    fun notifyLauncherForeground() {
        lastServerIp = null
        gameStartTime = 0L
        updatePresence()
    }

    // ==================== 内部实现 ====================

    private fun updatePresence() {
        val token = prefs?.getString(KEY_TOKEN, null)
        if (token.isNullOrEmpty()) return
        val account = cachedAccount
        scope.launch {
            val username = account?.username
            val skinUrl = account?.let {
                runCatching { resolveSkinUrl(it) }.onFailure { e ->
                    Logger.debug(TAG, "Failed to resolve skin url", e)
                }.getOrNull()
            }
            runCatching {
                putActivity(
                    token = token,
                    username = username,
                    serverIp = lastServerIp,
                    largeImage = AllSettings.discordLargeImage.getValue().takeIf { it.isNotBlank() },
                    smallImage = skinUrl
                )
            }.onFailure { e ->
                // 第一次失败时，去掉小图标（部分 Discord 版本不接受外链图片）重试一次
                if (skinUrl != null) {
                    runCatching {
                        putActivity(token, username, lastServerIp,
                            AllSettings.discordLargeImage.getValue().takeIf { it.isNotBlank() }, null)
                    }
                } else {
                    Logger.warning(TAG, "Failed to update Discord presence", e)
                }
            }
        }
    }

    private suspend fun putActivity(
        token: String,
        username: String?,
        serverIp: String?,
        largeImage: String?,
        smallImage: String?
    ) {
        val details = when {
            serverIp != null -> "Playing $serverIp"
            gameStartTime != 0L -> STATE_PLAYING_MC
            else -> STATE_IN_MENU
        }
        val activity = buildJsonObject {
            put("name", ACTIVITY_NAME)
            put("type", 0)
            put("details", details)
            put("state", username ?: "Minecraft")
            val assets = buildJsonObject {
                if (!largeImage.isNullOrBlank()) {
                    put("large_image", largeImage)
                    put("large_text", ACTIVITY_NAME)
                }
                if (!smallImage.isNullOrBlank()) {
                    put("small_image", smallImage)
                    if (!username.isNullOrBlank()) put("small_text", username)
                }
            }
            if (assets.isNotEmpty()) put("assets", assets)
            if (gameStartTime != 0L) {
                put("timestamps", buildJsonObject { put("start", gameStartTime) })
            }
        }
        val resp = GLOBAL_CLIENT.request("$DISCORD_BASE/api/v10/users/@me/activities") {
            method = HttpMethod.Put
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(activity.toString())
        }
        if (!resp.status.isSuccess()) {
            val text = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
            throw IllegalStateException("Discord activities API returned ${resp.status}: $text")
        }
    }

    /**
     * 获取玩家皮肤图片 URL（用于 Rich Presence 的小图标）
     */
    private suspend fun resolveSkinUrl(account: Account): String? {
        // 1) 微软账号：Mojang sessionserver
        if (account.isMicrosoftAccount()) {
            fetchSkinUrlFromSessionServer("https://sessionserver.mojang.com", account.profileId)
                ?.let { return it }
        }
        // 2) Ely.by 纹理接口（Ely.by / 离线账号都适用）
        runCatching {
            withTimeout(8000) {
                val resp = GLOBAL_CLIENT.get(
                    "https://api.ely.by/v2/textures/" + URLEncoder.encode(account.username, "UTF-8")
                )
                if (resp.status == HttpStatusCode.OK) {
                    val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    json["skin"]?.jsonObject?.get("url")?.jsonPrimitive?.content?.let { return it }
                }
            }
        }
        // 3) 其他认证服务器（Yggdrasil sessionserver）
        if (account.isAuthServerAccount()) {
            account.otherBaseUrl?.removeSuffix("/")?.let { base ->
                fetchSkinUrlFromSessionServer("$base/sessionserver", account.profileId)?.let { return it }
            }
        }
        return null
    }

    private suspend fun fetchSkinUrlFromSessionServer(base: String, profileId: String): String? {
        return runCatching {
            withTimeout(8000) {
                val resp = GLOBAL_CLIENT.get("$base/session/minecraft/profile/$profileId")
                if (resp.status != HttpStatusCode.OK) return@withTimeout null
                val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val properties = json["properties"]?.jsonArray ?: return@withTimeout null
                for (prop in properties) {
                    val propObj = prop.jsonObject
                    if (propObj["name"]?.jsonPrimitive?.content != "textures") continue
                    val encoded = propObj["value"]?.jsonPrimitive?.content ?: continue
                    val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    val textures = Json.parseToJsonElement(decoded).jsonObject["textures"]?.jsonObject
                    return@withTimeout textures?.get("SKIN")?.jsonObject?.get("url")?.jsonPrimitive?.content
                }
                null
            }
        }.getOrNull()
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): String {
        val form = buildString {
            append("client_id=").append(URLEncoder.encode(BuildKeys.DISCORD_CLIENT_ID, "UTF-8"))
            append("&grant_type=authorization_code")
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
        }
        val resp = GLOBAL_CLIENT.post("$DISCORD_BASE/api/v10/oauth2/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form)
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("Discord token exchange failed (${resp.status}): $text")
        }
        val json = Json.parseToJsonElement(text).jsonObject
        return json["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No access_token in Discord token response")
    }

    private suspend fun fetchDiscordUsername(token: String): String? {
        return runCatching {
            withTimeout(8000) {
                val resp = GLOBAL_CLIENT.get("$DISCORD_BASE/api/v10/users/@me") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                if (!resp.status.isSuccess()) return@withTimeout null
                val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                json["global_name"]?.jsonPrimitive?.content
                    ?: json["username"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    /**
     * 生成 PKCE code_verifier（43-128 个安全字符）
     */
    private fun newPkceVerifier(): String {
        val bytes = ByteArray(48).also { Random.nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .trimEnd('=')
    }

    /**
     * PKCE code_challenge = BASE64URL(SHA256(code_verifier))
     */
    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .trimEnd('=')
    }
}
