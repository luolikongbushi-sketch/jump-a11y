package cn.imrhj.jumpa11y

/**
 * 自动校准：一次跳跃就能算出这台机器真正的系数。
 *
 * 问题在于，游戏里的距离是按它自己的单位算的，我们能量的只有屏幕像素，两者之间差一个
 * 缩放比例 —— 这个比例跟分辨率有关，所以网上那些按分辨率列的参数表没法直接照抄。
 *
 * 但游戏的物理是固定的：按多久跳多远，这个关系哪台机器都一样，变的只是像素和游戏单位
 * 之间的换算。所以量一次实际跳出来的距离，就能把这个比例反解出来。
 *
 * 推导：拿 S 当起点、E 当想跳到的终点、L 当实际落点
 *
 *     d目标 = |E - S|      我们要求的像素距离
 *     d实际 = |L - S|      实际跳出来的像素距离
 *
 * 时长 = 距离 × 系数，而时长又跟真实跳跃成正比，所以：
 *
 *     d目标 × k    对应"想跳的那一下"
 *     d实际 × k    对应"实际跳的那一下"
 *
 * 正好落到目标上，就是这两下相等的时候：
 *
 *     d实际 × k真 = d目标 × k
 *  => k真 = k × (d目标 / d实际)
 *
 * 所以量一次就够，跟分辨率、跟机器都没关系了。
 */
object Calibrator {

    /** 实际跳出来还不到这么远，比值没意义，不采信。 */
    const val MIN_USABLE_ACTUAL_PX = 60f

    /** 比值离 1 太远基本是点错了位置，不是真要校准。 */
    const val MIN_RATIO = 0.3f
    const val MAX_RATIO = 3.0f

    sealed interface Result {
        data class Success(
            val coefficient: Float,
            val previous: Float,
            val ratio: Float,
            val targetPx: Float,
            val actualPx: Float,
            /** 夹到边界了，值仍然采用，但可以提一句 */
            val wasClamped: Boolean
        ) : Result

        data class Rejected(val reason: Reason, val actualPx: Float, val ratio: Float) : Result

        enum class Reason {
            /** 棋子几乎没动，或者两个点挨太近 */
            JUMP_TOO_SHORT,
            /** 比值离谱，大概是点歪了 */
            RATIO_IMPLAUSIBLE
        }
    }

    fun calibrate(
        start: JumpCalculator.Point?,
        end: JumpCalculator.Point?,
        landed: JumpCalculator.Point?,
        current: Float
    ): Result {
        val dTarget = JumpCalculator.distancePx(start, end)
        val dActual = JumpCalculator.distancePx(start, landed)

        if (dTarget <= 0f || dActual < MIN_USABLE_ACTUAL_PX) {
            return Result.Rejected(Result.Reason.JUMP_TOO_SHORT, dActual, 0f)
        }

        val ratio = dTarget / dActual
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            return Result.Rejected(Result.Reason.RATIO_IMPLAUSIBLE, dActual, ratio)
        }

        val raw = current * ratio
        val clamped = JumpCalculator.sanitizeCoefficient(raw)

        return Result.Success(
            coefficient = clamped,
            previous = current,
            ratio = ratio,
            targetPx = dTarget,
            actualPx = dActual,
            wasClamped = clamped != raw
        )
    }
}
