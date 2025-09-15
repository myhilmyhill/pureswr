package myhilmyhill.pureswr

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import myhilmyhill.pureswr.data.model.Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.Entry
import myhilmyhill.pureswr.data.repository.SubsonicApiException
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import myhilmyhill.pureswr.ui.FolderDisplay
import myhilmyhill.pureswr.ui.SettingsDialog
import myhilmyhill.pureswr.ui.theme.PureswrTheme
import javax.inject.Inject

enum class LoadingState {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

// Sentinel object to represent the state before credentials have been loaded from DataStore
private object CredentialsLoadingMarker

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    private var subsonicRepository: SubsonicRepository? by mutableStateOf(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PureswrTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                // credentialsLoadingState can be CredentialsLoadingMarker initially, then Credentials?, or null.
                val credentialsLoadingState: Any? by userPreferencesRepository.credentialsFlow
                    .collectAsStateWithLifecycle(initialValue = CredentialsLoadingMarker)

                var showSettingsDialog by remember { mutableStateOf(false) }
                // This flag tracks if we have made the initial decision about showing the settings dialog.
                var initialDialogDecisionMade by remember { mutableStateOf(false) }

                var currentFolderId by remember { mutableStateOf<String?>(null) }
                var currentFolderName by remember { mutableStateOf("") }
                var folderEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
                var folderLoadingState by remember { mutableStateOf(LoadingState.IDLE) }
                var folderLoadError by remember { mutableStateOf<String?>(null) }
                var folderHistory by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }

                // Derived state: actual Credentials object or null, once loaded.
                val actualCredentials = if (credentialsLoadingState === CredentialsLoadingMarker) {
                    null // Not yet loaded
                } else {
                    credentialsLoadingState as? Credentials // Loaded, could be null or Credentials obj
                }

                // Effect to decide if the initial settings dialog should be shown, once and only once.
                LaunchedEffect(credentialsLoadingState, initialDialogDecisionMade) {
                    if (credentialsLoadingState !== CredentialsLoadingMarker && !initialDialogDecisionMade) {
                        // Credentials have loaded and we haven'''t made the initial dialog decision yet.
                        if (actualCredentials == null) {
                            // If, after the initial load, credentials are confirmed to be null,
                            // then show the settings dialog.
                            showSettingsDialog = true
                        }
                        // Mark that we'''ve made the initial decision, regardless of whether dialog was shown.
                        initialDialogDecisionMade = true
                    }
                }

                // Effect to manage SubsonicRepository and reset folder state when actualCredentials change
                LaunchedEffect(actualCredentials) {
                    if (actualCredentials != null) {
                        if (subsonicRepository?.matchesCredentials(actualCredentials) != true || subsonicRepository == null) {
                            subsonicRepository = SubsonicRepository(
                                baseUrl = actualCredentials.baseUrl,
                                username = actualCredentials.username,
                                password = actualCredentials.password
                            )
                            // Reset to root folder when credentials change or are first loaded
                            currentFolderId = SubsonicRepository.SYNTHETIC_ROOT_ID
                            currentFolderName = SubsonicRepository.SYNTHETIC_ROOT_NAME // Or fetch from getFolderContents
                            folderHistory = emptyList()
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE // Trigger reload for the new repo/creds
                        }
                    } else { // actualCredentials is null
                        if (subsonicRepository != null) {
                            subsonicRepository = null // Clear repository if credentials are cleared
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null
                            currentFolderName = ""
                            folderHistory = emptyList()
                        }
                    }
                }

