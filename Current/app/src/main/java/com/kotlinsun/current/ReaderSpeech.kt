package com.kotlinsun.current

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Keeps the utterance index on pause; stopped or stale callbacks cannot advance playback. */
internal class ReaderSpeech(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0
    private var chunks = emptyList<String>()
    private var index = 0
    private var serial = 0
    private var activeId: String? = null
    private var paused = false
    private var finished: ((Boolean) -> Unit)? = null

    fun start(text: String, languageTag: String, onFinished: (Boolean) -> Unit) {
        stop()
        chunks = text.split(Regex("(?<=\\n)"))
            .flatMap { paragraph -> paragraph.chunked(1500) }
            .filter { it.isNotBlank() }
        if (chunks.isEmpty()) { onFinished(false); return }
        finished = onFinished
        val token = generation
        engine = TextToSpeech(context) { status ->
            main.post {
                if (token != generation) return@post
                val tts = engine
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    finish(false); return@post
                }
                val locale = Locale.forLanguageTag(languageTag)
                if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE ||
                    tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                    finish(false); return@post
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) { main.post {
                        if (token == generation && !paused && utteranceId == activeId) {
                            activeId = null
                            index++
                            speakNext()
                        }
                    } }
                    @Deprecated("Legacy callback")
                    override fun onError(utteranceId: String) { main.post {
                        if (token == generation && !paused && utteranceId == activeId) finish(false)
                    } }
                })
                speakNext()
            }
        }
    }

    private fun speakNext() {
        if (paused) return
        if (index >= chunks.size) { finish(true); return }
        val token = generation
        val id = "$token:$index:${++serial}"
        activeId = id
        if (engine?.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, null, id)
            != TextToSpeech.SUCCESS) finish(false)
    }

    fun pause() {
        if (engine == null || paused) return
        paused = true
        activeId = null
        engine?.stop()
    }

    fun resume() {
        if (engine == null || !paused) return
        paused = false
        speakNext()
    }

    private fun finish(success: Boolean) {
        val callback = finished
        stop()
        callback?.invoke(success)
    }

    fun stop() {
        generation++
        finished = null
        paused = false
        chunks = emptyList()
        index = 0
        activeId = null
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
