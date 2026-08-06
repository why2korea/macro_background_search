package com.why2korea.bgsearch.util

/**
 * 문자열 매칭 정규화.
 *
 * 화면에서 읽은 텍스트와 사용자가 입력한 문자열을 같은 규칙으로 정규화한 뒤 비교한다.
 *  - 공백류(일반 공백, NBSP, 제로폭, BOM, 탭/개행)를 단일 공백으로 접는다
 *  - 앞뒤 공백 제거
 *  - 소문자화
 */
object TextNorm {

    private val WS = Regex("[\\s\u00a0\u200b\u200c\u200d\ufeff\u3000]+")

    fun of(s: CharSequence?): String {
        if (s.isNullOrEmpty()) return ""
        return WS.replace(s, " ").trim().lowercase()
    }

    /** haystack 안에 needle 이 포함되는지. 둘 다 이미 정규화된 값이어야 한다. */
    fun contains(haystackNorm: String, needleNorm: String): Boolean =
        needleNorm.isNotEmpty() && haystackNorm.contains(needleNorm)
}
