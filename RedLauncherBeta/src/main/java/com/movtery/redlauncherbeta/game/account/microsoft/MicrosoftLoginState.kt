/*
 * Red Launcher
 * Copyright (C) 2026 redglitchx001-dev and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.account.microsoft

import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MicrosoftLoginState"

/**
 * The device code that is currently waiting for the user to approve it.
 *
 * @property userCode the short code the user has to enter on the Microsoft page (e.g. `ABCD-EFGH`)
 * @property verificationUrl the page where the code has to be entered
 */
data class MicrosoftDeviceCode(
    val userCode: String,
    val verificationUrl: String
)

/**
 * Shares the device code of the running Microsoft login with the UI layer.
 *
 * The login runs inside a background [com.movtery.zalithlauncher.coroutine.Task] while the user
 * interacts with the in-app browser. Only a short-lived toast carried the code before, which was
 * easy to miss (the device code expires after a few minutes). Publishing it here lets the browser
 * screen keep the code on screen for as long as the login is waiting.
 */
object MicrosoftLoginState {
    private val _deviceCode = MutableStateFlow<MicrosoftDeviceCode?>(null)

    /** The pending device code, or `null` when no device code login is running. */
    val deviceCode: StateFlow<MicrosoftDeviceCode?> = _deviceCode.asStateFlow()

    /** `true` while a device code login is waiting for the user to approve it. */
    val isLoggingIn: Boolean get() = _deviceCode.value != null

    /** Called right before the verification page is shown to the user. */
    fun begin(userCode: String, verificationUrl: String) {
        _deviceCode.value = MicrosoftDeviceCode(userCode, verificationUrl)
        Logger.debug(TAG, "Device code login started, user code: $userCode")
    }

    /** Called when the login finished, no matter the outcome. */
    fun end() {
        if (_deviceCode.value != null) {
            Logger.debug(TAG, "Device code login finished")
        }
        _deviceCode.value = null
    }
}
