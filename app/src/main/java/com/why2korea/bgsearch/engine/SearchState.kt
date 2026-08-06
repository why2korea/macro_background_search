package com.why2korea.bgsearch.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 루프의 현재 단계. */
enum class Phase {
    IDLE,
    LOADING,
    FIND_PRIMARY,
    AFTER_CLICK,
    SCAN_SECONDARY,
    WAIT_REFRESH,
    PAUSED_FOUND,
    ERROR
}

/** 오버레이 표시 상태. */
enum class OverlayMode { HIDDEN, EXPANDED, COLLAPSED }

data class SearchSnapshot(
    val serviceAlive: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val overlayMode: OverlayMode = OverlayMode.HIDDEN,
    val round: Int = 0,
    val step: Int = 0,
    val scrollInfo: String = "-",
    val status: String = "대기 중",
    val currentUrl: String = "",
    /** 이번에 발견된 2차 문자열 목록 (일시정지 중일 때만 채워짐) */
    val foundTexts: List<String> = emptyList(),
    val foundTimeText: String = "",
    val foundShotPath: String? = null,
    /** 누적 발견 횟수 (버블 뱃지에 표시) */
    val foundCount: Int = 0,
    val elapsedText: String = "00:00:00",
    val logs: List<String> = emptyList()
) {
    val paused: Boolean get() = phase == Phase.PAUSED_FOUND
}

/**
 * 서비스(단일 소유자) → UI(Activity / 오버레이) 로 흐르는 단방향 상태 통로.
 *
 * Activity 와 오버레이가 같은 프로세스에 있으므로 바인딩 없이 이 싱글턴만으로 충분하다.
 * 반대 방향(명령)은 OverlayService 의 static 헬퍼(Intent) 또는 서비스 인스턴스 직접 호출로 처리한다.
 */
object SearchBus {

    private const val LOG_MAX = 200

    private val _snapshot = MutableStateFlow(SearchSnapshot())
    val snapshot: StateFlow<SearchSnapshot> = _snapshot.asStateFlow()

    fun update(block: (SearchSnapshot) -> SearchSnapshot) = _snapshot.update(block)

    fun log(line: String) = _snapshot.update {
        it.copy(logs = (it.logs + line).takeLast(LOG_MAX))
    }

    fun clearLogs() = _snapshot.update { it.copy(logs = emptyList()) }

    fun reset() {
        _snapshot.value = SearchSnapshot()
    }
}
