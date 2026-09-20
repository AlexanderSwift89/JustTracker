package com.justtracker.app.util

import android.util.Log
import com.justtracker.app.BuildConfig

/** Thin logging facade. Geo-data is only ever logged in debug builds (docs/07_security.md). */
object AppLog {
    private const val TAG = "JustTracker"

    fun d(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)

    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

    /** Log that may contain coordinates: compiled to a no-op in release. */
    fun geo(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[geo] " + msg())
    }
}
