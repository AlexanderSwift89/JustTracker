package io.treklog.app.ui.poi

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.treklog.app.R
import io.treklog.app.domain.poi.PoiKind
import io.treklog.app.util.UnitFormatter

/**
 * Bottom sheet with the place description and the "Read aloud" action (docs/04_ux_design.md §2.7).
 * Shown by the Record and Detail screens whenever [state] is non-null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiCard(
    state: PoiCardState,
    formatter: UnitFormatter,
    onDismiss: () -> Unit,
    onToggleSpeak: () -> Unit,
) {
    val context = LocalContext.current
    val pageUrl = state.summary?.pageUrl ?: state.poi.wikipedia.pageUrl

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(state.poi.name, style = MaterialTheme.typography.titleLarge)
            val subtitle = buildString {
                append(stringResource(state.poi.kind.labelRes()))
                state.distanceM?.let { append(" · ").append(formatter.distance(it)) }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 260.dp),
            ) {
                when (state.status) {
                    SummaryStatus.LOADING -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                    )
                    SummaryStatus.READY -> Text(
                        state.summary?.extract.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    SummaryStatus.UNAVAILABLE -> Text(
                        stringResource(R.string.poi_summary_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.ttsAvailable) {
                    Button(onClick = onToggleSpeak, enabled = state.status != SummaryStatus.LOADING, modifier = Modifier.height(48.dp)) {
                        Icon(if (state.speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (state.speaking) R.string.poi_stop_reading else R.string.poi_read_aloud))
                    }
                }
                TextButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pageUrl.toUri())) } },
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.poi_open_wikipedia))
                }
            }
            Text(
                stringResource(R.string.poi_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
        }
    }
}

fun PoiKind.labelRes(): Int = when (this) {
    PoiKind.MUSEUM -> R.string.poi_kind_museum
    PoiKind.ATTRACTION -> R.string.poi_kind_attraction
    PoiKind.HISTORIC -> R.string.poi_kind_historic
    PoiKind.WORSHIP -> R.string.poi_kind_worship
    PoiKind.NATURE -> R.string.poi_kind_nature
    PoiKind.PLACE -> R.string.poi_kind_place
}
