package cn.imrhj.jumpa11y

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏选点层。
 *
 * ## 选点分两步：先粗选，再微调
 *
 * 粗选就是直接点一下，点落在哪就是哪，不做任何偏移。但手指比目标大，点完往往差几个像素，
 * 所以不马上进下一步，而是留一个微调阶段：
 *
 *    - 手指可以按在屏幕**任意位置**滑动，点跟着做**相对移动**（1:1）。
 *      往上滑点上移，往下滑点下移，斜着滑就斜着动。
 *    - 松手不会提交，可以反复滑，滑到对准为止。
 *    - 点【确定】才真正锁定这个点。
 *
 * 之所以用相对移动而不是让手指直接拖那个点：手指按在目标上会把它挡住，看不见就没法对。
 * 相对移动的话手指在哪儿都行，点始终看得见。
 *
 * ## 坐标系
 *
 * 有两套，弄混了画出来的点就会偏：
 *
 *  - `MotionEvent.rawX/rawY` 是**屏幕坐标**，注入手势要的就是这个，所以存的是它
 *  - `onDraw` 用的是**视图坐标**，另外存一份专门用来画
 *
 * 两套之间只差一个固定的窗口偏移，所以滑动的位移量对两边是一样的，直接加就行。
 */
@SuppressLint("ViewConstructor")
class SelectionCanvas(context: Context) : FrameLayout(context) {

    /** 点变化时通知面板刷新按钮状态。 */
    var onPointsChanged: (() -> Unit)? = null

    // ---- 已确定的点，屏幕坐标（注入用这个）----
    var startPoint: JumpCalculator.Point? = null
        private set
    var endPoint: JumpCalculator.Point? = null
        private set
    var landingPoint: JumpCalculator.Point? = null
        private set

    // ---- 已确定的点，视图坐标（画图用）----
    private var startLocal: JumpCalculator.Point? = null
    private var endLocal: JumpCalculator.Point? = null
    private var landingLocal: JumpCalculator.Point? = null

    /** 正在微调、还没确定的那个点（屏幕坐标）。 */
    var stagedScreen: JumpCalculator.Point? = null
        private set

    private var stagedLocal: JumpCalculator.Point? = null

    /** 微调时的基准：按下那一刻的手指位置和点位，用位移差来移动。 */
    private var dragBaseFinger: JumpCalculator.Point? = null
    private var dragBaseLocal: JumpCalculator.Point? = null
    private var dragBaseScreen: JumpCalculator.Point? = null

    /** 手指当前在哪，用来画那条提示线。 */
    private var fingerLocal: JumpCalculator.Point? = null

    /** 校准流程里，下一次确定的是落点。 */
    var awaitingLanding: Boolean = false

    /** 现在轮到哪个点。 */
    enum class Stage { START, END, LANDING }
    val stage: Stage
        get() = when {
            awaitingLanding -> Stage.LANDING
            startPoint == null -> Stage.START
            else -> Stage.END
        }

    /** 有没确定的点，面板据此决定【确定】能不能点。 */
    val hasStaged: Boolean get() = stagedScreen != null

    private val density = resources.displayMetrics.density

    private fun col(id: Int) = ContextCompat.getColor(context, id)

