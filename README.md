# macro_background_search

플로팅 오버레이 방식 웹 문자열 탐색 Android 앱.
작업 기록은 날짜별 `README.YYYYMMDD.md` 로도 보관하며, 이 문서에는 같은 내용을 아래에 계속 추가(append)한다.

---



---

# 백그라운드 문자열 탐색 (macro_background_search) — 2026-08-06 작업 기록

플로팅 오버레이 방식 웹 문자열 탐색 Android 앱 **신규 제작**.
참고 프로젝트 `../macro_search_manpodae` 를 읽고 재사용 가능한 모듈을 이식했으며,
**참고 프로젝트 원본은 한 글자도 수정하지 않았다.**

- 패키지: `com.why2korea.bgsearch`
- 앱 이름: 백그라운드 문자열 탐색
- Kotlin + Jetpack Compose / minSdk 26 / compileSdk · targetSdk 34
- 빌드 확인: `assembleDebug`, `assembleRelease` 모두 BUILD SUCCESSFUL (lint-vital 포함)

---

## 1. 반영내역

### 1-1. 참고 프로젝트에서 이식한 모듈

| 이식 대상 | 원본 | 신규 위치 | 변경점 |
|---|---|---|---|
| DOM 탐색 / 클릭 / 스크롤 JS | `web/InjectScripts.kt` | `engine/InjectScripts.kt` | 문자열 정규화 비교, 2차 문자열 N개 동시 스캔 추가 |
| WebView 코루틴 래퍼 | `web/WatcherWebView.kt` | `engine/WebController.kt` | Activity 의존 제거, 오버레이용 WebView 직접 생성, 스크린샷 방식 교체 |
| 탐색 루프 | `ui/WatcherViewModel.kt` | `engine/SearchEngine.kt` | ViewModel → Service 소유 엔진으로 이동, 발견 시 "정지" → "일시정지" |
| 알림 / 진동 / 채널 | `service/WatcherService.kt` | `service/Notifier.kt` | 채널별 on/off, 알림 액션(계속/정지/패널/종료) 추가 |
| Foreground Service + WakeLock | `service/WatcherService.kt` | `service/OverlayService.kt` | 오버레이 윈도우 소유, 프로세스 재시작 복원 추가 |
| DataStore 설정 저장 | `data/SettingsStore.kt` | `data/SettingsStore.kt` | 2차 문자열 목록(JSON), 알림 토글, 버블 좌표, 실행중 플래그 추가 |
| 설정 UI | `ui/MainScreen.kt` | `ui/MainScreen.kt` | 권한 안내 카드, 2차 문자열 추가/삭제 UI, 알림 채널 토글 추가 |

### 1-2. 요구사항 3가지 반영

**① 찾을 문자열 여러 개 추가**
- `SearchConfig.secondaryTexts: List<String>` — 추가/삭제 제한 없음.
- `matchAll` 토글: 기본 **OR**(하나라도 발견 시 알림), 켜면 **AND**(전부 발견해야 알림).
- AND 판정은 한 라운드 안에서 스크롤 스텝마다 매칭 결과를 **누적**한다.
  (지연 로딩 페이지에서 문자열이 서로 다른 스크롤 위치에 나타나도 판정되게 하기 위함)

**② 다른 앱 위에 떠서 백그라운드로 계속 동작**
- `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY` 기반 오버레이 윈도우.
- `OverlayService` = `foregroundServiceType="dataSync"` Foreground Service + `PARTIAL_WAKE_LOCK`.
- Activity 는 설정 화면일 뿐. Activity 가 죽어도 탐색은 계속된다.

**③ 1cm 원형 플로팅 버블로 축소 + 버블 뒤에서 탐색 계속**
- 버블 지름 = `DisplayMetrics.xdpi / 2.54` 로 런타임 계산 (xdpi·ydpi 평균, 비정상 시 densityDpi → 60dp 폴백).
- 축소 시 **WebView 를 파괴하지 않는다.** 윈도우 크기를 유지한 채
  `alpha = 0.01f` + `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE` 적용.
- 버블: 드래그 이동 / 가장자리 스냅 애니메이션 / 롱프레스 시 하단 종료 영역 표시 / 탭하면 확장.

### 1-3. 탐색 동작

```
루프 (사용자가 중지할 때까지 무한 반복)
 1. URL 로드                       (타임아웃 30초)
 2. 1차 문자열 탐색 → 없으면 스크롤하며 계속 → 발견 시 해당 요소 클릭
 3. 클릭 후 DOM/URL 변화 대기 → 맨 위로 → 스크롤 1스텝씩 내리며 2차 문자열 목록 탐색
 4. 발견 → 즉시 알림 → 루프 일시정지 ([계속] 누르면 재개)
 5. 페이지 끝까지 미발견 → 새로고침 → 대기(기본 5초) → 2번으로 복귀
```

