package myhilmyhill.pureswr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log // Logcat用に追加
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import android.os.Bundle // For MediaItem extras

// App's R class for app-specific resources like ic_launcher_foreground
// import myhilmyhill.pureswr.R // この行が重複している場合は削除してください (プロジェクト構成による)

private const val PLAYER_CHANNEL_ID = "pureswr_player_channel"
private const val PLAYER_NOTIFICATION_ID = 1
private const val TAG = "PlaybackServiceDebug" // Logcat用タグ

private fun extractFolderIdFromMediaItem(mediaItem: MediaItem?): String? {
    return mediaItem?.mediaMetadata?.extras?.getString("folderId")
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer // ExoPlayerをクラスメンバーにする
    private lateinit var playerListener: Player.Listener // Listenerを保持

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PLAYER_CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // ExoPlayerの初期化
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        // Player.Listenerの定義と追加
        playerListener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (timeline.isEmpty) {
                    Log.d(TAG, "onTimelineChanged: Timeline is empty.")
                    return
                }
                val window = Timeline.Window()
                if (player.currentMediaItemIndex >= 0 && player.currentMediaItemIndex < timeline.windowCount) {
                    timeline.getWindow(player.currentMediaItemIndex, window)
                } else if (timeline.windowCount > 0) {
                     timeline.getWindow(0, window)
                } else {
                    Log.d(TAG, "onTimelineChanged: Timeline has no windows to inspect.")
                    return
                }

                Log.d(TAG, "onTimelineChanged (Reason: $reason):")
                Log.d(TAG, "  Player Duration: ${player.duration} ms (C.TIME_UNSET is ${C.TIME_UNSET})")
                Log.d(TAG, "  Window isSeekable: ${window.isSeekable}")
                Log.d(TAG, "  Window isDynamic: ${window.isDynamic}")
                Log.d(TAG, "  Window durationMs: ${window.durationMs}")
                Log.d(TAG, "  Window defaultPositionMs: ${window.defaultPositionMs}")
                Log.d(TAG, "  Player isCurrentMediaItemSeekable: ${player.isCurrentMediaItemSeekable}")
                Log.d(TAG, "  Player CMD_SEEK_IN_CURRENT_MEDIA_ITEM: ${player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}")
                Log.d(TAG, "  Player CMD_SEEK_TO_DEFAULT_POSITION: ${player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)}")
                Log.d(TAG, "  Player CMD_SEEK_BACK: ${player.isCommandAvailable(Player.COMMAND_SEEK_BACK)}")
                Log.d(TAG, "  Player CMD_SEEK_FORWARD: ${player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)}")

                player.currentMediaItem?.mediaMetadata?.extras?.let { extras ->
                    Log.d(TAG, "  MediaItem Extras - duration_ms: ${extras.getLong("duration_ms", -1L)}")
                    Log.d(TAG, "  MediaItem Extras - folderId: ${extras.getString("folderId")}")
                    Log.d(TAG, "  MediaItem Extras - artist: ${extras.getString("artist")}")
                    Log.d(TAG, "  MediaItem Extras - album: ${extras.getString("album")}")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition (Reason: $reason):")
                mediaItem?.let {
                    Log.d(TAG, "  New Media ID: ${it.mediaId}")
                    Log.d(TAG, "  New Media Title: ${it.mediaMetadata.title}")
                    it.mediaMetadata.extras?.let { extras ->
                        Log.d(TAG, "  New MediaItem Extras - duration_ms: ${extras.getLong("duration_ms", -1L)}")
                        Log.d(TAG, "  New MediaItem Extras - folderId: ${extras.getString("folderId")}")
                    }
                }
                Log.d(TAG, "  Player Duration (after transition): ${player.duration} ms")
                Log.d(TAG, "  Player isCurrentMediaItemSeekable (after transition): ${player.isCurrentMediaItemSeekable}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player Error:", error)
            }

            override fun onAvailableCommandsChanged(commands: Player.Commands) {
                Log.d(TAG, "onAvailableCommandsChanged:")
                Log.d(TAG, "  SEEK_IN_CURRENT_MEDIA_ITEM: ${commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}")
                Log.d(TAG, "  SEEK_TO_DEFAULT_POSITION: ${commands.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)}")
                Log.d(TAG, "  SEEK_TO_MEDIA_ITEM: ${commands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM)}")
            }
        }
        player.addListener(playerListener) // 保持したリスナーを追加

        mediaSession = MediaSession.Builder(this, player).build()

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val smallIconResId = myhilmyhill.pureswr.R.drawable.ic_launcher_foreground

                val notificationBuilder = NotificationCompat.Builder(this@PlaybackService, PLAYER_CHANNEL_ID)
                    .setSmallIcon(smallIconResId)

                notificationBuilder
                    .addAction(actionFactory.createMediaAction(mediaSession, androidx.core.graphics.drawable.IconCompat.createWithResource(this@PlaybackService, androidx.media3.ui.R.drawable.exo_legacy_controls_previous), "Previous", Player.COMMAND_SEEK_TO_PREVIOUS))
                    .addAction(
                        if (mediaSession.player.isPlaying) {
                            actionFactory.createMediaAction(mediaSession, androidx.core.graphics.drawable.IconCompat.createWithResource(this@PlaybackService, androidx.media3.ui.R.drawable.exo_legacy_controls_pause), "Pause", Player.COMMAND_PLAY_PAUSE)
                        } else {
                            actionFactory.createMediaAction(mediaSession, androidx.core.graphics.drawable.IconCompat.createWithResource(this@PlaybackService, androidx.media3.ui.R.drawable.exo_legacy_controls_play), "Play", Player.COMMAND_PLAY_PAUSE)
                        }
                    )
                    .addAction(actionFactory.createMediaAction(mediaSession, androidx.core.graphics.drawable.IconCompat.createWithResource(this@PlaybackService, androidx.media3.ui.R.drawable.exo_legacy_controls_next), "Next", Player.COMMAND_SEEK_TO_NEXT))

                val mediaStyle = MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_STOP.toLong()))

                val contentIntent = Intent(this@PlaybackService, MainActivity::class.java).apply {
                    val currentMediaItem = mediaSession.player.currentMediaItem
                    if (currentMediaItem != null) {
                        val folderId = extractFolderIdFromMediaItem(currentMediaItem)
                        if (folderId != null) {
                            putExtra("folderId", folderId)
                        }
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
                    .setContentTitle(mediaSession.player.currentMediaItem?.mediaMetadata?.title ?: "Unknown Title")
                    .setContentText(mediaSession.player.currentMediaItem?.mediaMetadata?.artist ?: "Unknown Artist")
                    .setOngoing(mediaSession.player.isPlaying)
                    .setStyle(mediaStyle)
                    .setContentIntent(pendingContentIntent)

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

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(playerListener) // 保持していたリスナーを削除
            player.release() // mediaSessionより先にplayerを解放するのが一般的
            release()
            mediaSession = null
        }
        super.onDestroy()
        Log.d(TAG, "PlaybackService destroyed.")
    }
}
