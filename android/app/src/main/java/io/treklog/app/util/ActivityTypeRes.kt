package io.treklog.app.util

import androidx.annotation.StringRes
import io.treklog.app.R
import io.treklog.app.domain.model.ActivityType

@StringRes
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.WALK -> R.string.activity_walk
    ActivityType.RUN -> R.string.activity_run
    ActivityType.BIKE -> R.string.activity_bike
    ActivityType.CAR -> R.string.activity_car
    ActivityType.OTHER -> R.string.activity_other
    ActivityType.UNKNOWN -> R.string.activity_unknown
}