- WebView + JavaScript 인젝션으로 DOM 텍스트 탐색·클릭·스크롤 수행.
- **JS `alert()` 사용 안 함.** 페이지가 띄우는 alert/confirm/beforeunload 는 `WebChromeClient` 가 자동 confirm 처리해 블로킹을 막는다.
- same-origin iframe 순회 포함, cross-origin 은 try/catch 로 무시하고 개수만 로그에 남긴다.
- 무한스크롤: 바닥에서 `scrollHeight` 가 **3회 연속** 변하지 않으면 바닥으로 판정.
- 문자열 매칭: 공백(NBSP·제로폭·BOM 포함) 접기 + 소문자화 후 비교.
- 각 단계 타임아웃(JS 15초 / 로드 30초). 실패해도 루프가 죽지 않고 다음 사이클로 넘어간다.
  루프 전체 예외는 5초 백오프 후 자동 재기동.
- 새로고침 최소 간격 5초 강제 (서버 부담 방지).

### 1-4. 알림 (각각 on/off)

| 채널 | 설정 키 | 동작 |
|---|---|---|
| 시스템 알림 | `notifySystem` | IMPORTANCE_HIGH 채널, [계속]/[정지] 액션 포함 |
| 진동 | `notifyVibrate` | 1초 × 3회 패턴 |
| 사운드 | `notifySound` | 기본 알림음 |
| 오버레이 배너 | `notifyBanner` | 화면 상단 오버레이 배너 + WebView 안 하이라이트/배너 |
| 버블 색상·뱃지 | `notifyBubble` | 버블 빨강 전환 + 누적 발견 횟수 뱃지 |
| 스크린샷 | `notifyScreenshot` | `filesDir/shots/found_yyyyMMdd_HHmmss.png` 저장 |

발견 이력은 `filesDir/found_log.txt` 에도 탭 구분으로 누적된다.

### 1-5. 플로팅 오버레이 윈도우 구성

| 윈도우 | 내용 | 플래그 |
|---|---|---|
| webWindow | WebView 전용. 항상 붙어 있고 크기가 바뀌지 않는다 | 확장: 포커스 가능 / 축소: `NOT_TOUCHABLE`+`NOT_FOCUSABLE`, alpha 0.01 |
| controlWindow | 하단 컨트롤 바 (시작·정지·계속·축소·설정·종료) | `NOT_FOCUSABLE` |
| bubbleWindow | 원형 버블 (약 1cm) | `NOT_FOCUSABLE`+`LAYOUT_NO_LIMITS` |
| bannerWindow | 발견 배너 (계속·정지·닫기) | `NOT_FOCUSABLE` |
| closeWindow | 드래그/롱프레스 시 하단 종료 영역 | `NOT_FOCUSABLE`+`NOT_TOUCHABLE` |

컨트롤 바를 WebView 와 같은 윈도우에 넣지 않은 이유: 같은 윈도우면 컨트롤 바를 숨길 때 WebView 높이가 바뀌어
페이지가 리플로우되고 스크롤 위치가 흐트러진다. 윈도우를 분리하면 WebView 뷰포트가 절대 바뀌지 않는다.

### 1-6. 상태 복원

- 설정은 전부 DataStore 에 저장(입력 후 400ms 디바운스 자동 저장).
- `was_running` 플래그 저장 → `START_STICKY` 로 프로세스가 재시작되면 이전 탐색을 자동 복원.
- Activity 는 `configChanges` 지정으로 회전 시 재생성되지 않으며, 어차피 루프는 서비스가 들고 있어 회전과 무관.
- 버블 좌표도 DataStore 에 저장 → 재시작 시 같은 자리에 복원.

### 1-7. 권한 처리

`SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE(+DATA_SYNC)` / `WAKE_LOCK` / `VIBRATE` /
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

- 첫 화면 상단에 권한 카드가 있고, 각 항목에서 해당 설정 화면 인텐트로 바로 이동한다.
- 미허용 상태여도 **크래시 없이** 안내만 뜬다.
  (`Settings.canDrawOverlays` false → 오버레이 창을 만들지 않고 로그만 남김,
   `POST_NOTIFICATIONS` 없으면 `SecurityException` 을 삼킴, 설정 인텐트 실패 시 앱 정보 화면으로 폴백)
- **AccessibilityService 는 사용하지 않는다.**

---

