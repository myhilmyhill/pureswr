package myhilmyhill.pureswr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import javax.inject.Inject

private const val PLAYER_CHANNEL_ID = "pureswr_player_channel"
private const val PLAYER_NOTIFICATION_ID = 1
private const val CUSTOM_COMMAND_RANDOM_PLAY = "RANDOM_PLAY"

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer // ExoPlayerをクラスメンバーにする
    private lateinit var playerListener: Player.Listener // Listenerを保持
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            PLAYER_CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        playerListener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (timeline.isEmpty) {
                    return
                }
                val window = Timeline.Window()
                if (player.currentMediaItemIndex >= 0 && player.currentMediaItemIndex < timeline.windowCount) {
                    timeline.getWindow(player.currentMediaItemIndex, window)
                } else if (timeline.windowCount > 0) {
                     timeline.getWindow(0, window)
                } else {
                    return
                }
            }
        }
        player.addListener(playerListener)

        // Create custom command for random play
        val randomPlayCommand = SessionCommand(CUSTOM_COMMAND_RANDOM_PLAY, Bundle.EMPTY)
        
        // Create CommandButton for random play
        val randomPlayButton = CommandButton.Builder()
            .setDisplayName("Random")
            .setSessionCommand(randomPlayCommand)
            .setIconResId(R.drawable.ic_shuffle)
            .build()
        
        // Create MediaSession with callback and custom layout
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val connectionResult = super.onConnect(session, controller)
                    val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                        .add(randomPlayCommand)
                        .build()
                    return MediaSession.ConnectionResult.accept(
                        availableSessionCommands,
                        connectionResult.availablePlayerCommands
                    )
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CUSTOM_COMMAND_RANDOM_PLAY) {
                        handleRandomPlay()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        
        // Set custom layout on the session to include the random play button
        mediaSession?.setCustomLayout(ImmutableList.of(randomPlayButton))

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val smallIconResId = R.drawable.ic_launcher_foreground

                val notificationBuilder = NotificationCompat.Builder(this@PlaybackService, PLAYER_CHANNEL_ID)
                    .setSmallIcon(smallIconResId)

                val mediaStyle = MediaStyleNotificationHelper.MediaStyle(mediaSession)

                val contentIntent = Intent(this@PlaybackService, MainActivity::class.java).apply {
                    val currentMediaItemExtras = mediaSession.player.currentMediaItem?.mediaMetadata?.extras
                    if (currentMediaItemExtras != null) {
                        putExtra("folderId", currentMediaItemExtras.getString("folderId"))
                    }
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingContentIntent = PendingIntent.getActivity(
                    this@PlaybackService,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                notificationBuilder
                    .setContentTitle(mediaSession.player.currentMediaItem?.mediaMetadata?.title)
                    .setContentText(mediaSession.player.currentMediaItem?.mediaMetadata?.artist)
                    .setOngoing(mediaSession.player.isPlaying)
                    .setStyle(mediaStyle)
                    .setContentIntent(pendingContentIntent)

                // Add custom layout buttons (including random play button)
                for (button in customLayout) {
                    notificationBuilder.addAction(actionFactory.createCustomActionFromCustomCommandButton(mediaSession, button))
                }
                
                return MediaNotification(PLAYER_NOTIFICATION_ID, notificationBuilder.build())
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: android.os.Bundle
            ): Boolean {
                return false
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == false || player?.mediaItemCount == 0) {
            stopSelf()
        }
    }

    private fun handleRandomPlay() {
        serviceScope.launch {
            try {
                val credentials = userPreferencesRepository.credentialsFlow.firstOrNull()
                if (credentials == null) {
                    showToast("No credentials configured")
                    return@launch
                }
                
                val repository = SubsonicRepository(
                    baseUrl = credentials.baseUrl,
                    username = credentials.username,
                    password = credentials.password
                )
                
                val randomSong = repository.getRandomSong()
                
                // Create MediaItem from random song
                val extrasBundle = Bundle().apply {
                    putString("folderId", randomSong.parentFolderId)
                }
                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(randomSong.name)
                    .setArtist(randomSong.dir)
                    .setExtras(extrasBundle)
                    .build()
                
                val streamUrl = repository.getStreamMediaItem(randomSong.id)
                    .setMediaMetadata(mediaMetadata)
                    .build()
                
                // Play the random song
                withContext(Dispatchers.Main) {
                    player.setMediaItem(streamUrl)
                    player.prepare()
                    player.play()
                    showToast("${randomSong.name}\n${randomSong.dir}")
                }
                
                repository.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error playing random song: ${e.message}")
                }
            }
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this@PlaybackService, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}