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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
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
                // Each badge carries its own half-gap as touch padding, so the
                // rows themselves add no spacing.
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(0.dp),
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

/*
 * Badge geometry lifted from imhd.sk's own line badges, measured on its mobile
 * layout (375 px viewport, where 1 CSS px maps to 1 dp): a 35.15 px box whose
 * 19 px radius makes it a full circle, holding 19 px bold Roboto — Android's
 * default face — in white, with 2.375 px of inner padding and 3.8 px of margin
 * per side, so adjacent badges sit 7.6 px apart.
 */
private val BadgeSize = 35.15f.dp
private val BadgePadding = 2.375f.dp
private val BadgeGap = 7.6f.dp
private val BadgeFontSize = 19f.sp

/**
 * Line number badge in the operator's own livery, sized to match the badges on
 * the city's transit portal, so the chip reads like the number on the front of
 * the vehicle. [defaultMinSize] rather than a fixed size so the circle can still
 * grow at large system font scales instead of clipping the digits.
 *
 * The badge is drawn at imhd's exact 35.15 dp, but the click target is the outer
 * box — the circle plus the margin that supplies the gap between neighbours —
 * giving roughly 43 dp to aim at on a moving vehicle without altering a pixel of
 * the artwork. Clipping that outer box to a circle as well keeps the ripple round
 * and leaves diagonal neighbours un-abutted.
 */
@Composable
private fun LineChip(line: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(BadgeGap / 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = BadgeSize, minHeight = BadgeSize)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(2.dp, outline, CircleShape) else Modifier)
                .padding(BadgePadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                line,
                color = Color.White,
                fontSize = BadgeFontSize,
                fontWeight = FontWeight.Bold,
            )
        }
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
