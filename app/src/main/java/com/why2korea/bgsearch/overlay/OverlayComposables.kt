package com.why2korea.bgsearch.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.why2korea.bgsearch.engine.Phase
import com.why2korea.bgsearch.engine.SearchBus

/** 오버레이 UI 가 서비스에 보내는 명령. */
interface OverlayActions {
    /** 패널의 [시작] — 버블로 접고 카운트다운 후 시작 */
    fun onStartWithCountdown()

    /** 버블 탭 — 즉시 시작/정지 토글 */
    fun onToggleFromBubble()

    fun onStop()
    fun onCollapse()
    fun onExpand()
    fun onOpenSettings()
    fun onExit()
    fun onResumeSearch()
    fun onDismissBanner()
}

private val OverlayColors = darkColorScheme()

@Composable
private fun OverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OverlayColors, content = content)
}

/**
 * 확장 상태 패널.
 * WebView 가 없어졌으므로 상태 + 로그 + 버튼만 있는 컴팩트한 하단 패널이다.
 */
@Composable
fun ControlPanel(actions: OverlayActions) {
    val s by SearchBus.snapshot.collectAsState()
    OverlayTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF2101418))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                "백그라운드 문자열 탐색",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                headline(s.round, s.step, s.elapsedText, s.foundCount),
                color = Color(0xFF90A4AE),
                fontSize = 11.sp
            )
            Text(
                s.status,
                color = if (s.phase == Phase.NO_SERVICE) Color(0xFFEF9A9A) else Color(0xFFB0BEC5),
                fontSize = 11.sp,
                maxLines = 2
            )
            if (s.targetPackage.isNotBlank()) {
                Text("대상: ${s.targetPackage}", color = Color(0xFF607D8B), fontSize = 10.sp)
            }

            if (s.logs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(96.dp)) {
                    items(s.logs.asReversed()) { line ->
                        Text(
                            line,
                            color = Color(0xFF78909C),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (s.running) {
                    BarButton("정지", Color(0xFFC62828), Modifier.weight(1f)) { actions.onStop() }
                } else {
                    BarButton("시작", Color(0xFF2E7D32), Modifier.weight(1f)) {
                        actions.onStartWithCountdown()
                    }
                }
                if (s.phase == Phase.PAUSED_FOUND) {
                    BarButton("계속", Color(0xFF1565C0), Modifier.weight(1f)) { actions.onResumeSearch() }
                }
                BarButton("축소", Color(0xFF37474F), Modifier.weight(1f)) { actions.onCollapse() }
                BarButton("설정", Color(0xFF37474F), Modifier.weight(1f)) { actions.onOpenSettings() }
                BarButton("종료", Color(0xFF424242), Modifier.weight(1f)) { actions.onExit() }
            }
        }
    }
}

private fun headline(round: Int, step: Int, elapsed: String, found: Int): String =
    "R$round · step $step · $elapsed · 발견 $found"

@Composable
private fun BarButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** 발견 시 화면 상단에 뜨는 오버레이 배너. */
@Composable
fun FoundBanner(actions: OverlayActions) {
    val s by SearchBus.snapshot.collectAsState()
    OverlayTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF2D32F2F))
                .padding(10.dp)
        ) {
            Text("문자열 발견!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                s.foundTexts.joinToString(", ").ifBlank { "-" },
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 3
            )
            Text(s.foundTimeText, color = Color(0xFFFFCDD2), fontSize = 11.sp)
            if (s.foundShotPath != null) {
                Text("스크린샷 저장됨", color = Color(0xFFFFCDD2), fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BarButton("계속", Color(0xFF1B5E20), Modifier.weight(1f)) { actions.onResumeSearch() }
                BarButton("정지", Color(0xFF37474F), Modifier.weight(1f)) { actions.onStop() }
                BarButton("닫기", Color(0xFF616161), Modifier.weight(1f)) { actions.onDismissBanner() }
            }
        }
    }
}

/** 버블을 끌어다 놓으면 종료되는 하단 영역. */
@Composable
fun CloseZone(active: Boolean) {
    OverlayTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(if (active) Color(0xF2B71C1C) else Color(0xB3212121)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (active) "놓으면 종료" else "여기로 끌어다 놓으면 종료",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
