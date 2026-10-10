package com.chrisgoac.gymlog.timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.chrisgoac.gymlog.MainActivity
import com.chrisgoac.gymlog.R
import com.chrisgoac.gymlog.domain.timer.RestTimerAlarm

/**
 * Wakes the device at the end of a rest and raises one local, functional notification ("Descanso
 * terminado"; tap opens the workout). No remote or promotional notifications exist in the app.
 *
 * Scheduling: `setExactAndAllowWhileIdle` only when the system already lets the app use exact
 * alarms (`canScheduleExactAlarms()`, API 31+); otherwise `setAndAllowWhileIdle` (may fire a few
 * minutes late under Doze). The app never declares or requests exact-alarm permissions.
 */
class RestTimerNotifier(context: Context) : RestTimerAlarm {
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)

    override fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long) {
        val intent = alarmPendingIntent(runId, sessionId)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMillis, intent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMillis, intent)
            }
        } catch (_: SecurityException) {
            // The exact-alarm right was revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMillis, intent)
        }
    }

    override fun cancel() {
        alarmManager.cancel(alarmPendingIntent(runId = "", sessionId = ""))
        notifications.cancel(NOTIFICATION_ID)
    }

    /** Raises the end-of-rest notification (no-op when the user has notifications off). */
    fun postFinished(sessionId: String?) {
        if (!notifications.areNotificationsEnabled()) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_WORKOUT, sessionId)
        val tap = PendingIntent.getActivity(context, REQUEST_OPEN, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rest)
            .setContentTitle(context.getString(R.string.rest_notification_title))
            .setContentText(context.getString(R.string.rest_notification_text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // nothing personal in it
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) != null) return
        // DEFAULT: one short sound at the end of a rest, no heads-up interruptions; the user can mute it in system settings.
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.rest_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.rest_channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun alarmPendingIntent(runId: String, sessionId: String): PendingIntent {
        val intent = Intent(context, RestTimerReceiver::class.java)
            .setAction(ACTION_REST_DONE)
            .putExtra(EXTRA_RUN_ID, runId)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(context, REQUEST_ALARM, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "rest_timer"
        const val NOTIFICATION_ID = 4001
        const val ACTION_REST_DONE = "com.chrisgoac.gymlog.action.REST_DONE"
        const val EXTRA_RUN_ID = "runId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_OPEN_WORKOUT = "openWorkoutSessionId"
        private const val REQUEST_ALARM = 1
        private const val REQUEST_OPEN = 2
        private const val NOTIFICATION_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}

/** Receives the alarm of [RestTimerNotifier] (not exported). */
class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RestTimerNotifier.ACTION_REST_DONE) return
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        handle(context, intent, appInForeground = foreground)
    }

    /** With the app on screen the in-app bar (haptic + announcement) already tells the user: no notification. */
    internal fun handle(context: Context, intent: Intent, appInForeground: Boolean) {
        if (appInForeground) return
        RestTimerNotifier(context).postFinished(intent.getStringExtra(RestTimerNotifier.EXTRA_SESSION_ID))
    }
}
