package cn.imrhj.jumpa11y

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 浮层底下那条控制栏。
 *
 * 单独开一个窗口，因为选点层大部分时间是穿透的（不接收触摸），按钮得留在另一个窗口里
 * 才能一直点得到 —— 特别是【退出】。就算选点层卡住了，这个按钮也能点。
 *
 * 按状态只显示该显示的按钮：
 *
 *   待机    -> [选点] [校准] [退出]
 *   点起点  -> "点一下棋子"            + [重选] [退出]
 *   调起点  -> "滑动微调，好了点确定"   + [确定] [重选] [退出]
 *   点终点  -> "点一下目标方块"        + [重选] [退出]
 *   调终点  -> "滑动微调，好了点确定"   + [确定] [重选] [退出]
 *   就绪    -> 距离和时长              + [跳] [重选] [退出]
 *
 * 【跳】只有两个点都确定了才出现；没确定之前是隐藏的，免得乱点就跳出去。
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

    private val btnPick: Button
    private val btnCalibrate: Button
    private val btnConfirm: Button
    private val btnJump: Button
    private val btnReset: Button

    private fun col(id: Int) = ContextCompat.getColor(context, id)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 18f * density
            setColor(col(R.color.surface))
            setStroke((1f * density).toInt(), col(R.color.stroke))
        }
        val px = (16 * density).toInt()
        setPadding(px, (12 * density).toInt(), px, (12 * density).toInt())

        tvStatus = TextView(context).apply {
            setTextColor(col(R.color.text))
            textSize = 13.5f
            letterSpacing = 0.01f
        }
        addView(tvStatus)

        tvReadout = TextView(context).apply {
            setTextColor(col(R.color.accent))
            textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        addView(tvReadout)

        tvCoeff = TextView(context).apply {
            setTextColor(col(R.color.text_faint))
            textSize = 11f
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        addView(tvCoeff)

        rowButtons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        addView(rowButtons)

        btnPick = makeButton(R.string.btn_pick, R.drawable.bg_btn_accent, R.color.accent) { listener?.onStartPicking() }
        btnCalibrate = makeButton(R.string.btn_calibrate, R.drawable.bg_btn, R.color.warn) { listener?.onStartCalibration() }
        btnConfirm = makeButton(R.string.btn_confirm, R.drawable.bg_btn_accent, R.color.accent) { listener?.onConfirm() }
        btnJump = makeButton(R.string.btn_jump, R.drawable.bg_btn_accent, R.color.ok) { listener?.onJump() }
        btnReset = makeButton(R.string.btn_again, R.drawable.bg_btn, R.color.text_dim) { listener?.onReset() }
        val btnHide = makeButton(R.string.btn_hide, R.drawable.bg_btn_danger, R.color.bad) { listener?.onHide() }

        rowButtons.addView(btnPick)
        rowButtons.addView(btnCalibrate)
        rowButtons.addView(btnConfirm)
        rowButtons.addView(btnJump)
        rowButtons.addView(btnReset)
        rowButtons.addView(btnHide)

        renderIdle()
    }

    private fun makeButton(textRes: Int, bgRes: Int, textColorRes: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = context.getString(textRes)
            textSize = 14f
            isAllCaps = false
            letterSpacing = 0.02f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setTextColor(col(textColorRes))
            background = ContextCompat.getDrawable(context, bgRes)
            setPadding((16 * density).toInt(), (9 * density).toInt(), (16 * density).toInt(), (9 * density).toInt())
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (7 * density).toInt() }
        }

    // ---------------------------------------------------------------- 各种状态

    fun renderIdle() {
        tvStatus.setText(R.string.ov_idle_hint)
        tvReadout.text = dashReadout()
        show(pick = true, calibrate = true, confirm = false, jump = false, reset = false)
        refreshCoeff()
    }

    /** 还没点，等用户粗选一个点。 */
    fun renderAwaitingTap(stage: SelectionCanvas.Stage) {
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_pick_start
                SelectionCanvas.Stage.END -> R.string.ov_pick_end
                SelectionCanvas.Stage.LANDING -> R.string.cal_step_landing
            }
        )
        tvReadout.text = dashReadout()
        show(pick = false, calibrate = false, confirm = false, jump = false, reset = true)
        refreshCoeff()
    }

    /** 粗选好了，等微调和确定。 */
    fun renderAdjusting(stage: SelectionCanvas.Stage) {
        tvStatus.setText(
            when (stage) {
                SelectionCanvas.Stage.START -> R.string.ov_adjust_start
                SelectionCanvas.Stage.END -> R.string.ov_adjust_end
                SelectionCanvas.Stage.LANDING -> R.string.ov_adjust_landing
            }
        )
        tvReadout.text = dashReadout()
        show(pick = false, calibrate = false, confirm = true, jump = false, reset = true)
        refreshCoeff()
    }

    /** 两个点都确定了，可以跳。 */
    fun renderReady(start: JumpCalculator.Point?, end: JumpCalculator.Point?) {
        tvStatus.setText(R.string.ov_ready)
        tvReadout.text = readoutFor(start, end)
        show(pick = false, calibrate = false, confirm = false, jump = true, reset = true)
        btnJump.isEnabled = true
        refreshCoeff()
    }

    fun renderCalibrationIntro() {
        tvStatus.setText(R.string.cal_short)
        tvReadout.text = dashReadout()
        show(pick = false, calibrate = false, confirm = false, jump = false, reset = true)
        refreshCoeff()
    }

    fun renderBusy() {
        tvStatus.setText(R.string.ov_jumping)
        btnJump.isEnabled = false
    }

    fun renderMessage(message: String) {
        tvStatus.text = message
        btnJump.isEnabled = false
    }

    private fun show(
        pick: Boolean, calibrate: Boolean, confirm: Boolean, jump: Boolean, reset: Boolean
    ) {
        btnPick.visibility = if (pick) View.VISIBLE else View.GONE
        btnCalibrate.visibility = if (calibrate) View.VISIBLE else View.GONE
        btnConfirm.visibility = if (confirm) View.VISIBLE else View.GONE
        btnJump.visibility = if (jump) View.VISIBLE else View.GONE
        btnReset.visibility = if (reset) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 文字

    private fun dashReadout(): String =
        "${context.getString(R.string.ov_distance)} —    ${context.getString(R.string.ov_duration)} —"

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
            "   ·   $ms ${context.getString(R.string.ov_unit_ms)}"
    }

    fun refreshCoeff() {
        tvCoeff.text = context.getString(R.string.ov_coeff) + " " + fmt(store.coefficient)
    }

    private fun fmt(v: Float): String {
        val h = (v * 100f).toInt()
        return "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }
}
