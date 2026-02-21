package com.ecgmonitor.app

import android.app.Application
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.firebase.FirebaseRepository
import com.ecgmonitor.app.data.firebase.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class EcgMonitorApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var bleManager: BleManager
        private set

    lateinit var syncManager: SyncManager
        private set

    lateinit var consentManager: ConsentManager
        private set

    lateinit var firebaseRepository: FirebaseRepository
        private set

    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())
        bleManager = BleManager(this)

        val database = EcgDatabase.getDatabase(this)
        consentManager = ConsentManager(this)
        firebaseRepository = FirebaseRepository()

        syncManager = SyncManager(
            database = database,
            firebaseRepository = firebaseRepository,
            consentManager = consentManager
        )

        // Initialize on app startup
        applicationScope.launch {
            syncManager.initialise()
            Timber.i("SyncManager initialized on app startup")
        }
    }
}