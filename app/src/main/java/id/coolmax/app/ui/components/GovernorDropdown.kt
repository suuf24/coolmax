package id.coolmax.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.coolmax.app.R
import androidx.compose.ui.res.stringResource

/**
 * Material 3 dropdown for `scaling_governor`.
 *
 * Options always come from the live `scaling_available_governors` list of the target cluster, so the
 * user can never pick a governor the kernel does not expose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernorDropdown(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.governor_dropdown_label_short),
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val safeOptions = options.distinct().sorted()
    val displayValue = selected.ifBlank { safeOptions.firstOrNull().orEmpty() }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        NeuTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            if (safeOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.governor_none_available)) },
                    onClick = { expanded = false }
                )
            }
            safeOptions.forEach { governor ->
                DropdownMenuItem(
                    text = { Text(governor) },
                    onClick = {
                        expanded = false
                        if (governor != selected) onSelected(governor)
                    }
                )
            }
        }
    }
}
