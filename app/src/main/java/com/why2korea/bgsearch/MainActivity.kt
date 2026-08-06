package com.why2korea.bgsearch

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.why2korea.bgsearch.ui.MainScreen

/**
 * 설정 전용 단일 Activity.
 *
 * 탐색 루프와 WebView 는 OverlayService 가 소유하므로 이 Activity 가 죽어도 탐색은 계속된다.
 * android:configChanges 를 지정해 회전 시 재생성도 일어나지 않는다.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BgSearchTheme {
                MainScreen()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 로그인 세션(쿠키) 유실 방지
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
    }
}

@Composable
fun BgSearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
