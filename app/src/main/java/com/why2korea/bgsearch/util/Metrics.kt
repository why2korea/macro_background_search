package com.why2korea.bgsearch.util

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log

private const val TAG = "BgSearchMetrics"

object Metrics {

    /** xdpi/ydpi 가 이 범위를 벗어나면 비정상으로 본다. (일부 기기는 0 이나 터무니없는 값을 보고한다) */
    private const val MIN_DPI = 80f
    private const val MAX_DPI = 1200f

    /** 폴백 크기(dp). 물리 계산이 불가능할 때만 쓴다. */
    private const val FALLBACK_DP = 60f

    /**
     * 물리 1cm 에 해당하는 픽셀 수.
     *
     * dp 하드코딩 대신 DisplayMetrics.xdpi / 2.54 로 계산한다.
     * xdpi 가 비정상이면 ydpi → densityDpi 순으로 대체하고,
     * 그래도 안 되면 60dp 로 폴백한다.
     */
    fun oneCmPx(ctx: Context): Int {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        val candidates = listOf(dm.xdpi, dm.ydpi)
        val sane = candidates.filter { it.isFinite() && it in MIN_DPI..MAX_DPI }
        val dpi = when {
            sane.size == 2 -> (sane[0] + sane[1]) / 2f
            sane.size == 1 -> sane[0]
            dm.densityDpi.toFloat() in MIN_DPI..MAX_DPI -> dm.densityDpi.toFloat()
            else -> {
                Log.w(TAG, "abnormal dpi (x=${dm.xdpi}, y=${dm.ydpi}, d=${dm.densityDpi}) - fallback")
                return dp(ctx, FALLBACK_DP)
            }
        }
        val px = (dpi / 2.54f).toInt()
        // 계산 결과 자체가 터무니없으면 역시 폴백한다.
        return if (px in 24..400) px else dp(ctx, FALLBACK_DP)
    }

    fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun screenWidthPx(ctx: Context): Int = ctx.resources.displayMetrics.widthPixels

    fun screenHeightPx(ctx: Context): Int = ctx.resources.displayMetrics.heightPixels
}
