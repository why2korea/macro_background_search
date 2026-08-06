package com.why2korea.bgsearch.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.why2korea.bgsearch.engine.SearchBus
import com.why2korea.bgsearch.util.Metrics

@Composable
fun MainScreen(vm: SetupViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    val engine by SearchBus.snapshot.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 설정 화면에서 돌아왔을 때 권한 상태를 다시 읽기 위한 리프레시 키
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val canOverlay = remember(permTick) { Permissions.canDrawOverlays(context) }
    val hasNoti = remember(permTick) { Permissions.hasNotification(context) }
    val battOk = remember(permTick) { Permissions.isIgnoringBatteryOptimizations(context) }

    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNoti) {
            runCatching { notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "백그라운드 문자열 탐색",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "오버레이 패널에서 탐색이 돌아가고, 축소하면 약 1cm 버블 뒤에서 계속 동작합니다.",
                fontSize = 12.sp,
                color = Color(0xFF616161)
            )

            PermissionCard(canOverlay, hasNoti, battOk) { which ->
                when (which) {
                    0 -> Permissions.openOverlaySettings(context)
                    1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            .onFailure { Permissions.openAppNotificationSettings(context) }
                    } else Permissions.openAppNotificationSettings(context)

                    2 -> Permissions.requestIgnoreBatteryOptimizations(context)
                }
            }

            InputCard(state, vm)
            AdvancedCard(state, vm)
            NotifyCard(state, vm)

            ControlRow(
                canOverlay = canOverlay,
                running = engine.running,
                onStart = { vm.startSearch { activity?.moveTaskToBack(true) } },
                onOpenOverlay = { vm.openOverlay { activity?.moveTaskToBack(true) } },
                onStop = { vm.stopSearch() },
                onExit = { vm.exitService() }
            )

            if (state.message.isNotBlank()) {
                Text(state.message, color = Color(0xFFC62828), fontSize = 13.sp)
                LaunchedEffect(state.message) {
                    kotlinx.coroutines.delay(4000)
                    vm.clearMessage()
                }
            }

            StatusCard(engine.round, engine.step, engine.elapsedText, engine.status, engine.foundCount)
            BubbleInfo()
            LogCard(engine.logs)
        }
    }
}

// ---------------------------------------------------------------- 권한

@Composable
private fun PermissionCard(
    canOverlay: Boolean,
    hasNoti: Boolean,
    battOk: Boolean,
    onFix: (Int) -> Unit
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("권한", fontWeight = FontWeight.Bold)
            PermissionRow("다른 앱 위에 표시 (필수)", canOverlay) { onFix(0) }
            PermissionRow("알림 표시", hasNoti) { onFix(1) }
            PermissionRow("배터리 최적화 제외", battOk) { onFix(2) }
            if (!canOverlay) {
                Text(
                    "오버레이 권한이 없으면 플로팅 패널과 버블이 뜨지 않습니다. (앱은 크래시하지 않습니다)",
                    fontSize = 11.sp,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            (if (granted) "○  " else "●  ") + label,
            fontSize = 13.sp,
            color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828)
        )
        TextButton(onClick = onFix) { Text(if (granted) "설정" else "허용하기", fontSize = 12.sp) }
    }
}

// ---------------------------------------------------------------- 입력

