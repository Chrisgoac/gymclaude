package com.chrisgoac.gymlog.timer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * POST_NOTIFICATIONS (API 33+) is asked in context: the first time a rest timer starts, once.
 * Declining keeps the timer working in the app; only the background notification is lost.
 */
object NotificationPermissionPrompt {
    private const val PREFS = "gymlog.timer"
    private const val ASKED = "notificationPermissionAsked"

    fun shouldPrompt(sdkInt: Int, granted: Boolean, askedBefore: Boolean) = sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore

    /** Records the ask and returns true when the caller must launch the system request now. */
    fun consumePrompt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!shouldPrompt(Build.VERSION.SDK_INT, granted, prefs.getBoolean(ASKED, false))) return false
        prefs.edit { putBoolean(ASKED, true) }
        return true
    }
}
