package org.meshtastic.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val DangerRed = Color(0xFFB71C1C)
private val DangerRedLight = Color(0xFFE53935)

@Composable
fun EmergencyWipeSection() {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showFinalConfirmDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val wipeManager: EmergencyWipeHandler = koinInject()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0000)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DangerRed)
    ) {
        Button(
            onClick = { showConfirmDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "⚠ EMERGENCY DATA WIPE",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
        }
    }

    // First confirmation
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    "⚠ EMERGENCY WIPE",
                    color = DangerRedLight,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This will permanently delete ALL mission data, messages, " +
                            "zones, and settings.\n\n" +
                            "The application will be disabled and cannot be reopened " +
                            "until reinstalled.\n\n" +
                            "This action CANNOT be undone.",
                    color = Color.White
                )
            },
            containerColor = Color(0xFF1A0000),
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        showFinalConfirmDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("CONTINUE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("CANCEL", color = Color.Gray)
                }
            }
        )
    }

    // Final confirmation — must type to confirm
    if (showFinalConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showFinalConfirmDialog = false },
            title = {
                Text(
                    "FINAL WARNING",
                    color = DangerRedLight,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you absolutely sure?\n\n" +
                            "Tapping WIPE NOW will immediately destroy all data " +
                            "and disable this application.",
                    color = Color.White
                )
            },
            containerColor = Color(0xFF1A0000),
            confirmButton = {
                Button(
                    onClick = {
                        showFinalConfirmDialog = false
                        scope.launch {
                            wipeManager.executeEmergencyWipe()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text(
                        "WIPE NOW",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalConfirmDialog = false }) {
                    Text("CANCEL", color = Color.Gray)
                }
            }
        )
    }
}