package com.kotlinsun.current

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Speaks the complete extracted text in bounded utterances; late callbacks are ignored. */
internal class ReaderSpeech(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0

    fun start(text: String, finished: (Boolean) -> Unit) {
        stop()
        val token = generation
        val chunks = text.chunked(1800).filter { it.isNotBlank() }
        if (chunks.isEmpty()) { finished(false); return }
        engine = TextToSpeech(context) { status ->
            main.post {
                if (token != generation) return@post
                val current = engine
                if (status != TextToSpeech.SUCCESS || current == null) {
                    stop(); finished(false); return@post
                }
                var index = 0
                fun next() {
                    if (token != generation) return
                    if (index == chunks.size) { stop(); finished(true); return }
                    val utterance = "$token:${index++}"
                    if (current.speak(chunks[index - 1], TextToSpeech.QUEUE_FLUSH, null, utterance)
                        != TextToSpeech.SUCCESS) { stop(); finished(false) }
                }
                current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) { main.post { next() } }
                    @Deprecated("Legacy callback")
                    override fun onError(utteranceId: String) { main.post {
                        if (token == generation) { stop(); finished(false) }
                    } }
                })
                next()
            }
        }
    }

    fun stop() {
        generation++
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
