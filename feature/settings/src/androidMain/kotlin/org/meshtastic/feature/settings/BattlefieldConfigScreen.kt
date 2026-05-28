package org.meshtastic.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.meshtastic.core.prefs.BattlefieldPrefs
import org.meshtastic.core.ui.component.MainAppBar

private val MilitaryGreen = Color(0xFF4CAF50)
private val DarkBackground = Color(0xFF0F1A0F)
private val TextColor = Color(0xFFE8F5E9)
private val SubTextColor = Color(0xFF81C784)

// Simple local data class — no dependency on androidApp
private data class UnitInfo(val code: String, val displayName: String, val drawableName: String)

private val unitTypes = listOf(
    UnitInfo("S", "Soldier", "soldier_marker"),
    UnitInfo("T", "Tank", "marker_tank"),
    UnitInfo("D", "Drone", "marker_drone"),
    UnitInfo("H", "Heli", "marker_heli"),
)

@Composable
fun BattlefieldConfigScreen(onNavigateUp: () -> Unit) {
    val prefs: BattlefieldPrefs = koinInject()
    val currentOperationId by prefs.operationId.collectAsStateWithLifecycle()
    val currentMissionId by prefs.missionId.collectAsStateWithLifecycle()
    val currentUnitTypeCode by prefs.unitTypeCode.collectAsStateWithLifecycle()

    var draftOperationId by rememberSaveable(currentOperationId) { mutableStateOf(currentOperationId) }
    var draftMissionId by rememberSaveable(currentMissionId) { mutableStateOf(currentMissionId) }
    var draftUnitTypeCode by rememberSaveable(currentUnitTypeCode) { mutableStateOf(currentUnitTypeCode) }

    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            MainAppBar(
                title = "Battlefield Config",
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                actions = {},
                onClickChip = {},
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "OPERATION IDENTITY",
                color = MilitaryGreen,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = draftOperationId,
                onValueChange = { draftOperationId = it },
                label = { Text("Operation ID", color = SubTextColor) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MilitaryGreen,
                    unfocusedBorderColor = SubTextColor,
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor,
                    cursorColor = MilitaryGreen,
                    focusedLabelColor = MilitaryGreen,
                )
            )

            OutlinedTextField(
                value = draftMissionId,
                onValueChange = { draftMissionId = it },
                label = { Text("Mission ID", color = SubTextColor) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MilitaryGreen,
                    unfocusedBorderColor = SubTextColor,
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor,
                    cursorColor = MilitaryGreen,
                    focusedLabelColor = MilitaryGreen,
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "UNIT TYPE",
                color = MilitaryGreen,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                unitTypes.forEach { unit ->
                    UnitCard(
                        unit = unit,
                        isSelected = draftUnitTypeCode == unit.code,
                        onClick = { draftUnitTypeCode = unit.code }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    prefs.setOperationId(draftOperationId)
                    prefs.setMissionId(draftMissionId)
                    prefs.setUnitTypeCode(draftUnitTypeCode)
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MilitaryGreen)
            ) {
                Text(
                    text = "SAVE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

@Composable
private fun UnitCard(unit: UnitInfo, isSelected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(unit.drawableName) {
        try {
            val resId = context.resources.getIdentifier(unit.drawableName, "drawable", context.packageName)
            if (resId != 0) {
                val original = BitmapFactory.decodeResource(context.resources, resId)
                if (original != null) Bitmap.createScaledBitmap(original, 120, 120, true) else null
            } else null
        } catch (e: Exception) { null }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) Modifier.border(BorderStroke(2.dp, MilitaryGreen), RoundedCornerShape(8.dp))
                else Modifier.border(BorderStroke(1.dp, Color(0xFF3A4A3A)), RoundedCornerShape(8.dp))
            )
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = unit.displayName,
                modifier = Modifier.size(64.dp)
            )
        } else {
            Text(unit.code, color = MilitaryGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = unit.displayName.uppercase(),
            color = if (isSelected) MilitaryGreen else SubTextColor,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.5.sp
        )
    }
}