package com.movtery.redlauncherbeta.feature.discord
import android.content.Context
import android.content.Intent
import android.net.Uri
object DiscordManager {
    private const val CLIENT_ID = "YOUR_DISCORD_CLIENT_ID"
    private const val REDIRECT_URI = "redlauncher://discord"
    fun startLogin(context: Context) {
        val url = "https://discord.com/api/oauth2/authorize?client_id=${CLIENT_ID}&redirect_uri=${REDIRECT_URI}&response_type=code&scope=identify%20activities.write"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
    fun updatePresence(username: String, serverIp: String?, isPlaying: Boolean) {
        val state = if (serverIp.isNullOrEmpty()) "In Meniul Principal" else "Joaca pe ${serverIp}"
        println("Discord RPC Updated: User ${username} | ${state}")
    }
}
