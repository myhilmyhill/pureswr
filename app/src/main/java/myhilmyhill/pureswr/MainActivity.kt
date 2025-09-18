package myhilmyhill.pureswr

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
// import android.util.Log // Logcatで見る場合はこちらを使い、printlnをLog.d("YourTag", "message")に置き換えてください
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import myhilmyhill.pureswr.data.model.Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.Entry // Added import for Entry
import myhilmyhill.pureswr.data.repository.FolderEntry // Keep for other usages if any, or specific casts
import myhilmyhill.pureswr.data.repository.SubsonicApiException
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import myhilmyhill.pureswr.ui.FolderDisplay
import myhilmyhill.pureswr.ui.SettingsDialog
import myhilmyhill.pureswr.ui.theme.PureswrTheme

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class LoadingState { IDLE, LOADING, SUCCESS, ERROR }

private object CredentialsLoadingMarker

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private var mediaController: MediaController? = null
    private var currentActivityIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivityIntent = intent
        userPreferencesRepository = UserPreferencesRepository(this)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel("pureswr_player_channel")
            if (channel == null) {
                // Channel might be created by PlaybackService
            }
        }

        setContent {
            PureswrTheme {
                val scope = rememberCoroutineScope()

                var currentFolderId by remember { mutableStateOf<String?>(currentActivityIntent?.getStringExtra("folderId") ?: "al-1") }
                var currentFolderName by remember { mutableStateOf("") }
                var folderEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
                var folderHistory by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
                var folderLoadingState by remember { mutableStateOf(LoadingState.IDLE) }
                var folderLoadError by remember { mutableStateOf<String?>(null) }

                var showSettingsDialog by remember { mutableStateOf(false) }
                val credentialsLoadingState by userPreferencesRepository.credentialsFlow.collectAsState(initial = CredentialsLoadingMarker)
                var initialDialogDecisionMade by remember { mutableStateOf(false) }

                var currentBackEvent by remember { mutableStateOf<BackEventCompat?>(null) }

                val actualCredentials = if (credentialsLoadingState === CredentialsLoadingMarker) {
                    null
                } else {
                    credentialsLoadingState as? Credentials
                }

                var subsonicRepository by remember { mutableStateOf<SubsonicRepository?>(null) }

                LaunchedEffect(currentActivityIntent) {
                    val intentToProcess = currentActivityIntent // ローカルコピーを使用
                    val newFolderIdFromIntent = intentToProcess?.getStringExtra("folderId")
                    println("MainActivityFolderDebug: LaunchedEffect(currentActivityIntent) triggered. Intent: $intentToProcess, newFolderIdFromIntent: $newFolderIdFromIntent, currentFolderId (before change): $currentFolderId")

                    if (newFolderIdFromIntent != null && newFolderIdFromIntent != currentFolderId) {
                        println("MainActivityFolderDebug: Intent changed. New folderId: $newFolderIdFromIntent. Current (old): $currentFolderId")
                        currentFolderId = newFolderIdFromIntent
                        folderHistory = emptyList()
                        folderLoadingState = LoadingState.IDLE
                        println("MainActivityFolderDebug: State set to IDLE due to intent change. New Folder ID: $currentFolderId. History Cleared.")
                    } else if (newFolderIdFromIntent != null && newFolderIdFromIntent == currentFolderId) {
                        if (folderLoadingState != LoadingState.LOADING && folderLoadingState != LoadingState.IDLE) {
                            println("MainActivityFolderDebug: Same folderId ($newFolderIdFromIntent) received from intent, state was $folderLoadingState. Ensuring reload by setting to IDLE.")
                            folderLoadingState = LoadingState.IDLE
                        } else {
                             println("MainActivityFolderDebug: Same folderId ($newFolderIdFromIntent) received, and already loading or idle ($folderLoadingState). No state change needed here from LaunchedEffect.")
                        }
                    } else if (newFolderIdFromIntent == null && intentToProcess?.action == Intent.ACTION_MAIN) {
                         println("MainActivityFolderDebug: LaunchedEffect(currentActivityIntent) - Main action, no folderId from intent. currentFolderId: $currentFolderId. Ensuring load for default/current.")
                         if (currentFolderId == (intentToProcess.getStringExtra("folderId") ?: "al-1") && folderLoadingState != LoadingState.LOADING && folderLoadingState != LoadingState.SUCCESS) {
                            folderLoadingState = LoadingState.IDLE
                         }
                    } else {
                        println("MainActivityFolderDebug: LaunchedEffect(currentActivityIntent) - folderId did not change or was null. newFolderIdFromIntent: $newFolderIdFromIntent, currentFolderId: $currentFolderId, Intent: $intentToProcess")
                    }
                }

                if (folderHistory.isNotEmpty() && !showSettingsDialog && actualCredentials != null) {
                    PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
                        try {
                            progress.collect { event -> currentBackEvent = event }
                            val previousFolder = folderHistory.last()
                            currentFolderId = previousFolder.first
                            folderHistory = folderHistory.dropLast(1)
                            folderLoadingState = LoadingState.IDLE
                            println("MainActivityFolderDebug: State changed to IDLE (PredictiveBack). New Folder ID: $currentFolderId")
                        } catch (e: CancellationException) {
                            // Back gesture cancelled
                        } finally {
                            currentBackEvent = null
                        }
                    }
                }

                // Updated LaunchedEffect for showing settings dialog
                LaunchedEffect(credentialsLoadingState, initialDialogDecisionMade, currentActivityIntent) {
                    if (credentialsLoadingState !== CredentialsLoadingMarker && !initialDialogDecisionMade) {
                        val intentToInspect = currentActivityIntent // Capture for consistent use
                        val hasFolderIdFromIntent = intentToInspect?.getStringExtra("folderId") != null

                        println("MainActivityResolverDebug: Effect for dialog. CredentialsLoaded: ${credentialsLoadingState !== CredentialsLoadingMarker}, DecisionMade: $initialDialogDecisionMade, HasFolderId: $hasFolderIdFromIntent, ActualCredsNull: ${actualCredentials == null}")

                        if (actualCredentials == null && !hasFolderIdFromIntent) {
                            println("MainActivityResolverDebug: SHOWING settings dialog. Reason: No credentials AND no folderId from intent.")
                            showSettingsDialog = true
                        } else {
                            println("MainActivityResolverDebug: NOT showing settings dialog. (ActualCredsNull: ${actualCredentials == null}, HasFolderId: $hasFolderIdFromIntent)")
                        }
                        initialDialogDecisionMade = true
                    } else {
                        println("MainActivityResolverDebug: Effect for dialog SKIPPED. (CS_Marker: ${credentialsLoadingState === CredentialsLoadingMarker}, DecisionMade: $initialDialogDecisionMade)")
                    }
                }

                LaunchedEffect(actualCredentials) {
                    val currentRepo = subsonicRepository 
                    if (actualCredentials != null) {
                        val credentialsChanged = currentRepo == null ||
                                                 currentRepo.baseUrl != actualCredentials.baseUrl ||
                                                 currentRepo.username != actualCredentials.username

                        if (credentialsChanged) {
                            println("MainActivityFolderDebug: Credentials changed or repo null. Recreating SubsonicRepository. Current instance: ${System.identityHashCode(currentRepo)}. New credentials hash: ${actualCredentials.hashCode()}")
                            subsonicRepository = SubsonicRepository(
                                baseUrl = actualCredentials.baseUrl,
                                username = actualCredentials.username,
                                password = actualCredentials.password
                            )
                            println("MainActivityFolderDebug: SubsonicRepository recreated. New instance: ${System.identityHashCode(subsonicRepository)}")

                            val intentFolderId = currentActivityIntent?.getStringExtra("folderId")
                            if (intentFolderId == null && currentFolderId == "al-1") {
                                // Keep al-1 if it was the default and no new intent folderId
                            } else if (intentFolderId != null) {
                                currentFolderId = intentFolderId
                                folderHistory = emptyList() // Ensure history is cleared if intent changes folder
                            } else {
                                currentFolderId = "al-1" // Default if no specific folder from intent
                                folderHistory = emptyList()
                            }
                            currentFolderName = ""
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            println("MainActivityFolderDebug: State set to IDLE due to credential/repository change. Folder ID: $currentFolderId")
                        } else {
                            println("MainActivityFolderDebug: Credentials match existing SubsonicRepository. Instance: ${System.identityHashCode(currentRepo)}. Credentials hash: ${actualCredentials.hashCode()}")
                        }
                    } else { 
                        if (currentRepo != null) {
                            println("MainActivityFolderDebug: Clearing SubsonicRepository. Old instance: ${System.identityHashCode(currentRepo)}")
                            subsonicRepository = null
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null
                            currentFolderName = ""
                            folderHistory = emptyList()
                            mediaController?.stop()
                            mediaController?.clearMediaItems()
                            println("MainActivityFolderDebug: State set to IDLE due to credentials cleared.")
                        }
                    }
                }

                LaunchedEffect(subsonicRepository, currentFolderId) {
                    val repositoryAtLaunch = subsonicRepository
                    if (repositoryAtLaunch != null && currentFolderId != null && folderLoadingState == LoadingState.IDLE) {
                        val folderIdToLoad = currentFolderId
                        println("MainActivityFolderDebug: Attempting to load folder. ID: $folderIdToLoad, State: $folderLoadingState, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                        folderLoadingState = LoadingState.LOADING
                        println("MainActivityFolderDebug: State changed to LOADING. ID: $folderIdToLoad, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                        folderLoadError = null
                        try {
                            println("MainActivityFolderDebug: Calling getFolderContents for ID: $folderIdToLoad, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                            val result = withContext(Dispatchers.IO) {
                                withTimeoutOrNull(60000L) { 
                                    repositoryAtLaunch.getFolderContents(folderIdToLoad!!)
                                }
                            }
                            if (subsonicRepository !== repositoryAtLaunch) { 
                                 println("MainActivityFolderDebug: Repo instance changed (ref check) during load for $folderIdToLoad! Original: ${System.identityHashCode(repositoryAtLaunch)}, Current Global: ${System.identityHashCode(subsonicRepository)}. Current folderLoadingState: $folderLoadingState.")
                                 if (folderLoadingState == LoadingState.LOADING) { 
                                    println("MainActivityFolderDebug: Repo changed and current coroutine was still LOADING. Discarding result from old repo for $folderIdToLoad.")
                                 } else {
                                     println("MainActivityFolderDebug: Repo changed, but current folderLoadingState is $folderLoadingState (not LOADING). This coroutine for $folderIdToLoad will not update state from potentially stale data.")
                                 }
                                 return@LaunchedEffect 
                            }
                            if (result == null) { 
                                val errorMsg = "Error: Folder loading timed out for folderId: $folderIdToLoad."
                                println("MainActivityFolderDebug: Timeout. ID: $folderIdToLoad, Error: $errorMsg. isActive: $isActive, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                println("MainActivityFolderDebug: State changed to ERROR (Timeout). ID: $folderIdToLoad")
                                if (isActive) {
                                    Toast.makeText(this@MainActivity, folderLoadError, Toast.LENGTH_LONG).show()
                                }
                            } else { 
                                if (isActive) {
                                    println("MainActivityFolderDebug: Success loading folder. ID: $folderIdToLoad, Result Name: ${result.name}, Entry count: ${result.entries.size}, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                                    folderEntries = result.entries
                                    currentFolderName = result.name
                                    folderLoadingState = LoadingState.SUCCESS
                                    println("MainActivityFolderDebug: State changed to SUCCESS. ID: $folderIdToLoad, Entries set: ${folderEntries.size}")
                                } else {
                                     println("MainActivityFolderDebug: Success for $folderIdToLoad, but coroutine inactive. State potentially stale, not updated. RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}")
                                }
                            }
                        } catch (e: CancellationException) {
                            println("MainActivityFolderDebug: Coroutine for $folderIdToLoad (RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}) cancelled. Current global repo: ${System.identityHashCode(subsonicRepository)}. State: $folderLoadingState. Exception: $e")
                            throw e
                        } catch (e: SubsonicApiException) {
                            if (subsonicRepository !== repositoryAtLaunch && folderLoadingState != LoadingState.LOADING) { 
                                 println("MainActivityFolderDebug: SubsonicApiException for $folderIdToLoad (RepoLaunch: ${System.identityHashCode(repositoryAtLaunch)}), but repo changed (ref check) and state is $folderLoadingState. Not setting ERROR from this stale coroutine.")
                                 return@LaunchedEffect
                            }
                            val errorMsg = "API Error: ${e.message} (Code: ${e.code ?: "N/A"}) for folderId: $folderIdToLoad"
                            println("MainActivityFolderDebug: SubsonicApiException. ID: $folderIdToLoad, Error: $errorMsg, isActive: $isActive, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}, Exception: $e")
                            folderLoadError = errorMsg
                            folderLoadingState = LoadingState.ERROR
                            println("MainActivityFolderDebug: State changed to ERROR (API Exception). ID: $folderIdToLoad")
                            if (isActive) {
                                Toast.makeText(this@MainActivity, folderLoadError, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            if (subsonicRepository !== repositoryAtLaunch && folderLoadingState != LoadingState.LOADING) { 
                                 println("MainActivityFolderDebug: Generic Exception for $folderIdToLoad (RepoLaunch: ${System.identityHashCode(repositoryAtLaunch)}), but repo changed (ref check) and state is $folderLoadingState. Not setting ERROR from this stale coroutine.")
                                 return@LaunchedEffect
                            }
                            val errorMsg = "Error loading folder: ${e.message} for folderId: $folderIdToLoad"
                            println("MainActivityFolderDebug: Generic Exception. ID: $folderIdToLoad, Error: $errorMsg, isActive: $isActive, RepoInstance: ${System.identityHashCode(repositoryAtLaunch)}, Exception: $e")
                            folderLoadError = errorMsg
                            folderLoadingState = LoadingState.ERROR
                            println("MainActivityFolderDebug: State changed to ERROR (Generic Exception). ID: $folderIdToLoad")
                            if (isActive) {
                                Toast.makeText(this@MainActivity, folderLoadError, Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        if (subsonicRepository == null || currentFolderId == null) {
                            println("MainActivityFolderDebug: Skipped folder load (Repo or FolderID null). SubsonicRepo: ${subsonicRepository != null}, FolderID: $currentFolderId, State: $folderLoadingState")
                        } 
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(currentFolderName.ifEmpty { if (currentFolderId == "al-1" && folderLoadingState != LoadingState.SUCCESS) "Loading Album..." else if (folderLoadingState != LoadingState.SUCCESS) "Loading..." else "Folder" }) },
                            navigationIcon = {
                                if (folderHistory.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val previousFolder = folderHistory.last()
                                        currentFolderId = previousFolder.first
                                        folderHistory = folderHistory.dropLast(1)
                                        folderLoadingState = LoadingState.IDLE
                                        println("MainActivityFolderDebug: State changed to IDLE (TopAppBar Back). New Folder ID: $currentFolderId")
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { 
                                    println("MainActivityFolderDebug: Settings icon clicked. Show settings dialog.")
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
                                    println("MainActivityFolderDebug: SettingsDialog dismissed.")
                                    showSettingsDialog = false 
                                },
                                onSave = { newCredentials ->
                                    println("MainActivityFolderDebug: SettingsDialog save. New credentials hash: ${newCredentials.hashCode()}")
                                    scope.launch { userPreferencesRepository.saveCredentials(newCredentials) }
                                    showSettingsDialog = false
                                }
                            )
                        } else if (actualCredentials == null) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Please configure your Subsonic server.")
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { 
                                        println("MainActivityFolderDebug: Open Settings button clicked (actualCredentials null).")
                                        showSettingsDialog = true 
                                    }) { Text("Open Settings") }
                                }
                            }
                        } else if (subsonicRepository != null && currentFolderId != null) {
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
                                    val folderDisplayModifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { currentBackEvent?.let { event ->
                                                val progress = event.progress
                                                scaleX = 1f - progress * 0.1f
                                                scaleY = 1f - progress * 0.1f
                                                alpha = 1f - progress * 0.3f
                                                translationX = when (event.swipeEdge) {
                                                    BackEventCompat.EDGE_LEFT -> progress * size.width * 0.2f
                                                    BackEventCompat.EDGE_RIGHT -> progress * -size.width * 0.2f
                                                    else -> 0f
                                                }
                                                if (progress < 0.01f) {
                                                    translationX = 0f
                                                }
                                            } }
                                    FolderDisplay(
                                        modifier = folderDisplayModifier,
                                        entries = folderEntries,
                                        onFolderClick = { folder ->
                                            if (folder is FolderEntry) {
                                                folderHistory = folderHistory + (currentFolderId!! to currentFolderName)
                                                currentFolderId = folder.id
                                                folderLoadingState = LoadingState.IDLE
                                                println("MainActivityFolderDebug: State changed to IDLE (onFolderClick). New Folder ID: $currentFolderId")
                                            } else {
                                                Toast.makeText(this@MainActivity, "Clicked item is not a folder.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onFileClick = { file ->
                                            actualCredentials?.let { creds ->
                                                if (file !is FolderEntry) {
                                                    val downloadUrl = "${creds.baseUrl}/rest/download?u=${creds.username}&p=${creds.password}&v=1.16.1&c=PureSWR&id=${file.id}"
                                                    val extrasBundle = Bundle().apply {
                                                        putString("folderId", currentFolderId)
                                                    }
                                                    val mediaMetadata = MediaMetadata.Builder()
                                                        .setTitle(file.name)
                                                        .setExtras(extrasBundle)
                                                        .build()
                                                    val mediaItem = MediaItem.Builder()
                                                        .setUri(Uri.parse(downloadUrl))
                                                        .setMediaId(file.id)
                                                        .setMediaMetadata(mediaMetadata)
                                                        .build()

                                                    mediaController?.setMediaItem(mediaItem)
                                                    mediaController?.prepare()
                                                    mediaController?.play()
                                                    Toast.makeText(this@MainActivity, "Playing: ${file.name}", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Clicked item is a folder, not a playable file.", Toast.LENGTH_SHORT).show()
                                                }
                                            } ?: run {
                                                Toast.makeText(this@MainActivity, "Error: Credentials not available.", Toast.LENGTH_SHORT).show()
                                            }
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
                                            folderLoadingState = LoadingState.IDLE // Retry action
                                            println("MainActivityFolderDebug: State changed to IDLE (Retry Button). Folder ID: $currentFolderId")
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        println("MainActivityFolderDebug: onNewIntent. Intent Action: ${intent.action}, Data: ${intent.dataString}, Extras: ${intent.extras?.keySet()?.joinToString { key -> "$key=${intent.extras?.get(key)}" }}")
        currentActivityIntent = intent
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onStop() {
        super.onStop()
        mediaController?.release()
        mediaController = null
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
