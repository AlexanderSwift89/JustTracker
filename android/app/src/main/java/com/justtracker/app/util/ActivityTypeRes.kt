package com.justtracker.app.util

import androidx.annotation.StringRes
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType

@StringRes
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.WALK -> R.string.activity_walk
    ActivityType.RUN -> R.string.activity_run
    ActivityType.BIKE -> R.string.activity_bike
    ActivityType.CAR -> R.string.activity_car
    ActivityType.OTHER -> R.string.activity_other
    ActivityType.UNKNOWN -> R.string.activity_unknown
}
