package com.why2korea.bgsearch.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 루프의 현재 단계. */
enum class Phase {
    IDLE,
    /** [시작] 후 대상 앱으로 이동할 시간을 주는 카운트다운 */
    COUNTDOWN,
    FIND_PRIMARY,
    AFTER_CLICK,
    SCAN_SECONDARY,
    REFRESHING,
    PAUSED_FOUND,
    NO_SERVICE,
    ERROR
}

/** 오버레이 표시 상태. */
enum class OverlayMode { HIDDEN, EXPANDED, COLLAPSED }

data class SearchSnapshot(
    val serviceAlive: Boolean = false,
    val scannerReady: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val overlayMode: OverlayMode = OverlayMode.HIDDEN,
    val round: Int = 0,
    val step: Int = 0,
    /** 카운트다운 남은 초 */
    val countdown: Int = 0,
    /** 현재 스캔 중인 대상 앱 패키지 */
    val targetPackage: String = "",
    val status: String = "대기 중",
    val foundTexts: List<String> = emptyList(),
    val foundTimeText: String = "",
    val foundShotPath: String? = null,
    val foundCount: Int = 0,
    val elapsedText: String = "00:00:00",
    val logs: List<String> = emptyList()
) {
    val paused: Boolean get() = phase == Phase.PAUSED_FOUND
}

/**
 * 엔진(단일 소유자) → UI(Activity / 오버레이) 로 흐르는 단방향 상태 통로.
 * 같은 프로세스이므로 바인딩 없이 이 싱글턴만으로 충분하다.
 */
object SearchBus {

    private const val LOG_MAX = 200

    private val _snapshot = MutableStateFlow(SearchSnapshot())
    val snapshot: StateFlow<SearchSnapshot> = _snapshot.asStateFlow()

    fun update(block: (SearchSnapshot) -> SearchSnapshot) = _snapshot.update(block)

    fun log(line: String) = _snapshot.update {
        it.copy(logs = (it.logs + line).takeLast(LOG_MAX))
    }

    fun reset() {
        _snapshot.value = SearchSnapshot()
    }
}
