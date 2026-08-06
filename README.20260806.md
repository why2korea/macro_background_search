# 백그라운드 문자열 탐색 (macro_background_search) — 2026-08-06 작업 기록

다른 앱 화면에서 문자열을 찾아 클릭하고 스크롤 탐색하는 Android 앱.

- 패키지: `com.why2korea.bgsearch`
- Kotlin + Jetpack Compose / minSdk 26 / compileSdk · targetSdk 34
- 참고 프로젝트 `../macro_search_manpodae` 는 읽기만 했고 **한 글자도 수정하지 않았다.**

> **중요** — 이 문서는 하루 안에 두 번의 서로 다른 구현을 담고 있다.
> 1장~4장은 오전에 만든 **v1.0.x (앱 내장 WebView 방식)** 기록이고,
> 5장은 요구사항 재정의에 따라 오후에 **전면 재작성한 v2.0.0 (접근성 서비스 방식)** 기록이다.
> **현재 코드는 v2.0.0 이며, WebView 방식은 완전히 제거되었다.**

---

## 1. v1.0.x → v2.0.0 : 무엇이 바뀌었나

| | v1.0.x (폐기) | v2.0.0 (현재) |
|---|---|---|
| 탐색 대상 | 앱이 내장한 WebView 안의 웹페이지 | **지금 화면에 떠 있는 다른 앱** (크롬 웹페이지 포함) |
| 입력값 | URL + 1차 문자열 + 2차 문자열 | **1차 문자열 + 2차 문자열** (URL 없음) |
| 읽는 방법 | WebView + JavaScript 인젝션 | **AccessibilityService 노드 트리 순회** |
| 클릭 | JS `fireClick()` | `ACTION_CLICK` → 실패 시 좌표 탭 제스처 |
| 스크롤 | JS `scrollTop` 조작 | `ACTION_SCROLL_FORWARD` → 실패 시 스와이프 제스처 |
| 미발견 시 | `WebView.reload()` | **맨 위로 → 당겨서 새로고침 제스처 → 1차부터 재시작** |
| 스크린샷 | `View.draw(Canvas)` | `AccessibilityService.takeScreenshot()` (API 30+) |
| 축소 상태 | WebView 를 alpha 0.01 로 숨겨 뒤에서 계속 실행 | 버블만 남고, 대상은 원래부터 다른 앱이라 숨길 것이 없음 |

원래 지시서에는 "AccessibilityService 는 사용하지 않는다", "타 앱 화면의 텍스트를 읽는 방식은
채택하지 않는다"고 되어 있었다. 요구사항이 재정의되면서 이 두 조항이 **명시적으로 뒤집혔고**,
사용자 승인을 받은 뒤 전면 재작성했다.

---

## 2. 반영내역 (v2.0.0)

### 2-1. 입력값

- **1차 문자열** 1개 — 화면에서 찾아 클릭할 문자열
- **2차 문자열** N개 — 클릭 후 스크롤하며 찾을 문자열 (추가/삭제 자유)
  - 기본 **OR** (하나라도 발견 시 알림) / **AND** 토글 제공
- 스크롤 1스텝 크기, 스텝 간 대기, 시작 카운트다운, 새로고침 후 대기, 최대 라운드

### 2-2. 동작 흐름

```
[시작] → 버블로 축소 + 카운트다운(기본 5초) → 그 사이 대상 앱으로 이동
   ↓
반복 (사용자가 중지할 때까지)
 1. 현재 화면에서 1차 문자열 탐색 → 없으면 스크롤하며 계속 → 발견 시 클릭
 2. 클릭 후 화면 전환 대기(1.5초)
 3. 스크롤 1스텝씩 내리며 2차 문자열 목록 탐색 (매 스텝 매칭 결과 누적)
 4. 발견 → 즉시 알림 → 일시정지 ([계속] 누르면 재개)
 5. 바닥까지 미발견 → 맨 위로 스크롤 → 당겨서 새로고침 제스처 → 대기 → 1번으로
```

### 2-3. 기술 방식

