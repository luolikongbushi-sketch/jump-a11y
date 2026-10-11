package cn.imrhj.jumpa11y

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主界面。就干三件事：看服务开没开、开浮层、调系数。
 *
 * 浮层不在这个 Activity 里，在 [JumpAccessibilityService] 里。Activity 没法直接操作
 * 无障碍服务，所以两边靠 [JumpAccessibilityService.overlayRequested] 这个标记传话。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: CalibrationStore
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var btnShowOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CalibrationStore(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从设置里回来，每次回前台都刷一下状态
        refreshStatus()
    }

    // ---------------------------------------------------------------- 控件

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun body(text: String, size: Float = 13f, colorId: Int = R.color.text_dim) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(col(colorId))
            letterSpacing = 0.015f
            setLineSpacing(dp(5).toFloat(), 1f)
        }

    private fun sectionTitle(resId: Int) = TextView(this).apply {
        text = getString(resId)
        textSize = 15f
        setTextColor(col(R.color.text))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }

    private fun hairline() = View(this).apply {
        setBackgroundColor(col(R.color.stroke))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(22); bottomMargin = dp(22) }
    }

    private fun wideButton(resId: Int, accent: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = getString(resId)
        textSize = 15f
        isAllCaps = false
        letterSpacing = 0.02f
        minHeight = 0; minimumHeight = 0
        setTextColor(col(if (accent) R.color.accent else R.color.text))
        background = ContextCompat.getDrawable(
            this@MainActivity,
            if (accent) R.drawable.bg_btn_accent else R.drawable.bg_btn
        )
        setPadding(dp(18), dp(13), dp(18), dp(13))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun halfButton(resId: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(resId)
        textSize = 14f
        isAllCaps = false
        letterSpacing = 0.02f
        minHeight = 0; minimumHeight = 0
        setTextColor(col(R.color.text_dim))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(10) }
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(36))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 27f
            setTextColor(col(R.color.text))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.01f
        })
        root.addView(body(getString(R.string.main_subtitle), 12.5f, R.color.text_faint).apply {
            setPadding(0, dp(6), 0, dp(26))
        })

        // ---- 服务状态 ----
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col(R.color.text_faint))
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(9) }
        }
        statusRow.addView(statusDot)
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(col(R.color.text))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        statusRow.addView(statusText)
        root.addView(statusRow)

        statusHint = body("", 12.5f, R.color.text_faint).apply { setPadding(0, dp(7), 0, 0) }
        root.addView(statusHint)

        btnShowOverlay = wideButton(R.string.btn_show_overlay) { requestOverlay() }
        root.addView(btnShowOverlay)
        root.addView(wideButton(R.string.btn_open_settings, accent = false) { openAccessibilitySettings() })

        root.addView(hairline())

        // ---- 用法 ----
        root.addView(sectionTitle(R.string.main_howto_title))
        root.addView(body(getString(R.string.main_howto_body)))

        root.addView(hairline())

        // ---- 系数 ----
        root.addView(sectionTitle(R.string.main_param_title))
        root.addView(body(getString(R.string.main_coeff_label)))

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(store.coefficient.toString())
            setTextColor(col(R.color.text))
            textSize = 19f
            letterSpacing = 0.02f
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        root.addView(input)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        row.addView(halfButton(R.string.btn_save) {
            val v = input.text.toString().toFloatOrNull()
            if (v == null || v < JumpCalculator.MIN_COEFFICIENT || v > JumpCalculator.MAX_COEFFICIENT) {
                toast(getString(R.string.main_param_bad))
            } else {
                store.coefficient = v
                input.setText(store.coefficient.toString())
                toast(getString(R.string.main_param_saved))
            }
        })
        row.addView(halfButton(R.string.btn_reset_default) {
            store.resetCoefficient()
            input.setText(store.coefficient.toString())
            toast(getString(R.string.main_param_saved))
        })
        root.addView(row)

        root.addView(body(getString(R.string.main_coeff_hint), 11.5f, R.color.text_faint).apply {
            setPadding(0, dp(10), 0, 0)
        })

        root.addView(hairline())

        // ---- 校准说明 ----
        root.addView(sectionTitle(R.string.main_device_title))
        root.addView(body(getString(R.string.main_device_body)))

        root.addView(hairline())

        // ---- 说明 ----
        root.addView(sectionTitle(R.string.main_warn_title))
        root.addView(body(getString(R.string.main_warn_body), 12.5f, R.color.text_faint))

        return ScrollView(this).apply {
            setBackgroundColor(col(R.color.bg))
            isFillViewport = true
            addView(root)
        }
    }

    // ---------------------------------------------------------------- 动作

    private fun refreshStatus() {
        val on = JumpAccessibilityService.isRunning
        statusText.text = getString(if (on) R.string.status_ok else R.string.status_no)
        statusHint.text = getString(if (on) R.string.status_hint_ok else R.string.status_hint_no)
        statusDot.background?.setTint(col(if (on) R.color.ok else R.color.bad))
        btnShowOverlay.isEnabled = on
        btnShowOverlay.alpha = if (on) 1f else 0.45f
    }

    private fun requestOverlay() {
        if (!JumpAccessibilityService.isRunning) {
            toast(getString(R.string.ov_not_ready))
            return
        }
        JumpAccessibilityService.overlayRequested = true
        toast(getString(R.string.ov_shown))
        // 自己退到后台，让用户直接看到游戏和浮层
        moveTaskToBack(true)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
