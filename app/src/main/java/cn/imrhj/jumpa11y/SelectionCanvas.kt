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
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏选点层。
 *
 * ## 为什么点要往上抬
 *
 * 手指按在目标上，正好把目标挡住了，看不见点在哪。所以确认点画在手指**上方** [PICK_OFFSET_DP]：
 *
 *     手指位置           <- 淡白圈，标出你手指在哪（好看出偏移了多少）
 *          |
 *          |  抬高这么多
 *          v
 *     确认点             <- 亮十字，这个才是真正记录的坐标
 *
 * 用的时候手指往目标**下方**一点按，让十字落在目标上就行。
 * 抬多少改 [PICK_OFFSET_DP] 这一个常量，改成 0 就是点在手指正下方。
 *
 * 按住的时候点跟着手指走，可以拖着对准了再松手。
 *
 * ## 坐标系
 *
 * 这里有两套坐标，弄混了画出来的点就会偏：
 *
 *  - `MotionEvent.rawX/rawY` 是**屏幕坐标**，注入手势要的就是这个，所以存的是它
 *  - `onDraw` 用的是**视图坐标**，所以另外存了一份专门用来画
 *
 * 两边用的是同一个抬高量，所以你看到十字落在哪，按下去就是哪。
 */
@SuppressLint("ViewConstructor")
class SelectionCanvas(context: Context) : FrameLayout(context) {

    companion object {
        /**
         * 确认点相对手指往上抬多少（dp）。0 就是贴着手指。
         */
        const val PICK_OFFSET_DP = 58f
    }

    /** 点有变化时通知面板刷新。 */
    var onPointsChanged: (() -> Unit)? = null

    // ---- 记录的点，屏幕坐标（注入用这个）----
    var startPoint: JumpCalculator.Point? = null
        private set
    var endPoint: JumpCalculator.Point? = null
        private set
    var landingPoint: JumpCalculator.Point? = null
        private set

    // ---- 同样的点，视图坐标（只用来画）----
    private var startLocal: JumpCalculator.Point? = null
    private var endLocal: JumpCalculator.Point? = null
    private var landingLocal: JumpCalculator.Point? = null

    /** 手指当前在哪（视图坐标），按下期间才有值。 */
    private var fingerLocal: JumpCalculator.Point? = null

    /** 抬高之后那个点（视图坐标），也就是松手会被记录的位置。 */
    private var dragLocal: JumpCalculator.Point? = null

    /** 校准流程里，下一次记录的是落点。 */
    var awaitingLanding: Boolean = false

    /** 下一次记录的是起点（true）还是终点（false）。 */
    val expectsStart: Boolean get() = startPoint == null || endPoint != null

    private val density = resources.displayMetrics.density
    private val offsetPx = PICK_OFFSET_DP * density

    // ---------------------------------------------------------------- 画笔

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EE7F5")
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
    }
    private val paintStart = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EE7F5"); style = Paint.Style.FILL
    }
    private val paintEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5A97F"); style = Paint.Style.FILL
    }
    private val paintLanding = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7BE0A8"); style = Paint.Style.FILL
    }
    private val paintHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    /** 拖动时那个亮十字 */
    private val paintDrag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    /** 手指实际位置的淡圈 */
    private val paintFinger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    /** 连接手指和确认点的虚线，让抬高的距离看得见 */
    private val paintLeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FFFFFF")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f * density, 0f, 1f * density, Color.BLACK)
    }
    private val paintMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3")
        textSize = 14f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f * density, 0f, 1f * density, Color.BLACK)
    }

    init {
        setWillNotDraw(false)
        // 压得很淡的一层，能看出浮层在，又不影响看游戏
        setBackgroundColor(Color.parseColor("#12000000"))
        isClickable = true
    }

    // ---------------------------------------------------------------- 状态变更

    fun clearAll() {
        startPoint = null; startLocal = null
        endPoint = null; endLocal = null
        landingPoint = null; landingLocal = null
        fingerLocal = null; dragLocal = null
        awaitingLanding = false
        invalidate()
        onPointsChanged?.invoke()
    }

    /**
     * 切换到"下一次记录的是落点"。
     *
     * 特意不触发 [onPointsChanged]：服务是紧接着就把它设成可触摸的，
     * 在这里回调会把面板状态弹回去。
     */
    fun prepareForLanding() {
        awaitingLanding = true
        landingPoint = null
        landingLocal = null
        fingerLocal = null
        dragLocal = null
        invalidate()
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateDrag(event)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fingerLocal != null) updateDrag(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                updateDrag(event)
                commit(
                    local = dragLocal,
                    // 屏幕坐标同样减去抬高量，保证"看到的"和"按下去的"是同一个点
                    screen = JumpCalculator.Point(event.rawX, event.rawY - offsetPx)
                )
                fingerLocal = null
                dragLocal = null
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                onPointsChanged?.invoke()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                fingerLocal = null; dragLocal = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 重新算手指圈和抬高后的十字。 */
    private fun updateDrag(event: MotionEvent) {
        val finger = JumpCalculator.Point(event.x, event.y)
        fingerLocal = finger
        // 夹一下，免得抬高之后跑出屏幕
        dragLocal = JumpCalculator.Point(
            finger.x,
            max(0f, min(height.toFloat(), finger.y - offsetPx))
        )
        invalidate()
    }

    private fun commit(local: JumpCalculator.Point?, screen: JumpCalculator.Point) {
        if (local == null) return
        if (awaitingLanding) {
            landingPoint = screen; landingLocal = local
            awaitingLanding = false
            return
        }
        if (startPoint == null || endPoint != null) {
            // 开一对新的
            startPoint = screen; startLocal = local
            endPoint = null; endLocal = null
            landingPoint = null; landingLocal = null
        } else {
            endPoint = screen; endLocal = local
        }
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = startLocal
        val e = endLocal
        val l = landingLocal

        // 这次的跳跃（起点 -> 终点）
        if (s != null && e != null) {
            canvas.drawPath(Path().apply { moveTo(s.x, s.y); lineTo(e.x, e.y) }, paintLine)
            canvas.drawText(
                "${JumpCalculator.distancePx(startPoint, endPoint).toInt()} px",
                (s.x + e.x) / 2f, (s.y + e.y) / 2f - 14f * density, paintMid
            )
        }

        // 上次实际跳的（只有校准时才有）
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

        // 拖动中的预览，画在最后保证在最上层
        val f = fingerLocal
        val d = dragLocal
        if (f != null && d != null) {
            canvas.drawCircle(f.x, f.y, 20f * density, paintFinger)
            canvas.drawLine(f.x, f.y, d.x, d.y, paintLeader)

            val r = 13f * density
            canvas.drawCircle(d.x, d.y, r, paintDrag)
            canvas.drawCircle(d.x, d.y, 3f * density, paintDrag)
            canvas.drawLine(d.x - r * 1.9f, d.y, d.x + r * 1.9f, d.y, paintDrag)
            canvas.drawLine(d.x, d.y - r * 1.9f, d.x, d.y + r * 1.9f, paintDrag)
        }
    }

    private fun drawDot(canvas: Canvas, p: JumpCalculator.Point, fill: Paint, label: String) {
        canvas.drawCircle(p.x, p.y, 8f * density, fill)
        canvas.drawCircle(p.x, p.y, 18f * density, paintHalo)
        canvas.drawText(label, p.x + 24f * density, p.y + 5f * density, paintLabel)
    }
}
