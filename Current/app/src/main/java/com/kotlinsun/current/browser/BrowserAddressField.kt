package com.kotlinsun.current.browser

import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.R
import com.kotlinsun.current.ui.theme.currentAccentTextColor
import com.kotlinsun.current.ui.theme.currentPlaceholderColor

@Composable
internal fun BrowserAddressField(
    ui: BrowserUiState,
    value: TextFieldValue,
    editing: Boolean,
    isError: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onSuggestion: (AddressSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    suggestionsVisible: Boolean = false,
) {
    val isHome = ui.selectedTab?.url == null
    val inputDescription = stringResource(R.string.address_input_description)
    val fieldShape = MaterialTheme.shapes.medium
    val addressInput: @Composable () -> Unit = {
        if (ui.activeMode == TabMode.PRIVATE) {
            PrivateAddressInput(value, inputDescription, onValueChange, onFocusChange,
                onSubmit, isError, isHome || editing)
        } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { contentDescription = inputDescription },
            singleLine = true,
            isError = isError,
            placeholder = { Text(stringResource(R.string.address_or_search),
                color = currentPlaceholderColor()) },
            leadingIcon = {
                Icon(if (isError) Icons.Filled.ErrorOutline else if (isHome || editing) Icons.Filled.Search else if (
                    ui.selectedTab?.url?.startsWith("https://", true) == true) Icons.Filled.Lock
                else Icons.Filled.Language,
                    contentDescription = if (isError) stringResource(R.string.address_input_error) else null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (isHome || editing) IconButton(onClick = onSubmit,
                    modifier = Modifier.size(48.dp)) {
                    if (flat) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.address_submit),
                            modifier = Modifier.size(22.dp))
                    } else {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.address_submit),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            },
            shape = fieldShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
        }
    }
    Box(modifier) {
        if (flat) {
            val stroke = when {
                isError -> MaterialTheme.colorScheme.error
                editing -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Surface(Modifier.fillMaxWidth(), shape = fieldShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(if (isError) 2.dp else if (editing) 1.5.dp else 1.dp, stroke),
                tonalElevation = 0.dp) { addressInput() }
        } else {
            BrowserInputSurface(Modifier.fillMaxWidth(), shape = fieldShape,
                focused = editing, error = isError) { addressInput() }
        }
        DropdownMenu(expanded = editing && suggestionsVisible && ui.suggestions.isNotEmpty(),
            onDismissRequest = onDismissSuggestions,
            offset = DpOffset(0.dp, 8.dp),
            modifier = Modifier.widthIn(max = 360.dp).heightIn(max =
                if (LocalConfiguration.current.screenHeightDp < 480) 180.dp else 320.dp),
            properties = PopupProperties(focusable = false),
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            ui.suggestions.forEach { suggestion ->
                val suggestionDescription = stringResource(R.string.suggestion_description,
                    stringResource(suggestion.sourceRes), suggestion.title, suggestion.url)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(suggestion.title, maxLines = 1)
                            Text(stringResource(suggestion.sourceRes), style = MaterialTheme.typography.bodySmall,
                                color = currentAccentTextColor())
                            Text(suggestion.url, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = suggestionDescription
                    },
                    onClick = { onSuggestion(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun PrivateAddressInput(
    value: TextFieldValue,
    description: String,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    isError: Boolean,
    showSubmit: Boolean,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val placeholder = MaterialTheme.colorScheme.onSurfaceVariant
    val hint = stringResource(R.string.address_or_search)
    val submit = stringResource(R.string.address_submit)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Search,
            contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(factory = { context ->
            EditText(context).apply {
                isSingleLine = true
                setSelectAllOnFocus(true)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setHint(hint)
                contentDescription = description
                setTextColor(android.graphics.Color.rgb((foreground.red * 255).toInt(),
                    (foreground.green * 255).toInt(), (foreground.blue * 255).toInt()))
                setHintTextColor(android.graphics.Color.rgb((placeholder.red * 255).toInt(),
                    (placeholder.green * 255).toInt(), (placeholder.blue * 255).toInt()))
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO) { onSubmit(); true } else false
                }
                setOnFocusChangeListener { _, focused -> onFocusChange(focused) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val text = s?.toString().orEmpty()
                        val composingStart = s?.let(BaseInputConnection::getComposingSpanStart) ?: -1
                        val composingEnd = s?.let(BaseInputConnection::getComposingSpanEnd) ?: -1
                        onValueChange(TextFieldValue(text, TextRange(selectionStart.coerceIn(0, text.length),
                            selectionEnd.coerceIn(0, text.length)),
                            composition = if (composingStart >= 0 && composingEnd >= composingStart)
                                TextRange(composingStart, composingEnd) else null))
                    }
                })
            }
        }, update = { input ->
            input.hint = hint
            input.contentDescription = description
            if (input.text.toString() != value.text) {
                input.setText(value.text)
                input.setSelection(value.selection.end.coerceIn(0, value.text.length))
            }
        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        if (showSubmit) IconButton(onClick = onSubmit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = submit)
        }
    }
}
