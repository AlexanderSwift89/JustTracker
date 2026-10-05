package com.justtracker.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens an https link in a browser. `CATEGORY_BROWSABLE` limits the targets to apps that accept untrusted web
 * links (docs/07_security.md SEC-06); without such an app nothing happens.
 */
fun Context.openInBrowser(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
