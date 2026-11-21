package io.hydrion.hub.ui.composer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.hydrion.hub.R

@Stable
internal interface ChatDictationControls {
    val isActive: Boolean
    val prompting: Boolean
    val waiting: Boolean
    val resultReady: Boolean
    val level: Float
    fun start()
    fun cancel()
    fun save()
    fun send()
}

private enum class Setup { Idle, Consent, RequestPermission, AwaitPermission, Ready }

private class ChatDictation(
    private val context: Context,
    private val serviceFactory: DictationSpeechFactory,
    private val conversationId: String,
    private val isForeground: () -> Boolean,
    private val readDraft: () -> TextFieldValue?,
    private val onResult: (TextFieldValue, Boolean) -> Unit,
) : ChatDictationControls {
    private data class Request(val service: DictationService, val draft: TextFieldValue)
    private var request: Request? = null
    var setup by mutableStateOf(Setup.Idle)
        private set
    private var session by mutableStateOf<DictationSession?>(null)
    private var speech: DictationSpeech? = null
    private var rmsSummary: RmsSummary? = null
    private var ended = false
    private var disposed = false
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private val preferences by lazy { context.getSharedPreferences("chat_dictation", Context.MODE_PRIVATE) }
    override val isActive get() = session != null
    override val prompting get() = setup != Setup.Idle
    override var waiting by mutableStateOf(false)
        private set
    override var resultReady by mutableStateOf(false)
        private set
    override var level by mutableFloatStateOf(0f)
        private set

    private fun notice(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun start() {
        if (disposed || !isForeground() || isActive || request != null) return
        val draft = readDraft() ?: return
        val service = try {
            serviceFactory.available(context)
        } catch (_: RuntimeException) {
            null
        }
        if (service == null) {
            notice(R.string.dictation_service_missing)
            return
        }
        request = Request(service, draft)
        if (service == DictationService.System && !preferences.getBoolean("allow_default_service", false)) {
            setup = Setup.Consent
        } else {
            checkPermission()
        }
    }

    fun allowSystemService() {
        if (setup != Setup.Consent || !isForeground()) return
        preferences.edit().putBoolean("allow_default_service", true).apply()
        checkPermission()
    }

    private fun checkPermission() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setup = Setup.Ready
            resume()
        } else {
            setup = Setup.RequestPermission
        }
    }

    fun requestPermission(launch: () -> Unit) {
        if (setup != Setup.RequestPermission) return
        setup = Setup.AwaitPermission
        try {
            launch()
        } catch (_: RuntimeException) {
            permissionResult(false)
        }
    }

    fun permissionResult(granted: Boolean) {
        if (setup != Setup.AwaitPermission) return
        if (!granted) {
            cancel()
            notice(R.string.dictation_service_permission)
            return
        }
        // The activity result can arrive at STARTED, before the permission dialog has returned focus.
        setup = Setup.Ready
        resume()
    }

    fun resume() {
        val pending = request ?: return
        if (disposed || setup != Setup.Ready || !isForeground()) return
        request = null
        setup = Setup.Idle
        val current = readDraft()
        if (current == null || pending.draft.text != current.text || pending.draft.selection != current.selection) {
            notice(R.string.chat_dictation_draft_changed)
            return
        }
        val target = DictationSession(conversationId, current)
        session = target
        val summary = DictationRmsDiagnostic.take()
        rmsSummary = summary
        try {
            speech = serviceFactory.create(context, pending.service, callbacks(target, summary))
            // Services can fail to bind without firing any callback.
            armTimeout(target, 10_000)
            speech?.start()
        } catch (_: SecurityException) {
            fail(target, R.string.dictation_service_permission)
        } catch (_: RuntimeException) {
            fail(target, R.string.dictation_service_missing)
        }
    }

    private fun callbacks(target: DictationSession, summary: RmsSummary?) = object : DictationSpeechListener {
        private var listening = false
        private fun live() = session === target && !target.hasFinal

        override fun ready() {
            if (!live() || listening || waiting || ended) return
            listening = true
            // Replace the bind deadline once. An engine that hangs after "ready" must release the mic too.
            armTimeout(target, 120_000)
        }

        override fun rms(db: Float) {
            if (!live() || waiting || ended) {
                summary?.record(db, null)
                return
            }
            if (!listening) ready()
            val normalized = if (db.isFinite()) (db / 10f).coerceIn(0f, 1f) else 0f
            level = normalized
            summary?.record(db, normalized)
        }

        override fun end() {
            if (!live() || ended) return
            ended = true
            waiting = true
            level = 0f
            armTimeout(target, 8_000)
        }

        override fun finalText(text: String) {
            if (!live()) return
            if (!target.finalResult(text)) {
                fail(target, R.string.dictation_service_no_result)
                return
            }
            clearTimeout()
            releaseSpeech(cancel = false)
            level = 0f
            // An automatic end of speech leaves the transcript pending until the user's decision.
            complete(target)
        }

        override fun error(code: Int) {
            if (!live()) return
            val message = when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.dictation_service_no_result
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.dictation_service_permission
                SpeechRecognizer.ERROR_AUDIO -> R.string.dictation_service_audio
                else -> R.string.dictation_service_failed
            }
            fail(target, message)
        }
    }

    override fun save() = choose(DictationAction.Save)
    override fun send() = choose(DictationAction.Send)

    private fun choose(action: DictationAction) {
        val target = session ?: return
        if (waiting) return
        if (!target.choose(action)) return
        if (target.hasFinal) {
            complete(target)
        } else {
            waiting = true
            level = 0f
            armTimeout(target, 8_000)
            if (!ended) {
                try {
                    speech?.stop()
                } catch (_: RuntimeException) {
                    fail(target, R.string.dictation_service_failed)
                }
            }
        }
    }

    private fun complete(target: DictationSession) {
        if (session !== target || !target.hasFinal) return
        val commit = target.consume(conversationId, if (isForeground()) readDraft() else null)
        if (target.isActive) {
            waiting = false
            resultReady = true
            return
        }
        cancel()
        if (commit != null) onResult(commit.draft, commit.action == DictationAction.Send)
        else notice(R.string.chat_dictation_draft_changed)
    }

    fun pause() {
        // A microphone permission dialog itself pauses its host. Only its pending request survives PAUSE.
        if (setup != Setup.AwaitPermission && setup != Setup.Ready) cancel()
    }

    override fun cancel() {
        request = null
        setup = Setup.Idle
        val target = session
        session = null // Ignore even synchronous callbacks from cancel()/destroy().
        target?.cancel()
        clearTimeout()
        releaseSpeech(cancel = true)
        ended = false
        waiting = false
        resultReady = false
        level = 0f
    }

    fun dispose() {
        disposed = true
        cancel()
    }

    private fun fail(target: DictationSession, message: Int) {
        if (session !== target) return
        cancel()
        notice(message)
    }

    private fun releaseSpeech(cancel: Boolean) {
        val engine = speech
        speech = null
        if (engine != null) {
            if (cancel) try { engine.cancel() } catch (_: RuntimeException) { /* Service may have died. */ }
            try { engine.destroy() } catch (_: RuntimeException) { /* Already disconnected. */ }
        }
        rmsSummary?.finish()?.let { Log.i("HydrionRms", it) }
        rmsSummary = null
    }

    private fun armTimeout(target: DictationSession, millis: Long) {
        clearTimeout()
        val deadline = Runnable { fail(target, R.string.dictation_service_timeout) }
        timeout = deadline
        handler.postDelayed(deadline, millis)
    }

    private fun clearTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }
}

