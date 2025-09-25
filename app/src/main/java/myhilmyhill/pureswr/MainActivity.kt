package myhilmyhill.pureswr

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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import myhilmyhill.pureswr.data.model.Entry
import myhilmyhill.pureswr.data.model.MusicEntry
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import myhilmyhill.pureswr.ui.FolderDisplay
import myhilmyhill.pureswr.ui.SettingsDialog
import myhilmyhill.pureswr.ui.theme.PureswrTheme

enum class LoadingState { IDLE, LOADING, SUCCESS, ERROR }

private object CredentialsLoadingMarker

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private var mediaControllerAsState by mutableStateOf<MediaController?>(null)
    private var currentActivityIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivityIntent = intent
        userPreferencesRepository = UserPreferencesRepository(this)

//        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        val channel = notificationManager.getNotificationChannel(\'pureswr_player_channel\')

        setContent {
            PureswrTheme {
                val scope = rememberCoroutineScope()
                val currentMediaController = mediaControllerAsState

                var currentFolderId by remember { mutableStateOf<String?>(currentActivityIntent?.getStringExtra("folderId")) }
                var currentFolderName by remember { mutableStateOf("") }
                var currentPlayingTrackId by remember { mutableStateOf("") }
                var isMusicPlaying by remember { mutableStateOf(false) }
                var isPlayerLoading by remember { mutableStateOf(false) }
                var folderEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
                var parentFolderId by remember { mutableStateOf<String?>(null) }
                var folderLoadingState by remember { mutableStateOf(LoadingState.IDLE) }
                var folderLoadError by remember { mutableStateOf<String?>(null) }
                var showSettingsDialog by remember { mutableStateOf(false) }
                val credentialsLoadingState by userPreferencesRepository.credentialsFlow.collectAsState(initial = CredentialsLoadingMarker)
                var currentBackEvent by remember { mutableStateOf<BackEventCompat?>(null) }

                val actualCredentials = if (credentialsLoadingState === CredentialsLoadingMarker) null else credentialsLoadingState as? Credentials
                var subsonicRepository by remember { mutableStateOf<SubsonicRepository?>(null) }

                DisposableEffect(currentMediaController) {
                    val listenerImpl = object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            isMusicPlaying = isPlaying
                            if (isPlaying) {
                                isPlayerLoading = false
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            currentPlayingTrackId = mediaItem?.mediaId ?: ""
                            isMusicPlaying = currentMediaController?.isPlaying ?: false
                            if (mediaItem == null) {
                                isPlayerLoading = false
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY,
                                Player.STATE_ENDED,
                                Player.STATE_IDLE -> {
                                    isPlayerLoading = false
                                }
                                Player.STATE_BUFFERING -> {
                                    // isPlayerLoading should be true if a user action initiated loading.
                                    // This callback might also occur during mid-track buffering,
                                    // in which case we don't want to show a persistent spinner
                                    // unless it was due to an initial load.
                                    // The initial sync and click handlers are better for setting isPlayerLoading = true.
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            isPlayerLoading = false
                            currentPlayingTrackId = ""
                            scope.launch { Toast.makeText(this@MainActivity, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show() }
                        }
                    }
                    currentMediaController?.addListener(listenerImpl)

                    // Initial state synchronization
                    if (currentMediaController != null) {
                        currentPlayingTrackId = currentMediaController.currentMediaItem?.mediaId ?: ""
                        isMusicPlaying = currentMediaController.isPlaying
                        val playbackState = currentMediaController.playbackState
                        val currentItem = currentMediaController.currentMediaItem

                        if (currentMediaController.isPlaying) {
                            isPlayerLoading = false // If playing, not loading
                        } else {
                            when (playbackState) {
                                Player.STATE_READY,
                                Player.STATE_ENDED,
                                Player.STATE_IDLE -> isPlayerLoading = false // Definitively not loading
                                Player.STATE_BUFFERING -> isPlayerLoading = currentItem != null // Loading if buffering a known item
                                else -> isPlayerLoading = false // Default to not loading for other states
                            }
                        }
                    } else {
                        // Reset states if controller is null
                        currentPlayingTrackId = ""
                        isMusicPlaying = false
                        isPlayerLoading = false
                    }

                    onDispose {
                        currentMediaController?.removeListener(listenerImpl)
                    }
                }

                LaunchedEffect(currentActivityIntent) {
                    val newFolderIdFromIntent = currentActivityIntent?.getStringExtra("folderId")
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
                    if (actualCredentials != null) {
                        subsonicRepository = SubsonicRepository(
                            baseUrl = actualCredentials.baseUrl,
                            username = actualCredentials.username,
                            password = actualCredentials.password
                        )
                        // Reset folder state if credentials change and folder isn\'t from intent
                        if (currentActivityIntent?.getStringExtra("folderId") == null) {
                           currentFolderId = null
                        }
                        folderLoadingState = LoadingState.IDLE
                    } else {
                        if (subsonicRepository != null) {
                            subsonicRepository = null
                            folderEntries = emptyList()
                            folderLoadingState = LoadingState.IDLE
                            currentFolderId = null
                            currentFolderName = ""
                            currentPlayingTrackId = ""
                            isMusicPlaying = false
                            isPlayerLoading = false
                            parentFolderId = null
                            currentMediaController?.stop()
                            currentMediaController?.clearMediaItems()
                        }
                    }
                }

                LaunchedEffect(subsonicRepository, currentFolderId) {
                    val repositoryAtLaunch = subsonicRepository
                    if (repositoryAtLaunch != null && folderLoadingState == LoadingState.IDLE) {
                        folderLoadingState = LoadingState.LOADING
                        folderLoadError = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                withTimeoutOrNull(10000) { repositoryAtLaunch.getFolderContents(currentFolderId) }
                            }
                            if (result == null) {
                                folderLoadError = "Error: Folder loading timed out for folderId: $currentFolderId."
                                folderLoadingState = LoadingState.ERROR
                            } else {
                                folderEntries = result.entries
                                currentFolderName = result.name
                                parentFolderId = result.parentFolderId
                                folderLoadingState = LoadingState.SUCCESS
                            }
                        } catch (e: Exception) {
                            if (subsonicRepository !== repositoryAtLaunch && folderLoadingState != LoadingState.LOADING) return@LaunchedEffect
                            folderLoadError = "Error loading folder: ${e.message} for folderId: $currentFolderId"
                            folderLoadingState = LoadingState.ERROR
                            if (isActive) Toast.makeText(this@MainActivity, folderLoadError, Toast.LENGTH_LONG).show()
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
                                        folderLoadingState = LoadingState.IDLE
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                // Random Play Button
                                IconButton(onClick = {
                                    if (subsonicRepository != null) {
                                        scope.launch {
                                            isPlayerLoading = true
                                            try {
                                                val randomSong = withContext(Dispatchers.IO) {
                                                    subsonicRepository!!.getRandomSong()
                                                }
                                                val mediaItem = createMediaItemFromEntry(randomSong, randomSong.parentFolderId, subsonicRepository!!)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "${randomSong.name}\n${randomSong.dir}", Toast.LENGTH_SHORT).show()
                                                    currentPlayingTrackId = randomSong.id
                                                    // isPlayerLoading = true; // Already set before the try block
                                                    currentMediaController?.setMediaItem(mediaItem)
                                                    currentMediaController?.prepare()
                                                    currentMediaController?.play()
                                                }
                                            } catch (e: Exception) {
                                                isPlayerLoading = false
                                                Toast.makeText(this@MainActivity, "Error playing random song: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        Toast.makeText(this@MainActivity, "Error: Subsonic repository not available.", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Filled.Shuffle, contentDescription = "Play Random Song")
                                }
                                // Settings Button
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (credentialsLoadingState === CredentialsLoadingMarker) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() }
                        } else if (showSettingsDialog) {
                            SettingsDialog(
                                currentBaseUrl = actualCredentials?.baseUrl ?: "",
                                currentUsername = actualCredentials?.username ?: "",
                                currentPassword = actualCredentials?.password ?: "",
                                onDismissRequest = { showSettingsDialog = false },
                                onSave = {
                                    scope.launch { userPreferencesRepository.saveCredentials(it) }
                                    currentFolderId = null
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
                                    Button(onClick = { showSettingsDialog = true }) { Text("Open Settings") }
                                }
                            }
                        } else if (subsonicRepository != null) {
                            when (folderLoadingState) {
                                LoadingState.LOADING -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() }
                                LoadingState.SUCCESS -> {
                                    val folderDisplayModifier = Modifier.fillMaxSize().graphicsLayer {
                                         currentBackEvent?.let { event ->
                                            val progress = event.progress; scaleX = 1f - progress * 0.1f; scaleY = 1f - progress * 0.1f; alpha = 1f - progress * 0.3f
                                            translationX = when (event.swipeEdge) {
                                                BackEventCompat.EDGE_LEFT -> progress * size.width * 0.2f
                                                BackEventCompat.EDGE_RIGHT -> progress * -size.width * 0.2f
                                                else -> 0f
                                            }
                                            if (progress < 0.01f) translationX = 0f
                                        }
                                    }
                                    FolderDisplay(
                                        modifier = folderDisplayModifier,
                                        entries = folderEntries,
                                        currentPlayingTrackId = currentPlayingTrackId,
                                        isMusicPlaying = isMusicPlaying,
                                        isLoading = isPlayerLoading,
                                        onFolderClick = { folder ->
                                            currentFolderId = folder.id
                                            currentFolderName = folder.name
                                            folderLoadingState = LoadingState.IDLE
                                        },
                                        onFileClick = { file ->
                                            if (subsonicRepository != null) {
                                                scope.launch {
                                                    val mediaItem = createMediaItemFromEntry(file, currentFolderId, subsonicRepository!!)
                                                    withContext(Dispatchers.Main) {
                                                        currentPlayingTrackId = file.id
                                                        isPlayerLoading = true
                                                        currentMediaController?.setMediaItem(mediaItem)
                                                        currentMediaController?.prepare()
                                                        currentMediaController?.play()
                                                    }
                                                }
                                            } else {
                                                isPlayerLoading = false
                                                Toast.makeText(this@MainActivity, "Error: Subsonic repository not available.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                LoadingState.ERROR -> {
                                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("Failed to load folder: ${currentFolderName.ifEmpty { "selected folder" }}")
                                        folderLoadError?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = {
                                            subsonicRepository = SubsonicRepository(
                                                baseUrl = actualCredentials.baseUrl,
                                                username = actualCredentials.username,
                                                password = actualCredentials.password
                                            )
                                            folderLoadingState = LoadingState.IDLE
                                        }) { Text("Retry") }
                                    }
                                }
                                LoadingState.IDLE -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() }
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
        controllerFuture.addListener({
            mediaControllerAsState = controllerFuture.get()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        mediaControllerAsState?.release()
        mediaControllerAsState = null
    }

    private fun createMediaItemFromEntry(entry: MusicEntry, folderIdForExtras: String?, repository: SubsonicRepository): MediaItem {
        val extrasBundle = Bundle().apply {
            putString("folderId", folderIdForExtras)
        }
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(entry.name)
            .setArtist(entry.dir)
            .setExtras(extrasBundle)
            .build()
        return repository.getStreamMediaItem(entry.id)
            .setMediaMetadata(mediaMetadata)
            .build()
    }
}