| 기능 | 구현 | 폴백 |
|---|---|---|
| 화면 텍스트 읽기 | `windows` → `AccessibilityWindowInfo.root` BFS 순회 (노드 상한 4000) | `rootInActiveWindow` |
| 문자열 매칭 | `text` + `contentDescription` 을 정규화 후 비교 | — |
| 대상 노드 선택 | 문자열을 포함하는 **가장 안쪽** 노드, 화면에 보이는 것 우선 | 숨은 노드 |
| 클릭 | 클릭 가능한 조상(최대 12단계)에 `ACTION_CLICK` | 노드 중심 좌표 탭 제스처 |
| 스크롤 | 가장 큰 스크롤 가능 노드에 `ACTION_SCROLL_FORWARD` | 화면 중앙 스와이프 제스처 |
| 맨 위로 | `ACTION_SCROLL_BACKWARD` 반복 | 아래 방향 스와이프 반복 |
| 새로고침 | 화면 22% → 78% 지점으로 **700ms 느린 드래그** (pull-to-refresh) | — |
| 바닥 판정 | 스크롤 전후 화면 텍스트 해시 비교, **3회 연속 동일**하면 바닥 | — |
| 스크린샷 | `takeScreenshot()` (API 30+), 하드웨어 버퍼 → 소프트웨어 복사 후 PNG | 미지원 시 skip |

- 문자열 정규화: 공백류(일반 공백 · NBSP · 제로폭 · BOM · 전각 공백 · 탭/개행)를 단일 공백으로
  접고, 앞뒤 공백 제거 후 소문자화. (`util/TextNorm.kt`)
- **자기 자신의 오버레이 창은 노드 순회에서 제외한다.** 그러지 않으면 컨트롤 패널의
  "시작/정지" 같은 글자를 대상 화면 텍스트로 오인한다.
- 모든 단계에 타임아웃(제스처 8초 / 스크린샷 8초). 실패해도 루프가 죽지 않는다.
  루프 전체 예외는 5초 백오프 후 자동 재기동.
- 접근성 서비스가 꺼져 있으면 루프를 죽이지 않고 3초마다 재확인하며 안내 상태를 유지한다.

### 2-4. 플로팅 버블 / 오버레이

- 버블 지름 = `DisplayMetrics.xdpi / 2.54` **런타임 계산** (xdpi·ydpi 평균 → densityDpi → 60dp 폴백)
- 버블 조작
  - **탭** = 시작/정지 토글 (설정에서 끄면 탭 = 패널 열기)
  - **더블탭** = 패널 열기
  - **드래그** = 이동 + 가장자리 스냅 애니메이션
  - **롱프레스** = 하단 종료 영역 표시, 끌어다 놓으면 종료
- 버블 색: 회청(대기) / 초록(탐색 중) / 주황(카운트다운·오류·서비스꺼짐) / 빨강(발견)
  카운트다운 중에는 버블에 남은 초가 표시된다.
- 오버레이 창 구성

| 창 | 내용 | 플래그 |
|---|---|---|
| panelWindow | 컴팩트 패널 (상태 · 로그 · 시작/정지/계속/축소/설정/종료) | `NOT_FOCUSABLE` |
| bubbleWindow | 원형 버블 (약 1cm) | `NOT_FOCUSABLE` + `LAYOUT_NO_LIMITS` |
| bannerWindow | 발견 배너 (계속 · 정지 · 닫기) | `NOT_FOCUSABLE` |
| closeWindow | 하단 종료 영역 | `NOT_FOCUSABLE` + `NOT_TOUCHABLE` |

**모든 창이 `FLAG_NOT_FOCUSABLE` 이다.** 포커스를 뺏지 않아야 접근성 서비스가 읽는
"활성 창"이 대상 앱으로 유지된다.

### 2-5. 알림 (각각 on/off)

| 채널 | 동작 |
|---|---|
| 시스템 알림 | IMPORTANCE_HIGH, [계속]/[정지] 액션 포함 |
| 진동 | 1초 × 3회 |
| 사운드 | 기본 알림음 |
| 오버레이 배너 | 화면 상단 배너 |
| 버블 색상·뱃지 | 빨강 전환 + 누적 발견 횟수 뱃지 |
| 스크린샷 | `filesDir/shots/found_yyyyMMdd_HHmmss.png` |

