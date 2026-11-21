package io.hydrion.hub.ui.composer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class DictationService { OnDevice, System }

internal interface DictationSpeechListener {
    fun ready() {}
    fun rms(db: Float)
    fun end()
    fun finalText(text: String)
    fun error(code: Int)
}

internal interface DictationSpeech {
    fun start()
    fun stop()
    fun cancel()
    fun destroy()
}

internal interface DictationSpeechFactory {
    fun available(context: Context): DictationService?
    fun create(context: Context, service: DictationService, listener: DictationSpeechListener): DictationSpeech
}

internal val LocalDictationSpeechFactory = staticCompositionLocalOf<DictationSpeechFactory> { AndroidDictationSpeech }

private object AndroidDictationSpeech : DictationSpeechFactory {
    override fun available(context: Context): DictationService? = when {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> DictationService.OnDevice
        SpeechRecognizer.isRecognitionAvailable(context) -> DictationService.System
        else -> null
    }

    override fun create(context: Context, service: DictationService, listener: DictationSpeechListener): DictationSpeech {
        val recognizer = if (service == DictationService.OnDevice && Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        return object : DictationSpeech {
            override fun start() {
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = listener.ready()
                    override fun onBeginningOfSpeech() = listener.ready()
                    override fun onRmsChanged(rmsdB: Float) = listener.rms(rmsdB)
                    override fun onEndOfSpeech() = listener.end()
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        listener.finalText(matches?.firstOrNull { it.isNotBlank() }.orEmpty())
                    }
                    override fun onError(error: Int) = listener.error(error)
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                recognizer.startListening(request)
            }

            override fun stop() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }
}
