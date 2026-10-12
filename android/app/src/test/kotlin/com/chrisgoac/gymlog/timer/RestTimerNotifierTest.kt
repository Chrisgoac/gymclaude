package com.chrisgoac.gymlog.timer

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.Lifecycle
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RestTimerNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifier = RestTimerNotifier(context)
    private val end = 5_000_000L

    @Before fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    @Test fun `exact alarm is used only when the system already allows it`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifier.schedule("run", "s1", end)
        val exact = alarms.scheduledAlarms.single()
        assertEquals(end, exact.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, exact.type)
        assertEquals("exact: zero-length window", 0L, exact.windowLengthMs)
        assertTrue(exact.allowWhileIdle)
    }

    @Test fun `without exact alarm access it falls back to an inexact while-idle alarm and never throws`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        notifier.schedule("run", "s1", end)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(end, alarm.triggerAtMs)
        assertEquals("inexact: system-chosen window", -1L, alarm.windowLengthMs)
        assertTrue(alarm.allowWhileIdle)
    }

    @Test fun `rescheduling replaces the previous alarm and cancel removes it`() {
        notifier.schedule("run1", "s1", end)
        notifier.schedule("run2", "s1", end + 30_000)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(end + 30_000, alarms.scheduledAlarms.single().triggerAtMs)
        notifier.cancel()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun `manifest does not request exact alarm permissions`() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse(requested.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(requested.contains("android.permission.USE_EXACT_ALARM"))
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun `the receiver raises one functional notification on a quiet channel and cancel dismisses it`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.postFinished("s1")
        val channel = manager.getNotificationChannel(RestTimerNotifier.CHANNEL_ID)!!
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertFalse(channel.canShowBadge())
        val posted = shadowOf(manager).allNotifications.single()
        assertNotNull(posted.contentIntent)
        assertEquals("Descanso terminado", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        notifier.cancel()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `nothing is posted when notifications are off`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        notifier.postFinished("s1")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `a running controller schedules, pause schedules nothing more`() = runBlocking {
        var now = 1_000L
        val controller = RestTimerController(MemoryStore(), notifier, clock = { now }, newRunId = { "run" })
        controller.start("s1", 60)
        assertEquals(1, alarms.scheduledAlarms.size)
        controller.pause()
        assertTrue("paused: no alarm armed", alarms.scheduledAlarms.isEmpty())
        now += 10_000
        controller.restore("s1")
        assertTrue("restoring a paused timer arms nothing", alarms.scheduledAlarms.isEmpty())
        controller.resume()
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun `the receiver stays silent only while the workout screen is visible`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val intent = android.content.Intent(RestTimerNotifier.ACTION_REST_DONE).putExtra(RestTimerNotifier.EXTRA_SESSION_ID, "s1")
        RestTimerReceiver().handle(context, intent, workoutScreenVisible = true)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        // App in the foreground on another screen (or in the background): the notification is the alert.
        RestTimerReceiver().handle(context, intent, workoutScreenVisible = false)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun `onReceive reads the workout screen visibility, not the process state`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val owner = TestLifecycleOwner()
        val intent = android.content.Intent(RestTimerNotifier.ACTION_REST_DONE).putExtra(RestTimerNotifier.EXTRA_SESSION_ID, "s1")

        owner.registry.currentState = Lifecycle.State.STARTED
        WorkoutScreenVisibility.track(owner)
        RestTimerReceiver().onReceive(context, intent)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        owner.registry.currentState = Lifecycle.State.CREATED // user went to another tab / screen
        RestTimerReceiver().onReceive(context, intent)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertFalse(WorkoutScreenVisibility.isVisible)
    }

    private class TestLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class MemoryStore : com.chrisgoac.gymlog.domain.timer.RestTimerStore {
        var saved: com.chrisgoac.gymlog.domain.timer.RestTimerState? = null
        override suspend fun read() = saved
        override suspend fun write(state: com.chrisgoac.gymlog.domain.timer.RestTimerState?) { saved = state }
    }
}
