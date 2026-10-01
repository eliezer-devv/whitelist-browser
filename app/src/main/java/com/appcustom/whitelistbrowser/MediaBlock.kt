package com.appcustom.whitelistbrowser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * "No photos" and "no videos" modes (lists: "noPhotos", "noVideos", and "noMedia" for both). The page
 * itself opens normally, but:
 *  1. no photos: images don't load (WebView's own switch, set by MainActivity), and image files are refused;
 *  2. no videos: video and audio files and embedded players are refused as they're requested;
 *  3. [script] replaces what's off with small placeholders, and stops any player that starts anyway.
 */
object MediaBlock {
    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ogv", "m3u8", "mpd", "ts", "m4s", "flv")
    private val SOUND_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif", "ico")
    // Embedded video players and the servers they stream from.
    private val VIDEO_HOSTS = listOf("googlevideo.com", "youtube-nocookie.com", "ytimg.com", "vimeocdn.com",
        "player.vimeo.com", "dailymotion.com", "dmcdn.net", "jwplayer.com", "jwpcdn.com", "brightcove.net",
        "wistia.com", "wistia.net", "twitch.tv", "ttvnw.net")

    private fun ext(request: WebResourceRequest) =
        (request.url.path?.lowercase() ?: "").substringAfterLast('/').substringAfterLast('.', "")
    private fun accept(request: WebResourceRequest): String {
        val h = request.requestHeaders ?: emptyMap()
        return (h["Accept"] ?: h["accept"] ?: "").lowercase()
    }

    /** Is this request a photo (an image file)? */
    fun isImage(request: WebResourceRequest): Boolean =
        ext(request) in IMAGE_EXT || accept(request).startsWith("image/")

    /** Is this request sound: an audio file or stream? */
    fun isSound(request: WebResourceRequest): Boolean =
        ext(request) in SOUND_EXT || accept(request).startsWith("audio/")

