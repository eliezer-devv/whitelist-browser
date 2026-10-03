package com.appcustom.whitelistbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps a site's sound playing with the app in the background (minimised, or the screen off), the way music apps
 * do: Android lets an app keep playing while it shows a "playing" notification. The notification says which site,
 * opens the app when tapped, and has Stop. Started when the app goes to the background with something playing;
 * stopped when it comes back, or when nothing's playing any more.
 */
class PlaybackService : Service() {

    private var session: android.media.session.MediaSession? = null
    private val artLoader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var artUrl: String? = null
    private var art: android.graphics.Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android's media session: the lock screen, the media panel and headphone buttons talk to it. */
    private fun session(): android.media.session.MediaSession = session ?: android.media.session.MediaSession(this, "WhitelistBrowser").also { s ->
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() = send("play")
            override fun onPause() = send("pause")
            override fun onSkipToNext() = send("nexttrack")
            override fun onSkipToPrevious() = send("previoustrack")
            override fun onStop() = send("stop")
        })
        s.isActive = true
        session = s
    }

    /** A button pressed (on the notification, the lock screen or headphones): to the app, which presses it on the page. */
    private fun send(cmd: String) { sendBroadcast(Intent(ACTION_MEDIA).setPackage(packageName).putExtra(EXTRA_CMD, cmd)) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val site = intent?.getStringExtra(EXTRA_SITE) ?: "a website"
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Playing from $site" }
        val artist = intent?.getStringExtra(EXTRA_ARTIST).orEmpty()
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val canPrev = intent?.getBooleanExtra(EXTRA_PREV, false) ?: false
        val canNext = intent?.getBooleanExtra(EXTRA_NEXT, false) ?: false
        val newArt = intent?.getStringExtra(EXTRA_ART)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing in the background", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a site's sound plays with the app in the background"
                setShowBadge(false)
            })
        }
        // What's playing, and which buttons work (as the site says).
        val s = session()
        s.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, artist.ifBlank { site })
            .apply { art?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        var actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP
        if (canPrev) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (canNext) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        s.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
            .build())
        // The notification: artwork, title, artist; Previous, Play/Pause, Next (where the site has them), Stop.
        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsPi)
        fun button(code: Int, icon: Int, label: String, cmd: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), label,
            PendingIntent.getBroadcast(this, code, Intent(ACTION_MEDIA).setPackage(packageName).putExtra(EXTRA_CMD, cmd), flagsPi)).build()
        val buttons = ArrayList<Notification.Action>()
        if (canPrev) buttons += button(10, R.drawable.ic_d_prev, "Previous", "previoustrack")
        buttons += if (playing) button(11, R.drawable.ic_d_pause, "Pause", "pause") else button(12, R.drawable.ic_d_play, "Play", "play")
        if (canNext) buttons += button(13, R.drawable.ic_d_next, "Next", "nexttrack")
        buttons += button(14, R.drawable.ic_d_stop, "Stop", "stop")
        val main = if (canPrev) 1 else 0                          // the Play/Pause button's place
        val compact = listOfNotNull(if (canPrev) 0 else null, main, if (canNext) main + 1 else null).toIntArray()
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_d_sound)
            .setContentTitle(title)
            .setContentText(if (artist.isNotBlank()) "$artist · $site" else site)
            .apply { art?.let { setLargeIcon(it) } }
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { buttons.forEach { addAction(it) } }
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(*compact))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        // The artwork: fetched once per picture, then the notification is shown again with it.
        if (newArt != null && newArt != artUrl) {
            artUrl = newArt
            val again = Intent(intent ?: return START_NOT_STICKY)
            artLoader.execute {
                val bmp = runCatching {
                    val c = java.net.URL(newArt).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
                }.getOrNull()
                if (bmp != null && artUrl == newArt) { art = bmp; runCatching { startService(again) } }
            }
        } else if (newArt == null && artUrl != null) { artUrl = null; art = null }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.let { it.isActive = false; it.release() }
        session = null
        artLoader.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_MEDIA = "com.appcustom.whitelistbrowser.MEDIA"
        const val EXTRA_CMD = "cmd"
        private const val EXTRA_SITE = "site"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_ART = "art"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_PREV = "prev"
        private const val EXTRA_NEXT = "next"
        private const val CHANNEL = "playback"
        private const val ID = 4417

        /** Shows (or updates) the media notification: [info] is the page's own description of what's playing. */
        fun show(ctx: Context, site: String, info: org.json.JSONObject?) {
            val i = Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_SITE, site)
                .putExtra(EXTRA_PLAYING, info?.optBoolean("playing", true) ?: true)
            if (info != null) i.putExtra(EXTRA_TITLE, info.optString("title")).putExtra(EXTRA_ARTIST, info.optString("artist"))
                .putExtra(EXTRA_ART, info.optString("art")).putExtra(EXTRA_PREV, info.optBoolean("prev"))
                .putExtra(EXTRA_NEXT, info.optBoolean("next"))
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } }

        /**
         * On every page, before its own scripts: tells the page it's still visible when the app goes to the
         * background (sites like YouTube stop playing otherwise), and keeps track of whether anything's playing
         * (players on the page, and ones made in code), so the app knows whether to keep it going.
         */
        const val PAGE_SCRIPT = """
(function () {
  if (window.__wlbBg) return; window.__wlbBg = true;
  try {
    Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return false; } });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return 'visible'; } });
    Object.defineProperty(document, 'webkitHidden', { configurable: true, get: function () { return false; } });
  } catch (e) {}
  // Only the page's "hidden" notices (never focus or blur events: forms rely on those).
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function (ev) {
    window.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
  });
  var players = new Set(), last = null;
  function watch(el) {
    if (!el) return; last = el;
    if (el.__wlbTracked) return; el.__wlbTracked = true; players.add(el);
  }
  ['play', 'playing'].forEach(function (ev) { document.addEventListener(ev, function (e) { watch(e.target); }, true); });
  var p = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { watch(this); return p.apply(this, arguments); };
  // The buttons the site supports (it tells the browser, for media controls): kept so they can be pressed from outside.
  var handlers = {};
  try {
    var ms = navigator.mediaSession;
    if (ms && ms.setActionHandler) {
      var set = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (a, h) { handlers[a] = h; try { return set(a, h); } catch (e) {} };
    }
  } catch (e) {}
  window.__wlbPlaying = function () {
    var n = 0; players.forEach(function (el) { if (!el.paused && !el.ended && !el.muted && el.volume > 0) n++; }); return n;
  };
  window.__wlbStopAll = function () { players.forEach(function (el) { try { el.pause(); } catch (e) {} }); };
  // What's playing, for the media controls: as the site describes it (title, artist, artwork), and its buttons.
  window.__wlbMediaInfo = function () {
    var has = false; players.forEach(function (el) { if (!el.ended && (el.currentTime > 0 || !el.paused)) has = true; });
    var md = null, art = '';
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
    try { if (md && md.artwork && md.artwork.length) art = new URL(md.artwork[md.artwork.length - 1].src, location.href).href; } catch (e) {}
    return { playing: window.__wlbPlaying() > 0, has: has, title: (md && md.title) || document.title || '', artist: (md && md.artist) || '',
      album: (md && md.album) || '', art: art, prev: !!handlers.previoustrack, next: !!handlers.nexttrack };
  };
  // A media button pressed (notification, lock screen, headphones): the site's own handler if it has one.
  window.__wlbMediaDo = function (a) {
    if (handlers[a]) { try { handlers[a]({ action: a }); return; } catch (e) {} }
    if (a === 'play' && last) { try { last.play(); } catch (e) {} }
    if (a === 'pause') window.__wlbStopAll();
  };
})();
"""
    }
}
