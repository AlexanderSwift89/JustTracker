package com.justtracker.app.util

import android.os.Trace

/**
 * Names a section in system traces (Perfetto, `adb shell perfetto`): the hot paths of a recording and the track line
 * are visible next to frames and GC (docs/08_test_plan.md §2). Costs a flag check when no trace is being taken.
 */
inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
