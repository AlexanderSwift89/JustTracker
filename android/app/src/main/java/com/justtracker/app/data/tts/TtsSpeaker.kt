package com.justtracker.app.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Process-wide wrapper over the platform TextToSpeech engine (on-device, no network permission
 * beyond what the engine itself has). Exposes which utterance is currently spoken so the card can
 * toggle its button, and ducks other audio (navigation, music) while speaking.
 */
class TtsSpeaker(
    context: Context,
    /** Locale used when the engine lacks the text language — the app language, not the device one. */
    private val fallbackLocale: () -> Locale = { Locale.getDefault() },
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _speakingId = MutableStateFlow<String?>(null)

    /** Id passed to [speak] while it is being spoken; null when idle. */
    val speakingId: StateFlow<String?> = _speakingId

    private val _available = MutableStateFlow(true)

    /** False when no TTS engine could be initialised — UI hides the read-aloud button. */
    val available: StateFlow<Boolean> = _available

    private var ready = false
    private var pending: Pending? = null

    private class Pending(val id: String, val text: String, val lang: String, val flush: Boolean)

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        _available.value = ready
        if (!ready) AppLog.w("TTS init failed: $status")
        pending?.let { p ->
            pending = null
            if (ready) speak(p.id, p.text, p.lang, p.flush)
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speakingId.value = utteranceId
            }

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

            private fun finished(utteranceId: String?) {
                _speakingId.compareAndSet(utteranceId, null)
                if (_speakingId.value == null) audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
    }

    /**
     * @param lang BCP-47 language of [text]; falls back to the device locale when the engine lacks it.
     * @param flush true replaces whatever is being spoken (manual tap); false queues (auto announcements).
     */
    fun speak(id: String, text: String, lang: String, flush: Boolean = true) {
        if (!ready) {
            if (_available.value) pending = Pending(id, text, lang, flush)
            return
        }
        val locale = Locale.forLanguageTag(lang)
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(fallbackLocale())
        }
        audioManager.requestAudioFocus(focusRequest)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // Engines limit a single utterance (~4000 chars); the lead section always fits.
        val r = tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), mode, null, id)
        if (r != TextToSpeech.SUCCESS) {
            AppLog.w("TTS speak failed: $r")
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        _speakingId.value = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
