package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecgmonitor.app.ui.theme.MedicalCardBackground
import com.ecgmonitor.app.ui.theme.MedicalCyan
import com.ecgmonitor.app.ui.theme.MedicalDarkBackground
import com.ecgmonitor.app.ui.theme.MedicalDarkBlue
import com.ecgmonitor.app.ui.theme.MedicalGreen
import com.ecgmonitor.app.ui.theme.MedicalOrange
import com.ecgmonitor.app.ui.theme.MedicalTextSecondary

/**
 * Shown on first launch and whenever [ConsentManager.CONSENT_VERSION] changes.
 * The user must scroll to the bottom and explicitly accept before accessing the app.
 *
 * Regulatory basis: GDPR Article 9 — explicit consent required for health data.
 * Do not modify the consent text without incrementing [ConsentManager.CONSENT_VERSION].
 */
@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var hasScrolledToBottom by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState.value) {
        if (scrollState.value >= scrollState.maxValue - 10) {
            hasScrolledToBottom = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MedicalDarkBackground) {
        Column(modifier = Modifier.fillMaxSize()) {

            Surface(color = MedicalDarkBlue) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MedicalGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "ECG Monitor",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Before you begin, please read and accept the following",
                        fontSize = 14.sp,
                        color = MedicalTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                ConsentSection(
                    icon = Icons.Default.Storage,
                    title = "What data we collect",
                    body = "This app records ECG (electrocardiogram) electrical signals from your body via the connected sensor. Each recording stores: the raw ECG waveform, session timestamps, duration, automatically detected arrhythmia events, and derived statistics (heart rate, beat classification). No other personal information is collected. Your name, location, and contact details are never stored."
                )

                ConsentSection(
                    icon = Icons.Default.Security,
                    title = "How your data is stored",
                    body = "All data is stored locally on this device only. It is not transmitted to any server, cloud service, or third party. Data remains on your device until you delete it. Uninstalling the app permanently deletes all stored data."
                )

                ConsentSection(
                    icon = Icons.Default.Favorite,
                    title = "Medical disclaimer",
                    body = "This app is intended for personal health monitoring and informational purposes only. It is not a medical device cleared for clinical diagnosis. Results should not be used as a substitute for professional medical advice, diagnosis, or treatment. If you experience cardiac symptoms, seek medical attention immediately. The health status indicators (Normal / Attention / Concerning) are informational aids, not clinical diagnoses."
                )

                ConsentSection(
                    icon = Icons.Default.Storage,
                    title = "Your rights",
                    body = "You may withdraw consent and delete all stored data at any time via Settings → Delete All Data. Declining consent means the app cannot store recordings and most functionality will be unavailable. You can re-consent at any time from the Settings screen."
                )

                Text(
                    "Consent version 1.0 · Last updated January 2026",
                    fontSize = 11.sp,
                    color = MedicalTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Surface(color = MedicalCardBackground) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!hasScrolledToBottom) {
                        Text(
                            "Scroll to read all information before accepting",
                            fontSize = 12.sp,
                            color = MedicalOrange,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = onAccept,
                        enabled = hasScrolledToBottom,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MedicalGreen,
                            disabledContainerColor = MedicalTextSecondary.copy(alpha = 0.3f)
                        )
                    ) {
                        Text("I understand and accept", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MedicalTextSecondary)
                    ) {
                        Text("Decline")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentSection(icon: ImageVector, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MedicalCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(body, fontSize = 13.sp, color = MedicalTextSecondary, lineHeight = 20.sp)
        HorizontalDivider(color = MedicalTextSecondary.copy(alpha = 0.15f))
    }
}