## 2. 올릴 파일 / 올리면 안 되는 파일

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
| `*.apk`, `*.aab`, `*.log` | 산출물 / 로그 |

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

## 3. 업로드 · 갱신 명령어 3종

> 아래 `<GITHUB_ID>` / `<REPO>` 는 본인 값으로 바꿔서 쓴다.
> 현재 이 폴더는 아직 git 저장소가 아니다. 최초 업로드부터 시작하면 된다.

### ① 최초 업로드

```bash
cd C:/why2korea/claude/macro_background_search
git init
git branch -M main
git add .
git status            # local.properties 가 목록에 없는지 반드시 확인
git commit -m "feat: 플로팅 오버레이 방식 웹 문자열 탐색 앱 신규 제작"
git remote add origin https://github.com/<GITHUB_ID>/<REPO>.git
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
> git clean -fd          # 추적되지 않는 파일까지 삭제
> ```

---

## 4. 빌드 / 실행

```bash
cd C:/why2korea/claude/macro_background_search
./gradlew.bat assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat assembleRelease        # app-release-unsigned.apk (서명 별도)
./gradlew.bat installDebug           # 연결된 기기에 설치
```

빌드 결과 (2026-08-06):
- `app-debug.apk` 약 9.3 MB — BUILD SUCCESSFUL
- `app-release-unsigned.apk` 약 6.2 MB — BUILD SUCCESSFUL (lint-vital 통과)

### 첫 실행 순서

1. 앱 실행 → 권한 카드에서 **다른 앱 위에 표시** 허용 (필수)
2. 알림 권한 허용, 배터리 최적화 제외 권장
3. 대상 URL / 1차 문자열 입력, 2차 문자열을 하나씩 **추가**
4. `오버레이 열고 탐색 시작` → 앱은 뒤로 빠지고 오버레이 패널이 뜬다
5. 패널의 `축소` 를 누르면 약 1cm 버블만 남고, 탐색은 버블 뒤에서 계속된다
6. 버블 탭 → 다시 확장 / 버블을 하단 종료 영역으로 끌면 서비스 종료


---

## 5. 실기기 검증 및 버그 수정 (2026-08-06 오후)

Galaxy Z Fold (**SM-F966N**, Android 15, 1080x2520, 420dpi) 실기기에 설치해 검증했다.

### 5-1. 발견한 버그 — 오버레이 컨트롤 바 재부착 시 터치 무반응

**증상**
확장 패널의 버튼(시작/축소/설정/종료)이 **첫 확장에서는 정상 동작**하지만,
한 번 축소했다가 버블을 탭해 다시 확장하면 **모든 버튼이 무반응**이 된다.
화면에는 정상적으로 그려지고, 크래시나 예외 로그도 전혀 남지 않는다.

