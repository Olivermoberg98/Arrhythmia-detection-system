package com.ecgmonitor.app

import android.app.Application
import com.github.mikephil.charting.BuildConfig
import timber.log.Timber
import kotlin.getValue
import kotlin.lazy
import com.ecgmonitor.app.data.ble.BleManager

class EcgMonitorApp : Application() {
    val bleManager by lazy { BleManager(this) }
    override fun onCreate() {
        super.onCreate()
        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        Timber.i("ECG Monitor App initialized")
    }
}
