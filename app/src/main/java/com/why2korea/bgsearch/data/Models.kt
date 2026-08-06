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
    /** 2차 문자열 매칭. false = OR(같은 줄에 하나라도), true = AND(같은 줄에 전부) */
    val matchAll: Boolean = false,

    /**
     * 3차 문자열 목록 (선택).
     * 비어 있지 않으면 **2차 문자열이 발견된 그 줄 안에** 이 중 하나가 더 있어야 발견으로 친다.
     * 예) 2차 "09일" + 3차 "예약가능" → "09일" 이 있는 줄에 "예약가능" 도 있어야 알림
     */
    val tertiaryTexts: List<String> = emptyList(),

    /** 발견한 줄을 한 번 클릭한 뒤에 알림 처리할지 (기본 켬) */
    val clickFoundRow: Boolean = true,

    /** 새로고침이 실패했을 때 뒤로가기로 이전 화면에 복귀할지 (기본 켬) */
    val backOnRefreshFail: Boolean = true,

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

    fun tertiaries(): List<String> = tertiaryTexts.filter { it.isNotBlank() }

    fun isRunnable(): Boolean = primaryText.isNotBlank() && secondaries().isNotEmpty()
}

/** 최근 입력 히스토리 1건. */
data class HistoryItem(
    val primaryText: String,
    val secondaryTexts: List<String>,
    val tertiaryTexts: List<String> = emptyList()
) {
    fun label(): String {
        val base = primaryText + " > " + secondaryTexts.joinToString(", ")
        return if (tertiaryTexts.isEmpty()) base
        else base + " + " + tertiaryTexts.joinToString(", ")
    }
}
