package com.justtracker.app.util

import android.os.Build
import android.os.Trace

/** False only in JVM unit tests, where `android.os` is a stub (SDK_INT is 0 there) — the androidx.tracing check. */
@PublishedApi
internal val tracingAvailable: Boolean = Build.VERSION.SDK_INT > 0

/**
 * Names a section in system traces (Perfetto, `adb shell perfetto`): the hot paths of a recording and the track line
 * are visible next to frames and GC (docs/08_test_plan.md §2). Costs a flag check when no trace is being taken.
 * Synchronous code only: a section must end on the thread it began on.
 */
inline fun <T> traced(name: String, block: () -> T): T {
    if (!tracingAvailable) return block()
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
