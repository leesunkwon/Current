package com.kotlinsun.current.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotlinsun.current.R
import com.kotlinsun.current.engine.ReadableBlock

private val languages = listOf("ko" to R.string.reader_language_ko,
    "en" to R.string.reader_language_en, "ja" to R.string.reader_language_ja,
    "zh" to R.string.reader_language_zh, "es" to R.string.reader_language_es,
    "fr" to R.string.reader_language_fr)

internal class ReaderActions(
    val setSpeechLanguage: (String) -> Unit = {},
    val toggleSpeech: () -> Unit = {},
    val resumeSpeech: () -> Unit = {},
    val pauseSpeech: () -> Unit = {},
    val translate: (String) -> Unit = {},
    val showOriginal: () -> Unit = {},
)

@Composable
internal fun ReaderScreen(ui: BrowserUiState, controller: BrowserController) {
    ReaderScreenContent(ui, ReaderActions(
        setSpeechLanguage = controller::setReaderSpeechLanguage,
        toggleSpeech = controller::toggleReaderSpeech,
        resumeSpeech = controller::resumeReaderSpeech,
        pauseSpeech = controller::pauseReaderSpeech,
        translate = controller::translateReader,
        showOriginal = controller::showOriginalReader,
    ))
}

@Composable
internal fun ReaderScreenContent(ui: BrowserUiState, actions: ReaderActions) {
    val page = ui.readerPage ?: return
    val original = page.blocks.ifEmpty {
        page.text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            .map { ReadableBlock(it) }
    }
    val blocks = ui.readerTranslation ?: original
    val uriHandler = LocalUriHandler.current
    var speechMenu by remember { mutableStateOf(false) }
    var translationMenu by remember { mutableStateOf(false) }
    var attributionOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(page.title.ifBlank { stringResource(R.string.reader_mode) },
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Text(AddressResolver.displayUrl(page.url), maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.readerTranslation == null) {
                    Column {
                        TextButton(onClick = { speechMenu = true },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.reader_voice_language,
                                languages.firstOrNull { it.first == ui.readerSpeechLanguage }?.second
                                    ?.let { stringResource(it) } ?: ui.readerSpeechLanguage))
                        }
                        DropdownMenu(expanded = speechMenu, onDismissRequest = { speechMenu = false }) {
                            languages.forEach { (tag, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                    speechMenu = false
                                    actions.setSpeechLanguage(tag)
                                })
                            }
                        }
                    }
                }
                Button(onClick = actions.toggleSpeech,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (ui.readerSpeaking) R.string.reader_stop_speech
                        else R.string.reader_start_speech))
                }
                if (ui.readerSpeaking) TextButton(
                    onClick = if (ui.readerSpeechPaused) actions.resumeSpeech
                        else actions.pauseSpeech,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (ui.readerSpeechPaused) R.string.reader_resume_speech
                        else R.string.reader_pause_speech))
                }
                if (ui.readerTranslation == null) {
                    Column {
                        Button(onClick = { translationMenu = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !ui.readerTranslating) {
                            Text(stringResource(R.string.reader_translate))
                        }
                        DropdownMenu(expanded = translationMenu,
                            onDismissRequest = { translationMenu = false }) {
                            languages.forEach { (tag, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                    translationMenu = false
                                    actions.translate(tag)
                                })
                            }
                        }
                    }
                    if (ui.readerTranslating) TextButton(onClick = actions.showOriginal,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                } else TextButton(onClick = actions.showOriginal,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_show_original))
                }
                if (ui.readerTranslating) {
                    if (ui.readerTranslationTotal == 0) {
                        Text(stringResource(R.string.reader_translation_model))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.reader_translation_progress,
                            ui.readerTranslationProgress, ui.readerTranslationTotal))
                        LinearProgressIndicator(progress = {
                            ui.readerTranslationProgress.toFloat() / ui.readerTranslationTotal
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (ui.readerTranslation != null) {
                    TextButton(onClick = { attributionOpen = true },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.reader_translation_attribution))
                    }
                    Text(stringResource(R.string.reader_translation_notice),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.reader_translation_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(blocks) { block ->
            Text(block.text,
                modifier = if (block.heading) Modifier.semantics { heading() } else Modifier,
                style = if (block.heading) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.bodyLarge,
                lineHeight = if (block.heading) MaterialTheme.typography.titleLarge.lineHeight
                    else MaterialTheme.typography.bodyLarge.lineHeight)
        }
    }
    if (attributionOpen) AlertDialog(onDismissRequest = { attributionOpen = false },
        title = { Text(stringResource(R.string.reader_translation_attribution)) },
        text = { Text(stringResource(R.string.reader_translation_disclaimer)) },
        confirmButton = { TextButton(onClick = { attributionOpen = false }) {
            Text(stringResource(R.string.dialog_confirm))
        } },
        dismissButton = { TextButton(onClick = {
            attributionOpen = false
            uriHandler.openUri("https://translate.google.com")
        }) { Text(stringResource(R.string.reader_translation_brand_link)) } })
}
