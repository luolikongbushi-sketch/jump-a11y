package cn.imrhj.jumpa11y

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * 浅色/深色主题的切换，选完记住。
 *
 * 颜色本身不在这里管 —— 用的是 Android 自带的那套：values/colors.xml 是浅色，
 * values-night/colors.xml 是深色，颜色名一一对应。这里只负责告诉系统"这次要用哪套"。
 *
 * 浮层那边不用特意处理：窗口是自己拿 ContextCompat 取色的，配置一变就会跟着变。
 */
object ThemeSettings {

    private const val PREFS = "jump_a11y_prefs"
    private const val KEY_DARK = "dark_theme"

    /** 默认跟系统走。 */
    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DARK, dark) }
        apply(dark)
    }

    /**
     * 在 Activity 的 onCreate 最开始调用。
     *
     * 用 setDefaultNightMode 而不是给 Activity 单独设，是为了让浮层也跟着换 ——
     * 无障碍服务是另一个进程组件，它读的是全局配置。
     */
    fun applyStored(context: Context) {
        apply(isDark(context))
    }

    private fun apply(dark: Boolean) {
        val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
