package com.why2korea.bgsearch.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 축소 상태의 원형 플로팅 버블.
 *
 * 지름은 dp 하드코딩이 아니라 Metrics.oneCmPx() 로 계산된 "물리 1cm" 값을 받는다.
 * 상태에 따라 색이 바뀌고, 발견 횟수가 있으면 우측 상단에 뱃지를 그린다.
 */
class BubbleView(
    ctx: Context,
    private val sizePx: Int
) : View(ctx) {

    companion object {
        val COLOR_IDLE = Color.parseColor("#546E7A")   // 회청색 - 대기
        val COLOR_RUNNING = Color.parseColor("#2E7D32") // 초록 - 탐색 중
        val COLOR_PAUSED = Color.parseColor("#EF6C00")  // 주황 - 일시정지
        val COLOR_FOUND = Color.parseColor("#D32F2F")   // 빨강 - 발견
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEB3B")
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var bubbleColor = COLOR_IDLE
    private var badgeCount = 0
    private var label = "S"

    init {
        ring.strokeWidth = sizePx * 0.06f
        badgeText.textSize = sizePx * 0.30f
        glyph.textSize = sizePx * 0.42f
        // 버블은 아주 작으므로 그림자를 소프트웨어 레이어로 그린다.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        fill.setShadowLayer(sizePx * 0.10f, 0f, sizePx * 0.04f, Color.argb(140, 0, 0, 0))
    }

    fun setBubbleColor(c: Int) {
        if (bubbleColor == c) return
        bubbleColor = c
        invalidate()
    }

    fun setBadge(count: Int) {
        if (badgeCount == count) return
        badgeCount = count
        invalidate()
    }

    fun setLabel(text: String) {
        if (label == text) return
        label = text
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 그림자와 뱃지가 잘리지 않도록 약간의 여백을 둔다.
        val pad = (sizePx * 0.22f).toInt()
        setMeasuredDimension(sizePx + pad * 2, sizePx + pad * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = sizePx * 0.22f
        val cx = pad + sizePx / 2f
        val cy = pad + sizePx / 2f
        val r = sizePx / 2f

        fill.color = bubbleColor
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)

        val fm = glyph.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, glyph)

        if (badgeCount > 0) {
            val br = sizePx * 0.26f
            val bx = cx + r * 0.72f
            val by = cy - r * 0.72f
            canvas.drawCircle(bx, by, br, badgeFill)
            val t = if (badgeCount > 99) "99+" else badgeCount.toString()
            val bfm = badgeText.fontMetrics
            canvas.drawText(t, bx, by - (bfm.ascent + bfm.descent) / 2f, badgeText)
        }
    }
}
