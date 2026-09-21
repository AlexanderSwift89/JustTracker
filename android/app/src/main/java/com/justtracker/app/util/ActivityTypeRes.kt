package com.justtracker.app.util

import androidx.annotation.StringRes
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.AppLanguage

@StringRes
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.WALK -> R.string.activity_walk
    ActivityType.RUN -> R.string.activity_run
    ActivityType.BIKE -> R.string.activity_bike
    ActivityType.CAR -> R.string.activity_car
    ActivityType.OTHER -> R.string.activity_other
    ActivityType.UNKNOWN -> R.string.activity_unknown
}

/** Endonyms — the same in every locale so a user can always find their own language. */
@StringRes
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.EN -> R.string.language_en
    AppLanguage.RU -> R.string.language_ru
}
