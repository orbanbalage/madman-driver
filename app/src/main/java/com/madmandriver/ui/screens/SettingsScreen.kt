package com.madmandriver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.madmandriver.Graph
import com.madmandriver.data.Sensitivity

@Composable
fun SettingsScreen() {
    val settings by Graph.settings.flow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Detection sensitivity", style = MaterialTheme.typography.titleMedium)
        Column {
            Sensitivity.entries.forEach { sensitivity ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.sensitivity == sensitivity,
                        onClick = { Graph.settings.update { it.copy(sensitivity = sensitivity) } },
                    )
                    Text(sensitivity.label)
                }
            }
        }
        Text(
            "Events trigger when smoothed horizontal acceleration exceeds the threshold. " +
                "~1.5–2 m/s² already forces standing passengers to brace; ~3 m/s² is a fall risk.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        SettingSwitch(
            title = "Vibrate on detected event",
            checked = settings.hapticFeedback,
            onChange = { v -> Graph.settings.update { it.copy(hapticFeedback = v) } },
        )
        SettingSwitch(
            title = "Keep screen on while recording",
            checked = settings.keepScreenOn,
            onChange = { v -> Graph.settings.update { it.copy(keepScreenOn = v) } },
        )

        HorizontalDivider()

        OutlinedTextField(
            value = settings.agencyEmail,
            onValueChange = { v -> Graph.settings.update { it.copy(agencyEmail = v) } },
            label = { Text("Report recipient e-mail") },
            supportingText = { Text("Transport operator address used by the “Report” button") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        Text(
            "All data stays on this device until you explicitly share it. Measurements are " +
                "indicative — smartphone sensors are not calibrated instruments.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
