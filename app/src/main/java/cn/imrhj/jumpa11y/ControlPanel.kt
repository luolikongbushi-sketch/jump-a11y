package cn.imrhj.jumpa11y

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 浮层底部的控制栏。
 *
 * ## 排版是按拇指操作来定的
 *
 * 1. **主操作固定在最右边**，宽度写死 96dp。右手握机时拇指从右下扫过来最容易够到，
 *    而且位置和大小都不变，用两三次就形成肌肉记忆了。
 *    主按钮的文字会在【选点】【确定】【跳】之间切换，但位置和宽度不动。
 *
 * 2. **次要操作固定在左边**，宽度写死 68dp。重选和退出一直在原处，不会忽隐忽现。
 *
 * 3. **所有按钮宽度写死**。之前用 wrap_content，导致"重选"比"退出浮层"窄，
 *    主按钮又因为字号大更宽，一排看过去参差不齐。写死宽度就整齐了。
 *
 * 4. **高度拉开层次**：主操作 52dp，次要操作 48dp。
 *    48dp 是 Android 建议的最小触控区，主操作再大一点，闭着眼也能按到。
 *
 * 5. **信息分两级**：上面小字说现在该干嘛，下面大字是你关心的结果（距离和时长）。
 *
 * 6. **按下去有振动**，主操作重一下、次要轻一下，不用看屏幕也能确认。
 *
 * ## 各状态的布局
 *
 *     待机     [校准][退出]        ……        [选点]
 *     等点选   [重选][退出]        ……        （暂时没有主操作）
 *     微调中   [重选][退出]        ……        [确定]
 *     就绪     [重选][退出]        ……        [跳]
 *
 * ## 为什么是单独一个窗口
 *
 * 选点层大部分时间是穿透的（不接收触摸），按钮得留在另一个窗口才能一直点得到 ——
 * 尤其是退出。就算选点层卡住了，这里的按钮照样能点。
 */
