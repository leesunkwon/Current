package com.kotlinsun.current.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
    val fieldShape = if (flat) RoundedCornerShape(8.dp) else RoundedCornerShape(10.dp)
    val addressInput: @Composable () -> Unit = {
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
            shape = MaterialTheme.shapes.medium,
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