@Composable
private fun InputCard(state: SetupUiState, vm: SetupViewModel) {
    var historyOpen by remember { mutableStateOf(false) }
    val cfg = state.config

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cfg.url,
                onValueChange = vm::onUrlChange,
                label = { Text("대상 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfg.primaryText,
                onValueChange = vm::onPrimaryChange,
                label = { Text("1차 문자열 (찾아서 클릭)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("2차 문자열 목록 (${cfg.secondaryTexts.size}개)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.newSecondary,
                    onValueChange = vm::onNewSecondaryChange,
                    label = { Text("추가할 문자열") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { vm.addSecondary() }) { Text("추가") }
            }

            if (cfg.secondaryTexts.isEmpty()) {
                Text("아직 없습니다. 최소 1개 이상 추가하세요.", fontSize = 12.sp, color = Color(0xFF9E9E9E))
            } else {
                cfg.secondaryTexts.forEachIndexed { idx, t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${idx + 1}. $t", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.removeSecondary(idx) }) {
                            Text("삭제", fontSize = 12.sp, color = Color(0xFFC62828))
                        }
                    }
                }
            }

            SwitchRow(
                label = if (cfg.matchAll) "AND 매칭 (전부 발견해야 알림)" else "OR 매칭 (하나라도 발견하면 알림)",
                checked = cfg.matchAll,
                onChange = vm::setMatchAll
            )

            Box {
                OutlinedButton(
                    onClick = { historyOpen = true },
                    enabled = state.history.isNotEmpty()
                ) { Text("최근 입력 (${state.history.size})") }
                DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                    state.history.forEach { h ->
                        DropdownMenuItem(
                            text = { Text(h.label(), fontSize = 12.sp) },
                            onClick = {
                                vm.applyHistory(h)
                                historyOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 고급

@Composable
private fun AdvancedCard(state: SetupUiState, vm: SetupViewModel) {
    val cfg = state.config
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp)) {
            SectionHeader("고급 설정", state.advancedOpen) { vm.toggleAdvanced() }
            if (state.advancedOpen) {
                Text("스크롤 1스텝 크기: ${"%.2f".format(cfg.scrollRatio)} × 화면높이")
                Slider(
                    value = cfg.scrollRatio,
                    onValueChange = vm::setRatio,
                    valueRange = 0.1f..1.5f
                )
                NumberField("스텝 간 대기 (ms)", cfg.stepDelayMs.toString()) {
                    vm.setStepDelay(it.toLongOrNull() ?: cfg.stepDelayMs)
                }
                NumberField("새로고침 후 대기 (ms, 최소 5000)", cfg.refreshDelayMs.toString()) {
                    vm.setRefreshDelay(it.toLongOrNull() ?: cfg.refreshDelayMs)
                }
                NumberField("최대 라운드 (0 = 무제한)", cfg.maxRounds.toString()) {
                    vm.setMaxRounds(it.toIntOrNull() ?: cfg.maxRounds)
                }
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "축소 시 WebView 크기 유지 (권장)",
                    checked = cfg.keepFullSizeWhenCollapsed,
                    onChange = vm::setKeepFullSize
                )
                Text(
                    if (cfg.keepFullSizeWhenCollapsed)
                        "화면 크기를 유지한 채 투명도만 거의 0 으로 낮춥니다. 뷰포트가 바뀌지 않아 스크롤 탐색이 안정적입니다."
                    else
                        "버블과 같은 1cm 크기로 줄여 버블 뒤에 둡니다. 화면 점유는 최소지만 페이지가 좁은 폭으로 리플로우됩니다.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 알림 채널

@Composable
private fun NotifyCard(state: SetupUiState, vm: SetupViewModel) {
    val cfg = state.config
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp)) {
            SectionHeader("알림 채널", state.notifyOpen) { vm.toggleNotify() }
            if (state.notifyOpen) {
                SwitchRow("시스템 알림 (고우선순위)", cfg.notifySystem, vm::setNotifySystem)
                SwitchRow("진동", cfg.notifyVibrate, vm::setNotifyVibrate)
                SwitchRow("사운드", cfg.notifySound, vm::setNotifySound)
                SwitchRow("오버레이 배너", cfg.notifyBanner, vm::setNotifyBanner)
                SwitchRow("버블 색상 변경 + 뱃지", cfg.notifyBubble, vm::setNotifyBubble)
                SwitchRow("발견 시점 스크린샷 저장", cfg.notifyScreenshot, vm::setNotifyScreenshot)
            }
        }
    }
}

// ---------------------------------------------------------------- 컨트롤

@Composable
private fun ControlRow(
    canOverlay: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onOpenOverlay: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = canOverlay && !running,
                modifier = Modifier.weight(1f)
            ) { Text("오버레이 열고 탐색 시작") }

            Button(
                onClick = onStop,
                enabled = running,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier.weight(1f)
            ) { Text("정지") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenOverlay,
                enabled = canOverlay,
                modifier = Modifier.weight(1f)
            ) { Text("오버레이만 열기") }
            OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f)) { Text("서비스 종료") }
        }
    }
}

// ---------------------------------------------------------------- 상태 / 로그

@Composable
private fun StatusCard(round: Int, step: Int, elapsed: String, status: String, foundCount: Int) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatusCell("라운드", if (round == 0) "-" else round.toString())
                StatusCell("스텝", step.toString())
                StatusCell("경과", elapsed)
                StatusCell("발견", foundCount.toString())
            }
            Spacer(Modifier.height(6.dp))
            Text(status, fontSize = 13.sp, color = Color(0xFF424242))
        }
    }
}

@Composable
private fun RowScope.StatusCell(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF757575))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun BubbleInfo() {
    val ctx = LocalContext.current
    val px = remember { Metrics.oneCmPx(ctx) }
    val dm = ctx.resources.displayMetrics
    Text(
        "버블 지름: ${px}px (xdpi=${"%.1f".format(dm.xdpi)}, ydpi=${"%.1f".format(dm.ydpi)} 기준 물리 1cm)",
        fontSize = 11.sp,
        color = Color(0xFF9E9E9E)
    )
}

@Composable
private fun LogCard(logs: List<String>) {
    if (logs.isEmpty()) return
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("로그", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.height(180.dp)) {
                items(logs.asReversed()) { line ->
                    Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 공통 위젯

@Composable
private fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        TextButton(onClick = onToggle) { Text(if (open) "접기" else "펼치기") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    )
}
