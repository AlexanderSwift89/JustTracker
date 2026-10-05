package com.justtracker.app.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Package of the app under test, passed in by the baseline-profile Gradle plugin. */
fun targetPackage(): String =
    InstrumentationRegistry.getArguments().getString("targetAppId") ?: error("targetAppId not passed by the baselineprofile plugin")

private const val WAIT_MS = 5_000L

private fun text(vararg labels: String) = By.text(Pattern.compile(labels.joinToString("|") { Pattern.quote(it) }))

/** Location and notifications granted up front: no system dialog interrupts a journey. */
fun MacrobenchmarkScope.grantPermissions() {
    for (p in listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.POST_NOTIFICATIONS")) {
        device.executeShellCommand("pm grant $packageName $p")
    }
}

/** First launch: the language step, then the permissions step (both "Continue"); nothing to do once onboarded. */
fun MacrobenchmarkScope.passOnboardingIfShown() {
    repeat(2) {
        val next = device.wait(Until.findObject(text("Continue", "Продолжить")), WAIT_MS) ?: return
        next.click()
        device.waitForIdle()
    }
}

/** Every tab once and back to Record: the screens a user meets in the first minute. */
fun MacrobenchmarkScope.visitTabs() {
    val tabs = listOf(arrayOf("History", "История"), arrayOf("Stats", "Статистика"), arrayOf("Settings", "Настройки"), arrayOf("Record", "Запись"))
    for (tab in tabs) {
        device.wait(Until.findObject(text(*tab)), WAIT_MS)?.click()
        device.waitForIdle()
    }
}
