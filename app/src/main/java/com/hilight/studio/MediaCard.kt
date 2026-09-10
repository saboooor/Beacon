package com.hilight.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MediaCard(store: Store) {
    val currentMedia by store.mediaTracker.currentMedia.collectAsStateWithLifecycle()
    val mediaSyncEnabled by store.mediaSyncEnabled.collectAsStateWithLifecycle()
    val isSample = store.mediaTracker.isSampleMode()

    PixelCard {
        SectionTitle(
            stringResource(R.string.media_card_title),
            trailing = {
                when {
                    isSample -> LivePill(stringResource(R.string.media_sample_badge), ok = true)
                    currentMedia?.isPlaying == true -> LivePill(stringResource(R.string.media_playing), ok = true)
                    currentMedia != null -> LivePill(stringResource(R.string.media_paused), ok = false)
                }
            },
        )
        Caption(stringResource(R.string.media_card_caption))

        if (currentMedia != null) {
            val media = currentMedia!!
            val previewAmbient = remember(media.colors) {
                Ambient(
                    pattern = Pattern.CUSTOM,
                    perLed = media.colors,
                    rotateMs = 1500,
                    rotateFade = true,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Artwork thumbnail or music icon
                if (media.artwork != null) {
                    Image(
                        bitmap = media.artwork.asImageBitmap(),
                        contentDescription = media.title,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(media.primaryColor).copy(alpha = 0.25f))
                            .border(1.dp, Color(media.primaryColor).copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color(media.primaryColor),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        media.title ?: stringResource(R.string.media_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!media.artist.isNullOrBlank()) {
                        Text(
                            media.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 8-LED Preview Disc
                HiLightDiscPreview(
                    pattern = Pattern.CUSTOM,
                    cfg = previewAmbient,
                    active = true,
                    modifier = Modifier.size(50.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 8 Color Swatches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                media.colors.forEachIndexed { _, c ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(Color(c), CircleShape)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                CircleShape,
                            ),
                    )
                }
            }
        } else {
            Caption(stringResource(R.string.media_no_playback))
        }

        // Live Media Sync Toggle
        ToggleRow(
            stringResource(R.string.media_sync_toggle),
            mediaSyncEnabled,
        ) {
            store.setMediaSyncEnabled(it)
        }
        Caption(stringResource(R.string.media_sync_hint))

        // Sample music button for easy testing & demo
        OutlinedButton(
            onClick = { store.mediaTracker.toggleSampleMedia() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonLabel(
                stringResource(
                    if (isSample) R.string.media_exit_sample
                    else R.string.media_test_sample
                )
            )
        }
    }
}

