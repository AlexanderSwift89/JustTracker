package com.justtracker.app.util

import android.util.Log
import com.justtracker.app.BuildConfig

/**
 * Thin logging facade (docs/07_security.md SEC-12). Geo-data is only ever logged in debug builds. In release,
 * warnings and errors name the exception class only: a message may carry a file path with a track name, a
 * document URI or a quoted server answer. Class names are R8-obfuscated; `mapping.txt` decodes them.
 *
 * Release lines are written with [Log.println]: R8 strips every `Log.v/d/i/w/e/wtf` call in release, the
 * libraries' ones included (SEC-15, proguard-rules.pro).
 */
object AppLog {
    private const val TAG = "JustTracker"

    fun d(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(TAG, msg, t) else Log.println(Log.WARN, TAG, releaseLine(msg, t))
    }

    fun e(msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, msg, t) else Log.println(Log.ERROR, TAG, releaseLine(msg, t))
    }

    /** Log that may contain coordinates: compiled to a no-op in release. */
    fun geo(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[geo] " + msg())
    }

    /** What a release build writes: the message and the exception class — no exception message, no stack trace. */
    internal fun releaseLine(msg: String, t: Throwable?): String = if (t == null) msg else "$msg [${t.javaClass.name}]"
}
