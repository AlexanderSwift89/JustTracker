package com.justtracker.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.track.AccelerationEpisode
import com.justtracker.app.domain.track.TrackAcceleration
import com.justtracker.app.ui.common.AccelerationColorScale
import com.justtracker.app.ui.common.ListSectionHeader
import com.justtracker.app.ui.common.icon
import com.justtracker.app.ui.common.labelRes
import com.justtracker.app.ui.common.tint
import com.justtracker.app.util.UnitFormatter

/**
 * "Speeding up and slowing down" under the stats tiles (US-24, docs/04_ux_design.md §2.13): the strongest episodes of
 * each kind, strongest first; a tap moves the scrubber to where the episode starts. "Show all (N)" lists every episode
 * in the order they happened. Shown only when the track has episodes.
 */
@Composable
internal fun EpisodesSection(
    acceleration: TrackAcceleration,
    formatter: UnitFormatter,
    onEpisode: (AccelerationEpisode) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strongest = remember(acceleration) { acceleration.strongest() }
    Column(modifier) {
        ListSectionHeader(stringResource(R.string.detail_episodes_title), Modifier.semantics { heading() })
        strongest.forEach { episode -> EpisodeRow(episode, formatter, onClick = { onEpisode(episode) }) }
        if (acceleration.episodes.size > strongest.size) {
            TextButton(onClick = onShowAll, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.detail_episodes_show_all, acceleration.episodes.size))
            }
        }
    }
}

/** Every episode in the order they happened; a tap moves the scrubber there and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodesSheet(
    acceleration: TrackAcceleration,
    formatter: UnitFormatter,
    onEpisode: (AccelerationEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.detail_episodes_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .semantics { heading() },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(acceleration.episodes) { episode ->
                EpisodeRow(episode, formatter, onClick = {
                    onEpisode(episode)
                    onDismiss()
                })
            }
        }
    }
}

/**
 * "▲ 0 → 72 km/h in 10 s" over "+2.0 m/s² · 9.4 km from start" (the peak): the arrow and its colour say the direction,
 * the speeds say it again in words. One TalkBack node that reads the whole episode.
 */
@Composable
private fun EpisodeRow(episode: AccelerationEpisode, formatter: UnitFormatter, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = AccelerationColorScale.palette(colors.background.luminance() < 0.5f)
    val from = formatter.speedWholeValue(episode.fromSpeedMps.toDouble())
    val to = formatter.speedWholeValue(episode.toSpeedMps.toDouble())
    val peak = episode.peakMps2.toDouble()
    val distance = formatter.distance(episode.startDistanceM)
    val duration = formatter.shortDuration(episode.durationMs)
    val headline = stringResource(R.string.detail_episode_speeds, from, to, formatter.speedUnit(), duration)
    val signedPeak = formatter.accelerationValue(peak)
    val supporting = stringResource(R.string.detail_episode_detail, signedPeak, formatter.accelerationUnit(), distance)
    val spoken = stringResource(
        R.string.cd_episode,
        stringResource(episode.kind.labelRes()),
        from,
        to,
        formatter.speedUnit(),
        formatter.shortDurationSpoken(episode.durationMs),
        formatter.accelerationMagnitude(peak),
        formatter.accelerationUnitSpoken(),
        distance,
    )
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            val tint = episode.kind.tint(Color(palette.up), Color(palette.down), colors.onSurfaceVariant)
            Icon(episode.kind.icon(), contentDescription = null, tint = tint)
        },
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
    )
}
