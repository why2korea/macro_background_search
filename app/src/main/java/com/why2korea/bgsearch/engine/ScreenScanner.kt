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

    /** 1차 문자열을 찾아 클릭 */
    suspend fun clickText(text: String): ClickResult

    /** 한 스텝 아래로 스크롤. 실제로 내려갔으면 true */
    suspend fun scrollDown(ratio: Float): Boolean

    /** 화면을 맨 위로 올린다 */
    suspend fun scrollToTop(maxSteps: Int): Boolean

    /** 당겨서 새로고침 (위에서 아래로 길게 끌어내리는 제스처) */
    suspend fun pullToRefresh(): Boolean

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
