package myhilmyhill.pureswr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList

private const val PLAYER_CHANNEL_ID = "pureswr_player_channel"
private const val PLAYER_NOTIFICATION_ID = 1

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer // ExoPlayerをクラスメンバーにする
    private lateinit var playerListener: Player.Listener // Listenerを保持
    private var noisyAudioReceiver: BroadcastReceiver? = null

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

        mediaSession = MediaSession.Builder(this, player).build()

        // Register BroadcastReceiver for headphone disconnection
        noisyAudioReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    // Pause playback when headphones are disconnected
                    player.pause()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyAudioReceiver, filter)

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
        // Unregister BroadcastReceiver
        noisyAudioReceiver?.let {
            unregisterReceiver(it)
            noisyAudioReceiver = null
        }
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}