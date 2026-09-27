package com.kotlinsun.current.browser

import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.kotlinsun.current.engine.ReadableBlock
import com.kotlinsun.current.engine.ReadablePage

/** Explicit, on-device translation. Only language models are downloaded; page text is not stored. */
internal class ReaderTranslator {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var translator: Translator? = null
    private var identifier: LanguageIdentifier? = null

    fun translate(page: ReadablePage, targetTag: String,
        progress: (Int, Int) -> Unit, completed: (List<ReadableBlock>?) -> Unit) {
        cancel()
        val token = generation
        val target = runCatching { TranslateLanguage.fromLanguageTag(targetTag) }.getOrNull()
        if (target == null) { completed(null); return }
        val declared = page.language?.let {
            runCatching { TranslateLanguage.fromLanguageTag(it) }.getOrNull()
        }
        if (declared != null) {
            begin(page, declared, target, token, progress, completed)
            return
        }
        val identifier = runCatching { LanguageIdentification.getClient() }
            .getOrElse { completed(null); return }
        this.identifier = identifier
        val identification = runCatching { identifier.identifyLanguage(page.text.take(500)) }
            .getOrElse { cancel(); completed(null); return }
        identification
            .addOnSuccessListener detect@ { tag ->
                if (this.identifier === identifier) {
                    this.identifier = null
                    identifier.close()
                }
                if (token != generation) return@detect
                val source = runCatching { TranslateLanguage.fromLanguageTag(tag) }.getOrNull()
                if (source == null) completed(null)
                else begin(page, source, target, token, progress, completed)
            }
            .addOnFailureListener {
                if (this.identifier === identifier) {
                    this.identifier = null
                    identifier.close()
                }
                if (token == generation) completed(null)
            }
    }

    private fun begin(page: ReadablePage, source: String, target: String, token: Int,
        progress: (Int, Int) -> Unit, completed: (List<ReadableBlock>?) -> Unit) {
        val blocks = page.blocks.ifEmpty {
            page.text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                .map { ReadableBlock(it) }
        }
        if (source == target) { completed(blocks); return }
        val client = runCatching { Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(target).build()) }
            .getOrElse { completed(null); return }
        translator = client
        val pieces = blocks.flatMapIndexed { index, block ->
            block.text.chunked(2500).map { index to it }
        }
        if (pieces.isEmpty()) { cancel(); completed(null); return }
        progress(0, pieces.size)
        val results = Array(blocks.size) { StringBuilder() }
        val model = runCatching { client.downloadModelIfNeeded(
            DownloadConditions.Builder().requireWifi().build()) }
            .getOrElse { cancel(); completed(null); return }
        model
            .addOnSuccessListener model@ {
                if (token != generation) return@model
                fun next(position: Int) {
                    if (token != generation) return
                    if (position == pieces.size) {
                        val translated = blocks.mapIndexed { index, block ->
                            block.copy(text = results[index].toString())
                        }
                        cancel()
                        completed(translated)
                        return
                    }
                    val (index, text) = pieces[position]
                    val task = runCatching { client.translate(text) }.getOrElse {
                        cancel(); completed(null); return
                    }
                    task
                        .addOnSuccessListener piece@ { result ->
                            if (token != generation) return@piece
                            if (results[index].isNotEmpty()) results[index].append(' ')
                            results[index].append(result)
                            progress(position + 1, pieces.size)
                            main.post { next(position + 1) }
                        }
                        .addOnFailureListener {
                            if (token == generation) { cancel(); completed(null) }
                        }
                }
                next(0)
            }
            .addOnFailureListener {
                if (token == generation) { cancel(); completed(null) }
            }
    }

    fun cancel() {
        generation++
        identifier?.close()
        identifier = null
        translator?.close()
        translator = null
    }
}