발견 이력은 `filesDir/found_log.txt` 에도 누적된다.

### 2-6. 상태 유지

- 설정은 DataStore 에 저장 (입력 후 400ms 디바운스 자동 저장)
- `was_running` 플래그 → `START_STICKY` 로 프로세스 재시작 시 이전 탐색 복원
- 버블 좌표 저장 → 재시작 시 같은 자리 복원
- Foreground Service(`dataSync`) + `PARTIAL_WAKE_LOCK`
- Activity 는 `configChanges` 지정으로 회전 시 재생성되지 않으며, 루프는 서비스가 소유

### 2-7. 권한

`SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE(+DATA_SYNC)` / `WAKE_LOCK` /
`VIBRATE` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + **접근성 서비스**(설정에서 수동 활성화)

첫 화면 권한 카드에서 각 설정 화면으로 바로 이동한다. 미허용 상태여도 **크래시 없이** 안내만 뜬다.

### 2-8. 파일 구성

```
app/src/main/java/com/why2korea/bgsearch/
  MainActivity.kt                설정 전용 Activity
  data/Models.kt                 SearchConfig, HistoryItem
  data/SettingsStore.kt          DataStore
  engine/ScreenScanner.kt        화면 읽기·조작 추상화 + ScannerHolder
  engine/SearchEngine.kt         탐색 루프
  engine/SearchState.kt          SearchBus (상태 통로)
  service/ScanService.kt         ★ AccessibilityService (읽기·클릭·스크롤·제스처·캡처)
  service/OverlayService.kt      Foreground Service + 오버레이 오케스트레이션
  service/Notifier.kt            알림 채널 / 진동
  overlay/OverlayManager.kt      윈도우 관리, 버블 제스처
  overlay/BubbleView.kt          원형 버블 렌더링
  overlay/OverlayLifecycleOwner.kt  ComposeView 용 owner
  overlay/OverlayComposables.kt  패널 / 배너 / 종료 영역
  ui/MainScreen.kt               설정 화면
  ui/SetupViewModel.kt           설정 편집 + 명령 전송
  ui/Permissions.kt              권한 확인 / 설정 이동
  util/Metrics.kt                물리 1cm 계산
  util/TextNorm.kt               문자열 정규화
app/src/main/res/xml/accessibility_service_config.xml
```

### 2-9. 알려진 한계

- **접근성 노드를 노출하지 않는 화면은 못 읽는다** — 일부 게임, Canvas 로 텍스트를 직접 그리는 앱
- **`FLAG_SECURE` 창(은행앱 등)은 읽기·캡처 모두 불가**
- **Play 스토어 정책상 접근성 서비스를 비접근성 목적에 쓰면 등록이 거부된다** → 개인 사이드로드 전용
- 접근성 서비스는 사용자가 설정에서 직접 켜야 한다 (adb·코드로 못 켬)
- `takeScreenshot()` 은 API 30 미만에서 동작하지 않는다

---

## 3. 올릴 파일 / 올리면 안 되는 파일

### 올려야 하는 파일

```
.gitignore
build.gradle.kts
settings.gradle.kts
gradle.properties
gradlew
gradlew.bat
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
README.md
README.20260806.md
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/why2korea/bgsearch/**/*.kt
app/src/main/res/**
```

### 올리면 안 되는 파일

| 파일 | 이유 |
|---|---|
| `local.properties` | 로컬 Android SDK 절대경로 (개인 PC 경로 노출) |
| `*.keystore`, `*.jks`, `*.p12`, `*.pem`, `key*.properties`, `signing.properties` | 앱 서명 키 — 유출 시 위조 앱 배포 가능 |
| `google-services.json` | 프로젝트 키 |
| `build/`, `app/build/`, `.gradle/`, `captures/` | 빌드 산출물 |
| `.idea/`, `*.iml` | IDE 개인 설정 |
| `.claude/settings.local.json` | 로컬 도구 설정 |
| `_backup/` | 작업 백업 (소스셋 밖 보관) |
| `dist/`, `*.apk`, `*.aab`, `*.log` | 산출물 / 로그 |

