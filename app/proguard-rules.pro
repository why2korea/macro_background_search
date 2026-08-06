# WebView JavaScript bridge 를 쓰게 될 경우를 대비해 브리지 멤버를 보존한다.
-keepclassmembers class com.why2korea.bgsearch.engine.** {
    @android.webkit.JavascriptInterface <methods>;
}
