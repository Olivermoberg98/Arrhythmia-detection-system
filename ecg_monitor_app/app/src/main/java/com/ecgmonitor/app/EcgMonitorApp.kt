package com.ecgmonitor.app

import android.app.Application
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.firebase.FirebaseRepository
import com.ecgmonitor.app.data.firebase.SyncManager
import timber.log.Timber

class EcgMonitorApp : Application() {

    lateinit var bleManager: BleManager
        private set

    lateinit var syncManager: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())
        bleManager = BleManager(this)

        val database = EcgDatabase.getDatabase(this)
        val consentManager = ConsentManager(this)
        val firebaseRepository = FirebaseRepository()

        syncManager = SyncManager(
            database = database,
            firebaseRepository = firebaseRepository,
            consentManager = consentManager
        )
    }
}