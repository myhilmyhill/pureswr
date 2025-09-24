package myhilmyhill.pureswr.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import myhilmyhill.pureswr.data.model.Entry
import myhilmyhill.pureswr.data.model.FolderEntry
import myhilmyhill.pureswr.data.model.MusicEntry
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.SubsonicRepository

class FolderViewModelFactory(
    private val subsonicRepository: SubsonicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderViewModel(userPreferencesRepository = userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    folderViewModelFactory: FolderViewModelFactory,
    onNavigateToSettings: () -> Unit
) {
    val folderViewModel: FolderViewModel = viewModel(factory = folderViewModelFactory)
    val uiState by folderViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        folderViewModel.loadFolder(null) // Changed to load root folder by default
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = uiState.folder?.name ?: "Subsonic Explorer") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                uiState.isLoading && uiState.folder == null -> {
                    CircularProgressIndicator()
                }
                uiState.error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${uiState.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Button(onClick = { folderViewModel.loadFolder(uiState.folder?.id) }) {
                           Text("Retry")
                        }
                    }
                }
                uiState.folder != null -> {
                    val folder = uiState.folder!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.isLoading) { 
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (folder.entries.isEmpty()) {
                            Text(
                                "This folder is empty.",
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
                            )
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(folder.entries) { entry ->
                                    EntryItem(
                                        entry = entry,
                                        onItemClick = { selectedEntry ->
                                            if (selectedEntry is FolderEntry) {
                                                folderViewModel.loadFolder(selectedEntry.id)
                                            } else if (selectedEntry is MusicEntry) {
                                                println("Clicked on music: ${selectedEntry.name} (ID: ${selectedEntry.id})")
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text("Welcome! Initializing...", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun EntryItem(entry: Entry, onItemClick: (Entry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick(entry) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (entry) {
            is FolderEntry -> Icons.Filled.Folder
            is MusicEntry -> Icons.Filled.MusicNote
        }
        Icon(
            imageVector = icon,
            contentDescription = entry.name,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
    }
}
