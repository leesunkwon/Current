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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsun.current.R
import androidx.compose.ui.res.stringResource

@Composable
internal fun ReaderScreen(ui: BrowserUiState, controller: BrowserController) {
    val page = ui.readerPage ?: return
    val paragraphs = page.text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(page.title.ifBlank { stringResource(R.string.reader_mode) },
                    style = MaterialTheme.typography.headlineMedium)
                Text(AddressResolver.displayUrl(page.url), maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = controller::toggleReaderSpeech,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (ui.readerSpeaking) R.string.reader_stop_speech
                        else R.string.reader_start_speech))
                }
            }
        }
        items(paragraphs) { paragraph ->
            Text(paragraph, style = MaterialTheme.typography.bodyLarge,
                lineHeight = 28.sp)
        }
    }
}
