package com.u1.slicer.browse

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.browse.printables.PrintablesPrint

@Composable
internal fun PrintablesModelCard(
    model: PrintablesPrint,
    source: String,
    thumbnails: PrintablesThumbnailLoader,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        PrintablesThumbnail(
            model.imageUrl,
            thumbnails,
            Modifier.fillMaxWidth().aspectRatio(4f / 3f),
        )
        ModelAttribution(model, source, Modifier.padding(12.dp), expanded = false)
    }
}

@Composable
internal fun PrintablesModelHeading(
    model: PrintablesPrint,
    source: String,
    thumbnails: PrintablesThumbnailLoader,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = MaterialTheme.shapes.medium) {
            PrintablesThumbnail(model.imageUrl, thumbnails, Modifier.size(80.dp))
        }
        ModelAttribution(model, source, Modifier.weight(1f), expanded = true)
    }
}

@Composable
private fun ModelAttribution(model: PrintablesPrint, source: String, modifier: Modifier, expanded: Boolean) {
    val author = model.author?.takeIf(String::isNotBlank)
    val attribution = if (author == null) source else stringResource(R.string.browse_model_attribution, author, source)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = model.name,
            style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class ThumbnailState(val loading: Boolean, val bitmap: Bitmap? = null)

@Composable
private fun PrintablesThumbnail(url: String?, thumbnails: PrintablesThumbnailLoader, modifier: Modifier) {
    key(url, thumbnails) {
        val state by produceState(ThumbnailState(loading = !url.isNullOrBlank())) {
            value = ThumbnailState(loading = false, bitmap = url?.takeIf(String::isNotBlank)?.let { thumbnails.load(it) })
        }
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val bitmap = state.bitmap
                when {
                    bitmap != null -> Image(
                        bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                    state.loading -> {
                        val loadingDescription = stringResource(R.string.browse_thumbnail_loading)
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).semantics { contentDescription = loadingDescription },
                            strokeWidth = 2.dp,
                        )
                    }
                    else -> Icon(
                        Icons.Outlined.ImageNotSupported,
                        contentDescription = stringResource(R.string.browse_thumbnail_unavailable),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}
