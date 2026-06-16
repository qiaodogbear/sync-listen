package com.synclisten.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MemberAvatar(
    emoji: String,
    displayName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content = @Composable {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji.isNotBlank()) {
                Text(emoji, fontSize = (size.value * 0.5f).sp, textAlign = TextAlign.Center)
            } else {
                Text(
                    displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick != null) {
        Box(modifier = modifier.clickable(onClick = onClick)) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}
