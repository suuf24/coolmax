package id.coolmax.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.domain.thermal.ThermalGuardConfig
import id.coolmax.app.domain.thermal.ThermalThrottlePlanner
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuSlider
import id.coolmax.app.ui.components.NeuStatusPill
import id.coolmax.app.ui.components.NeuSwitch
import id.coolmax.app.ui.components.NeuTextField
import id.coolmax.app.ui.components.NeuVariant
import id.coolmax.app.ui.theme.LocalNeuPalette
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated thermal menu: the custom limit used to live inside the Screen-Off tab, but it is a global
 * safety ceiling (it caps every other profile), so it gets its own sidebar entry.
 */
@Composable
fun ThermalScreen(viewModel: CoolMaxViewModel) {
    val triggerSettings by viewModel.triggerSettings.collectAsState()
    val currentTemperature by viewModel.currentMaxTemperature.collectAsState()
    val throttling by viewModel.thermalThrottleActive.collectAsState()
    val thermals by viewModel.thermalReadings.collectAsState()
    val palette = LocalNeuPalette.current

    val limit = triggerSettings.thermalLimitCelsius
    val enabled = triggerSettings.thermalGuardEnabled
    var limitText by rememberSaveable { mutableStateOf(limit.roundToInt().toString()) }

    LaunchedEffect(limit) {
        if (limitText.toFloatOrNull()?.roundToInt() != limit.roundToInt()) {
            limitText = limit.roundToInt().toString()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live temperature + throttle status
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.thermal_screen_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTemperature?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-- °C",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    currentTemperature == null -> palette.muted
                                    throttling -> palette.danger
                                    currentTemperature!! >= limit -> palette.warning
                                    else -> palette.accent
                                }
                            )
                            Text(
                                text = if (thermals.isEmpty()) {
                                    stringResource(R.string.thermal_sensor_unavailable)
                                } else {
                                    thermals.take(3).joinToString(" • ") {
                                        "${it.type} ${String.format(Locale.US, "%.1f°C", it.tempCelsius)}"
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted
                            )
                        }
                        NeuStatusPill(
                            text = stringResource(
                                if (throttling) R.string.thermal_status_throttling else R.string.thermal_status_normal
                            ),
                            color = if (throttling) palette.danger else palette.success
                        )
                    }
                    Text(
                        text = stringResource(R.string.thermal_screen_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted
                    )
                }
            }
        }

        // Controls
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.thermal_guard_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.thermal_guard_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted
                            )
                        }
                        NeuSwitch(
                            checked = enabled,
                            onCheckedChange = viewModel::setThermalGuardEnabled
                        )
                    }

                    Text(
                        text = stringResource(R.string.thermal_limit_label_format, limit),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    NeuSlider(
                        value = limit,
                        onValueChange = viewModel::setThermalLimitCelsius,
                        valueRange = ThermalGuardConfig.MIN_LIMIT_CELSIUS..ThermalGuardConfig.MAX_LIMIT_CELSIUS,
                        steps = (ThermalGuardConfig.MAX_LIMIT_CELSIUS - ThermalGuardConfig.MIN_LIMIT_CELSIUS).toInt() - 1,
                        enabled = enabled
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeuTextField(
                            value = limitText,
                            onValueChange = { raw ->
                                limitText = raw.filter { it.isDigit() }.take(2)
                                limitText.toFloatOrNull()?.let(viewModel::setThermalLimitCelsius)
                            },
                            label = { Text(stringResource(R.string.thermal_input_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        )
                        NeuButton(
                            onClick = { viewModel.setThermalLimitCelsius(ThermalGuardConfig.DEFAULT_LIMIT_CELSIUS) },
                            enabled = enabled,
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text(stringResource(R.string.thermal_reset_default))
                        }
                    }

                    if (ThermalThrottlePlanner.isHighRisk(limit)) {
                        Text(
                            text = stringResource(R.string.thermal_high_risk_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.danger
                        )
                    }
                    Text(
                        text = stringResource(R.string.thermal_guard_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
            }
        }

        // How the guard behaves
        item {
            NeuCard(modifier = Modifier.fillMaxWidth(), variant = NeuVariant.INSET) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.thermal_info_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    ThermalInfoRow(stringResource(R.string.thermal_info_stepdown))
                    ThermalInfoRow(stringResource(R.string.thermal_info_hysteresis))
                    ThermalInfoRow(stringResource(R.string.thermal_info_floor))
                    ThermalInfoRow(stringResource(R.string.thermal_info_priority))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.thermal_info_polling),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun ThermalInfoRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "•", style = MaterialTheme.typography.bodySmall, color = LocalNeuPalette.current.accent)
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}
