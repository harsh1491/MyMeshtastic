package org.meshtastic.app.battlefield

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

private val MilitaryGreen = Color(0xFF4CAF50)
private val SectionBackground = Color(0xFF1B2A1B)
private val TextColor = Color(0xFFE8F5E9)
private val SubTextColor = Color(0xFF81C784)

@Composable
fun BattlefieldConfigSection(
    operationId: String,
    missionId: String,
    selectedUnitTypeCode: String,
    onOperationIdChange: (String) -> Unit,
    onMissionIdChange: (String) -> Unit,
    onUnitTypeSelected: (UnitType) -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SectionBackground),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MilitaryGreen)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "⚔ BATTLEFIELD CONFIG",
                color = MilitaryGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )

            OutlinedTextField(
                value = operationId,
                onValueChange = onOperationIdChange,
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
                value = missionId,
                onValueChange = onMissionIdChange,
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

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "UNIT TYPE",
                color = SubTextColor,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                UnitType.entries.forEach { unitType ->
                    UnitTypeCard(
                        unitType = unitType,
                        isSelected = selectedUnitTypeCode == unitType.code,
                        onClick = { onUnitTypeSelected(unitType) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitTypeCard(
    unitType: UnitType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    val bitmap = remember(unitType.drawableName) {
        loadBitmapWithoutBlackBackground(context, unitType.drawableName)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) {
                    Modifier.border(BorderStroke(2.dp, MilitaryGreen), RoundedCornerShape(8.dp))
                } else {
                    Modifier.border(BorderStroke(1.dp, Color(0xFF3A4A3A)), RoundedCornerShape(8.dp))
                }
            )
            .clickable { onClick() }
            .padding(6.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = unitType.displayName,
                modifier = Modifier.size(48.dp)
            )
        } else {
            Text(
                text = unitType.code,
                color = MilitaryGreen,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = unitType.displayName.uppercase(),
            color = if (isSelected) MilitaryGreen else SubTextColor,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.5.sp
        )
    }
}

fun loadBitmapWithoutBlackBackground(
    context: android.content.Context,
    drawableName: String
): Bitmap? {
    return try {
        val resId = context.resources.getIdentifier(
            drawableName, "drawable", context.packageName
        )
        if (resId == 0) return null

        val original = BitmapFactory.decodeResource(context.resources, resId) ?: return null
        val mutable = original.copy(Bitmap.Config.ARGB_8888, true)

        val width = mutable.width
        val height = mutable.height
        val pixels = IntArray(width * height)
        mutable.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r < 40 && g < 40 && b < 40) {
                pixels[i] = android.graphics.Color.TRANSPARENT
            }
        }

        mutable.setPixels(pixels, 0, width, 0, 0, width, height)
        Bitmap.createScaledBitmap(mutable, 80, 80, true)
    } catch (e: Exception) {
        android.util.Log.e("BattlefieldConfig", "Failed to load: $drawableName", e)
        null
    }
}