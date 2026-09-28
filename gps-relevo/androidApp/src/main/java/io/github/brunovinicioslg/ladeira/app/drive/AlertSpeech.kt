package io.github.brunovinicioslg.ladeira.app.drive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Speaks alerts over whatever is playing: music ducks while the phone talks. Use from the main
 * thread; [onAvailability] reports (on the main thread) whether the phone can speak the language.
 */
class AlertSpeech(context: Context, private val onAvailability: (Boolean) -> Unit) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(ATTRIBUTES)
        .build()
    private var ready = false
    private var unavailable = false
    private var shutDown = false

    /**
     * Alerts that came before the speech engine finished starting (it can take a second or two):
     * starting the trip at the top of a hill must not lose the first warning.
     */
    private val waiting = ArrayDeque<Pair<String, Long>>()

    /** Utterances queued and not finished; focus is held while above zero. */
    private var pending = 0
    private var nextId = 0L
    private val tts = TextToSpeech(appContext) { status -> mainHandler.post { onInit(status) } }

    private fun onInit(status: Int) {
        if (shutDown) return
        if (status != TextToSpeech.SUCCESS) {
            giveUp()
            return
        }
        val result = tts.setLanguage(appContext.resources.configuration.locales[0])
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            giveUp()
            return
        }
        tts.setAudioAttributes(ATTRIBUTES)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                mainHandler.post { finished() }
            }

            @Deprecated("Replaced by onError(String, Int)")
            override fun onError(utteranceId: String?) {
                mainHandler.post { finished() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { finished() }
            }
        })
        ready = true
        onAvailability(true)
        val now = SystemClock.elapsedRealtime()
        waiting.filter { (_, at) -> now - at <= MAX_WAIT_MS }.forEach { (text, _) -> say(text) }
        waiting.clear()
    }

    private fun giveUp() {
        unavailable = true
        waiting.clear()
        onAvailability(false)
    }

    fun speak(text: String) {
        if (shutDown || unavailable) return
        if (!ready) {
            waiting += text to SystemClock.elapsedRealtime()
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
            return
        }
        say(text)
    }

    private fun say(text: String) {
        if (pending == 0) audioManager.requestAudioFocus(focusRequest)
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "alert-${nextId++}")
        if (result == TextToSpeech.SUCCESS) pending++ else if (pending == 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun finished() {
        if (pending == 0) return
        pending--
        if (pending == 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        shutDown = true
        waiting.clear()
        mainHandler.removeCallbacksAndMessages(null)
        tts.stop()
        tts.shutdown()
        if (pending > 0) audioManager.abandonAudioFocusRequest(focusRequest)
        pending = 0
    }

    private companion object {
        /** Older than this, a waiting alert may no longer be true: the vehicle has moved on. */
        const val MAX_WAIT_MS = 10_000L
        const val MAX_WAITING = 5

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
