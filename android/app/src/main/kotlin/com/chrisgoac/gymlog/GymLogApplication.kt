package com.chrisgoac.gymlog

import android.app.Application
import androidx.work.Configuration

/** Process entry point; owns the manual-DI [AppContainer] (which initializes Clerk). */
class GymLogApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // After `container` is assigned: starting sync may touch WorkManager, which reads it above.
        container.startSync()
    }
}
