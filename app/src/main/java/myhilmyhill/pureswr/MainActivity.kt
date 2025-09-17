package myhilmyhill.pureswr

import android.os.Bundle
import android.util.Log
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

private object CredentialsLoadingMarker

const val TAG = "FolderLoadEffect"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    private var subsonicRepository: SubsonicRepository? by mutableStateOf(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PureswrTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val credentialsLoadingState: Any? by userPreferencesRepository.credentialsFlow
                    .collectAsStateWithLifecycle(initialValue = CredentialsLoadingMarker)

                var showSettingsDialog by remember { mutableStateOf(false) }
                var initialDialogDecisionMade by remember { mutableStateOf(false) }

                var currentFolderId by remember { mutableStateOf<String?>("al-1") } 
                var currentFolderName by remember { mutableStateOf("") } 
                var folderEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
                var folderLoadingState by remember { mutableStateOf(LoadingState.IDLE) }
                var folderLoadError by remember { mutableStateOf<String?>(null) }
                var folderHistory by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }

                val actualCredentials = if (credentialsLoadingState === CredentialsLoadingMarker) {
                    null
                } else {
                    credentialsLoadingState as? Credentials
                }

                LaunchedEffect(credentialsLoadingState, initialDialogDecisionMade) {
                    if (credentialsLoadingState !== CredentialsLoadingMarker && !initialDialogDecisionMade) {
                        if (actualCredentials == null) {
                            showSettingsDialog = true
                        }
                        initialDialogDecisionMade = true
                    }
                }

                LaunchedEffect(actualCredentials) {
                    if (actualCredentials != null) {
                        if (subsonicRepository?.matchesCredentials(actualCredentials) != true || subsonicRepository == null) {
                            subsonicRepository = SubsonicRepository(
                                baseUrl = actualCredentials.baseUrl,
                                username = actualCredentials.username,
                                password = actualCredentials.password
                            )
                            currentFolderId = "al-1" 
                            currentFolderName = "" 
                            folderHistory = emptyList()
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                        }
                    } else {
                        if (subsonicRepository != null) {
                            subsonicRepository = null
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null 
                            currentFolderName = ""
                            folderHistory = emptyList()
                        }
                    }
                }

                LaunchedEffect(subsonicRepository, currentFolderId) { 
                    if (subsonicRepository != null && currentFolderId != null && folderLoadingState == LoadingState.IDLE) {
                        val folderIdToLoad = currentFolderId // Capture non-null value
                        
                        folderLoadingState = LoadingState.LOADING
                        folderLoadError = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                withTimeoutOrNull(20000L) { // 20 seconds timeout
                                    val folderResult = subsonicRepository!!.getFolderContents(folderIdToLoad) // Use captured value
                                    folderResult // Return the result
                                }
                            }

                            if (result == null) { // Timeout occurred
                                if (isActive) {
                                    val errorMsg = "Error: Folder loading timed out for folderId: $folderIdToLoad."
                                    folderLoadError = errorMsg
                                    folderLoadingState = LoadingState.ERROR
                                    Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                                }
                            } else { // Successful load
                                if (isActive) {
                                    folderEntries = result.entries
                                    currentFolderName = result.name
                                    folderLoadingState = LoadingState.SUCCESS
                                }
                            }
                        } catch (e: SubsonicApiException) {
                            if (isActive) {
                                val errorMsg = "API Error: ${e.message} (Code: ${e.code ?: "N/A"}) for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                            } else {
                                val errorMsg = "API Error (coroutine inactive): ${e.message} (Code: ${e.code ?: "N/A"}) for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, "Folder load failed (coroutine became inactive).", Toast.LENGTH_LONG).show()                                
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                val errorMsg = "Error loading folder: ${e.message} for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                            } else {
                                val errorMsg = "Generic error (coroutine inactive): ${e.message} for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, "Folder load failed (coroutine became inactive).", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(currentFolderName.ifEmpty { if(currentFolderId == "al-1") "Loading Album..." else "Loading..."} ) },
                            navigationIcon = {
                                if (folderHistory.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val previousFolder = folderHistory.last()
                                        currentFolderId = previousFolder.first
                                        folderHistory = folderHistory.dropLast(1)
                                        folderLoadingState = LoadingState.IDLE
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = {
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
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
                                Text("Loading settings...", modifier = Modifier.padding(top = 70.dp))
                            }
                        } else if (showSettingsDialog) {
                            SettingsDialog(
                                currentBaseUrl = actualCredentials?.baseUrl ?: "",
                                currentUsername = actualCredentials?.username ?: "",
                                currentPassword = actualCredentials?.password ?: "",
                                onDismissRequest = {
                                    showSettingsDialog = false
                                },
                                onSave = { newCredentials ->
                                    scope.launch {
                                        userPreferencesRepository.saveCredentials(newCredentials)
                                    }
                                    showSettingsDialog = false
                                }
                            )
                        } else if (actualCredentials == null) {
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
                            Log.d("UI_STATE_CHECK", "Evaluating UI for folder. folderLoadingState is $folderLoadingState, currentFolderId is $currentFolderId")
                            when (folderLoadingState) {
                                LoadingState.LOADING -> {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                        Text(
                                            if (currentFolderName.isNotEmpty()) "Loading $currentFolderName..." else "Loading...", 
                                            modifier = Modifier.padding(top = 70.dp)
                                        )
                                    }
                                }
                                LoadingState.SUCCESS -> {
                                    FolderDisplay(
                                        modifier = Modifier.fillMaxSize(),
                                        entries = folderEntries,
                                        onFolderClick = { folder ->
                                            Log.d(TAG, "FolderClick: to folderId=${folder.id}, name=${folder.name}")
                                            folderHistory = folderHistory + (currentFolderId to currentFolderName)
                                            currentFolderId = folder.id
                                            Log.d(TAG, "FolderClick - Setting folderLoadingState to IDLE")
                                            folderLoadingState = LoadingState.IDLE
                                        },
                                        onFileClick = { file ->
                                            Toast.makeText(context, "Clicked file: ${file.name}", Toast.LENGTH_SHORT).show()
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
                                        Button(onClick = { 
                                            Log.d(TAG, "Retry button clicked - Setting folderLoadingState to IDLE")
                                            folderLoadingState = LoadingState.IDLE
                                        }) { 
                                            Text("Retry")
                                        }
                                    }
                                }
                                LoadingState.IDLE -> {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                        Text("Initializing folder view...", modifier = Modifier.padding(top = 70.dp))
                                    }
                                }
                            }
                        } else if (actualCredentials != null && subsonicRepository == null) {
                             Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
                                Text("Connecting to server...", modifier = Modifier.padding(top = 70.dp))
                            }
                        } else {
                             Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("Please select a folder or check settings.")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun SubsonicRepository.matchesCredentials(creds: Credentials): Boolean {
    return this.baseUrl == creds.baseUrl && this.username == creds.username
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PureswrTheme {
        Text("PureSWR App Preview")
    }
}
