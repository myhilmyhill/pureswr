package myhilmyhill.pureswr

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import myhilmyhill.pureswr.data.model.Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.Entry
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import myhilmyhill.pureswr.ui.FolderDisplay
import myhilmyhill.pureswr.ui.SettingsDialog
import myhilmyhill.pureswr.ui.theme.PureswrTheme

enum class LoadingState { IDLE, LOADING, SUCCESS, ERROR }

private object CredentialsLoadingMarker

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private var mediaController: MediaController? = null
    private var playerListener: Player.Listener? = null // Store the listener
    private var currentActivityIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivityIntent = intent
        userPreferencesRepository = UserPreferencesRepository(this)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel("pureswr_player_channel")
        if (channel == null) {
            // Channel might be created by PlaybackService
        }

        setContent {
            PureswrTheme {
                val scope = rememberCoroutineScope()

                var currentFolderId by remember { mutableStateOf<String?>(currentActivityIntent?.getStringExtra("folderId") ?: "al-1") }
                var currentFolderName by remember { mutableStateOf("") }
                var currentPlayingTrackId by remember { mutableStateOf("") }
                var isMusicPlaying by remember { mutableStateOf(false) } // New state for playback status
                var folderEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
                var parentFolderId by remember { mutableStateOf<String?>(null) }
                var folderLoadingState by remember { mutableStateOf(LoadingState.IDLE) }
                var folderLoadError by remember { mutableStateOf<String?>(null) }

                var showSettingsDialog by remember { mutableStateOf(false) }
                val credentialsLoadingState by userPreferencesRepository.credentialsFlow.collectAsState(initial = CredentialsLoadingMarker)

                var currentBackEvent by remember { mutableStateOf<BackEventCompat?>(null) }

                val actualCredentials = if (credentialsLoadingState === CredentialsLoadingMarker) {
                    null
                } else {
                    credentialsLoadingState as? Credentials
                }

                var subsonicRepository by remember { mutableStateOf<SubsonicRepository?>(null) }

                // Listener for MediaController events
                DisposableEffect(mediaController) {
                    val listener = object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            isMusicPlaying = isPlaying
                            println("MainActivityPlayerDebug: onIsPlayingChanged: $isPlaying")
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            currentPlayingTrackId = mediaItem?.mediaId ?: ""
                            isMusicPlaying = mediaController?.isPlaying ?: false
                             println("MainActivityPlayerDebug: onMediaItemTransition. New Media ID: ${mediaItem?.mediaId}, Reason: $reason, isPlaying: $isMusicPlaying")
                        }
                    }
                    playerListener = listener
                    mediaController?.addListener(listener)
                    // Set initial state when controller is (re)connected
                    currentPlayingTrackId = mediaController?.currentMediaItem?.mediaId ?: ""
                    isMusicPlaying = mediaController?.isPlaying ?: false
                    println("MainActivityPlayerDebug: MediaController DisposableEffect - Listener added. Initial Track ID: $currentPlayingTrackId, IsPlaying: $isMusicPlaying")


                    onDispose {
                        mediaController?.removeListener(listener)
                        playerListener = null
                        println("MainActivityPlayerDebug: MediaController DisposableEffect - Listener removed.")
                    }
                }

                LaunchedEffect(currentActivityIntent) {
                    val newFolderIdFromIntent = currentActivityIntent?.getStringExtra("folderId")
                    println("MainActivityFolderDebug: LaunchedEffect for new folder ID: $newFolderIdFromIntent")
                    if (newFolderIdFromIntent != null && currentFolderId != newFolderIdFromIntent) {
                        currentFolderId = newFolderIdFromIntent
                        folderLoadingState = LoadingState.IDLE
                    }
                }

                if (parentFolderId != null && !showSettingsDialog && actualCredentials != null) {
                    PredictiveBackHandler(enabled = true) { progress: Flow<BackEventCompat> ->
                        try {
                            progress.collect { event -> currentBackEvent = event }
                            currentFolderId = parentFolderId
                            folderLoadingState = LoadingState.IDLE
                        } catch (_: CancellationException) {
                            // Back gesture cancelled
                        } finally {
                            currentBackEvent = null
                        }
                    }
                }

                LaunchedEffect(actualCredentials) {
                    val currentRepo = subsonicRepository
                    if (actualCredentials != null) {
                        subsonicRepository = SubsonicRepository(
                            baseUrl = actualCredentials.baseUrl,
                            username = actualCredentials.username,
                            password = actualCredentials.password
                        )
                        folderLoadingState = LoadingState.IDLE
                    } else {
                        if (currentRepo != null) {
                            subsonicRepository = null
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null
                            currentFolderName = ""
                            currentPlayingTrackId = ""
                            isMusicPlaying = false
                            parentFolderId = null
                            mediaController?.stop()
                            mediaController?.clearMediaItems()
                        }
                    }
                }

                LaunchedEffect(subsonicRepository, currentFolderId) {
                    val repositoryAtLaunch = subsonicRepository
                    if (repositoryAtLaunch != null && currentFolderId != null && folderLoadingState == LoadingState.IDLE) {
                        val folderIdToLoad = currentFolderId
                        folderLoadingState = LoadingState.LOADING
                        folderLoadError = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                withTimeoutOrNull(60000L) {
                                    repositoryAtLaunch.getFolderContents(folderIdToLoad)
                                }
                            }
                            if (subsonicRepository !== repositoryAtLaunch) {
                                 return@LaunchedEffect
                            }
                            if (result == null) {
                                val errorMsg = "Error: Folder loading timed out for folderId: $folderIdToLoad."
                                folderLoadError = errorMsg
                                folderLoadingState = LoadingState.ERROR
                                if (isActive) {
                                    Toast.makeText(this@MainActivity, folderLoadError, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                if (isActive) {
                                    folderEntries = result.entries
                                    currentFolderName = result.name
                                    parentFolderId = result.parentFolderId
                                    folderLoadingState = LoadingState.SUCCESS
                                }
                            }
                        } catch (e: Exception) {
                            if (subsonicRepository !== repositoryAtLaunch && folderLoadingState != LoadingState.LOADING) {
                                 println("MainActivityFolderDebug: Generic Exception for $folderIdToLoad (RepoLaunch: ${System.identityHashCode(repositoryAtLaunch)}), but repo changed (ref check) and state is $folderLoadingState. Not setting ERROR from this stale coroutine.")
                                 return@LaunchedEffect
                            }
                            val errorMsg = "Error loading folder: ${e.message} for folderId: $folderIdToLoad"
                            folderLoadError = errorMsg
                            folderLoadingState = LoadingState.ERROR
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
                            title = { Text(currentFolderName) },
                            navigationIcon = {
                                if (parentFolderId != null) {
                                    IconButton(onClick = {
                                        currentFolderId = parentFolderId
                                        currentFolderName = ""
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
                    Box(modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()) {
                        if (credentialsLoadingState === CredentialsLoadingMarker) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
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
                                    // Reset to default folder and clear related states when credentials are saved/overwritten
                                    currentFolderId = "al-1"
                                    folderLoadingState = LoadingState.IDLE
                                    currentFolderName = ""
                                    folderEntries = emptyList()
                                    parentFolderId = null
                                    showSettingsDialog = false
                                }
                            )
                        } else if (actualCredentials == null) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Please configure your Subsonic server.")
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        showSettingsDialog = true
                                    }) { Text("Open Settings") }
                                }
                            }
                        } else if (subsonicRepository != null && currentFolderId != null) {
                            when (folderLoadingState) {
                                LoadingState.LOADING -> {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        CircularProgressIndicator()
                                    }
                                }
                                LoadingState.SUCCESS -> {
                                    val folderDisplayModifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            currentBackEvent?.let { event ->
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
                                            }
                                        }
                                    FolderDisplay(
                                        modifier = folderDisplayModifier,
                                        entries = folderEntries,
                                        currentPlayingTrackId = currentPlayingTrackId,
                                        isMusicPlaying = isMusicPlaying,
                                        onFolderClick = { folder ->
                                            currentFolderId = folder.id
                                            currentFolderName = folder.name
                                            folderLoadingState = LoadingState.IDLE
                                        },
                                        onFileClick = { file ->
                                            actualCredentials.let { creds ->
                                                val currentSubsonicRepository = subsonicRepository
                                                if (currentSubsonicRepository != null) {
                                                    // Optimistically set, listener will confirm
                                                    // currentPlayingTrackId = file.id (already done by listener or by clicking the same item)
                                                    // isMusicPlaying = true (will be set by listener)
                                                    scope.launch {
//                                                        var songDetails: SubsonicApiSong? = null
//
//                                                        try {
//                                                            // Fetch song details in a background thread
//                                                            songDetails = withContext(Dispatchers.IO) {
//                                                                currentSubsonicRepository.getSongDetails(file.id)
//                                                            }
//                                                        } catch (e: Exception) {
//                                                            withContext(Dispatchers.Main) {
//                                                                Toast.makeText(this@MainActivity, "Could not fetch song details. Proceeding without.", Toast.LENGTH_SHORT).show()
//                                                            }
//                                                        }

                                                        val extrasBundle = Bundle().apply {
                                                            putString("folderId", currentFolderId)
                                                        }
                                                        val mediaMetadataBuilder = MediaMetadata.Builder()
                                                            .setTitle(file.name)
                                                            .setArtist(file.dir)
                                                            .setExtras(extrasBundle)
                                                        val mediaItem = currentSubsonicRepository.getStreamMediaItem(file.id)
                                                            .setMediaMetadata(mediaMetadataBuilder.build())
                                                            .build()

                                                        withContext(Dispatchers.Main) {
                                                            if (mediaController?.currentMediaItem?.mediaId == file.id) {
                                                                // If it's the same track, toggle play/pause
                                                                if (mediaController?.isPlaying == true) {
                                                                    mediaController?.pause()
                                                                } else {
                                                                    mediaController?.play()
                                                                }
                                                            } else {
                                                                // Different track, or no track playing
                                                                mediaController?.setMediaItem(mediaItem)
                                                                mediaController?.prepare()
                                                                mediaController?.play()
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Error: Subsonic repository not available.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
                                }
                                LoadingState.ERROR -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
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
                                    }
                                }
                            }
                        } else if (subsonicRepository == null) {
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
        currentActivityIntent = intent
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
                // Listener is attached via DisposableEffect in setContent now
                // Initial state update also happens in DisposableEffect
                 println("MainActivityPlayerDebug: MediaController connected in onStart.")
            },
            MoreExecutors.directExecutor() // Using directExecutor, but Main for UI updates inside listener
        )
    }

    override fun onStop() {
        super.onStop()
        // Listener is removed via DisposableEffect
        mediaController?.release()
        mediaController = null
        println("MainActivityPlayerDebug: MediaController released in onStop.")
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PureswrTheme {
        Text("App Preview")
    }
}
