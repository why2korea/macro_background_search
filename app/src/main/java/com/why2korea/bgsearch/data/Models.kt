package com.why2korea.bgsearch.data

/**
 * 탐색 설정값.
 *
 * 이 앱은 URL 을 받지 않는다. 화면에 떠 있는 "다른 앱"을 접근성 서비스로 읽어
 * 1차 문자열을 찾아 클릭하고, 스크롤하며 2차 문자열을 찾는다.
 */
data class SearchConfig(
    /** 먼저 찾아서 클릭할 문자열 (1개) */
    val primaryText: String = "",
    /** 클릭 후 스크롤하며 찾을 문자열 목록 (N개) */
    val secondaryTexts: List<String> = emptyList(),
    /** false = OR(하나라도 발견 시 알림), true = AND(전부 발견해야 알림) */
    val matchAll: Boolean = false,

    /** 스크롤 1스텝 크기 (화면 높이 대비 비율) */
    val scrollRatio: Float = 0.6f,
    /** 스텝 간 대기시간 (ms) */
    val stepDelayMs: Long = 900L,

    /** [시작] 후 버블로 축소되고 탐색이 시작되기까지의 카운트다운 (ms) */
    val startDelayMs: Long = 5_000L,
    /** 버블 탭으로도 시작/정지를 토글할지 여부 */
    val bubbleTapToggles: Boolean = true,

    /** 당겨서 새로고침 후 대기시간 (ms) */
    val refreshWaitMs: Long = 5_000L,

    /** 0 = 무제한 */
    val maxRounds: Int = 0,

    // ---- 알림 채널 on/off ----
    val notifySystem: Boolean = true,
    val notifyVibrate: Boolean = true,
    val notifySound: Boolean = true,
    val notifyBanner: Boolean = true,
    val notifyBubble: Boolean = true,
    val notifyScreenshot: Boolean = true
) {
    fun secondaries(): List<String> = secondaryTexts.filter { it.isNotBlank() }

    fun isRunnable(): Boolean = primaryText.isNotBlank() && secondaries().isNotEmpty()
}

/** 최근 입력 히스토리 1건. */
data class HistoryItem(
    val primaryText: String,
    val secondaryTexts: List<String>
) {
    fun label(): String = primaryText + " > " + secondaryTexts.joinToString(", ")
}