### `.gitignore` 전문

```gitignore
# --- Gradle / 빌드 산출물 -------------------------------------------------
.gradle/
build/
app/build/
captures/

# --- 로컬 환경 (절대 올리면 안 됨) ---------------------------------------
local.properties

# --- IDE ------------------------------------------------------------------
.idea/
*.iml
.DS_Store

# --- 서명 키 (절대 올리면 안 됨) ------------------------------------------
*.keystore
*.jks
*.p12
*.pem
keystore.properties
signing.properties
key.properties
google-services.json

# --- 로컬 도구 설정 -------------------------------------------------------
.claude/settings.local.json

# --- 작업 백업 (소스셋 밖에 보관해야 컴파일에 섞이지 않음) ----------------
_backup/

# --- 기타 -----------------------------------------------------------------
*.apk
*.aab
*.log
.cxx/
.externalNativeBuild/
```

---

## 4. 업로드 · 갱신 명령어 3종

> 저장소: `https://github.com/why2korea/macro_background_search`

### ① 최초 업로드

```bash
cd C:/why2korea/claude/macro_background_search
git init
git branch -M main
git add .
git status            # local.properties 가 목록에 없는지 반드시 확인
git commit -m "feat: 최초 업로드"
git remote add origin https://github.com/why2korea/macro_background_search.git
git push -u origin main
```

### ② 변경분 갱신 (평소 작업)

```bash
cd C:/why2korea/claude/macro_background_search
git add -A
git status
git commit -m "fix: 변경 내용 요약"
git pull --rebase origin main
git push origin main
```

### ③ 강제 동기화 (원격을 로컬 상태로 덮어쓰기 — 되돌릴 수 없음)

```bash
cd C:/why2korea/claude/macro_background_search
git add -A
git commit -m "chore: 강제 동기화"
git push --force-with-lease origin main
```

> 반대로 **로컬을 원격 상태로 되돌리려면** (로컬 변경 전부 삭제):
> ```bash
> git fetch origin
> git reset --hard origin/main
> git clean -fd
> ```

---

## 5. 빌드 / 설치 / 사용

```bash
cd C:/why2korea/claude/macro_background_search
./gradlew.bat assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat assembleRelease     # app-release-unsigned.apk (서명 별도)
./gradlew.bat installDebug        # 연결된 기기에 설치
```

### 첫 실행 순서

1. 앱 실행 → 권한 카드에서
   - **접근성 서비스** 허용 (설정 > 접근성 > 설치된 앱 > 백그라운드 문자열 탐색) — **필수**
   - **다른 앱 위에 표시** 허용 — **필수**
   - 알림 권한 허용, 배터리 최적화 제외 권장
2. 1차 문자열 입력, 2차 문자열을 하나씩 **추가**
3. `탐색 시작` → 앱이 뒤로 빠지고 버블로 축소, 카운트다운 시작
4. 카운트다운 동안 **대상 앱 / 웹사이트 화면으로 이동**
5. 카운트다운이 끝나면 그 화면에서 탐색 시작
6. 버블 탭 = 시작/정지 · 더블탭 = 패널 · 하단으로 끌면 종료

---

## 6. 검증 상태

- `assembleDebug` BUILD SUCCESSFUL
- **v2.0.0 은 실기기 동작을 아직 확인하지 못했다.** (USB 분리로 설치·테스트 미실행)
- v1.0.x 에서 실기기로 확인했던 항목 중 v2.0.0 에도 그대로 남아 있는 것
  - 물리 1cm 버블 계산 (`165px` @ xdpi 422.0)
  - 오버레이 창 표시, 버블 드래그·스냅, 확장/축소 왕복, 종료 시 완전 정리
  - `ComposeView` 는 표시할 때마다 새로 만든다 (재사용 시 터치 무반응 버그, v1.0.1 에서 수정)
