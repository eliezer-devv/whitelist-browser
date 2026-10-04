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
    private val artLoader = quietQueue()
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
            override fun onSeekTo(pos: Long) = send("seekto:$pos")
            override fun onRewind() = send("seekbackward")
            override fun onFastForward() = send("seekforward")
            override fun onCustomAction(action: String, extras: android.os.Bundle?) = send(action)
        })
        s.isActive = true
        session = s
    }

    /** A button pressed (on the notification, the lock screen or headphones): to the app, which presses it on the page. */
    private fun send(cmd: String) { sendBroadcast(Intent(ACTION_MEDIA).setPackage(packageName).putExtra(EXTRA_CMD, cmd)) }

    override fun onCreate() { super.onCreate(); running = this; AppLog.ready(this); AppLog.i("Sound", "Playing notification started") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (If Android won't let it show, it closes cleanly rather than crashing the app.)
        runCatching { show(intent) }.onFailure { AppLog.e("Sound", "Android didn't allow the playing notification", it); stopSelf() }
        return START_NOT_STICKY
    }

    /** An update from the app while it's running: straight to it (no "start" for Android to refuse in the background). */
    fun update(intent: Intent) { runCatching { show(intent) } }

    /** Builds (or rebuilds) the notification and the media session from what the page says is playing. */
    private fun show(intent: Intent?) {
        val site = intent?.getStringExtra(EXTRA_SITE) ?: "a website"
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Playing from $site" }
        val artist = intent?.getStringExtra(EXTRA_ARTIST).orEmpty()
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val canPrev = intent?.getBooleanExtra(EXTRA_PREV, false) ?: false
        val canNext = intent?.getBooleanExtra(EXTRA_NEXT, false) ?: false
        val newArt = intent?.getStringExtra(EXTRA_ART)?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("data:image/") }
        // (For About this phone: what's playing, the site's buttons, and what happened to the artwork.)
        lastPlaying = "$title · buttons: " + listOfNotNull(if (canPrev) "previous" else null, "play/pause", if (canNext) "next" else null).joinToString(", ") +
            " · artwork: " + when {
                intent?.getStringExtra(EXTRA_ART).isNullOrBlank() -> "none given by the site"
                newArt == null -> "not a web address"
                newArt == artUrl && art != null -> "loaded"
                else -> lastArt.ifBlank { "loading" }
            }
        val pos = intent?.getLongExtra(EXTRA_POS, 0L) ?: 0L
        val len = intent?.getLongExtra(EXTRA_LEN, 0L) ?: 0L
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
            .apply { if (len > 0) putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, len) }   // a progress bar
            .build())
        var actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP
        if (canPrev) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (canNext) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        if (len > 0) actions = actions or android.media.session.PlaybackState.ACTION_SEEK_TO or
            android.media.session.PlaybackState.ACTION_REWIND or android.media.session.PlaybackState.ACTION_FAST_FORWARD
        s.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                if (len > 0) pos else android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
            .apply {
                // Back / forward 10 seconds, where the site has no previous / next (newer Android shows these as buttons).
                // Back / forward 10 seconds, besides previous / next (newer Android shows these as extra buttons).
                if (len > 0) addCustomAction(android.media.session.PlaybackState.CustomAction.Builder("seekbackward", "Back 10 seconds", R.drawable.ic_d_rew10).build())
                if (len > 0) addCustomAction(android.media.session.PlaybackState.CustomAction.Builder("seekforward", "Forward 10 seconds", R.drawable.ic_d_fwd10).build())
            }
            .build())
        // The notification: artwork, title, artist; Previous, Play/Pause, Next (where the site has them), Stop.
        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsPi)
        fun button(code: Int, icon: Int, label: String, cmd: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), label,
            PendingIntent.getBroadcast(this, code, Intent(ACTION_MEDIA).setPackage(packageName).putExtra(EXTRA_CMD, cmd), flagsPi)).build()
        // Previous, back 10 s, Play/Pause, forward 10 s, Next (each where it applies), and Stop if there's room
        // (5 buttons at most). The compact view: Previous, Play/Pause, Next (or back / forward 10 s).
        val buttons = ArrayList<Notification.Action>()
        val prevAt = if (canPrev) buttons.size.also { buttons += button(10, R.drawable.ic_d_prev, "Previous", "previoustrack") } else -1
        val rewAt = if (len > 0) buttons.size.also { buttons += button(15, R.drawable.ic_d_rew10, "Back 10 seconds", "seekbackward") } else -1
        val playAt = buttons.size
        buttons += if (playing) button(11, R.drawable.ic_d_pause, "Pause", "pause") else button(12, R.drawable.ic_d_play, "Play", "play")
        val fwdAt = if (len > 0) buttons.size.also { buttons += button(16, R.drawable.ic_d_fwd10, "Forward 10 seconds", "seekforward") } else -1
        val nextAt = if (canNext) buttons.size.also { buttons += button(13, R.drawable.ic_d_next, "Next", "nexttrack") } else -1
        if (buttons.size < 5) buttons += button(14, R.drawable.ic_d_stop, "Stop", "stop")
        val compact = listOf(if (prevAt >= 0) prevAt else rewAt, playAt, if (nextAt >= 0) nextAt else fwdAt).filter { it >= 0 }.toIntArray()
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
        // The artwork: fetched once per picture, made small enough for a notification (a large picture makes the
        // update fail silently), then the notification is rebuilt with it.
        if (newArt != null && newArt != artUrl) {
            artUrl = newArt
            val again = intent?.let { Intent(it) } ?: return
            artLoader.execute {
                val bmp = runCatching {
                    if (newArt.startsWith("data:image/")) {
                        val bytes = android.util.Base64.decode(newArt.substringAfter(','), android.util.Base64.DEFAULT)
                        return@runCatching android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    val c = java.net.URL(newArt).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WhitelistBrowser")
                    try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
                }.onFailure { lastArt = "failed (${it.javaClass.simpleName}: ${it.message})" }.getOrNull()?.let { full ->
                    val scale = minOf(1f, 512f / maxOf(full.width, full.height))
                    if (scale >= 1f) full else android.graphics.Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                }
                if (bmp == null && !lastArt.startsWith("failed")) lastArt = "failed (not a picture Android can read)"
                if (bmp != null) lastArt = "loaded (${bmp.width}×${bmp.height})"
                if (bmp != null && artUrl == newArt) {
                    art = bmp
                    android.os.Handler(mainLooper).post { runCatching { show(again) } }
                }
            }
        } else if (newArt == null && artUrl != null) { artUrl = null; art = null }
    }

    override fun onDestroy() {
        if (running === this) running = null
        // An update waiting for the sound to stop: now.
        if (Updater.isPending(applicationContext)) Thread { runCatching { Updater.installWhenFree(applicationContext) } }.start()
        AppLog.i("Sound", "Playing notification stopped")
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
        private const val EXTRA_POS = "pos"
        private const val EXTRA_LEN = "len"
        private const val CHANNEL = "playback"
        /** What's playing now, for About this phone (to see why artwork or buttons might be missing). */
        @Volatile var lastPlaying = ""
        @Volatile private var lastArt = ""
        private const val ID = 4417

        /** The running service, if any (to update it directly). */
        @Volatile private var running: PlaybackService? = null
        /** Is the playing notification up (something's playing, or paused for a while)? */
        val isPlaying: Boolean get() = running != null

        /**
         * Shows (or updates) the media notification: [info] is the page's own description of what's playing. A running
         * service is updated directly; it's only started fresh (which Android allows while the app is on screen) if not.
         */
        fun show(ctx: Context, site: String, info: org.json.JSONObject?) {
            val i = Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_SITE, site)
                .putExtra(EXTRA_PLAYING, info?.optBoolean("playing", true) ?: true)
            if (info != null) i.putExtra(EXTRA_TITLE, info.optString("title")).putExtra(EXTRA_ARTIST, info.optString("artist"))
                .putExtra(EXTRA_ART, info.optString("art")).putExtra(EXTRA_PREV, info.optBoolean("prev"))
                .putExtra(EXTRA_NEXT, info.optBoolean("next"))
                .putExtra(EXTRA_POS, info.optLong("pos")).putExtra(EXTRA_LEN, info.optLong("len"))
            val r = running
            if (r != null) { android.os.Handler(android.os.Looper.getMainLooper()).post { r.update(i) }; return }
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
                .onFailure { AppLog.e("Sound", "Couldn't start the playing notification", it) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } }

        /**
         * On every page, before its own scripts: tells the page it's still visible when the app goes to the
         * background (sites like YouTube stop playing otherwise), and keeps track of whether anything's playing
         * (players on the page, and ones made in code), so the app knows whether to keep it going.
         */
        const val PAGE_SCRIPT = """
(function () {
  // Files the page builds itself ("blob:" addresses): kept for a minute after the page lets go of them, so a download
  // that starts as the page lets go (GitHub's do) can still be saved.
  if (!window.__wlbFiles) {
    window.__wlbFiles = new Map();
    var mk = URL.createObjectURL, rm = URL.revokeObjectURL;
    URL.createObjectURL = function (o) {
      var u = mk.apply(this, arguments);
      try { if (o instanceof Blob) window.__wlbFiles.set(u, o); } catch (e) {}
      return u;
    };
    URL.revokeObjectURL = function (u) {
      var self = this, args = arguments;
      setTimeout(function () { window.__wlbFiles.delete(u); try { rm.apply(self, args); } catch (e) {} }, 60000);
    };
    // The name the page gives such a download (<a download="README.md">), which Android doesn't pass on: kept too,
    // whether the link is tapped or "clicked" by the page's own code (often on a link that's never on the page).
    window.__wlbNames = new Map();
    var note = function (a) {
      try { if (a && a.href && a.href.indexOf('blob:') === 0 && a.hasAttribute('download')) window.__wlbNames.set(a.href, a.getAttribute('download') || ''); } catch (e) {}
    };
    var click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () { note(this); return click.apply(this, arguments); };
    document.addEventListener('click', function (e) { note(e.target && e.target.closest ? e.target.closest('a') : null); }, true);
  }
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
  // (Captured on the general function, so however the site registers them, they're seen.)
  var handlers = {};
  try {
    var MS = window.MediaSession && MediaSession.prototype;
    if (MS && MS.setActionHandler && !MS.setActionHandler.__wlb) {
      var set = MS.setActionHandler;
      var wrapped = function (a, h) { handlers[a] = h; return set.apply(this, arguments); };
      wrapped.__wlb = true;
      MS.setActionHandler = wrapped;
    }
  } catch (e) {}
  // Android's browser engine doesn't have Media Session (where sites describe what's playing and register their
  // buttons), so sites like YouTube Music don't describe anything. It's provided here, before the site's own scripts:
  // the site finds it as in Chrome, and what it sets is passed on to the notification.
  try {
    if (!('mediaSession' in navigator)) {
      if (!window.MediaMetadata) {
        window.MediaMetadata = function (init) {
          init = init || {};
          this.title = init.title || ''; this.artist = init.artist || ''; this.album = init.album || '';
          this.artwork = Array.isArray(init.artwork) ? init.artwork.slice() : [];
        };
      }
      // A real MediaSession (some sites check "instanceof MediaSession", or that window.MediaSession exists).
      var MS = window.MediaSession || function MediaSession() { this.metadata = null; this.playbackState = 'none'; };
      if (!window.MediaSession) {
        MS.prototype.setActionHandler = function (a, h) { handlers[a] = h; };
        MS.prototype.setPositionState = function () {};
        MS.prototype.setCameraActive = function () {};
        MS.prototype.setMicrophoneActive = function () {};
        window.MediaSession = MS;
      }
      var session = new MS();
      Object.defineProperty(Navigator.prototype, 'mediaSession', { configurable: true, get: function () { return session; } });
    }
  } catch (e) {}
  // What's playing, as the site last described it: kept, since some sites clear it when paused.
  var lastMeta = null;
  window.__wlbPlaying = function () {
    var n = 0; players.forEach(function (el) { if (!el.paused && !el.ended && !el.muted && el.volume > 0) n++; }); return n;
  };
  window.__wlbStopAll = function () { players.forEach(function (el) { try { el.pause(); } catch (e) {} }); };
  // What's playing, for the media controls: as the site describes it (title, artist, artwork), and its buttons.
  window.__wlbMediaInfo = function () {
    var has = false; players.forEach(function (el) { if (!el.ended && (el.currentTime > 0 || !el.paused)) has = true; });
    var md = null, art = '';
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
    if (md && md.title) lastMeta = { title: md.title, artist: md.artist, album: md.album, artwork: md.artwork };
    else if (has && lastMeta) md = lastMeta;                    // paused, and the site cleared it: as it was
    try { if (md && md.artwork && md.artwork.length) art = new URL(md.artwork[md.artwork.length - 1].src, location.href).href; } catch (e) {}
    // No artwork from the site: the video's preview picture, else the page's sharing picture (most pages have one).
    try {
      if (!art && last && last.poster) art = new URL(last.poster, location.href).href;
      var og = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
      if (!art && og && og.content) art = new URL(og.content, location.href).href;
    } catch (e) {}
    var pos = last && isFinite(last.currentTime) ? last.currentTime : 0, len = last && isFinite(last.duration) ? last.duration : 0;
    return { playing: window.__wlbPlaying() > 0, has: has, title: (md && md.title) || document.title || '', artist: (md && md.artist) || '',
      album: (md && md.album) || '', art: art, prev: !!handlers.previoustrack, next: !!handlers.nexttrack,
      pos: Math.round(pos * 1000), len: Math.round(len * 1000) };
  };
  // A media button pressed (notification, lock screen, headphones): the site's own handler if it has one.
  window.__wlbMediaDo = function (a) {
    // Seeking: "seekto:<ms>", "seekbackward", "seekforward" (the site's own if it has them, else on its player).
    if (a.indexOf('seekto:') === 0) {
      var t = parseInt(a.slice(7), 10) / 1000;
      if (handlers.seekto) { try { handlers.seekto({ action: 'seekto', seekTime: t }); return; } catch (e) {} }
      if (last) try { last.currentTime = t; } catch (e) {}
      return;
    }
    // Back / forward 10 seconds: done here, on the page's player (sites' own handlers can ignore the amount asked:
    // YouTube Music's always moves 5).
    if (a === 'seekbackward' || a === 'seekforward') {
      if (last) try {
        var t = last.currentTime + (a === 'seekforward' ? 10 : -10);
        last.currentTime = Math.max(0, isFinite(last.duration) ? Math.min(t, last.duration - 0.25) : t);
      } catch (e) {}
      return;
    }
    if (handlers[a]) { try { handlers[a]({ action: a, seekOffset: 10 }); return; } catch (e) {} }
    if (a === 'play' && last) { try { last.play(); } catch (e) {} }
    if (a === 'pause') window.__wlbStopAll();
  };
})();
"""
    }
}
