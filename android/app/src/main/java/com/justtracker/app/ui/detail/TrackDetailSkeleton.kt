package com.justtracker.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justtracker.app.ui.common.SkeletonBox
import com.justtracker.app.ui.common.SkeletonGroup
import com.justtracker.app.ui.common.SkeletonLine
import com.justtracker.app.ui.common.SkeletonStatTile

/**
 * Placeholder with the screen's own layout while the track and its points are read from Room
 * (docs/04_ux_design.md §7): map area, scrubber row, activity row and the first tiles of the grid —
 * stacked in portrait, map beside a pane in landscape.
 */
@Composable
internal fun TrackDetailSkeleton(twoPane: Boolean, paneWidth: Dp) {
    SkeletonGroup(Modifier.fillMaxSize()) {
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                SkeletonBox(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                Column(Modifier.width(paneWidth)) {
                    SkeletonCursorRows()
                    SkeletonActivityRow()
                    SkeletonTileRows(rows = 2)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SkeletonBox(
                    Modifier
                        .fillMaxWidth()
                        .weight(MAP_WEIGHT),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                SkeletonCursorRows()
                SkeletonActivityRow()
                SkeletonTileRows(rows = 3, modifier = Modifier.weight(1f - MAP_WEIGHT))
            }
        }
    }
}

@Composable
private fun SkeletonCursorRows() {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(24.dp), shape = MaterialTheme.shapes.small)
            SkeletonBox(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .height(8.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
            SkeletonBox(Modifier.size(24.dp), shape = MaterialTheme.shapes.small)
        }
        SkeletonLine(
            width = 140.dp,
            height = 14.dp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(4) { SkeletonLine(width = 56.dp, height = 18.dp) }
        }
    }
}

@Composable
private fun SkeletonActivityRow() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(32.dp), shape = androidx.compose.foundation.shape.CircleShape)
        SkeletonLine(width = 96.dp, height = 16.dp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SkeletonTileRows(rows: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonStatTile(Modifier.weight(1f))
                SkeletonStatTile(Modifier.weight(1f))
            }
        }
    }
}
