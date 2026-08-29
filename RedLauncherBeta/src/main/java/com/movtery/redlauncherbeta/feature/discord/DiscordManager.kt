package com.movtery.redlauncherbeta.feature.discord

import android.content.Context
import android.net.Uri
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.game.account.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * STUB FOR BISECTION - public API surface only
 */
object DiscordManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed interface State {
        data object NotConfigured : State

        data object Disconnected : State

        data class Connected(val username: String) : State
    }

    private val _state = MutableStateFlow<State>(
        if (BuildKeys.DISCORD_CLIENT_ID.isNullOrBlank()) State.NotConfigured else State.Disconnected
    )
    val state = _state.asStateFlow()

    fun isConnected(): Boolean = state.value is State.Connected

    fun initialize(context: Context) {
    }

    fun startLogin(context: Context) {
    }

    fun handleAuthRedirect(uri: Uri?) {
    }

    fun disconnect() {
        _state.value = State.Disconnected
    }

    fun clearPresence() {
    }

    fun notifyGameStart(account: Account?, serverIp: String?) {
    }

    fun notifyLauncherForeground() {
    }
}
