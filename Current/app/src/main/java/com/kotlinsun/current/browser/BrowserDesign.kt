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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotlinsun.current.R

@Composable
internal fun CurrentBrandIcon(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painter = painterResource(if (dark) R.drawable.current_icon_dark else R.drawable.current_icon_light),
        contentDescription = null,
        modifier = modifier.shadow(4.dp, shape).clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape),
    )
}

@Composable
internal fun BrowserGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    focused: Boolean = false,
    error: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val top = if (dark) Color(0xFF282828).copy(alpha = 0.99f)
        else Color.White.copy(alpha = 0.99f)
    val bottom = if (dark) Color(0xFF1B1B1B).copy(alpha = if (strong) 0.99f else 0.97f)
        else Color(0xFFF2F2F2).copy(alpha = if (strong) 0.99f else 0.97f)
    val stroke = when {
        error -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        dark -> Color.White.copy(alpha = 0.32f)
        else -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.shadow(if (strong) 20.dp else 12.dp, shape)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)), shape)
        .border(BorderStroke(if (error) 2.dp else if (focused) 1.5.dp else 1.dp, stroke), shape),
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
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CurrentBrandIcon(Modifier.size(24.dp), RoundedCornerShape(8.dp))
                    Text("Current", modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("WEB BROWSER", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(38.dp))
        WelcomeCollage()
        Spacer(Modifier.height(36.dp))
        Text("새로운 탐색의 시작", style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("필요한 페이지를 빠르게 찾고, 마음에 드는 곳은 편하게 다시 만나세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        Button(onClick = onStart, modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(min = 56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary)) {
            Text("시작하기", style = MaterialTheme.typography.labelLarge)
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
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
        Surface(Modifier.align(Alignment.TopStart).offset(x = 5.dp, y = 30.dp)
            .width(cardWidth).height(180.dp).rotate(-8f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.padding(16.dp)) {
                MiniAddress("current.app")
                Spacer(Modifier.height(20.dp))
                Icon(Icons.Filled.Language, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(35.dp))
                Spacer(Modifier.height(10.dp))
                Text("세상을 탐색하세요", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("언제나 손끝에서", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 4.dp)
            .width(cardWidth * 0.82f).height(177.dp).rotate(9f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 12.dp) {
            Column(Modifier.padding(18.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(25.dp))
                Text("무엇을 찾으세요?", color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(28.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f), CircleShape))
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(10.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f), CircleShape))
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp)
            .width(maxWidth * 0.81f).height(166.dp).rotate(-3f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.padding(18.dp)) {
                MiniAddress("내 탭")
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(50.dp), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.BookmarkBorder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("좋아하는 페이지", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium)
                        Text("언제든 다시 열기", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
            }
        }
    }
}

@Composable
private fun MiniAddress(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
