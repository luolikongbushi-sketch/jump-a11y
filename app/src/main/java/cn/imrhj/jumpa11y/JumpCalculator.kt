package cn.imrhj.jumpa11y

import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * 换算部分，不依赖任何 Android 类，方便单独测。
 *
 * 跳一跳里跳多远只跟按住多久有关，方向无所谓（按在一个点上按住就行），所以：
 *
 *     距离 = 两点间的像素距离
 *     时长 = 距离 × 系数
 *
 * 系数就是"每像素按多少毫秒"，不同机器不一样，见下面默认值的说明。
 */
object JumpCalculator {

    /**
     * 默认系数。参考了网上那个很有名的 Python 版辅助（wangshub/wechat_jump_game），
     * 它在 1920x1080 上实测是 1.392。起作用的是屏幕宽度，所以 1080 宽的机器都能用这个值起步。
     *
     * 游戏画面是按视口宽度缩放的，屏幕宽度不一样这个值就得变，所以做成可调 + 存本地。
     */
    const val DEFAULT_COEFFICIENT = 1.392f

    /** 两点离这么近基本是点歪了，不算数。 */
    const val MIN_DISTANCE_PX = 20f

    const val MIN_COEFFICIENT = 0.2f
    const val MAX_COEFFICIENT = 5.0f

    fun distancePx(start: Point?, end: Point?): Float {
        if (start == null || end == null) return 0f
        return hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
    }

    fun isUsable(start: Point?, end: Point?): Boolean =
        distancePx(start, end) >= MIN_DISTANCE_PX

    /**
     * @param maxDurationMs 系统上限，超了整条手势会被丢掉，所以必须夹一下
     */
    fun durationMs(distancePx: Float, coefficient: Float, maxDurationMs: Long): Long {
        if (distancePx <= 0f) return 0L
        val raw = (distancePx * coefficient).roundToLong()
        return raw.coerceIn(1L, maxDurationMs)
    }

    fun sanitizeCoefficient(value: Float): Float =
        value.coerceIn(MIN_COEFFICIENT, MAX_COEFFICIENT)

    /** 屏幕坐标上的一个点。 */
    data class Point(val x: Float, val y: Float)
}
