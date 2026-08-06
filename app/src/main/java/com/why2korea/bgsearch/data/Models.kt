package com.why2korea.bgsearch.data

/**
 * 탐색 설정값 전체.
 *
 * 참고 프로젝트(macro_search_manpodae)의 WatchSettings 를 확장한 것으로
 *  - secondaryTexts : 2차 문자열을 여러 개 보관 (참고 프로젝트는 1개 고정이었다)
 *  - matchAll       : OR(기본) / AND 매칭 토글
 *  - notify*        : 알림 채널별 on/off
 * 가 새로 추가되었다.
 */
data class SearchConfig(
    val url: String = "",
    /** 먼저 찾아서 클릭할 문자열 (1개) */
    val primaryText: String = "",
    /** 스크롤하며 찾을 문자열 목록 (N개) */
    val secondaryTexts: List<String> = emptyList(),
    /** false = OR(하나라도 발견 시 알림), true = AND(전부 발견해야 알림) */
    val matchAll: Boolean = false,

    /** 스크롤 1스텝 크기 (뷰포트 높이 대비 비율) */
    val scrollRatio: Float = 0.8f,
    /** 스텝 간 대기시간 (ms) */
    val stepDelayMs: Long = 800L,
    /** 새로고침 후 대기시간 (ms) */
    val refreshDelayMs: Long = 5_000L,
    /** 0 = 무제한 */
    val maxRounds: Int = 0,

    // ---- 알림 채널 on/off ----
    val notifySystem: Boolean = true,
    val notifyVibrate: Boolean = true,
    val notifySound: Boolean = true,
    val notifyBanner: Boolean = true,
    val notifyBubble: Boolean = true,
    val notifyScreenshot: Boolean = true,

    /**
     * 축소(버블) 상태에서 WebView 윈도우 크기를 그대로 유지할지 여부.
     *
     * true(기본)  : 윈도우 크기를 화면 전체로 유지한 채 alpha 만 거의 0 으로 낮춘다.
     *               → 뷰포트가 바뀌지 않아 스크롤 위치·리플로우가 깨지지 않는다.
     * false       : 버블과 같은 크기(약 1cm)로 줄여 버블 바로 뒤에 배치한다.
     *               → 화면 점유는 최소지만 페이지가 좁은 뷰포트로 리플로우된다.
     * 어느 쪽이든 크기를 0 으로 만들지는 않는다.
     */
    val keepFullSizeWhenCollapsed: Boolean = true
) {
    fun normalizedUrl(): String {
        val t = url.trim()
        if (t.isBlank()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    fun isRunnable(): Boolean =
        normalizedUrl().isNotBlank() &&
            primaryText.isNotBlank() &&
            secondaryTexts.any { it.isNotBlank() }
}

/** 최근 입력 히스토리 1건. */
data class HistoryItem(
    val url: String,
    val primaryText: String,
    val secondaryTexts: List<String>
) {
    fun label(): String {
        val host = try {
            url.substringAfter("//").substringBefore("/").ifBlank { url }
        } catch (e: Throwable) {
            url
        }
        return host + " | " + primaryText + " > " + secondaryTexts.joinToString(", ")
    }
}
