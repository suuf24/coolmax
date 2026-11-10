package id.coolmax.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuOutlinedButton
import id.coolmax.app.ui.components.NeuSurface
import id.coolmax.app.ui.components.NeuVariant
import id.coolmax.app.ui.theme.LocalNeuPalette

/**
 * Displayed when `libsu` reports that root access is not available or was denied by the user.
 * Never crashes; provides clear instructions, a Retry button, and a Developer Dry-Run option.
 */
@Composable
fun RootRequiredScreen(
    onRetryRootCheck: () -> Unit,
    onContinueInDryRun: () -> Unit
) {
    val palette = LocalNeuPalette.current
    NeuSurface(modifier = Modifier.fillMaxSize(), variant = NeuVariant.FLAT) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NeuSurface(
                modifier = Modifier.size(104.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.root_required_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = palette.onBase
            )
            Spacer(modifier = Modifier.height(14.dp))
            NeuCard(modifier = Modifier.fillMaxWidth(), variant = NeuVariant.INSET) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.root_required_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted
                    )
                }
            }
            Spacer(modifier = Modifier.height(26.dp))
            NeuButton(
                onClick = onRetryRootCheck,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.btn_retry_root))
            }
            Spacer(modifier = Modifier.height(12.dp))
            NeuOutlinedButton(
                onClick = onContinueInDryRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.btn_try_dry_run_mode))
            }
        }
    }
}
