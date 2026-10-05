package com.justtracker.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.util.AppLocale
import kotlinx.coroutines.launch

/**
 * First-launch steps that write settings. The view model outlives the activity AppCompat recreates when the language
 * changes, so a write started on the old activity still completes.
 */
class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {
    /** Persists [language], then applies it (US-18): the recreated activity must already see the choice. */
    fun chooseLanguage(language: AppLanguage, onSaved: () -> Unit) {
        viewModelScope.launch {
            settings.setLanguage(language)
            onSaved()
            AppLocale.apply(language)
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingDone()
            onDone()
        }
    }
}
