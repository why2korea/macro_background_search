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
import androidx.compose.material3.HorizontalDivider
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

    val hasA11y = remember(permTick) { Permissions.hasAccessibility(context) }
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

    val ready = hasA11y && canOverlay

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
                "버블로 축소한 뒤, 지금 보고 있는 다른 앱 화면에서 1차 문자열을 찾아 클릭하고 " +
                    "스크롤하며 2차 문자열을 찾습니다.",
                fontSize = 12.sp,
                color = Color(0xFF616161)
            )

            PermissionCard(hasA11y, canOverlay, hasNoti, battOk) { which ->
                when (which) {
                    0 -> Permissions.openAccessibilitySettings(context)
                    1 -> Permissions.openOverlaySettings(context)
                    2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            .onFailure { Permissions.openAppNotificationSettings(context) }
                    } else Permissions.openAppNotificationSettings(context)

                    3 -> Permissions.requestIgnoreBatteryOptimizations(context)
                }
            }

            InputCard(state, vm)
            AdvancedCard(state, vm)
            NotifyCard(state, vm)

            ControlRow(
                ready = ready,
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

            UsageCard()
            StatusCard(engine.round, engine.step, engine.elapsedText, engine.status, engine.foundCount)
            BubbleInfo()
            LogCard(engine.logs)
        }
    }
}

// ---------------------------------------------------------------- 권한

