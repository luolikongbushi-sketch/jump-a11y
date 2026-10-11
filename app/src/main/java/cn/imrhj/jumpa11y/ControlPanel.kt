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
 * 几条定下来的规矩：
 *
 * 1. **主操作永远在右边**。右手握机时拇指从右下扫过来最容易够到，也是 Material 的惯例。
 *    主按钮（确定 / 跳）位置固定不动，只换文字，用两三次就形成肌肉记忆。
 *
 * 2. **次要操作永远在左边同样的位置**。重选和退出每个状态下都在原处，不会跳来跳去。
 *    最早那版按钮忽隐忽现、位置乱换，每次都得重新找，用着别扭。
 *
 * 3. **一次最多三个按钮**，按状态只放需要的。
 *
 * 4. **主按钮 52dp 高**。Android 建议最小触控区 48dp，主操作再大一点，闭着眼也能按到。
 *    次要按钮做小做淡，不跟它抢。
 *
 * 5. **信息分两级**。上面一行小字说现在该干嘛，下面一行大字是你要的结果（距离和时长）。
 *
 * 6. **按下去有振动**，不用看屏幕也能确认点上了。
 *
 * ## 为什么是单独一个窗口
 *
 * 选点层大部分时间是穿透的（不接收触摸），按钮得留在另一个窗口才能一直点得到 ——
 * 尤其是退出。就算选点层卡住了，这里的按钮照样能点。
 *
 * ## 布局结构（从左到右）
 *
 *     [重选] [退出]        <弹性空白>        [主操作]
 *     ↑ 位置固定            ↑ 把主操作推到右边   ↑ 拇指区
 */
@SuppressLint("ViewConstructor")
class ControlPanel(
    context: Context,
    private val store: CalibrationStore
) : LinearLayout(context) {

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
    private val rowButtons: LinearLayout

    // 左边：次要操作，位置永远不变
    private val btnReset: Button
    private val btnHide: Button
    // 待机时才有：两个入口
    private val btnPick: Button
    private val btnCalibrate: Button
    // 右边：主操作，只换文字不换位置
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

        // ---- 信息区：小字说明 + 大字结果 ----
        tvStatus = TextView(context).apply {
            setTextColor(col(R.color.text_dim))
            textSize = 12.5f
            letterSpacing = 0.01f
        }
        addView(tvStatus)

        tvReadout = TextView(context).apply {
            setTextColor(col(R.color.accent))
            textSize = 21f
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

        // ---- 按钮区 ----
        rowButtons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        addView(rowButtons)

        // 建一次，之后只切 visibility
        btnReset = secondaryButton(R.string.btn_again) { listener?.onReset() }
        btnHide = secondaryButton(R.string.btn_hide) { listener?.onHide() }
        btnPick = primaryButton { listener?.onStartPicking() }
        btnCalibrate = secondaryButton(R.string.btn_calibrate) { listener?.onStartCalibration() }
        btnPrimary = primaryButton { primaryAction() }
        btnPick.setText(R.string.btn_pick)
        btnPrimary.setText(R.string.btn_confirm)

        // 左边两个
        rowButtons.addView(btnReset)
        rowButtons.addView(btnHide)
        // 待机入口（默认隐藏）
        rowButtons.addView(btnPick)
        rowButtons.addView(btnCalibrate)
        // 弹性空白：把主操作顶到最右边
        rowButtons.addView(View(context).apply {
            layoutParams = LayoutParams(0, 1).apply { weight = 1f }
        })
        // 主操作
        rowButtons.addView(btnPrimary)

        renderIdle()
    }

    // ---------------------------------------------------------------- 按钮工厂

    /** 主操作：大、亮、带描边。上下内边距 16dp + 16sp 字 ≈ 52dp 高。 */
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
            val px = (26 * density).toInt()
            val py = (16 * density).toInt()
            setPadding(px, py, px, py)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    /** 次要操作：小、淡，不抢注意力。但高度也保证 48dp，够手指点。 */
    private fun secondaryButton(textRes: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = context.getString(textRes)
            textSize = 13f
            isAllCaps = false
            letterSpacing = 0.02f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setTextColor(col(R.color.text_dim))
            background = ContextCompat.getDrawable(context, R.drawable.bg_btn)
            val px = (16 * density).toInt()
            val py = (12 * density).toInt()
            setPadding(px, py, px, py)
            // 触控区不小于 48dp（Android 的建议值），主按钮靠更大的内边距显得更醒目
            minimumHeight = (48 * density).toInt()
            setOnClickListener {
                // 次要操作用更轻的一下，和主操作区分开
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
        }

    // ---------------------------------------------------------------- 显隐

    /** 左边两个次要操作，位置固定，只切显隐 */
    private fun showSecondary(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        btnReset.visibility = v
        btnHide.visibility = v
    }

    /** 待机那两个入口 */
    private fun showIdleEntries(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        btnPick.visibility = v
        btnCalibrate.visibility = v
    }

    /** 主操作：换文字和绑定的动作，位置不动 */
    private fun setPrimary(labelRes: Int, action: () -> Unit) {
        btnPrimary.setText(labelRes)
        btnPrimary.isEnabled = true
        btnPrimary.alpha = 1f
        btnPrimary.visibility = View.VISIBLE
        primaryAction = action
    }

    private fun hidePrimary() {
        btnPrimary.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 各状态

    /** 待机：左边给两个入口，右边还没有可确定的东西 */
    fun renderIdle() {
        tvStatus.setText(R.string.ov_idle_hint)
        tvReadout.text = dashReadout()
        showSecondary(false)
        showIdleEntries(true)
        hidePrimary()
        refreshCoeff()
    }

    /** 等用户点一下（粗选），此时没有可确定的东西 */
    fun renderAwaitingTap(stage: SelectionCanvas.Stage) {
        showIdleEntries(false)
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_pick_start
                SelectionCanvas.Stage.END -> R.string.ov_pick_end
                SelectionCanvas.Stage.LANDING -> R.string.cal_step_landing
            }
        )
        tvReadout.text = dashReadout()
        showSecondary(true)
        hidePrimary()
        refreshCoeff()
    }

    /** 点过了，正在微调：主操作变【确定】 */
    fun renderAdjusting(stage: SelectionCanvas.Stage) {
        showIdleEntries(false)
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_adjust_start
                SelectionCanvas.Stage.END -> R.string.ov_adjust_end
                SelectionCanvas.Stage.LANDING -> R.string.ov_adjust_landing
            }
        )
        tvReadout.text = dashReadout()
        showSecondary(true)
        setPrimary(R.string.btn_confirm) { listener?.onConfirm() }
        refreshCoeff()
    }

    /** 两个点都定了：主操作变【跳】 */
    fun renderReady(start: JumpCalculator.Point?, end: JumpCalculator.Point?) {
        showIdleEntries(false)
        tvStatus.setText(R.string.ov_ready)
        tvReadout.text = readoutFor(start, end)
        showSecondary(true)
        setPrimary(R.string.btn_jump) { listener?.onJump() }
        refreshCoeff()
    }

    fun renderCalibrationIntro() {
        showIdleEntries(false)
        tvStatus.setText(R.string.cal_short)
        tvReadout.text = dashReadout()
        showSecondary(true)
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

    private fun dashReadout(): String =
        "${context.getString(R.string.ov_distance)} —   ${context.getString(R.string.ov_duration)} —"

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