                // Effect to load folder contents
                LaunchedEffect(subsonicRepository, currentFolderId, folderLoadingState) {
                    if (subsonicRepository != null && currentFolderId != null && folderLoadingState == LoadingState.IDLE) {
                        folderLoadingState = LoadingState.LOADING
                        folderLoadError = null
                        try {
                            // Ensure currentFolderName is updated based on actual loaded folder
                            val result = subsonicRepository!!.getFolderContents(currentFolderId!!) // currentFolderId known not null here
                            folderEntries = result.entries
                            currentFolderName = result.name
                            folderLoadingState = LoadingState.SUCCESS
                        } catch (e: SubsonicApiException) {
                            folderLoadError = "API Error: ${e.message} (Code: ${e.code ?: "N/A"})"
                            folderLoadingState = LoadingState.ERROR
                            Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            folderLoadError = "Error loading folder: ${e.message}"
                            folderLoadingState = LoadingState.ERROR
                            Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(if (actualCredentials == null || (folderHistory.isEmpty() && currentFolderName == SubsonicRepository.SYNTHETIC_ROOT_NAME)) "PureSWR" else currentFolderName) },
                            navigationIcon = {
                                if (folderHistory.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val previousFolder = folderHistory.last()
                                        currentFolderId = previousFolder.first
                                        // currentFolderName = previousFolder.second // Will be updated on load
                                        folderHistory = folderHistory.dropLast(1)
                                        folderLoadingState = LoadingState.IDLE // Trigger reload for previous folder
                                    }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    // User explicitly opens settings
                                    showSettingsDialog = true
                                }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (credentialsLoadingState === CredentialsLoadingMarker) {
                            // Show a global loading indicator while credentials are being loaded initially.
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
                                Text("Loading settings...", modifier = Modifier.padding(top = 70.dp))
                            }
                        } else if (showSettingsDialog) {
                            // Show settings dialog if showSettingsDialog is true (either by initial check or user action)
                            SettingsDialog(
                                currentBaseUrl = actualCredentials?.baseUrl ?: "",
                                currentUsername = actualCredentials?.username ?: "",
                                currentPassword = actualCredentials?.password ?: "",
                                onDismissRequest = {
                                    showSettingsDialog = false
                                    // If dismissed without saving and credentials are still null,
                                    // the `else if (actualCredentials == null)` block below will handle showing the prompt.
                                },
                                onSave = { newCredentials ->
                                    scope.launch {
                                        userPreferencesRepository.saveCredentials(newCredentials)
                                    }
                                    // Saving credentials will trigger `actualCredentials` update via Flow.
                                    // `LaunchedEffect(actualCredentials)` will handle repo re-init.
                                    // `initialDialogDecisionMade` is already true, so initial dialog logic won'''t re-run.
                                    showSettingsDialog = false // Explicitly hide after save.
                                }
                            )
                        } else if (actualCredentials == null) {
                            // Credentials loading is complete, they are null, and dialog is not (or no longer) shown.
                            // This state means user needs to configure.
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Please configure your Subsonic server.")
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { showSettingsDialog = true }) {
                                        Text("Open Settings")
                                    }
                                }
                            }
                        } else if (subsonicRepository != null && currentFolderId != null) {
                            // Credentials are available, repository is initialized, and a folder is selected.
                            when (folderLoadingState) {
                                LoadingState.LOADING -> {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                        Text(
                                            if (currentFolderName.isNotEmpty() && currentFolderName != SubsonicRepository.SYNTHETIC_ROOT_NAME) "Loading $currentFolderName..." else "Loading...",
                                            modifier = Modifier.padding(top = 70.dp)
                                        )
                                    }
                                }
                                LoadingState.SUCCESS -> {
                                    FolderDisplay(
                                        modifier = Modifier.fillMaxSize(),
                                        entries = folderEntries,
                                        onFolderClick = { folder ->
                                            folderHistory = folderHistory + (currentFolderId to currentFolderName) // Save current state
                                            currentFolderId = folder.id
                                            // currentFolderName = folder.name // Will be updated by LaunchedEffect on load
                                            folderLoadingState = LoadingState.IDLE
                                        },
                                        onFileClick = { file ->
                                            Toast.makeText(context, "Clicked file: ${file.name}", Toast.LENGTH_SHORT).show()
                                            // TODO: Implement music playback
                                        }
                                    )
                                }
                                LoadingState.ERROR -> {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Failed to load folder: ${currentFolderName.ifEmpty { "selected folder" }}")
                                        folderLoadError?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = { folderLoadingState = LoadingState.IDLE }) { // Retry
                                            Text("Retry")
                                        }
                                    }
                                }
                                LoadingState.IDLE -> {
                                    // This state should be brief if repo and folderId are set.
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                        Text("Initializing folder view...", modifier = Modifier.padding(top = 70.dp))
                                    }
                                }
                            }
                        } else if (actualCredentials != null && subsonicRepository == null) {
                            // Credentials available, but repository not yet initialized (should be very brief)
                             Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
                                Text("Connecting to server...", modifier = Modifier.padding(top = 70.dp))
                            }
                        } else {
                            // Fallback for any other unhandled state (e.g. currentFolderId is null but creds exist)
                             Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("Please select a folder or check settings.") // Or a more specific loading/error
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper extension for SubsonicRepository to check if it matches given credentials
fun SubsonicRepository.matchesCredentials(creds: Credentials): Boolean {
    return this.baseUrl == creds.baseUrl && this.username == creds.username // Password comparison might not be directly possible/needed if instance is re-created
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PureswrTheme {
        // Minimal preview, as MainActivity is complex
        Text("PureSWR App Preview")
    }
}
