package myhilmyhill.pureswr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import myhilmyhill.pureswr.data.repository.Entry
import myhilmyhill.pureswr.data.repository.FolderEntry
import myhilmyhill.pureswr.data.repository.MusicEntry
import myhilmyhill.pureswr.ui.theme.PureswrTheme

@Composable
fun FolderDisplay(
    modifier: Modifier = Modifier,
    entries: List<Entry>,
    onFolderClick: (FolderEntry) -> Unit,
    onFileClick: (MusicEntry) -> Unit,
    currentPlayingTrackId: String,
    isMusicPlaying: Boolean // New parameter for actual playback state
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
                        isCurrentTrack = entry.id == currentPlayingTrackId, // Is this the track in the player?
                        isActuallyPlaying = isMusicPlaying // Is the player currently playing?
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
    ListItem(name = entry.name, icon = Icons.Default.Folder, onClick = onClick)
}

@Composable
private fun MusicItem(
    entry: MusicEntry,
    onClick: () -> Unit,
    isCurrentTrack: Boolean, // Renamed for clarity
    isActuallyPlaying: Boolean // New parameter for actual playback state
) {
    val icon = if (isCurrentTrack) {
        if (isActuallyPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause
    } else {
        Icons.Default.AudioFile
    }
    ListItem(name = entry.name, icon = icon, onClick = onClick)
}

@Composable
private fun ListItem(
    name: String,
    icon: ImageVector,
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
        Icon(imageVector = icon, contentDescription = null)
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
            isMusicPlaying = true // m1 is playing
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FolderDisplayPaused() {
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
            currentPlayingTrackId = "m3",
            isMusicPlaying = false // m3 is the current track, but paused
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
            isMusicPlaying = false
        )
    }
}
