package com.fergusonconsultancy.nastro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock

/**
 * Keeps the tape turning with the screen off: a foreground service, a media session for the
 * lock screen, headset and car, and a notification with the same keys as the deck.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private var foreground = false
    private var lastKey = ""

    private val noisy = object : BroadcastReceiver() {
        // Headphones pulled out: stop, as a Walkman's jack would have
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Deck.stop() }
    }

    private val changed = { update() }

    override fun onCreate() {
        super.onCreate()
        Deck.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playing tape", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        session = MediaSession(this, "Nastro").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Deck.press(Deck.Key.PLAY)
                override fun onPause() = Deck.stop()
                override fun onStop() = Deck.stop()
                override fun onSkipToNext() = Deck.press(Deck.Key.FF)
                override fun onSkipToPrevious() = Deck.press(Deck.Key.REW)
            })
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        Deck.listen(changed)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PLAY -> if (Deck.mode == Deck.Mode.PLAY) Deck.stop() else Deck.press(Deck.Key.PLAY)
            ACT_NEXT -> Deck.press(Deck.Key.FF)
            ACT_PREV -> Deck.press(Deck.Key.REW)
            Intent.ACTION_MEDIA_BUTTON -> { /* routed through the session */ }
        }
        update(force = true)
        return START_NOT_STICKY
    }

    private fun update(force: Boolean = false) {
        val t = Deck.tape ?: return
        val playing = Deck.mode == Deck.Mode.PLAY || Deck.mode == Deck.Mode.AMS
        val cur = Deck.current()
        val key = "${t.albumId}/${cur.track?.id}/${Deck.mode}/${Deck.sideLetter}/${t.art != null}"
        if (key == lastKey && !force) return
        lastKey = key

        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, cur.track?.title ?: t.album)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, ((cur.track?.durSecs ?: 0.0) * 1000).toLong())
            .apply { t.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                (cur.into * 1000).toLong(), if (Deck.mode == Deck.Mode.PLAY) 1f else 0f, SystemClock.elapsedRealtime())
            .build())

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun act(icon: Int, title: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tape)
            .setContentTitle(cur.track?.title ?: t.album)
            .setContentText("${t.label}  ·  side ${Deck.sideLetter}")
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { t.art?.let { setLargeIcon(it) } }
            .addAction(act(android.R.drawable.ic_media_rew, "Back", ACT_PREV))
            .addAction(act(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Stop" else "Play", ACT_PLAY))
            .addAction(act(android.R.drawable.ic_media_ff, "Next", ACT_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()

        if (playing || force) {
            runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            foreground = true
        } else if (foreground) {
            // Stopped: let the system reclaim us, but leave the controls in the shade
            stopForeground(STOP_FOREGROUND_DETACH); foreground = false
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
    }

    override fun onDestroy() {
        Deck.unlisten(changed)
        runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "tape"
        const val ID = 1
        const val ACT_PLAY = "nastro.play"
        const val ACT_NEXT = "nastro.next"
        const val ACT_PREV = "nastro.prev"
    }
}
