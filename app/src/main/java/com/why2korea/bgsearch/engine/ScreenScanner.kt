package com.why2korea.bgsearch.engine

/** 화면 스캔 1회 결과 요약. 바닥 판정에 쓰인다. */
data class ScreenSnapshotInfo(
    /** 화면에서 읽어낸 텍스트 노드 개수 */
    val nodeCount: Int,
    /** 화면 전체 텍스트를 정규화해 합친 값의 해시. 스크롤 전후 비교용 */
    val contentHash: Int,
    /** 대상 앱 패키지명 (로그용) */
    val packageName: String
)

/** 1차 문자열 클릭 시도 결과. */
data class ClickResult(
    val found: Boolean,
    val clicked: Boolean,
    /** ACTION_CLICK / gesture-tap / none */
    val method: String,
    val snippet: String = "",
    val error: String? = null
)

/**
 * 2차(+3차) 문자열이 같은 줄에서 발견된 결과.
 *
 * "줄" = 조건을 모두 만족하는 **가장 작은 조상 노드**.
 * 3차 문자열이 화면 다른 곳에 있는 것을 같은 줄로 오인하지 않도록
 * 조상의 높이가 화면의 일정 비율 이하일 때만 줄로 인정한다.
 */
data class RowHit(
    /** 이 줄에서 발견된 2차 문자열들 */
    val secondaryMatched: List<String>,
    /** 이 줄에서 발견된 3차 문자열들 (3차 미설정이면 빈 목록) */
    val tertiaryMatched: List<String>,
    /** 줄 전체 텍스트 (로그·알림용, 앞부분만) */
    val rowText: String,
    val clicked: Boolean,
    /** ACTION_CLICK / gesture-tap / skipped / none */
    val clickMethod: String
)

/** 새로고침 시도 결과. */
data class RefreshResult(
    /** 실제로 새로고침이 일어난 것으로 확인됐는지 */
    val ok: Boolean,
    /** refresh-control / pull-to-refresh / none */
    val method: String,
    val detail: String = ""
)

/**
 * "지금 화면에 떠 있는 다른 앱"을 읽고 조작하는 기능의 추상화.
 * 실제 구현은 접근성 서비스(ScanService)가 제공한다.
 */
interface ScreenScanner {

    /** 접근성 서비스가 연결되어 실제로 화면을 읽을 수 있는 상태인지 */
    fun isReady(): Boolean

    /** 현재 화면(대상 앱)의 텍스트를 모두 정규화해 반환 */
    fun readScreenTexts(): List<String>

    /** 현재 화면 요약 (바닥 판정용) */
    fun snapshotInfo(): ScreenSnapshotInfo

    /** 주어진 문자열들이 현재 화면에 있는지 검사해 발견된 것만 반환 */
    fun matchOnScreen(targets: List<String>): List<String>

    /**
     * 1차 문자열을 찾아 클릭한다.
     *
     * 클릭 후 화면이 실제로 바뀌었는지 확인하고, 안 바뀌었으면 다른 방식으로 한 번 더 시도한다.
     * (ACTION_CLICK 이 true 를 돌려주고도 실제로는 아무 일도 일어나지 않는 웹페이지가 있다)
     *
     * @param preferGesture true 면 좌표 탭 제스처를 먼저 시도한다
     */
    suspend fun clickText(text: String, preferGesture: Boolean): ClickResult

    /**
     * 2차 문자열이 있는 "줄"을 찾는다. 3차 문자열이 주어지면 같은 줄에 그것까지 있어야 한다.
     * 찾으면 [doClick] 이 true 일 때 그 줄을 한 번 클릭한다.
     *
     * @param matchAllSecondary true 면 한 줄에 2차 문자열이 전부 있어야 한다
     * @return 조건을 만족하는 줄이 없으면 null
     */
    suspend fun findRowAndClick(
        secondaries: List<String>,
        matchAllSecondary: Boolean,
        tertiaries: List<String>,
        doClick: Boolean
    ): RowHit?

    /** 한 스텝 아래로 스크롤. 실제로 내려갔으면 true */
    suspend fun scrollDown(ratio: Float): Boolean

    /** 화면을 맨 위로 올린다 */
    suspend fun scrollToTop(maxSteps: Int): Boolean

    /**
     * 현재 페이지를 실제로 새로고침한다.
     * 제스처를 보내는 것으로 끝내지 않고, 화면이 실제로 다시 그려졌는지 확인한다.
     * 한 방법이 실패하면 다음 방법으로 넘어간다.
     */
    suspend fun refreshPage(waitMs: Long): RefreshResult

    /** 뒤로가기 */
    suspend fun pressBack(): Boolean

    /** 화면 캡처 저장. 미지원(API 30 미만)이거나 실패하면 false */
    suspend fun takeScreenshot(target: java.io.File): Boolean
}

/**
 * 접근성 서비스 인스턴스 보관소.
 *
 * 접근성 서비스는 시스템이 만들고 파괴하므로 앱이 직접 참조를 들 수 없다.
 * 서비스가 연결될 때 자기 자신을 여기 등록하고, 해제될 때 지운다.
 * 탐색 엔진은 매 단계마다 여기서 꺼내 쓰고, null 이면 "접근성 서비스 꺼짐"으로 처리한다.
 */
object ScannerHolder {

    @Volatile
    var scanner: ScreenScanner? = null
        private set

    fun attach(s: ScreenScanner) {
        scanner = s
    }

    fun detach(s: ScreenScanner) {
        if (scanner === s) scanner = null
    }

    fun isReady(): Boolean = scanner?.isReady() == true
}
