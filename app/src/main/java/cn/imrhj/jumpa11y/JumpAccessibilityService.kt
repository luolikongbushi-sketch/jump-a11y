package cn.imrhj.jumpa11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 服务本体：管两个浮层窗口，负责发手势。
 *
 * ## 为什么是两个窗口
 *
 * 最早只做了一个全屏浮层，结果它把所有触摸都吃掉了，游戏根本没法玩。所以拆成两个：
 *
 *   - 选点层：全屏。平时带 FLAG_NOT_TOUCHABLE，触摸直接穿到游戏上；
 *             只有正在选点的时候才接收触摸。
 *   - 控制栏：屏幕底部一小条，一直能点。
 *
 * ## 选点的流转
 *
 *   beginPicking -> 等用户点一下（粗选）
 *                -> 点落在手指位置，然后进入"微调"状态，等【确定】
 *                -> 【确定】之后锁住这个点，轮到下一个
 *                -> 起点和终点都确定后，控制栏变成【跳】
 *
 * 校准就是多一步：跳完之后再确定一个"实际落点"。
 *
 * ## 几条自己定的规矩
 *
 * 1. 加进去的窗口，任何情况下都要能移除。半路失败留下的全屏窗口会让整台手机点不动，
 *    这是最要紧的一条。
 * 2. 所有回调都包了异常。服务里抛异常会把无障碍服务带下去，代价太大。
 * 3. 任何一条失败路径上都要把选点层恢复成穿透，不能把人锁在屏幕外面。
 * 4. 同一时间只允许一次注入。
 *
 * ## 明确不做的事
 *
 *   - 不读屏幕内容（canRetrieveWindowContent 关着）
 *   - 不截屏
 *   - 不联网（APK 里连 INTERNET 权限都没有）
 *   - 不执行 shell，不要 root
 */
class JumpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JumpA11y"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 主界面点"显示浮层"时置上，服务连上或者收到事件后就来建浮层。 */
        @Volatile
        var overlayRequested: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var executor: JumpExecutor
    private lateinit var store: CalibrationStore

    private var canvas: SelectionCanvas? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var panel: ControlPanel? = null

    /** 选点层是不是正在接触摸。 */
    private var picking = false

    /** 是不是在走校准流程。 */
    private var calibrating = false

    /** 防止两次注入叠在一起。 */
    private var injecting = false

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        executor = JumpExecutor(this)
        store = CalibrationStore(this)
        Log.i(TAG, "service connected; gesture injection available")
        if (overlayRequested) showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只当心跳用。有时候用户在主界面点"显示浮层"时服务还没连上。
        if (overlayRequested && canvas == null) {
            try { showOverlay() } catch (t: Throwable) { Log.e(TAG, "heartbeat showOverlay", t) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        teardown()
        Log.i(TAG, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        teardown()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    /** 收掉一切。重复调用也没事。 */
    private fun teardown() {
        try { hideOverlay() } catch (t: Throwable) { Log.e(TAG, "teardown hideOverlay", t) }
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 浮层

    private fun showOverlay() {
        if (canvas != null) return

        var addedCanvas: SelectionCanvas? = null
        try {
            val c = SelectionCanvas(this).apply { onPointsChanged = { onPointsChangedSafely() } }
            val p = ControlPanel(this, store).apply {
                listener = object : ControlPanel.Listener {
                    override fun onStartPicking() = safely { beginPicking() }
                    override fun onStartCalibration() = safely { beginCalibration() }
                    override fun onConfirm() = safely { confirmStaged() }
                    override fun onJump() = safely { onJumpPressed() }
                    override fun onReset() = safely { resetPoints() }
                    override fun onHide() = safely {
                        hideOverlay()
                        overlayRequested = false
                    }
                }
            }

            val cParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,   // 默认穿透
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            val pParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                // 贴着底部导航条上方，离底边越近拇指越顺手。
                // 窗口做得越小，能挡到游戏的地方就越少。
                y = (64 * resources.displayMetrics.density).toInt()
            }

            windowManager.addView(c, cParams)
            addedCanvas = c
            windowManager.addView(p, pParams)

            canvas = c
            canvasParams = cParams
            panel = p
            picking = false
            calibrating = false
            p.renderIdle()
            Log.i(TAG, "overlay shown (canvas pass-through, panel touchable)")
        } catch (t: Throwable) {
            // 半路失败的话，已经加进去的窗口必须拿掉，不然它会一直留在屏幕上，
            // 严重的时候整台手机都点不了。
            Log.e(TAG, "failed to add overlay; rolling back", t)
            addedCanvas?.let {
                try { windowManager.removeView(it) } catch (e: Throwable) { Log.e(TAG, "rollback removeView", e) }
            }
            canvas = null; canvasParams = null; panel = null
            picking = false; calibrating = false
            toast(getString(R.string.ov_fail))
        }
    }

    private fun hideOverlay() {
        val c = canvas
        val p = panel
        canvas = null; canvasParams = null; panel = null
        picking = false
        calibrating = false
        injecting = false
        try { c?.let { windowManager.removeView(it) } } catch (t: Throwable) { Log.e(TAG, "remove canvas", t) }
        try { p?.let { windowManager.removeView(it) } } catch (t: Throwable) { Log.e(TAG, "remove panel", t) }
        Log.i(TAG, "overlay hidden")
    }

    /**
     * 切换选点层是"穿透"还是"接触摸"。
     *
     * 当安全关键处理：如果本来想设成可触摸、结果更新失败，就退回穿透，
     * 免得用户被困在点不动的屏幕上。
     */
    private fun setCanvasTouchable(touchable: Boolean) {
        val c = canvas ?: return
        val params = canvasParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(c, params)
        } catch (t: Throwable) {
            Log.e(TAG, "updateViewLayout failed; forcing pass-through", t)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            picking = false
            try { windowManager.updateViewLayout(c, params) } catch (e: Throwable) {
                Log.e(TAG, "recovery updateViewLayout also failed", e)
            }
        }
    }

    // ---------------------------------------------------------------- 流程

    /** 正常流程：选起点、选终点，然后跳。 */
    private fun beginPicking() {
        val c = canvas ?: return
        c.clearAll()
        calibrating = false
        picking = true
        setCanvasTouchable(true)
        panel?.renderAwaitingTap(SelectionCanvas.Stage.START)
    }

    /** 校准流程：先选起点终点，跳一次，再确定实际落点。 */
    private fun beginCalibration() {
        val c = canvas ?: return
        c.clearAll()
        calibrating = true
        picking = true
        setCanvasTouchable(true)
        panel?.renderCalibrationIntro()
    }

    /** 【重选】：把已经选的都清掉，回到待机。 */
    private fun resetPoints() {
        val c = canvas ?: return
        c.clearAll()
        calibrating = false
        picking = false
        setCanvasTouchable(false)
        panel?.renderIdle()
    }

    /** 【确定】：锁住当前正在微调的点，轮到下一个。 */
    private fun confirmStaged() {
        val c = canvas ?: return
        val stageBefore = c.stage
        val confirmed = c.confirmStaged() ?: return

        Log.i(TAG, "confirmed $stageBefore at (${confirmed.x}, ${confirmed.y})")

        when (stageBefore) {
            SelectionCanvas.Stage.START -> {
                // 起点定了，继续选终点；选点层继续接触摸
                panel?.renderAwaitingTap(SelectionCanvas.Stage.END)
            }
            SelectionCanvas.Stage.END -> {
                // 两个点都齐了，停止拦触摸，游戏可以正常玩
                picking = false
                setCanvasTouchable(false)
                panel?.renderReady(c.startPoint, c.endPoint)
            }
            SelectionCanvas.Stage.LANDING -> {
                picking = false
                setCanvasTouchable(false)
                finishCalibration(c.startPoint, c.endPoint, c.landingPoint)
            }
        }
    }

    private fun onPointsChangedSafely() {
        safely { onPointsChanged() }
    }

    private fun onPointsChanged() {
        val c = canvas ?: return
        // 只根据"有没有正在微调的点"切换面板状态
        if (c.hasStaged) {
            panel?.renderAdjusting(c.stage)
        } else {
            panel?.renderAwaitingTap(c.stage)
        }
    }

    private fun onJumpPressed() {
        if (calibrating) calibrateJump() else normalJump()
    }

    private fun normalJump() {
        val c = canvas ?: return
        val s = c.startPoint ?: return
        val e = c.endPoint ?: return
        if (!JumpCalculator.isUsable(s, e)) {
            toast(getString(R.string.ov_too_close))
            return
        }
        val dist = JumpCalculator.distancePx(s, e)
        val ms = JumpCalculator.durationMs(
            dist, store.coefficient,
            android.accessibilityservice.GestureDescription.getMaxGestureDuration()
        )
        Log.i(TAG, "normal jump dist=${dist.toInt()}px coeff=${store.coefficient} -> ${ms}ms")
        inject(s.x, s.y, ms) { _, _ -> resetAfterJump() }
    }

    private fun calibrateJump() {
        val c = canvas ?: return
        val s = c.startPoint ?: return
        val e = c.endPoint ?: return
        if (!JumpCalculator.isUsable(s, e)) {
            toast(getString(R.string.ov_too_close))
            return
        }
        val dist = JumpCalculator.distancePx(s, e)
        val ms = JumpCalculator.durationMs(
            dist, store.coefficient,
            android.accessibilityservice.GestureDescription.getMaxGestureDuration()
        )
        Log.i(TAG, "CALIBRATION jump dist=${dist.toInt()}px coeff=${store.coefficient} -> ${ms}ms")
        inject(s.x, s.y, ms) { _, ok ->
            if (ok) {
                canvas?.prepareForLanding()
                setCanvasTouchable(true)          // 接住"标落点"那一下
                picking = true
                panel?.renderAwaitingTap(SelectionCanvas.Stage.LANDING)
            } else {
                panel?.renderMessage(getString(R.string.cal_failed_inject))
                calibrating = false
                picking = false
                setCanvasTouchable(false)         // 别忘了恢复穿透
            }
        }
    }

    private fun finishCalibration(
        s: JumpCalculator.Point?,
        e: JumpCalculator.Point?,
        l: JumpCalculator.Point?
    ) {
        calibrating = false
        picking = false
        setCanvasTouchable(false)

        when (val r = Calibrator.calibrate(s, e, l, store.coefficient)) {
            is Calibrator.Result.Success -> {
                store.coefficient = r.coefficient
                Log.i(
                    TAG,
                    "calibrated coeff ${r.previous} -> ${r.coefficient} " +
                        "(ratio=${r.ratio} target=${r.targetPx.toInt()}px actual=${r.actualPx.toInt()}px clamped=${r.wasClamped})"
                )
                toast(getString(R.string.cal_applied, fmt(r.previous), fmt(r.coefficient), fmt(r.ratio)))
            }
            is Calibrator.Result.Rejected -> {
                val msg = when (r.reason) {
                    Calibrator.Result.Reason.JUMP_TOO_SHORT -> getString(R.string.cal_jump_too_short)
                    Calibrator.Result.Reason.RATIO_IMPLAUSIBLE -> getString(R.string.cal_ratio_bad, fmt(r.ratio))
                }
                Log.w(TAG, "calibration rejected: ${r.reason} actual=${r.actualPx.toInt()}px ratio=${r.ratio}")
                toast(msg)
            }
        }

        canvas?.clearAll()
        panel?.renderIdle()
    }

    // ---------------------------------------------------------------- 注入

    /**
     * 唯一一处发手势的地方。
     *
     * 注入前先把选点层藏起来，不然这一下会按在自己浮层上而不是游戏上。
     * 控制栏留着 —— 它很小，而且离按的位置远。
     */
    private fun inject(x: Float, y: Float, ms: Long, onFinished: (Boolean, Boolean) -> Unit) {
        if (injecting) {
            Log.w(TAG, "injection already in progress, ignored")
            return
        }
        injecting = true
        panel?.renderBusy()
        canvas?.visibility = View.INVISIBLE

        var callbackFired = false

        // 注意这里没有在回调里用 accepted。dispatchGesture 有可能在返回之前就同步回调了，
        // 那样捕获到的 accepted 还没赋值。回调自己的参数就够用。
        val accepted = executor.press(x, y, ms, object : JumpExecutor.Callback {
            override fun onFinished(completed: Boolean) {
                safely {
                    callbackFired = true
                    injecting = false
                    canvas?.visibility = View.VISIBLE
                    Log.i(TAG, "gesture callback completed=$completed")
                    handler.postDelayed({ safely { onFinished(completed, completed) } }, 180L)
                }
            }
        })

        Log.i(TAG, "dispatchGesture accepted=$accepted press=($x,$y) ${ms}ms")

        if (!accepted && !callbackFired) {
            injecting = false
            canvas?.visibility = View.VISIBLE
            panel?.renderMessage(getString(R.string.ov_fail))
            handler.postDelayed({ safely { onFinished(false, false) } }, 250L)
        }
    }

    private fun resetAfterJump() {
        canvas?.clearAll()
        picking = false
        calibrating = false
        setCanvasTouchable(false)
        panel?.renderIdle()
    }

    // ---------------------------------------------------------------- 杂项

    /** 跑 [block]，里面抛了什么都在这里吃掉，别让服务挂掉。 */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "recovered from exception", t)
            // 兜底：无论如何不能把屏幕锁住
            try {
                picking = false
                calibrating = false
                canvas?.let { c ->
                    canvasParams?.let { p ->
                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowManager.updateViewLayout(c, p)
                    }
                }
                panel?.renderIdle()
            } catch (e: Throwable) {
                Log.e(TAG, "recovery failed", e)
            }
        }
    }

    private fun fmt(v: Float): String {
        val h = (v * 100f).toInt()
        return "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }

    private fun toast(msg: String) {
        try {
            handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        } catch (t: Throwable) {
            Log.e(TAG, "toast failed", t)
        }
    }
}
