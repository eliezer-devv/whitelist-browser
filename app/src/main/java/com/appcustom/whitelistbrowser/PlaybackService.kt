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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val site = intent?.getStringExtra(EXTRA_SITE) ?: "a website"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing in the background", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a site's sound plays with the app in the background"
                setShowBadge(false)
            })
        }
        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsPi)
        // Stop: a quiet message to the app (it stops without coming to the front).
        val stop = PendingIntent.getBroadcast(this, 2, Intent(ACTION_STOP).setPackage(packageName), flagsPi)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_d_sound)
            .setContentTitle("Playing from $site")
            .setContentText("Whitelist Browser")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.appcustom.whitelistbrowser.STOP_SOUND"
        private const val EXTRA_SITE = "site"
        private const val CHANNEL = "playback"
        private const val ID = 4417

        fun start(ctx: Context, site: String) {
            val i = Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_SITE, site)
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
  var players = new Set();
  function watch(el) {
    if (!el || el.__wlbTracked) return; el.__wlbTracked = true; players.add(el);
  }
  ['play', 'playing'].forEach(function (ev) { document.addEventListener(ev, function (e) { watch(e.target); }, true); });
  var p = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { watch(this); return p.apply(this, arguments); };
  window.__wlbPlaying = function () {
    var n = 0; players.forEach(function (el) { if (!el.paused && !el.ended && !el.muted && el.volume > 0) n++; }); return n;
  };
  window.__wlbStopAll = function () { players.forEach(function (el) { try { el.pause(); } catch (e) {} }); };
})();
"""
    }
}
