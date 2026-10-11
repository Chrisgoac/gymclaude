package com.chrisgoac.gymlog.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log

/** The foreground [Activity] behind a (possibly wrapped) Compose context; features never see Android types. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Opens [uri] (Play's subscription page) outside the app; a device without a handler just does nothing. */
internal fun openExternalUri(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("GymLogBilling", "No app can open the subscription page", e)
    }
}
