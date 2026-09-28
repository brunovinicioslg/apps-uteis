package io.github.brunovinicioslg.ladeira.app.drive

import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class AlertSpeechTest {

    private var available: Boolean? = null
    private lateinit var speech: AlertSpeech

    @Before
    fun setUp() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
        speech = AlertSpeech(ApplicationProvider.getApplicationContext()) { available = it }
    }

    @After
    fun tearDown() {
        speech.shutdown()
        ShadowTextToSpeech.reset()
    }

    private val tts get() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    private fun engineStarts(status: Int = TextToSpeech.SUCCESS) {
        tts.onInitListener.onInit(status)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun alertsBeforeTheEngineIsReadyAreSpokenOnceItIs() {
        speech.speak("Descida longa em 200 metros.")
        assertThat(tts.spokenTextList).isEmpty()
        engineStarts()
        assertThat(available).isTrue()
        assertThat(tts.spokenTextList).containsExactly("Descida longa em 200 metros.")
    }

    @Test
    fun staleWaitingAlertsAreDropped() {
        speech.speak("Radar em 300 metros.")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        speech.speak("Lombada em 200 metros.")
        engineStarts()
        assertThat(tts.spokenTextList).containsExactly("Lombada em 200 metros.")
    }

    @Test
    fun withoutAVoiceNothingIsQueuedForever() {
        engineStarts(TextToSpeech.ERROR)
        assertThat(available).isFalse()
        speech.speak("Radar em 300 metros.")
        assertThat(tts.spokenTextList).isEmpty()
    }

    @Test
    fun readyEngineSpeaksInOrder() {
        engineStarts()
        speech.speak("Um.")
        speech.speak("Dois.")
        assertThat(tts.spokenTextList).containsExactly("Um.", "Dois.").inOrder()
    }
}