    /** Is this request a video: a file, a stream, or an embedded player? */
    fun isVideo(request: WebResourceRequest): Boolean {
        val url = request.url
        val host = url.host?.lowercase() ?: return false
        val path = url.path?.lowercase() ?: ""
        if (VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/embed")) return true
        if (path.contains("videoplayback")) return true
        if (ext(request) in VIDEO_EXT) return true
        if (accept(request).startsWith("video/")) return true
        if (isSound(request)) return false
        return (request.requestHeaders ?: emptyMap()).keys.any { it.equals("Range", ignoreCase = true) } // players fetch in ranges
    }

    fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** The page script for what's off here: photos, videos, or both. */
    /**
     * The page script for what's off here: photos, videos and/or sound. [allow]: single photos or videos
     * allowed anyway (host + path), left alone.
     */
    fun script(photos: Boolean, videos: Boolean, sound: Boolean, allow: List<String>) =
        "window.__wlbOff = {photos: $photos, videos: $videos, sound: $sound, allow: ${org.json.JSONArray(allow)}};\n" + PAGE_SCRIPT

    /**
     * Replaces what's off (window.__wlbOff) with small placeholders: "Photo blocked", "Video blocked" or
     * "Sound blocked", "tap to ask" (tiny images such as icons are just hidden). Stops players, mutes videos
     * when only sound is off, and keeps doing so as the page adds more. Single items in "allow" are left
     * alone. Tapping a placeholder asks for that kind, and that one item
     * (wlb://ask-media?kind=photos&src=...).
     */
    private const val PAGE_SCRIPT = """
(function () {
  var off = window.__wlbOff || { photos: true, videos: true, sound: true, allow: [] };
  var allow = off.allow || [];
  var key = (off.photos ? 'p' : '') + (off.videos ? 'v' : '') + (off.sound ? 's' : '');
  if (!key || window.__wlbNoMedia === key) return; window.__wlbNoMedia = key;
  function itemKey(u) {
    try { var x = new URL(u, location.href); return (x.host + x.pathname).toLowerCase().replace(/^www[.]/, ''); } catch (e) { return ''; }
  }
  function srcOf(el) { return el.currentSrc || el.src || el.getAttribute('src') || ''; }
  function allowed(el) { var s = srcOf(el); return !!s && allow.indexOf(itemKey(s)) >= 0; }
  var style = document.createElement('style');
  style.textContent =
    '.wlb-ph{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;max-width:100%;' +
    'min-height:40px;padding:6px 10px;margin:2px 0;border:1px dashed #8aa9a4;border-radius:8px;background:#eef4f2;' +
    'color:#1f3a3d;font:13px/1.3 system-ui,sans-serif;text-align:center;cursor:pointer;overflow:hidden}' +
    (off.videos ? 'object,embed{display:none!important}' : '') + (off.photos ? '*{background-image:none!important}' : '');
  (document.head || document.documentElement).appendChild(style);

  var LABEL = { photo: '\uD83D\uDDBC\uFE0F Photo blocked', video: '\uD83C\uDFAC Video blocked', sound: '\uD83D\uDD07 Sound blocked' };
  var KIND = { photo: 'photos', video: 'videos', sound: 'sound' };
  function placeholder(kind, w, h, src) {
    var d = document.createElement('div');
    d.className = 'wlb-ph';
    d.textContent = LABEL[kind] + ' \u00B7 tap to ask';
    if (w > 0) d.style.width = Math.min(w, window.innerWidth - 16) + 'px';
    if (h > 0) d.style.height = Math.min(h, 360) + 'px';
    d.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation();
      location.href = 'wlb://ask-media?kind=' + KIND[kind] + (src ? '&src=' + encodeURIComponent(src) : ''); }, true);
    return d;
  }
  function size(el) {
    return [parseInt(el.getAttribute('width'), 10) || el.offsetWidth || 0,
            parseInt(el.getAttribute('height'), 10) || el.offsetHeight || 0];
  }
  // Icons, logos and the like: just hidden. Known by a small stated size, or by their name.
  // (An image with no stated size looks small while it isn't loaded, so size alone can't tell.)
  function isIcon(el) {
    var w = parseInt(el.getAttribute('width'), 10), h = parseInt(el.getAttribute('height'), 10);
    if (w > 0 && h > 0) return w < 48 && h < 48;
    if (el.offsetWidth >= 48) return false;
    return /icon|logo|sprite|avatar|emoji|badge|pixel|spacer|favicon/i.test((el.getAttribute('src') || '') + ' ' + (el.className || ''));
  }
  function swap() {
    var what = [off.photos ? 'img:not([data-wlb])' : '', off.videos ? 'video:not([data-wlb]),iframe:not([data-wlb])' : '',
      off.sound ? 'audio:not([data-wlb])' : ''].filter(Boolean).join(',');
    if (what) document.querySelectorAll(what).forEach(function (el) {
      el.setAttribute('data-wlb', '1');
      var tag = el.tagName.toLowerCase();
      if (tag === 'iframe' && !/youtube|vimeo|dailymotion|player|video|wistia|twitch/i.test(el.src || '')) return;
      if (allowed(el)) return;                                   // allowed one by one
      var src = srcOf(el);
      if (tag === 'video' || tag === 'audio') {
        try { el.pause(); el.removeAttribute('src'); el.querySelectorAll('source').forEach(function (x) { x.remove(); }); el.load(); } catch (e) {}
      }
      var kind = tag === 'img' ? 'photo' : tag === 'audio' ? 'sound' : 'video';
      if (kind === 'sound' && !el.hasAttribute('controls')) { el.style.setProperty('display', 'none', 'important'); return; }
      var s = size(el);
      var icon = tag === 'img' && isIcon(el);
      var known = s[0] >= 48; // a real size to copy; otherwise a small placeholder
      el.style.setProperty('display', 'none', 'important');
      if (icon) return;
      if (el.parentNode) el.parentNode.insertBefore(placeholder(kind, known ? s[0] : 0, known ? s[1] : 0, src), el);
    });
    // Only sound off: videos play, muted.
    if (off.sound && !off.videos) document.querySelectorAll('video').forEach(function (v) { if (!allowed(v)) v.muted = true; });
  }
  swap();
  new MutationObserver(swap).observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('play', function (e) {
    var el = e.target, tag = (el.tagName || '').toLowerCase();
    if (allowed(el)) return;
    try {
      if (tag === 'video' && off.videos) el.pause();
      else if (tag === 'audio' && off.sound) el.pause();
      else if (tag === 'video' && off.sound) el.muted = true;
    } catch (x) {}
  }, true);
  if (off.sound) document.addEventListener('volumechange', function (e) {
    var el = e.target;
    if ((el.tagName || '').toLowerCase() === 'video' && !el.muted && !allowed(el)) el.muted = true;
  }, true);
})();
"""
}