    // ---------------------------------------------------------------- 画笔

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.marker_start)
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
    }
    private val paintStart = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.marker_start); style = Paint.Style.FILL
    }
    private val paintEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.marker_end); style = Paint.Style.FILL
    }
    private val paintLanding = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.marker_landing); style = Paint.Style.FILL
    }
    private val paintHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.overlay_halo)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    /** 正在微调的那个点：画成十字，明确表示"还没确定，可以动" */
    private val paintStaged = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.marker_start)
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintFinger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.overlay_halo)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    private val paintLeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.overlay_halo)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 7f * density), 0f)
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.overlay_label)
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f * density, 0f, 1f * density, col(R.color.overlay_label_shadow))
    }
    private val paintMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.overlay_label)
        textSize = 14f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f * density, 0f, 1f * density, col(R.color.overlay_label_shadow))
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(col(R.color.overlay_scrim))
        isClickable = true
    }

    // ---------------------------------------------------------------- 状态

    fun clearAll() {
        startPoint = null; startLocal = null
        endPoint = null; endLocal = null
        landingPoint = null; landingLocal = null
        stagedScreen = null; stagedLocal = null
        dragBaseFinger = null; dragBaseLocal = null; dragBaseScreen = null
        fingerLocal = null
        awaitingLanding = false
        invalidate()
        onPointsChanged?.invoke()
    }

    /** 丢弃当前正在微调的点，留在同一阶段重新粗选。 */
    fun discardStaged() {
        stagedScreen = null; stagedLocal = null
        dragBaseFinger = null; dragBaseLocal = null; dragBaseScreen = null
        fingerLocal = null
        invalidate()
        onPointsChanged?.invoke()
    }

    /**
     * 把正在微调的点确定下来，返回它（屏幕坐标）。
     * 落点只在校准流程里用到。
     */
    fun confirmStaged(): JumpCalculator.Point? {
        val screen = stagedScreen ?: return null
        val local = stagedLocal
        if (awaitingLanding) {
            landingPoint = screen; landingLocal = local
            awaitingLanding = false
        } else if (startPoint == null) {
            startPoint = screen; startLocal = local
        } else if (endPoint == null) {
            endPoint = screen; endLocal = local
        } else {
            // 两个点都定了，重新从起点来一轮
            startPoint = screen; startLocal = local
            endPoint = null; endLocal = null
        }
        stagedScreen = null; stagedLocal = null
        dragBaseFinger = null; dragBaseLocal = null; dragBaseScreen = null
        fingerLocal = null
        invalidate()
        onPointsChanged?.invoke()
        return screen
    }

    /** 切换成"下一次确定的是落点"，校准流程用。 */
    fun prepareForLanding() {
        awaitingLanding = true
        landingPoint = null; landingLocal = null
        stagedScreen = null; stagedLocal = null
        dragBaseFinger = null; dragBaseLocal = null; dragBaseScreen = null
        fingerLocal = null
        invalidate()
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val f = JumpCalculator.Point(event.x, event.y)
                fingerLocal = f
                if (stagedScreen == null) {
                    // 粗选：点就在手指这里，不做偏移
                    stagedLocal = JumpCalculator.Point(
                        clampX(event.x), clampY(event.y)
                    )
                    stagedScreen = JumpCalculator.Point(clampX(event.rawX), clampY(event.rawY))
                }
                // 记下基准，之后按位移差移动
                dragBaseFinger = f
                dragBaseLocal = stagedLocal
                dragBaseScreen = stagedScreen
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                onPointsChanged?.invoke()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val base = dragBaseFinger
                val baseLocal = dragBaseLocal
                val baseScreen = dragBaseScreen
                if (base != null && baseLocal != null && baseScreen != null) {
                    val dx = event.x - base.x
                    val dy = event.y - base.y
                    fingerLocal = JumpCalculator.Point(event.x, event.y)
                    stagedLocal = JumpCalculator.Point(
                        clampX(baseLocal.x + dx),
                        clampY(baseLocal.y + dy)
                    )
                    stagedScreen = JumpCalculator.Point(
                        clampX(baseScreen.x + dx),
                        clampY(baseScreen.y + dy)
                    )
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 松手只是结束这一次微调，不提交。点保留在屏幕上，可以再按再调。
                fingerLocal = null
                dragBaseFinger = null
                dragBaseLocal = null
                dragBaseScreen = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampX(v: Float) = max(0f, min(width.toFloat(), v))
    private fun clampY(v: Float) = max(0f, min(height.toFloat(), v))

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = startLocal
        val e = endLocal
        val l = landingLocal

        // 起点 -> 终点
        if (s != null && e != null) {
            canvas.drawPath(Path().apply { moveTo(s.x, s.y); lineTo(e.x, e.y) }, paintLine)
            canvas.drawText(
                "${JumpCalculator.distancePx(startPoint, endPoint).toInt()} px",
                (s.x + e.x) / 2f, (s.y + e.y) / 2f - 14f * density, paintMid
            )
        }

        // 起点 -> 实际落点（只有校准时才有）
        if (s != null && l != null) {
            canvas.drawPath(Path().apply { moveTo(s.x, s.y); lineTo(l.x, l.y) }, paintLine)
            canvas.drawText(
                "${JumpCalculator.distancePx(startPoint, landingPoint).toInt()} px",
                (s.x + l.x) / 2f, (s.y + l.y) / 2f + 24f * density, paintMid
            )
        }

        s?.let { drawDot(canvas, it, paintStart, context.getString(R.string.ov_label_start)) }
        e?.let { drawDot(canvas, it, paintEnd, context.getString(R.string.ov_label_end)) }
        l?.let { drawDot(canvas, it, paintLanding, context.getString(R.string.ov_label_landing)) }

        // 正在微调的点
        val st = stagedLocal
        if (st != null) {
            val color = when (stage) {
                Stage.START -> col(R.color.marker_start)
                Stage.END -> col(R.color.marker_end)
                Stage.LANDING -> col(R.color.marker_landing)
            }
            paintStaged.color = color

            // 画一条从手指到点的虚线，让"相对移动"这件事看得懂
            val f = fingerLocal
            if (f != null) {
                canvas.drawCircle(f.x, f.y, 18f * density, paintFinger)
                canvas.drawLine(f.x, f.y, st.x, st.y, paintLeader)
            }

            val r = 14f * density
            canvas.drawCircle(st.x, st.y, r, paintStaged)
            canvas.drawCircle(st.x, st.y, 3f * density, paintStaged)
            canvas.drawLine(st.x - r * 1.9f, st.y, st.x + r * 1.9f, st.y, paintStaged)
            canvas.drawLine(st.x, st.y - r * 1.9f, st.x, st.y + r * 1.9f, paintStaged)

            val label = when (stage) {
                Stage.START -> context.getString(R.string.ov_label_start)
                Stage.END -> context.getString(R.string.ov_label_end)
                Stage.LANDING -> context.getString(R.string.ov_label_landing)
            }
            canvas.drawText(
                "$label (${context.getString(R.string.ov_adjusting)})",
                st.x + 26f * density, st.y - 18f * density, paintLabel
            )
        }
    }

    private fun drawDot(canvas: Canvas, p: JumpCalculator.Point, fill: Paint, label: String) {
        canvas.drawCircle(p.x, p.y, 8f * density, fill)
        canvas.drawCircle(p.x, p.y, 18f * density, paintHalo)
        canvas.drawText(label, p.x + 24f * density, p.y + 5f * density, paintLabel)
    }
}
