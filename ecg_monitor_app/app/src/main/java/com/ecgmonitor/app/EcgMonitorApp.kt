package com.ecgmonitor.app

import android.app.Application
import com.github.mikephil.charting.BuildConfig
import timber.log.Timber

class EcgMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("ECG Monitor App initialized")
    }
}
