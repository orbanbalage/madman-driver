package com.madmandriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madmandriver.city.City
import com.madmandriver.city.TransitMode

/**
 * Mode / line / vehicle-number inputs, shared by the new-ride screen and the
 * post-recording edit dialog so both offer the same line suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RideDetailsEditor(
    city: City,
    mode: TransitMode,
    onModeChange: (TransitMode) -> Unit,
    line: String,
    onLineChange: (String) -> Unit,
    vehicleNumber: String,
    onVehicleNumberChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            city.modes.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = city.modes.size),
                ) {
                    Text(m.label)
                }
            }
        }

        OutlinedTextField(
            value = line,
            onValueChange = onLineChange,
            label = { Text("Line number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val suggestions = city.suggestedLines[mode].orEmpty()
        if (suggestions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = line.trim()
                suggestions.forEach { s ->
                    LineChip(
                        line = s,
                        color = Color(city.lineColorArgb(s)),
                        selected = s == current,
                        onClick = { onLineChange(s) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = vehicleNumber,
            onValueChange = onVehicleNumberChange,
            label = { Text("Vehicle number (optional)") },
            supportingText = {
                Text("Fleet number printed on the vehicle — helps the operator identify the driver")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Line number badge in the operator's own livery — colours mirror the line
 * badges on the city's transit portal, so the chip looks like the number the
 * passenger sees on the vehicle. White text is what the portal pairs with every
 * one of these colours.
 */
@Composable
private fun LineChip(line: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val outline = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
            .clip(shape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, outline, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            line,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Dialog for correcting a recorded ride's line, mode and vehicle number. */
@Composable
fun EditRideDialog(
    city: City,
    initialMode: TransitMode,
    initialLine: String,
    initialVehicleNumber: String?,
    onDismiss: () -> Unit,
    onSave: (mode: TransitMode, line: String, vehicleNumber: String?) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var line by rememberSaveable { mutableStateOf(initialLine) }
    var vehicle by rememberSaveable { mutableStateOf(initialVehicleNumber.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ride details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RideDetailsEditor(
                    city = city,
                    mode = mode,
                    onModeChange = { mode = it },
                    line = line,
                    onLineChange = { line = it },
                    vehicleNumber = vehicle,
                    onVehicleNumberChange = { vehicle = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(mode, line.trim(), vehicle.trim().ifBlank { null }) },
                enabled = line.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