@SuppressLint("ViewConstructor")
class ControlPanel(
    context: Context,
    private val store: CalibrationStore
) : LinearLayout(context) {

    companion object {
        /** 主操作宽度，固定。够放【选点】【确定】【跳】，也够拇指按。 */
        private const val PRIMARY_WIDTH_DP = 88
        /** 次要操作宽度，固定。最长的【退出浮层】是四个字，得放得下。 */
        private const val SECONDARY_WIDTH_DP = 76
        /** 主操作高度。比 48dp 的最小触控区再大一点。 */
        private const val PRIMARY_HEIGHT_DP = 52
        /** 次要操作高度，取 Android 建议的最小触控区。 */
        private const val SECONDARY_HEIGHT_DP = 48
    }

    interface Listener {
        fun onStartPicking()
        fun onStartCalibration()
        fun onConfirm()
        fun onJump()
        fun onReset()
        fun onHide()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    private val tvStatus: TextView
    private val tvReadout: TextView
    private val tvCoeff: TextView

    // 左边：次要操作，位置固定
    private val btnReset: Button
    private val btnCalibrate: Button
    private val btnHide: Button
    // 右边：主操作，位置固定，只换文字
    private val btnPrimary: Button

    /** 主操作当前的行为 */
    private var primaryAction: () -> Unit = {}

    private fun col(id: Int) = ContextCompat.getColor(context, id)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setColor(col(R.color.surface))
            setStroke((1f * density).toInt(), col(R.color.stroke))
        }
        val h = (18 * density).toInt()
        setPadding(h, (14 * density).toInt(), h, (14 * density).toInt())

        // ---- 信息区 ----
        tvStatus = TextView(context).apply {
            setTextColor(col(R.color.text_dim))
            textSize = 12.5f
            letterSpacing = 0.01f
        }
        addView(tvStatus)

        tvReadout = TextView(context).apply {
            setTextColor(col(R.color.accent))
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, (7 * density).toInt(), 0, 0)
        }
        addView(tvReadout)

        tvCoeff = TextView(context).apply {
            setTextColor(col(R.color.text_faint))
            textSize = 11f
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        addView(tvCoeff)

        // ---- 按钮区：左组 + 弹性空白 + 主操作 ----
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        addView(row)

        btnReset = secondaryButton(R.string.btn_again) { listener?.onReset() }
        btnCalibrate = secondaryButton(R.string.btn_calibrate) { listener?.onStartCalibration() }
        btnHide = secondaryButton(R.string.btn_hide) { listener?.onHide() }
        btnPrimary = primaryButton { primaryAction() }

        row.addView(btnReset)
        row.addView(btnCalibrate)
        row.addView(btnHide)
        // 弹性空白：把主操作顶到最右边（拇指区）
        row.addView(View(context).apply {
            layoutParams = LayoutParams(0, 1).apply { weight = 1f }
        })
        row.addView(btnPrimary)

        renderIdle()
    }

    // ---------------------------------------------------------------- 按钮工厂

    private fun primaryButton(onClick: () -> Unit): Button =
        Button(context).apply {
            textSize = 16f
            isAllCaps = false
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setTextColor(col(R.color.accent))
            background = ContextCompat.getDrawable(context, R.drawable.bg_btn_accent)
            setPadding(0, 0, 0, 0)   // 宽度写死了，靠 gravity 居中
            gravity = Gravity.CENTER
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            layoutParams = LayoutParams(
                (PRIMARY_WIDTH_DP * density).toInt(),
                (PRIMARY_HEIGHT_DP * density).toInt()
            )
        }

    private fun secondaryButton(textRes: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = context.getString(textRes)
            textSize = 12.5f
            isAllCaps = false
            letterSpacing = 0.0f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setTextColor(col(R.color.text_dim))
            background = ContextCompat.getDrawable(context, R.drawable.bg_btn)
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            maxLines = 1
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            layoutParams = LayoutParams(
                (SECONDARY_WIDTH_DP * density).toInt(),
                (SECONDARY_HEIGHT_DP * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
        }

    // ---------------------------------------------------------------- 显隐

    /** 左边那一组：传进来哪些就显示哪些，位置按声明顺序固定 */
    private fun showSecondary(reset: Boolean, calibrate: Boolean, hide: Boolean) {
        btnReset.visibility = if (reset) View.VISIBLE else View.GONE
        btnCalibrate.visibility = if (calibrate) View.VISIBLE else View.GONE
        btnHide.visibility = if (hide) View.VISIBLE else View.GONE
    }

    /** 主操作：换文字和绑定的动作，位置和宽度都不动 */
    private fun setPrimary(labelRes: Int, action: () -> Unit) {
        btnPrimary.setText(labelRes)
        btnPrimary.isEnabled = true
        btnPrimary.alpha = 1f
        btnPrimary.visibility = View.VISIBLE
        primaryAction = action
    }

    private fun hidePrimary() {
        // 留个同宽的占位，免得整行按钮左右漂移
        btnPrimary.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- 各状态

    /** 待机：左边是校准和退出，右边是选点（当作主操作） */
    fun renderIdle() {
        tvStatus.setText(R.string.ov_idle_hint)
        tvReadout.text = dashReadout()
        showSecondary(reset = false, calibrate = true, hide = true)
        setPrimary(R.string.btn_pick) { listener?.onStartPicking() }
        refreshCoeff()
    }

    /** 等用户点一下（粗选）。此时没有可确定的东西，主操作先不出 */
    fun renderAwaitingTap(stage: SelectionCanvas.Stage) {
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_pick_start
                SelectionCanvas.Stage.END -> R.string.ov_pick_end
                SelectionCanvas.Stage.LANDING -> R.string.cal_step_landing
            }
        )
        tvReadout.text = dashReadout()
        showSecondary(reset = true, calibrate = false, hide = true)
        hidePrimary()
        refreshCoeff()
    }

    /** 点过了，正在微调：主操作变【确定】 */
    fun renderAdjusting(stage: SelectionCanvas.Stage) {
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_adjust_start
                SelectionCanvas.Stage.END -> R.string.ov_adjust_end
                SelectionCanvas.Stage.LANDING -> R.string.ov_adjust_landing
            }
        )
        tvReadout.text = dashReadout()
        showSecondary(reset = true, calibrate = false, hide = true)
        setPrimary(R.string.btn_confirm) { listener?.onConfirm() }
        refreshCoeff()
    }

    /** 两个点都定了：主操作变【跳】 */
    fun renderReady(start: JumpCalculator.Point?, end: JumpCalculator.Point?) {
        tvStatus.setText(R.string.ov_ready)
        tvReadout.text = readoutFor(start, end)
        showSecondary(reset = true, calibrate = false, hide = true)
        setPrimary(R.string.btn_jump) { listener?.onJump() }
        refreshCoeff()
    }

    fun renderCalibrationIntro() {
        tvStatus.setText(R.string.cal_short)
        tvReadout.text = dashReadout()
        showSecondary(reset = true, calibrate = false, hide = true)
        hidePrimary()
        refreshCoeff()
    }

    fun renderBusy() {
        tvStatus.setText(R.string.ov_jumping)
        btnPrimary.isEnabled = false
        btnPrimary.alpha = 0.4f
    }

    fun renderMessage(message: String) {
        tvStatus.text = message
        btnPrimary.isEnabled = false
        btnPrimary.alpha = 0.4f
    }

    // ---------------------------------------------------------------- 文字

    /**
     * 还没有结果时的占位。
     *
     * 只画一条短横线，不占大字号也不写"距离/时长" —— 那两行大字是留给真实数字的，
     * 没数字的时候不该抢注意力，上面那行小字已经把该干嘛说清楚了。
     */
    private fun dashReadout(): String = "—"

    private fun readoutFor(start: JumpCalculator.Point?, end: JumpCalculator.Point?): String {
        val dist = JumpCalculator.distancePx(start, end)
        if (dist <= 0f) return dashReadout()
        // 兜一下：万一系统接口抛异常，面板也得能画出来
        val maxMs = try {
            android.accessibilityservice.GestureDescription.getMaxGestureDuration()
        } catch (t: Throwable) {
            60_000L
        }
        val ms = JumpCalculator.durationMs(dist, store.coefficient, maxMs)
        return "${dist.toInt()} ${context.getString(R.string.ov_unit_px)}" +
            "   ${ms} ${context.getString(R.string.ov_unit_ms)}"
    }

    fun refreshCoeff() {
        tvCoeff.text = context.getString(R.string.ov_coeff) + " " + fmt(store.coefficient)
    }

    private fun fmt(v: Float): String {
        val h = (v * 100f).toInt()
        return "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }
}
