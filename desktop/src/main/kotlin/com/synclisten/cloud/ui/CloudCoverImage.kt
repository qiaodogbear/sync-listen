package com.synclisten.cloud.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.synclisten.cloud.CloudCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CloudCoverImage(url: String, size: Dp = 56.dp) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            val bytes = CloudCover.fetch(url) ?: return@withContext null
            try {
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                if (image.width !in 1..4096 || image.height !in 1..4096) null else image.toComposeImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    if (bitmap != null) Image(bitmap!!, contentDescription = "专辑封面", modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    else Surface(modifier = Modifier.size(size), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Text("♪", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
