package cn.imrhj.jumpa11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 手势注入。整个项目只有这一个地方会往系统里发手势。
 *
 * 两个容易踩的点：
 *
 * 1. 路径只 moveTo，不 lineTo。跳一跳要的是"按住"而不是"滑动"，加了 lineTo
 *    就变成真的滑动了，游戏不认。
 *
 * 2. startTime 给了个 80ms 而不是 0。因为注入之前要把浮层藏起来，给系统一点时间，
 *    否则这一下可能按在还没消失的浮层上而不是游戏上。
 */
class JumpExecutor(private val service: AccessibilityService) {

    interface Callback {
        fun onFinished(completed: Boolean)
    }

    /**
     * 在 (x, y) 按住 durationMs。
     *
     * @return false 表示系统没接受（服务没连上、时长越界、坐标不在屏内）。
     *         调用方要把这个情况告诉用户，不能默默失败。
     */
    fun press(x: Float, y: Float, durationMs: Long, callback: Callback? = null): Boolean {
        val max = GestureDescription.getMaxGestureDuration()
        val safeDuration = durationMs.coerceIn(1L, max)

        val path = Path().apply {
            moveTo(x, y)
            // 故意不加 lineTo
        }

        val stroke = GestureDescription.StrokeDescription(path, START_DELAY_MS, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val cb = if (callback == null) null else object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = callback.onFinished(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = callback.onFinished(false)
        }

        return try {
            service.dispatchGesture(gesture, cb, null)
        } catch (t: Throwable) {
            // 这里抛异常会把服务带下去，包一层
            callback?.onFinished(false)
            false
        }
    }

    companion object {
        /** 注入前先等一会儿，让浮层收起来。 */
        const val START_DELAY_MS = 80L
    }
}
