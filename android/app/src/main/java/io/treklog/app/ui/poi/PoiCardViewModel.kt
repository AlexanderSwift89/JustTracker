package io.treklog.app.ui.poi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.treklog.app.di.AppContainer
import io.treklog.app.domain.poi.Poi
import io.treklog.app.domain.poi.PoiSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SummaryStatus { LOADING, READY, UNAVAILABLE }

data class PoiCardState(
    val poi: Poi,
    val status: SummaryStatus = SummaryStatus.LOADING,
    val summary: PoiSummary? = null,
    /** Distance from the user, when a live position is known. */
    val distanceM: Double? = null,
    val speaking: Boolean = false,
    val ttsAvailable: Boolean = true,
)

/** State of the place card (bottom sheet) shared by the Record and Detail screens. */
class PoiCardViewModel(private val container: AppContainer) : ViewModel() {
    private val selected = MutableStateFlow<PoiCardState?>(null)
    private var loadJob: Job? = null

    val state: StateFlow<PoiCardState?> = combine(selected, container.tts.speakingId, container.tts.available) { card, speakingId, available ->
        card?.copy(speaking = speakingId == card.poi.id, ttsAvailable = available)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(poi: Poi, distanceM: Double?) {
        loadJob?.cancel()
        selected.value = PoiCardState(poi = poi, distanceM = distanceM)
        loadJob = viewModelScope.launch {
            val summary = container.poiRepository.summary(poi.wikipedia)
            selected.update { s ->
                if (s?.poi?.id != poi.id) s
                else s.copy(summary = summary, status = if (summary != null) SummaryStatus.READY else SummaryStatus.UNAVAILABLE)
            }
        }
    }

    fun updateDistance(distanceM: Double?) = selected.update { it?.copy(distanceM = distanceM) }

    fun close() {
        loadJob?.cancel()
        val s = selected.value
        if (s != null && container.tts.speakingId.value == s.poi.id) container.tts.stop()
        selected.value = null
    }

    fun toggleSpeak() {
        val s = state.value ?: return
        if (s.speaking) {
            container.tts.stop()
            return
        }
        val summary = s.summary
        val text = if (summary != null) "${s.poi.name}. ${summary.extract}" else s.poi.name
        container.tts.speak(id = s.poi.id, text = text, lang = summary?.lang ?: s.poi.wikipedia.lang, flush = true)
    }
}
