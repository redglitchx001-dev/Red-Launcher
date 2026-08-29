package com.movtery.redlauncherbeta.feature.theme

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger

/**
 * Red Launcher Beta 主题管理器
 *
 * 管理 Liquid Glass（磨砂玻璃）主题风格：
 * - 应用窗口背景色（暗红色调，避免白色闪烁）
 * - 具体 UI 表现（半透明卡片、玻璃描边、模糊）由 Compose 层根据
 *   [AllSettings.liquidGlassEnabled] 实时生效
 */
object ThemeManager {
    private const val TAG = "ThemeManager"

    enum class ThemeStyle {
        CLASSIC,
        LIQUID_GLASS
    }

    /** Liquid Glass 窗口背景色：暗红色调 */
    private const val GLASS_WINDOW_BACKGROUND = 0xFF14090B

    fun isLiquidGlassEnabled(): Boolean = AllSettings.liquidGlassEnabled.getValue()

    /**
     * 应用主题风格到 Activity 窗口
     *
     * @param style 期望的主题风格；Liquid Glass 风格实际是否生效还受
     *              [AllSettings.liquidGlassEnabled] 设置控制
     */
    fun applyTheme(context: Context, style: ThemeStyle = ThemeStyle.LIQUID_GLASS) {
        if (style != ThemeStyle.LIQUID_GLASS) return
        if (context !is Activity) return
        if (!isLiquidGlassEnabled()) return

        runCatching {
            //暗红色调窗口背景，与磨砂玻璃 UI 搭配
            context.window.setBackgroundDrawable(ColorDrawable(GLASS_WINDOW_BACKGROUND))
            Logger.info(TAG, "Liquid glass theme applied")
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to apply liquid glass theme", e)
        }
    }
}
