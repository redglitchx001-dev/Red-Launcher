package com.movtery.redlauncherbeta.feature.theme
import android.app.Activity
import android.graphics.Color
import android.view.View
object ThemeManager {
    enum class ThemeStyle { LIQUID_GLASS, DARK, LIGHT }
    fun applyTheme(activity: Activity, style: ThemeStyle) {
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        when (style) {
            ThemeStyle.LIQUID_GLASS -> {
                // Background Resource set in XML, here we apply animations
                root.alpha = 0f
                root.translationY = 100f
                root.animate().alpha(1f).translationY(0f).setDuration(800).start()
            }
            ThemeStyle.DARK -> root.setBackgroundColor(Color.parseColor("#121212"))
            ThemeStyle.LIGHT -> root.setBackgroundColor(Color.parseColor("#FFFFFF"))
        }
    }
}
