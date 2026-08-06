package com.why2korea.bgsearch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.why2korea.bgsearch.MainActivity
import com.why2korea.bgsearch.R

private const val TAG = "BgSearchNotifier"

/** 알림 채널 생성 / 상시 알림 / 발견 알림 / 진동 담당. */
class Notifier(private val ctx: Context) {

    companion object {
        const val CHANNEL_STATUS = "bgsearch_status"
        const val CHANNEL_FOUND = "bgsearch_found"

        const val NOTI_ONGOING = 2001
        const val NOTI_FOUND = 2002
        const val NOTI_WARN = 2003

        /** 발견 시 진동 패턴: 1초 진동 × 3회 */
        val VIBRATE_PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000)
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                ctx.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.channel_status_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOUND,
                ctx.getString(R.string.channel_found_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_found_desc)
                enableVibration(true)
                vibrationPattern = VIBRATE_PATTERN
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    // ------------------------------------------------------------------ PendingIntent

    private fun contentIntent(): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String, req: Int): PendingIntent {
        val i = Intent(ctx, OverlayService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ------------------------------------------------------------------ 알림

    fun buildOngoing(status: String, running: Boolean): Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_search)
            .setContentTitle(if (running) "백그라운드 탐색 중" else "탐색 대기 중")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent())
        if (running) {
            b.addAction(0, "정지", serviceIntent(OverlayService.ACTION_STOP_SEARCH, 11))
        } else {
            b.addAction(0, "시작", serviceIntent(OverlayService.ACTION_START_SEARCH, 12))
        }
        b.addAction(0, "패널", serviceIntent(OverlayService.ACTION_EXPAND, 13))
        b.addAction(0, "종료", serviceIntent(OverlayService.ACTION_EXIT, 14))
        return b.build()
    }

    fun notifyOngoing(status: String, running: Boolean) = safeNotify(NOTI_ONGOING) {
        buildOngoing(status, running)
    }

    fun notifyFound(texts: List<String>, rowText: String, timeText: String, sound: Boolean) {
        val body = texts.joinToString(", ") +
            (if (rowText.isBlank()) "" else "\n\n[줄] " + rowText.take(120)) +
            "\n" + timeText
        val b = NotificationCompat.Builder(ctx, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_stat_search)
            .setContentTitle("문자열 발견!")
            .setContentText(texts.joinToString(", "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .addAction(0, "계속", serviceIntent(OverlayService.ACTION_RESUME_SEARCH, 15))
            .addAction(0, "정지", serviceIntent(OverlayService.ACTION_STOP_SEARCH, 16))
        if (sound) {
            b.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
        }
        safeNotify(NOTI_FOUND) { b.build() }
    }

    fun cancelFound() {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTI_FOUND)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    fun notifyWarn(message: String) = safeNotify(NOTI_WARN) {
        NotificationCompat.Builder(ctx, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_stat_search)
            .setContentTitle("탐색 경고")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private inline fun safeNotify(id: Int, build: () -> Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 미허용. 크래시 없이 무시한다.
            Log.w(TAG, "no notification permission", e)
        } catch (e: Throwable) {
            Log.w(TAG, "notify failed", e)
        }
    }

    // ------------------------------------------------------------------ 진동

    fun vibrate() {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            if (vib == null || !vib.hasVibrator()) return
            vib.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, -1))
        } catch (e: Throwable) {
            Log.w(TAG, "vibrate failed", e)
        }
    }
}
