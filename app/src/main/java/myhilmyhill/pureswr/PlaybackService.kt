package myhilmyhill.pureswr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player // Ensure Player is imported for command constants
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
// App's R class for app-specific resources like ic_launcher_foreground
import myhilmyhill.pureswr.R
import com.google.common.collect.ImmutableList

private const val PLAYER_CHANNEL_ID = "pureswr_player_channel"
private const val PLAYER_NOTIFICATION_ID = 1

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PLAYER_CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true) // Corrected
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val notificationBuilder = NotificationCompat.Builder(this@PlaybackService, PLAYER_CHANNEL_ID)

                // Standard notification actions using fully qualified R class for media3 drawables
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

                // Apply MediaStyle
                val mediaStyle = MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_STOP.toLong()))

                notificationBuilder
                    .setContentTitle(mediaSession.player.currentMediaItem?.mediaMetadata?.title ?: "Unknown Title")
                    .setContentText(mediaSession.player.currentMediaItem?.mediaMetadata?.artist ?: "Unknown Artist")
                    // Use app's R class for app-specific small icon
                    .setSmallIcon(myhilmyhill.pureswr.R.drawable.ic_launcher_foreground)
                    .setOngoing(mediaSession.player.isPlaying)
                    .setStyle(mediaStyle)

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
            this.player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