- v2.0.0 에서 새로 들어온 것은 **전부 미검증**이다
  - 접근성 노드 읽기 / 1차 문자열 클릭 / 스크롤 / 당겨서 새로고침 / `takeScreenshot()`

---

## 7. v2.0.1 실기기 검증 결과 (2026-08-06)

Galaxy Z Fold (**SM-F966N**, Android 15) 실기기에서 **실제 대상 사이트로 end-to-end 검증 완료.**

### 7-1. 검증된 항목

| 항목 | 결과 |
|---|---|
| 접근성 서비스 연결 | `accessibility service connected` |
| **크롬 웹 콘텐츠 노드 읽기** | 정상 (웹페이지 텍스트가 접근성 노드로 노출됨) |
| **1차 문자열 탐색 + 클릭** | `1차 클릭 성공 [ACTION_CLICK] "평택(만포대)"` — 폴백 없이 1순위 경로로 성공 |
| **2차 문자열 탐색** | `발견! 09일` |
| **스크린샷 캡처** | `takeScreenshot()` 성공, `files/shots/found_20260806_152153.png` 저장 |
| 발견 로그 파일 | `files/found_log.txt` 에 누적 기록 확인 |
| 오버레이 배너 | 상단 빨간 배너 + [계속]/[정지]/[닫기] 표시 |
| 버블 색상·뱃지 | 빨강 전환 + 누적 발견 횟수 뱃지 표시 |
| 버블 탭 = 시작/정지 토글 | 정상 |
| 프로세스 재시작 복원 | `was_running` 기반 자동 복원 동작 확인 |
| 종료 | 서비스 · 오버레이 창 전부 정리됨 |

### 7-2. 검증 중 발견해 수정한 버그

**[버블만 띄우기] 버튼이 버블이 아니라 패널을 열었다.**

`ACTION_SHOW_OVERLAY` 가 `onExpand()` 를 호출하고 있어, 버튼 이름과 반대로 동작했다.
확장 패널은 화면 하단을 가려 대상 앱을 덮어버리므로 의도와 정반대였다.
`onCollapse()` 로 수정했다. (v2.0.1)

### 7-3. 여전히 미검증

- **1차 문자열 미발견 시 경로** — 맨 위로 스크롤 → 당겨서 새로고침 제스처 → 재시작.
  테스트에서는 1차 문자열이 첫 화면에 바로 있어 이 경로를 타지 않았다.
- 스크롤 기반 2차 탐색 — 2차 문자열이 클릭 직후 화면에 이미 있어 스크롤 없이 발견됐다.
- `ACTION_CLICK` 실패 시 좌표 탭 제스처 폴백, `ACTION_SCROLL_FORWARD` 실패 시 스와이프 폴백
- 장시간(수 시간) 백그라운드 지속, 화면 꺼짐 상태 동작
- 배터리 최적화 제외 미적용 상태

### 7-4. 앞선 문서의 오류 정정

`접근성 서비스는 사용자가 설정에서 직접 켜야 한다 (adb·코드로 못 켬)` 라고 적었으나,
**adb 는 켤 수 있다.** `WRITE_SECURE_SETTINGS` 를 가진 shell 에서 다음으로 활성화된다.

```bash
adb shell settings put secure enabled_accessibility_services \
  "<기존값>:com.why2korea.bgsearch/.service.ScanService"
adb shell settings put secure accessibility_enabled 1
```

앱 자체 코드로는 켤 수 없다는 점은 그대로다.

### 7-5. 대상 사이트 이용약관 관련 (중요)

검증에 사용한 대상 페이지(해군복지포탈체계 체력단련장 예약)에는 화면상 다음 문구가 있다.

> ※불법매크로 등 악성행위자 자료 수집중입니다. 적발시 위규처리(자격정지) 예정입니다.

**이 사이트는 매크로/자동화 사용을 명시적으로 금지하고, 적발 시 자격정지를 예고하고 있다.**
이 앱을 해당 사이트에 사용하면 계정 제재 대상이 될 수 있다. 사용 여부와 그 결과는 사용자 책임이다.
