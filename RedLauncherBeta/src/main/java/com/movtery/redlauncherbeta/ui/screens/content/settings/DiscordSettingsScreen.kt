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
 */

package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.redlauncherbeta.feature.discord.DiscordManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.OwnOutlinedTextField
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import com.movtery.zalithlauncher.viewmodel.EventViewModel

@Composable
fun DiscordSettingsScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?,
    eventViewModel: EventViewModel,
) {
    val connectionState by DiscordManager.connectionState.collectAsState()

    var enabled by remember { mutableStateOf(DiscordManager.isEnabled()) }
    var tokenInput by remember { mutableStateOf(DiscordManager.getToken()) }
    var tokenVisible by remember { mutableStateOf(false) }

    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.Discord, settingsScreenKey, false)
    ) { isVisible ->
        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { scope ->
            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            DiscordManager.setEnabled(it)
                        },
                        title = "Discord Rich Presence",
                        summary = "Show on Discord what you are doing: skin face, server, players and play time"
                    )

                    SettingsCard(
                        position = CardPosition.Middle,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Discord Token",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Paste your Discord user token below. Red Launcher uses it only " +
                                "to update your Rich Presence (the same way CustomRPC does). " +
                                "Never share it with anyone.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        OwnOutlinedTextField(
                            value = if (tokenVisible) tokenInput else "•".repeat(tokenInput.length),
                            onValueChange = { if (tokenVisible) tokenInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            label = { Text("Token") },
                            supportingText = {
                                Text(if (tokenInput.isBlank()) "Required to connect" else connectionState)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Ascii
                            ),
                            trailingIcon = {
                                Text(
                                    text = if (tokenVisible) "HIDE" else "SHOW",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .align(Alignment.CenterVertically)
                                        .clickable { tokenVisible = !tokenVisible }
                                )
                            }
                        )
                        Button(
                            onClick = {
                                DiscordManager.setToken(tokenInput)
                                DiscordManager.connect()
                            },
                            enabled = enabled && tokenInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text("Save & Connect")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { DiscordManager.disconnect() },
                            enabled = DiscordManager.isReady,
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            Text("Disconnect")
                        }
                    }

                    SettingsCard(
                        position = CardPosition.Bottom,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "How to get your Discord token",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "1. Open Discord on PC\n" +
                                "2. Settings → Advanced → enable Developer Mode\n" +
                                "3. Right-click your avatar → Copy ID (that's not it)\n" +
                                "4. Open browser DevTools (F12) → Network → any request → " +
                                "Request Headers → copy the \"Authorization\" value\n\n" +
                                "Alternative: use any \"token sniffer\" website while logged in.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
