package myhilmyhill.pureswr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import myhilmyhill.pureswr.data.model.Entry
import myhilmyhill.pureswr.data.model.FolderEntry
import myhilmyhill.pureswr.data.model.MusicEntry
import myhilmyhill.pureswr.ui.theme.PureswrTheme

@Composable
fun FolderDisplay(
    modifier: Modifier = Modifier,
    entries: List<Entry>,
    onFolderClick: (FolderEntry) -> Unit,
    onFileClick: (MusicEntry) -> Unit,
    currentPlayingTrackId: String,
    isMusicPlaying: Boolean,
    isLoading: Boolean
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("This folder is empty.")
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            when (entry) {
                is FolderEntry -> {
                    FolderItem(entry = entry, onClick = { onFolderClick(entry) })
                }
                is MusicEntry -> {
                    MusicItem(
                        entry = entry,
                        onClick = { onFileClick(entry) },
                        isCurrentTrack = entry.id == currentPlayingTrackId,
                        isActuallyPlaying = isMusicPlaying,
                        isLoading = isLoading 
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderItem(
    entry: FolderEntry,
    onClick: () -> Unit
) {
    ListItem(
        name = entry.name,
        iconSlot = { Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder") },
        onClick = onClick
    )
}

@Composable
private fun MusicItem(
    entry: MusicEntry,
    onClick: () -> Unit,
    isCurrentTrack: Boolean,
    isActuallyPlaying: Boolean,
    isLoading: Boolean
) {
    ListItem(
        name = entry.name,
        iconSlot = { 
            if (isLoading && isCurrentTrack) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeCap = StrokeCap.Butt)
            } else {
                val iconToShow = if (isCurrentTrack) {
                    if (isActuallyPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause
                } else {
                    Icons.Default.AudioFile
                }
                Icon(
                    imageVector = iconToShow, 
                    contentDescription = if (isCurrentTrack) if (isActuallyPlaying) "Playing" else "Paused" else "Play file"
                )
            }
        },
        onClick = onClick
    )
}

@Composable
private fun ListItem(
    name: String,
    iconSlot: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        iconSlot()
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
fun FolderDisplayPreview() {
    PureswrTheme {
        val sampleEntries = listOf(
            FolderEntry(id = "f1", name = "My Favorite Albums", entries = emptyList()),
            MusicEntry(id = "m1", name = "Awesome Song.mp3", dir = "/My Favorite Albums"),
            MusicEntry(id = "m2", name = "Epic Theme.flac", dir = "/My Favorite Albums"),
            FolderEntry(id = "f2", name = "Soundtracks", entries = emptyList()),
            MusicEntry(id = "m3", name = "Paused Song.ogg", dir = "/Soundtracks")
        )
        FolderDisplay(
            entries = sampleEntries,
            onFolderClick = { },
            onFileClick = { },
            currentPlayingTrackId = "m1",
            isMusicPlaying = true,
            isLoading = false
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FolderDisplayPaused() {
    PureswrTheme {
        val sampleEntries = listOf(
            MusicEntry(id = "m3", name = "Paused Song.ogg", dir = "/Soundtracks")
        )
        FolderDisplay(
            entries = sampleEntries,
            onFolderClick = { },
            onFileClick = { },
            currentPlayingTrackId = "m3",
            isMusicPlaying = false,
            isLoading = false
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FolderDisplayLoadingPreview() {
    PureswrTheme {
        val sampleEntries = listOf(
            MusicEntry(id = "m1", name = "Another Song.mp3", dir = "/My Favorite Albums"),
            MusicEntry(id = "m2", name = "Epic Theme Being Loaded.flac", dir = "/My Favorite Albums"),
        )
        FolderDisplay(
            entries = sampleEntries,
            onFolderClick = { },
            onFileClick = { },
            currentPlayingTrackId = "m2",
            isMusicPlaying = false,
            isLoading = true
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FolderDisplayEmptyPreview() {
    PureswrTheme {
        FolderDisplay(
            entries = emptyList(),
            onFolderClick = {},
            onFileClick = {},
            currentPlayingTrackId = "",
            isMusicPlaying = false,
            isLoading = false
        )
    }
}
