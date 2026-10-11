package cn.imrhj.jumpa11y

import android.content.Context
import androidx.core.content.edit

/**
 * 系数存在本地。
 *
 * 之所以要存而不是写死：这个值跟屏幕宽度有关，换台机器就不一样；而且校准之后
 * 算出来的真实值得留着下次用。
 */
class CalibrationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var coefficient: Float
        get() = prefs.getFloat(KEY_COEFF, JumpCalculator.DEFAULT_COEFFICIENT)
        set(value) = prefs.edit { putFloat(KEY_COEFF, JumpCalculator.sanitizeCoefficient(value)) }

    fun resetCoefficient() {
        prefs.edit { putFloat(KEY_COEFF, JumpCalculator.DEFAULT_COEFFICIENT) }
    }

    /**
     * 上次运行时应用的版本号。
     *
     * 存这个是为了判断"是不是刚升级过"。升级之后无障碍授权通常会掉，得主动提醒
     * 用户重新去开，不然他会以为是应用坏了。
     */
    var lastSeenVersionCode: Int
        get() = prefs.getInt(KEY_LAST_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_LAST_VERSION, value) }

    companion object {
        private const val PREFS = "jump_a11y_prefs"
        private const val KEY_COEFF = "press_coefficient"
        private const val KEY_LAST_VERSION = "last_version_code"
    }
}
