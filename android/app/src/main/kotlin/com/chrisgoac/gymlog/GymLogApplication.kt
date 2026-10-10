package com.chrisgoac.gymlog

import android.app.Application

/** Process entry point; owns the manual-DI [AppContainer] (which initializes Clerk). */
class GymLogApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
