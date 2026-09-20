package com.justtracker.app.testing

import android.location.Location
import com.justtracker.app.data.location.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Test double: emits whatever the test pushes into [fixes]; [last] is returned by [lastKnown]. */
class FakeLocationSource(
    val fixes: MutableSharedFlow<Location> = MutableSharedFlow(extraBufferCapacity = 64),
    var last: Location? = null,
) : LocationSource {
    var requestedIntervalMs: Long? = null
        private set

    override fun updates(intervalMs: Long): Flow<Location> {
        requestedIntervalMs = intervalMs
        return fixes
    }

    override suspend fun lastKnown(): Location? = last
}
