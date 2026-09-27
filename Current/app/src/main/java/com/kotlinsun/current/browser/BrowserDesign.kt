package com.kotlinsun.current.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotlinsun.current.R

@Composable
internal fun CurrentBrandIcon(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    Image(
        painter = painterResource(R.drawable.current_icon_final),
        contentDescription = null,
        modifier = modifier.clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    )
}

@Composable
internal fun BrowserInputSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focused: Boolean = false,
    error: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val stroke = when {
        error -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant, shape)
        .border(BorderStroke(if (error || focused) 2.dp else 1.dp, stroke), shape),
        content = content)
}

@Composable
internal fun WelcomeScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CurrentBrandIcon(Modifier.size(24.dp), MaterialTheme.shapes.small)
                    Text(stringResource(R.string.app_name), modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(stringResource(R.string.welcome_browser_label), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(38.dp))
        WelcomeCollage()
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.welcome_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        Button(onClick = onStart, modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WelcomeCollage() {
    BoxWithConstraints(Modifier.widthIn(max = 440.dp).fillMaxWidth().height(352.dp)
        .clearAndSetSemantics { }) {
        val cardWidth = maxWidth * 0.62f
        Box(Modifier.align(Alignment.Center).size(250.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large))
        Surface(Modifier.align(Alignment.TopStart).offset(x = 5.dp, y = 30.dp)
            .width(cardWidth).height(180.dp).rotate(-3f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp)) {
                MiniAddress("current.app")
                Spacer(Modifier.height(20.dp))
                Icon(Icons.Filled.Language, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(35.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.welcome_explore), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.welcome_anytime), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 4.dp)
            .width(cardWidth * 0.82f).height(177.dp).rotate(3f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 4.dp) {
            Column(Modifier.padding(18.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(25.dp))
                Text(stringResource(R.string.new_tab_title), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(28.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f),
                        MaterialTheme.shapes.small))
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(10.dp)
                    .background(MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.small))
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp)
            .width(maxWidth * 0.81f).height(166.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp)) {
                MiniAddress(stringResource(R.string.welcome_my_tabs))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(50.dp), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.BookmarkBorder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.welcome_favorite), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.welcome_reopen), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.small))
            }
        }
    }
}

@Composable
private fun MiniAddress(label: String) {
    Surface(shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
