package com.justtracker.app.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.domain.model.AppSettings
import java.util.Locale

/**
 * Single place that turns the stored [AppLanguage] into locales (ADR-15).
 *
 * On API 33+ `AppCompatDelegate.setApplicationLocales` goes through the system LocaleManager and the
 * whole process follows it. On API 26–32 AppCompat only re-wraps AppCompatActivity contexts, so
 * non-UI code (service notification, auto-naming, POI language, TTS fallback) reads the choice from
 * DataStore via [current] / [localized], and [applyDefault] keeps `Locale.getDefault()` in step.
 */
object AppLocale {
    /** Locale for network/TTS/formatting: explicit app choice, then AppCompat, then the device. */
    fun current(settings: AppSettings): Locale =
        settings.language?.let { Locale.forLanguageTag(it.tag) }
            ?: AppCompatDelegate.getApplicationLocales().get(0)
            ?: Locale.getDefault()

    /** A context whose resources resolve in [locale] — for Service/Application code on API < 33. */
    fun localized(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    fun localized(context: Context, settings: AppSettings): Context = localized(context, current(settings))

    /** Makes `Locale.getDefault()` (java.time, Wikipedia language) follow the app language. */
    fun applyDefault(language: AppLanguage?) {
        language ?: return
        val locale = Locale.forLanguageTag(language.tag)
        if (Locale.getDefault().language != locale.language) Locale.setDefault(locale)
    }

    /** Pushes the stored choice into AppCompat when it drifted (reinstall, restore). Main thread only. */
    fun sync(language: AppLanguage?) {
        language ?: return
        val wanted = LocaleListCompat.forLanguageTags(language.tag)
        if (AppCompatDelegate.getApplicationLocales() != wanted) AppCompatDelegate.setApplicationLocales(wanted)
    }

    /** User changed the language: persist first (caller), then apply — AppCompat recreates activities. */
    fun apply(language: AppLanguage) {
        applyDefault(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