@Composable
internal fun rememberChatDictation(
    conversationId: String,
    enabled: Boolean,
    readDraft: () -> TextFieldValue?,
    onResult: (TextFieldValue, Boolean) -> Unit,
): ChatDictationControls = key(conversationId, enabled) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val factory = LocalDictationSpeechFactory.current
    val latestRead by rememberUpdatedState(readDraft)
    val latestResult by rememberUpdatedState(onResult)
    val controller = remember(context, lifecycle, factory) {
        ChatDictation(context, factory, conversationId,
            isForeground = { enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            readDraft = { latestRead() },
            onResult = { value, send -> latestResult(value, send) },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), controller::permissionResult)
    LaunchedEffect(controller, controller.setup) {
        controller.requestPermission { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                Lifecycle.Event.ON_STOP -> controller.cancel()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    if (controller.setup == Setup.Consent) {
        AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text(stringResource(R.string.dictation_service_consent_title)) },
            text = { Text(stringResource(R.string.dictation_service_consent_body)) },
            confirmButton = {
                TextButton(onClick = controller::allowSystemService) { Text(stringResource(R.string.dictation_service_allow)) }
            },
            dismissButton = {
                TextButton(onClick = controller::cancel) { Text(stringResource(R.string.dictation_service_cancel)) }
            },
        )
    }
    controller
}