**원인 규명 과정**
- `dumpsys window windows` 로 z-순서 확인 → 컨트롤 바 창(#9)이 WebView 창(#11) **위에 정상 배치**되어 있었다.
  즉 터치는 컨트롤 바 창까지 도달하고 있었고, z-순서 문제가 아니었다.
- 두 창 모두 `mHasSurface=true isReadyForDisplay()=true` → 렌더링도 정상.
- 결론: `ComposeView` 인스턴스를 `WindowManager.removeView()` 후 다시 `addView()` 로
  **다른 윈도우에 재사용**하면 그리기는 되지만 포인터 입력이 라우팅되지 않는다.

**수정**
`ViewCompositionStrategy.DisposeOnLifecycleDestroyed` 로 컴포지션을 유지한 채 뷰를 재사용하던 방식을 버리고,
표시할 때마다 `ComposeView` 를 **새로 생성**하고 숨길 때 참조를 버리도록 변경했다.
(`OverlayManager.newComposeView()` 신설, 컨트롤 바 · 발견 배너 · 종료 영역 3곳에 적용)

```
수정 파일: app/src/main/java/com/why2korea/bgsearch/overlay/OverlayManager.kt
백업:      _backup/OverlayManager.20260806.v1.kt
```

### 5-2. 실기기에서 확인된 항목

| 항목 | 결과 |
|---|---|
| 설치 / 실행 / UI 렌더링 | 정상, 크래시 없음 |
| **물리 1cm 버블 계산** | `165px` (xdpi=422.0, ydpi=421.1 → 421.6/2.54 = 166) 계산 정확 |
| 오버레이 패널 (WebView + Compose 컨트롤 바) | 정상 표시 |
| ComposeView in WindowManager 윈도우 | 동작 확인 (`OverlayLifecycleOwner` 유효) |
| Foreground Service | `isForeground=true`, `types=0x1(dataSync)`, WebView 샌드박스 프로세스 생성 확인 |
| 확장 → 축소 → 버블 | 정상, 버블 우측 가장자리 스냅 |
| **버블 뒤 화면 간섭 없음** | 홈 화면 위젯이 정상 렌더링·조작됨 (alpha 0.01 + NOT_TOUCHABLE 유효) |
| 버블 탭 → 재확장 | 정상 |
| **확장/축소 3회 왕복 후 버튼 동작** | 수정 후 PASS (수정 전 FAIL) |
| 종료 버튼 | 서비스 종료 + 오버레이 창 전부 제거 확인 |
| 서비스 지속성 | USB 분리 상태로 **약 39분** 유지, 프로세스 PID 동일, 패널 정상 |

### 5-3. 아직 확인하지 못한 항목

- **실제 탐색 루프** — URL·문자열을 넣고 돌린 적이 없다. 1차 클릭 대상 판정, 스크롤 컨테이너 인식,
  2차 스캔, 새로고침 사이클은 전부 미검증이다.
- **발견 이벤트 관련 전부** — 알림·진동·오버레이 배너·버블 뱃지·스크린샷 저장.
- **장시간(수 시간) 백그라운드 지속** 및 화면 꺼짐 상태 동작.
- 배터리 최적화 제외는 adb 로 설정할 수 없어 미적용 상태다.


---

# [v2.0.0 전면 재작성] 요구사항 재정의 반영 — 2026-08-06 오후

요구사항이 재정의되어 앱을 전면 재작성했다.

**변경 요지** — "앱 내장 WebView 안의 웹페이지를 탐색"에서
"**지금 화면에 떠 있는 다른 앱**을 탐색"으로 바뀌었다. URL 입력이 사라지고,
문자열 2종만 받아 버블로 축소한 뒤 다른 앱 화면에서 1차 문자열을 찾아 클릭하고
스크롤하며 2차 문자열을 찾는다.

**핵심 기술 변경** — 다른 앱 화면을 읽고 클릭하려면 `AccessibilityService` 외에 방법이 없다.
원래 지시서의 "AccessibilityService 사용 금지" / "타 앱 화면 텍스트를 읽지 않음" 두 조항이
명시적으로 뒤집혔고, 사용자 승인 후 재작성했다.

| | v1.0.x (폐기) | v2.0.0 (현재) |
|---|---|---|
| 탐색 대상 | 앱 내장 WebView | 화면에 떠 있는 다른 앱 |
| 입력값 | URL + 문자열 2종 | 문자열 2종 (URL 없음) |
| 읽기 | WebView + JS 인젝션 | AccessibilityService 노드 트리 |
| 클릭 | JS fireClick | ACTION_CLICK → 좌표 탭 제스처 |
| 스크롤 | JS scrollTop | ACTION_SCROLL_FORWARD → 스와이프 제스처 |
| 미발견 시 | WebView.reload() | 맨 위로 → 당겨서 새로고침 제스처 → 1차부터 재시작 |
| 스크린샷 | View.draw(Canvas) | AccessibilityService.takeScreenshot() (API 30+) |

**삭제** — `engine/InjectScripts.kt`, `engine/WebController.kt`, WebView 창, URL 입력, 페이지 로드/새로고침
**신규** — `service/ScanService.kt`(접근성 서비스), `engine/ScreenScanner.kt`, `util/TextNorm.kt`

**시작 방식 (둘 다 제공)**
- 패널 [시작] → 버블로 축소 + 카운트다운(기본 5초) → 그 사이 대상 앱으로 이동 → 자동 시작
- 버블 **탭** → 카운트다운 없이 즉시 시작/정지 토글 (이미 대상 앱을 보고 있을 때)
- 버블 **더블탭** → 패널 열기

**유지** — 1cm 물리 버블(xdpi/2.54), 드래그·가장자리 스냅·롱프레스 종료, Foreground Service +
WakeLock, DataStore 설정 저장 및 프로세스 재시작 복원, 알림 6채널 개별 on/off,
권한 미허용 시 크래시 없이 안내

**한계** — 접근성 노드를 노출하지 않는 화면(일부 게임, Canvas 직접 렌더링)은 못 읽음.
`FLAG_SECURE` 창(은행앱)은 읽기·캡처 불가. Play 스토어 등록 불가(개인 사이드로드 전용).

**검증 상태** — `assembleDebug` BUILD SUCCESSFUL. **실기기 동작은 미확인**(USB 분리).
접근성 노드 읽기 / 클릭 / 스크롤 / 당겨서 새로고침 / takeScreenshot 전부 미검증.

자세한 내용은 `README.20260806.md` 참고 (v2.0.0 기준으로 전면 재작성됨).