@Composable
private fun PermissionCard(
    hasA11y: Boolean,
    canOverlay: Boolean,
    hasNoti: Boolean,
    battOk: Boolean,
    onFix: (Int) -> Unit
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("권한", fontWeight = FontWeight.Bold)
            PermissionRow("접근성 서비스 (필수)", hasA11y) { onFix(0) }
            PermissionRow("다른 앱 위에 표시 (필수)", canOverlay) { onFix(1) }
            PermissionRow("알림 표시", hasNoti) { onFix(2) }
            PermissionRow("배터리 최적화 제외", battOk) { onFix(3) }
            if (!hasA11y) {
                Text(
                    "설정 > 접근성 > 설치된 앱 > '백그라운드 문자열 탐색' 을 켜야 다른 앱 화면을 읽을 수 있습니다.",
                    fontSize = 11.sp,
                    color = Color(0xFFC62828)
                )
            }
            if (!canOverlay) {
                Text(
                    "오버레이 권한이 없으면 버블과 패널이 뜨지 않습니다. (앱은 크래시하지 않습니다)",
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
                value = cfg.primaryText,
                onValueChange = vm::onPrimaryChange,
                label = { Text("1차 문자열 (찾아서 클릭)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "2차 문자열 목록 (${cfg.secondaryTexts.size}개)",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
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
                label = if (cfg.matchAll) "AND 매칭 (한 줄에 2차 문자열이 전부)"
                else "OR 매칭 (한 줄에 2차 문자열이 하나라도)",
                checked = cfg.matchAll,
                onChange = vm::setMatchAll
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                "3차 문자열 목록 (${cfg.tertiaryTexts.size}개, 선택)",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                "설정하면 2차 문자열이 발견된 「그 줄 안에」 이 중 하나가 더 있어야 발견으로 칩니다. " +
                    "비워두면 사용하지 않습니다.",
                fontSize = 11.sp,
                color = Color(0xFF757575)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.newTertiary,
                    onValueChange = vm::onNewTertiaryChange,
                    label = { Text("같은 줄에서 확인할 문자열") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { vm.addTertiary() }) { Text("추가") }
            }
            cfg.tertiaryTexts.forEachIndexed { idx, t ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${idx + 1}. $t", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.removeTertiary(idx) }) {
                        Text("삭제", fontSize = 12.sp, color = Color(0xFFC62828))
                    }
                }
            }

            SwitchRow(
                label = "발견한 줄을 클릭한 뒤 알림",
                checked = cfg.clickFoundRow,
                onChange = vm::setClickFoundRow
            )
            SwitchRow(
                label = "발견하면 재검색 자동 정지",
                checked = cfg.stopWhenFound,
                onChange = vm::setStopWhenFound
            )
            Text(
                if (cfg.stopWhenFound)
                    "발견 즉시 탐색을 멈춥니다. 배너의 [다시 시작] 으로 다시 돌릴 수 있습니다."
                else
                    "발견하면 일시정지만 하고, [계속] 을 누르면 이어서 탐색합니다.",
                fontSize = 11.sp,
                color = Color(0xFF757575)
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
                    valueRange = 0.1f..1.2f
                )
                NumberField("스텝 간 대기 (ms)", cfg.stepDelayMs.toString()) {
                    vm.setStepDelay(it.toLongOrNull() ?: cfg.stepDelayMs)
                }
                NumberField(
                    "1차 클릭 후 2차 탐색까지 대기 (ms)",
                    cfg.afterClickWaitMs.toString()
                ) { vm.setAfterClickWait(it.toLongOrNull() ?: cfg.afterClickWaitMs) }
                Text(
                    "클릭한 화면이 다 뜰 때까지 기다리는 시간입니다. 기본 2000ms.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    label = "1차 클릭에 좌표 탭 우선 사용",
                    checked = cfg.preferGestureTap,
                    onChange = vm::setPreferGestureTap
                )
                Text(
                    "클릭이 성공했다고 나오는데 실제로는 아무 일도 안 일어나면 켜세요. " +
                        "사람이 손가락으로 누른 것과 같은 방식으로 동작합니다.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
                NumberField("시작 카운트다운 (ms)", cfg.startDelayMs.toString()) {
                    vm.setStartDelay(it.toLongOrNull() ?: cfg.startDelayMs)
                }
                NumberField(
                    "못 찾았을 때 새로고침 전 대기 (ms)",
                    cfg.preRefreshWaitMs.toString()
                ) { vm.setPreRefreshWait(it.toLongOrNull() ?: cfg.preRefreshWaitMs) }
                Text(
                    "문자열을 못 찾은 뒤 새로고침을 시작하기까지 쉬는 시간입니다. 기본 5000ms.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
                NumberField("새로고침 후 로딩 대기 (ms)", cfg.refreshWaitMs.toString()) {
                    vm.setRefreshWait(it.toLongOrNull() ?: cfg.refreshWaitMs)
                }
                Text(
                    "새로고침 직후 화면이 다 뜨기를 기다리는 시간입니다. 이 시간이 지난 뒤 1차 문자열을 찾습니다. 기본 5000ms.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
                NumberField(
                    "1차 문자열 등장 대기 (최대 ms)",
                    cfg.contentWaitMs.toString()
                ) { vm.setContentWait(it.toLongOrNull() ?: cfg.contentWaitMs) }
                Text(
                    "목록을 나중에 불러오는 페이지 대응. 1차 문자열이 보이면 즉시 진행하고, " +
                        "이 시간까지 안 보이면 스크롤하며 찾습니다. 기본 15000ms.",
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
                NumberField("최대 라운드 (0 = 무제한)", cfg.maxRounds.toString()) {
                    vm.setMaxRounds(it.toIntOrNull() ?: cfg.maxRounds)
                }
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "버블 탭으로 시작/정지 (끄면 탭 = 패널 열기)",
                    checked = cfg.bubbleTapToggles,
                    onChange = vm::setBubbleTapToggles
                )
                Text(
                    "못 찾으면 새로고침만 합니다. 뒤로가기로 이전 페이지로 넘어가는 동작은 하지 않습니다.",
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

// ---------------------------------------------------------------- 사용법

@Composable
private fun UsageCard() {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("사용법", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("1. 문자열 입력 후 [탐색 시작] → 버블로 축소되고 카운트다운 시작", fontSize = 12.sp)
            Text("2. 카운트다운 동안 대상 앱/웹사이트 화면으로 이동", fontSize = 12.sp)
            Text("3. 카운트다운이 끝나면 그 화면에서 탐색 시작", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("버블 조작", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("• 탭 = 시작/정지 토글    • 더블탭 = 패널 열기", fontSize = 12.sp)
            Text("• 드래그 = 이동 (가장자리 스냅)    • 하단으로 끌면 종료", fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- 컨트롤

@Composable
private fun ControlRow(
    ready: Boolean,
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
                enabled = ready && !running,
                modifier = Modifier.weight(1f)
            ) { Text("탐색 시작") }

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
                enabled = ready,
                modifier = Modifier.weight(1f)
            ) { Text("버블만 띄우기") }
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
