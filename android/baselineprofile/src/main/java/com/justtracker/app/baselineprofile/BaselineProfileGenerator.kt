package com.justtracker.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Collects the classes and methods of a cold start and the first tab switches into the app's baseline profile. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = targetPackage(), includeInStartupProfile = true) {
        grantPermissions()
        pressHome()
        startActivityAndWait()
        passOnboardingIfShown()
        visitTabs()
    }
}
