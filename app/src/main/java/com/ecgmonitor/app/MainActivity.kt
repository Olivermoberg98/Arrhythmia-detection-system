package com.ecgmonitor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ecgmonitor.app.ui.screens.LiveMonitorScreen
import com.ecgmonitor.app.ui.theme.EcgMonitorTheme
import com.ecgmonitor.app.ui.viewmodels.LiveMonitorViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: LiveMonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            EcgMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LiveMonitorScreen(viewModel = viewModel)
                }
            }
        }

        Timber.d("MainActivity created")
    }
}
