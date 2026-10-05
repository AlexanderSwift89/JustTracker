package com.justtracker.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens an https link in a browser. `CATEGORY_BROWSABLE` limits the targets to apps that accept untrusted web
 * links (docs/07_security.md SEC-06); without such an app nothing happens. Anything but https is ignored, so a
 * future caller cannot turn it into an intent to another scheme (SEC-17).
 */
fun Context.openInBrowser(url: String) {
    val uri = url.toUri()
    if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
