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
    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ogv", "m3u8", "mpd", "ts", "m4s", "flv",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac"      // (sound counts with videos)
    )
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

    /** Is this request a video or sound: a file, a stream, or an embedded player? */
    fun isVideo(request: WebResourceRequest): Boolean {
        val url = request.url
        val host = url.host?.lowercase() ?: return false
        val path = url.path?.lowercase() ?: ""
        if (VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/embed")) return true
        if (path.contains("videoplayback")) return true
        if (ext(request) in VIDEO_EXT) return true
        val a = accept(request)
        if (a.startsWith("video/") || a.startsWith("audio/")) return true
        return (request.requestHeaders ?: emptyMap()).keys.any { it.equals("Range", ignoreCase = true) } // players fetch in ranges
    }

    fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** The page script for what's off here: photos, videos, or both. */
    fun script(photos: Boolean, videos: Boolean) =
        "window.__wlbOff = {photos: $photos, videos: $videos};\n" + PAGE_SCRIPT

    /**
     * Replaces pictures and/or videos (whichever are off: window.__wlbOff) with small "🖼️ Photo blocked ·
     * tap to ask" or "🎬 Video blocked · tap to ask" placeholders (tiny images such as icons are just hidden),
     * stops players, and keeps doing so as the page adds more. Tapping a placeholder asks for that kind
     * (wlb://ask-media?kind=photos or videos).
     */
    private const val PAGE_SCRIPT = """
(function () {
  var off = window.__wlbOff || { photos: true, videos: true };
  var key = (off.photos ? 'p' : '') + (off.videos ? 'v' : '');
  if (!key || window.__wlbNoMedia === key) return; window.__wlbNoMedia = key;
  var style = document.createElement('style');
  style.textContent =
    '.wlb-ph{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;max-width:100%;' +
    'min-height:40px;padding:6px 10px;margin:2px 0;border:1px dashed #8aa9a4;border-radius:8px;background:#eef4f2;' +
    'color:#1f3a3d;font:13px/1.3 system-ui,sans-serif;text-align:center;cursor:pointer;overflow:hidden}' +
    (off.videos ? 'audio,object,embed{display:none!important}' : '') + (off.photos ? '*{background-image:none!important}' : '');
  (document.head || document.documentElement).appendChild(style);

  function placeholder(kind, w, h) {
    var d = document.createElement('div');
    d.className = 'wlb-ph';
    d.textContent = (kind === 'video' ? '\uD83C\uDFAC Video blocked' : '\uD83D\uDDBC\uFE0F Photo blocked') + ' \u00B7 tap to ask';
    if (w > 0) d.style.width = Math.min(w, window.innerWidth - 16) + 'px';
    if (h > 0) d.style.height = Math.min(h, 360) + 'px';
    d.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation();
      location.href = 'wlb://ask-media?kind=' + (kind === 'video' ? 'videos' : 'photos'); }, true);
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
    var what = [off.photos ? 'img:not([data-wlb])' : '', off.videos ? 'video:not([data-wlb]),iframe:not([data-wlb])' : '']
      .filter(Boolean).join(',');
    if (!what) return;
    document.querySelectorAll(what).forEach(function (el) {
      el.setAttribute('data-wlb', '1');
      var tag = el.tagName.toLowerCase();
      if (tag === 'iframe' && !/youtube|vimeo|dailymotion|player|video|wistia|twitch/i.test(el.src || '')) return;
      if (tag === 'video') {
        try { el.pause(); el.removeAttribute('src'); el.querySelectorAll('source').forEach(function (x) { x.remove(); }); el.load(); } catch (e) {}
      }
      var s = size(el);
      var icon = tag === 'img' && isIcon(el);
      var known = s[0] >= 48; // a real size to copy; otherwise a small placeholder
      el.style.setProperty('display', 'none', 'important');
      if (icon) return;
      if (el.parentNode) el.parentNode.insertBefore(placeholder(tag === 'img' ? 'photo' : 'video', known ? s[0] : 0, known ? s[1] : 0), el);
    });
    if (off.videos) document.querySelectorAll('audio').forEach(function (m) { try { m.pause(); } catch (e) {} });
  }
  swap();
  new MutationObserver(swap).observe(document.documentElement, { childList: true, subtree: true });
  if (off.videos) document.addEventListener('play', function (e) { try { e.target.pause(); } catch (x) {} }, true);
})();
"""
}
