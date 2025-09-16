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
        enableEdgeToEdge()
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
                    Log.d(TAG, "actualCredentials changed: $actualCredentials")
                    if (actualCredentials != null) {
                        if (subsonicRepository?.matchesCredentials(actualCredentials) != true || subsonicRepository == null) {
                            Log.d(TAG, "Re-initializing SubsonicRepository and setting initial folder.")
                            subsonicRepository = SubsonicRepository(
                                baseUrl = actualCredentials.baseUrl,
                                username = actualCredentials.username,
                                password = actualCredentials.password
                            )
                            currentFolderId = "al-1" 
                            currentFolderName = "" 
                            folderHistory = emptyList()
                            folderEntries = emptyList()
                            Log.d(TAG, "actualCredentials - Setting folderLoadingState to IDLE (repo re-init)")
                            folderLoadingState = LoadingState.IDLE
                        }
                    } else {
                        if (subsonicRepository != null) {
                            Log.d(TAG, "Clearing SubsonicRepository because actualCredentials are null.")
                            subsonicRepository = null
                            folderEntries = emptyList()
                            Log.d(TAG, "actualCredentials - Setting folderLoadingState to IDLE (repo cleared)")
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null 
                            currentFolderName = ""
                            folderHistory = emptyList()
                        }
                    }
                }

                LaunchedEffect(subsonicRepository, currentFolderId) { 
                    Log.d(TAG, "FolderLoadEffect Triggered: repo=${subsonicRepository != null}, folderId=$currentFolderId, state=$folderLoadingState")
                    if (subsonicRepository != null && currentFolderId != null && folderLoadingState == LoadingState.IDLE) {
                        val folderIdToLoad = currentFolderId // Capture non-null value
                        
                        Log.d(TAG, "FolderLoadEffect: Condition met. Setting state to LOADING for folderId: $folderIdToLoad")
                        folderLoadingState = LoadingState.LOADING
                        folderLoadError = null
                        try {
                            Log.d(TAG, "FolderLoadEffect: Entering try block for folderId: $folderIdToLoad. Switching to Dispatchers.IO.")
                            val result = withContext(Dispatchers.IO) {
                                Log.d(TAG, "FolderLoadEffect: Inside Dispatchers.IO for folderId: $folderIdToLoad. Preparing to call getFolderContents with timeout.")
                                withTimeoutOrNull(20000L) { // 20 seconds timeout
                                    Log.d(TAG, "FolderLoadEffect: Calling subsonicRepository.getFolderContents for folderId: $folderIdToLoad")
                                    val folderResult = subsonicRepository!!.getFolderContents(folderIdToLoad) // Use captured value
                                    Log.d(TAG, "FolderLoadEffect: subsonicRepository.getFolderContents returned for folderId: $folderIdToLoad. Result: $folderResult")
                                    folderResult // Return the result
                                }
                            }
                            Log.d(TAG, "FolderLoadEffect: Returned from withContext(Dispatchers.IO) for folderId: $folderIdToLoad. Raw result: $result")

                            if (result == null) { // Timeout occurred
                                if (isActive) {
                                    val errorMsg = "Error: Folder loading timed out for folderId: $folderIdToLoad."
                                    Log.e(TAG, "FolderLoadEffect: Timeout occurred. $errorMsg")
                                    folderLoadError = errorMsg
                                    folderLoadingState = LoadingState.ERROR
                                    Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                                } else {
                                    Log.d(TAG, "FolderLoadEffect: Timeout occurred but coroutine is no longer active for folderId: $folderIdToLoad.")
                                }
                            } else { // Successful load
                                if (isActive) {
                                    Log.d(TAG, "FolderLoadEffect: Load successful for folderId: $folderIdToLoad. Entries count: ${result.entries.size}, Folder name: ${result.name}")
                                    folderEntries = result.entries
                                    currentFolderName = result.name
                                    folderLoadingState = LoadingState.SUCCESS
                                } else {
                                    Log.d(TAG, "FolderLoadEffect: Load successful but coroutine is no longer active for folderId: $folderIdToLoad.")
                                }
                            }
                        } catch (e: SubsonicApiException) {
                            if (isActive) {
                                val errorMsg = "API Error: ${e.message} (Code: ${e.code ?: "N/A"}) for folderId: $folderIdToLoad"
                                Log.e(TAG, "FolderLoadEffect: SubsonicApiException. $errorMsg", e)
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                            } else {
                                Log.d(TAG, "FolderLoadEffect: SubsonicApiException but coroutine is no longer active for folderId: $folderIdToLoad. This might indicate an issue if it still happens.", e)
                                val errorMsg = "API Error (coroutine inactive): ${e.message} (Code: ${e.code ?: "N/A"}) for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, "Folder load failed (coroutine became inactive).", Toast.LENGTH_LONG).show()                                
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                val errorMsg = "Error loading folder: ${e.message} for folderId: $folderIdToLoad"
                                Log.e(TAG, "FolderLoadEffect: Generic Exception. $errorMsg", e)
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, folderLoadError, Toast.LENGTH_LONG).show()
                            } else {
                                Log.d(TAG, "FolderLoadEffect: Generic Exception but coroutine is no longer active for folderId: $folderIdToLoad. This might indicate an issue if it still happens.", e)
                                val errorMsg = "Generic error (coroutine inactive): ${e.message} for folderId: $folderIdToLoad"
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                Toast.makeText(context, "Folder load failed (coroutine became inactive).", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Log.d(TAG, "FolderLoadEffect Skipped: repo=${subsonicRepository != null}, folderId=$currentFolderId, state=$folderLoadingState")
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(if (actualCredentials == null) "PureSWR" else currentFolderName.ifEmpty { if(currentFolderId == "al-1") "Loading Album..." else "Loading..."} ) }, 
                            navigationIcon = {
                                if (folderHistory.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val previousFolder = folderHistory.last()
                                        Log.d(TAG, "Navigation Back: to folderId=${previousFolder.first}, name=${previousFolder.second}")
                                        currentFolderId = previousFolder.first
                                        folderHistory = folderHistory.dropLast(1)
                                        Log.d(TAG, "Navigation Back - Setting folderLoadingState to IDLE")
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
                                    Log.d("UI_STATE_CHECK", "Displaying LOADING UI for $currentFolderName")
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                        Text(
                                            if (currentFolderName.isNotEmpty()) "Loading $currentFolderName..." else "Loading...", 
                                            modifier = Modifier.padding(top = 70.dp)
                                        )
                                    }
                                }
                                LoadingState.SUCCESS -> {
                                    Log.d("UI_STATE_CHECK", "Displaying SUCCESS UI for $currentFolderName")
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
                                    Log.d("UI_STATE_CHECK", "Displaying ERROR UI for $currentFolderName. Error: $folderLoadError")
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
                                    Log.d("UI_STATE_CHECK", "Displaying IDLE UI for $currentFolderName")
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
