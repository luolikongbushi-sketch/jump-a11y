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

    companion object {
        private const val PREFS = "jump_a11y_prefs"
        private const val KEY_COEFF = "press_coefficient"
    }
}
