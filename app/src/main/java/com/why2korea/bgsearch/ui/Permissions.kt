package com.why2korea.bgsearch.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.why2korea.bgsearch.service.ScanService

private const val TAG = "BgSearchPerm"

/**
 * 권한 확인 / 설정 화면 이동 헬퍼.
 * 어떤 경우에도 예외를 밖으로 던지지 않는다. (미허용 상태에서도 앱은 안내만 띄우고 살아 있어야 함)
 */
object Permissions {

    fun canDrawOverlays(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Throwable) {
        false
    }

    fun hasAccessibility(ctx: Context): Boolean = ScanService.isEnabled(ctx)

    fun hasNotification(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    } catch (e: Throwable) {
        false
    }

    fun openAccessibilitySettings(ctx: Context) = safeStart(ctx) {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun openOverlaySettings(ctx: Context) = safeStart(ctx) {
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + ctx.packageName)
        )
    }

    fun openAppNotificationSettings(ctx: Context) = safeStart(ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + ctx.packageName))
        }
    }

    fun requestIgnoreBatteryOptimizations(ctx: Context) = safeStart(ctx) {
        if (isIgnoringBatteryOptimizations(ctx)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + ctx.packageName))
        }
    }

    private inline fun safeStart(ctx: Context, build: () -> Intent) {
        try {
            val i = build()
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Throwable) {
            Log.w(TAG, "settings intent failed", e)
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName))
                if (ctx !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(fallback)
            } catch (e2: Throwable) {
                Log.w(TAG, "fallback settings intent failed", e2)
            }
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
