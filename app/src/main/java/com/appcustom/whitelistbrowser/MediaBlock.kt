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

    // Sound formats that are also used for videos' own soundtracks (a video's sound sent separately).
    private val SOUNDTRACK_EXT = setOf("m4a", "aac")

    /**
     * Is this request sound on its own (music, a podcast, a sound file or audio stream)? Videos keep their
     * sound: with [videosAllowed], formats often used for a video's own soundtrack (.m4a, .aac) aren't
     * counted, so allowed videos don't lose their sound.
     */
    fun isSound(request: WebResourceRequest, videosAllowed: Boolean = false): Boolean {
        val e = ext(request)
        val host = request.url.host?.lowercase() ?: ""
        // A video's own soundtrack, where videos are allowed: it plays with the video.
        if (videosAllowed && (e in SOUNDTRACK_EXT || VIDEO_HOSTS.any { host == it || host.endsWith(".$it") })) return false
        return e in SOUND_EXT || accept(request).startsWith("audio/")
    }

    /**
     * Is this request unmistakably a video: a video file, a video player's own server, an embedded player, or
     * labelled as video? Anything less certain (e.g. a stream fetched in pieces, which may be a song or a film)
     * isn't decided here: the page script judges it as it plays, by whether it has a picture.
     */
    fun isVideo(request: WebResourceRequest): Boolean {
        val url = request.url
        val host = url.host?.lowercase() ?: return false
        val path = url.path?.lowercase() ?: ""
        if (VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/embed")) return true
        if (path.contains("videoplayback")) return true
        if (ext(request) in VIDEO_EXT) return true
        return accept(request).startsWith("video/")
    }

    /** Is this allowed item (host + path) an embedded video player, like YouTube's or Vimeo's? */
    fun isPlayerKey(key: String): Boolean {
        val host = key.substringBefore('/')
        val path = key.substringAfter('/', "")
        return VIDEO_HOSTS.any { host == it || host.endsWith(".$it") } ||
            ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("embed"))
    }

    /** A player's video data, rather than a player itself (a player is a page, in a frame). */
    fun isStream(request: WebResourceRequest): Boolean = !accept(request).contains("text/html")

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
     * "Sound blocked", "tap to ask" (tiny images such as icons are just hidden). Stops players, and keeps doing
     * so as the page adds more. Videos that are allowed keep their sound. Single items in "allow" are left
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
  // Allowed one by one: by the address in use, or the one just set (the address in use catches up later).
  function allowed(el) {
    return [el.currentSrc, el.src, el.getAttribute('src')].some(function (s) { return !!s && allow.indexOf(itemKey(s)) >= 0; });
  }
  var style = document.createElement('style');
  style.textContent =
    '.wlb-ph{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;max-width:100%;' +
    'min-height:40px;padding:6px 10px;margin:2px 0;border:1px dashed #8aa9a4;border-radius:8px;background:#eef4f2;' +
    'color:#1f3a3d;font:13px/1.3 system-ui,sans-serif;text-align:center;cursor:pointer;overflow:hidden}' +
    // Videos off: a video player stays out of sight until it's known to have no picture (then it's sound).
    (off.videos ? 'object,embed{display:none!important}video:not([data-wlb-ok]){visibility:hidden!important}' : '') +
    (off.photos ? '*{background-image:none!important}' : '');
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
  // Takes a player off the page: stopped, emptied, and replaced with a placeholder (if it was visible).
  function takeOff(el, kind) {
    if (el.__wlbOff) { try { el.pause(); } catch (e) {} return; }      // already off: just keep it stopped
    el.__wlbOff = true;
    var src = srcOf(el), s = size(el), tag = el.tagName.toLowerCase();
    if (tag === 'video' || tag === 'audio') {
      try { el.pause(); el.removeAttribute('src'); el.querySelectorAll('source').forEach(function (x) { x.remove(); }); el.load(); } catch (e) {}
    }
    el.style.setProperty('display', 'none', 'important');
    var visible = s[0] > 0 || s[1] > 0 || el.hasAttribute('controls');
    if (!visible || (kind === 'sound' && !el.hasAttribute('controls'))) return;   // hidden players: no placeholder
    var known = s[0] >= 48;
    var ph = placeholder(kind, known ? s[0] : 0, known ? s[1] : 0, src);
    el.__wlbPh = ph;
    if (el.parentNode) el.parentNode.insertBefore(ph, el);
  }
  // Sound or video, judged by the media itself: an audio player is sound; a video player is video if it has
  // a picture, and sound if it doesn't (e.g. a music service's player). Known once it has loaded its first
  // details; until then: unknown.
  function kindOf(el) {
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'audio') return 'sound';
    if (tag !== 'video') return '';
    if (el.videoWidth > 0) return 'video';
    return el.readyState >= 1 ? 'sound' : '';
  }
  // Picture and sound, separately. "No videos": no picture (a video's sound still plays if sound is allowed).
  // "No sound": no sound at all (a video plays muted). Both off: stopped.
  function decide(el) {
    if (!el || !el.tagName || allowed(el)) { if (el && el.setAttribute) el.setAttribute('data-wlb-ok', '1'); return; }
    var k = kindOf(el);
    if (!k) return;
    if (k === 'sound') { if (off.sound) takeOff(el, 'sound'); else el.setAttribute('data-wlb-ok', '1'); return; }
    if (off.videos && off.sound) { takeOff(el, 'video'); return; }
    if (off.sound) silence(el);
    if (off.videos) soundOnly(el); else el.setAttribute('data-wlb-ok', '1');
  }
  // Sound off: a video plays muted, and stays muted if the page tries to unmute it.
  function silence(el) {
    if (el.__wlbMuted) return;
    el.__wlbMuted = true;
    try { el.muted = true; } catch (e) {}
    el.addEventListener('volumechange', function () { if (!el.muted) { try { el.muted = true; } catch (e) {} } });
  }
  // Videos off, sound on: the picture is hidden; it keeps playing (its sound). A label covers where it was, with
  // "Ask"; taps elsewhere still reach the player (so its own play / pause works).
  function soundOnly(el) {
    if (el.__wlbSoundOnly) return;
    el.__wlbSoundOnly = true;
    el.style.setProperty('opacity', '0', 'important');            // (it stays out of sight: no data-wlb-ok)
    var host = el.offsetParent, w = el.offsetWidth, h = el.offsetHeight;
    if (!host || w < 48 || h < 24) return;                        // a hidden player: nothing to label
    var tag = document.createElement('div');
    tag.className = 'wlb-ph';
    tag.style.cssText = 'position:absolute;left:' + el.offsetLeft + 'px;top:' + el.offsetTop + 'px;width:' + w + 'px;height:' + h +
      'px;margin:0;pointer-events:none;flex-direction:column;gap:6px;z-index:1';
    var t = document.createElement('div');
    t.textContent = LABEL.video.replace('Video blocked', 'Picture hidden') + ' \u00B7 sound only';
    var ask = document.createElement('a');
    ask.textContent = 'Ask for videos';
    ask.href = 'wlb://ask-media?kind=videos' + (srcOf(el) ? '&src=' + encodeURIComponent(srcOf(el)) : '');
    ask.style.cssText = 'pointer-events:auto;color:#1f5f55;font-weight:700;text-decoration:underline';
    tag.appendChild(t); tag.appendChild(ask);
    if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
    host.appendChild(tag);
  }
  function judge(e) { decide(e.target); }
  function swap() {
    var what = [off.photos ? 'img:not([data-wlb])' : '', off.videos ? 'iframe:not([data-wlb])' : '',
      off.sound ? 'audio:not([data-wlb])' : '', 'video:not([data-wlb])'].filter(Boolean).join(',');
    document.querySelectorAll(what).forEach(function (el) {
      el.setAttribute('data-wlb', '1');
      var tag = el.tagName.toLowerCase();
      if (tag === 'iframe' && !/youtube|vimeo|dailymotion|player|video|wistia|twitch/i.test(el.src || '')) return;
      if (allowed(el)) { el.setAttribute('data-wlb-ok', '1'); return; }   // allowed one by one
      if (tag === 'video') {
        // Both off: nothing to judge. Otherwise it's judged by the media itself, so it needs its first details:
        // a player that was told not to load anything until tapped is asked for just those.
        if (off.videos && off.sound) { takeOff(el, 'video'); return; }
        if (!off.videos && !off.sound) return;
        // Already failed to load (its video was blocked on the way, maybe before this script ran): with videos off,
        // replaced, so there's something to tap to ask for it.
        if (off.videos && (el.error || el.networkState === 3)) { takeOff(el, 'video'); return; }
        if (el.readyState >= 1) { decide(el); return; }
        try { if (el.preload === 'none') { el.preload = 'metadata'; if (srcOf(el)) el.load(); } } catch (e) {}
        return;
      }
      if (tag === 'audio' || tag === 'iframe') { takeOff(el, tag === 'audio' ? 'sound' : 'video'); return; }
      // Photos.
      var s = size(el);
      var known = s[0] >= 48; // a real size to copy; otherwise a small placeholder
      el.style.setProperty('display', 'none', 'important');
      if (isIcon(el)) return;
      var ph = placeholder('photo', known ? s[0] : 0, known ? s[1] : 0, srcOf(el));
      el.__wlbPh = ph;
      if (el.parentNode) el.parentNode.insertBefore(ph, el);
    });
    // Players in frames from the same site: judged the same way.
    document.querySelectorAll('iframe').forEach(function (f) {
      try {
        var d = f.contentDocument;
        if (d && !d.__wlbHooked) { d.__wlbHooked = true; ['play', 'playing', 'loadedmetadata'].forEach(function (ev) { d.addEventListener(ev, judge, true); }); }
      } catch (e) {}
    });
  }
  // Many sites give an image its real address later (as it's scrolled to): check again when it changes,
  // so one allowed one by one appears even then.
  function recheck(el) {
    if (!el.hasAttribute('data-wlb')) return;
    if (allowed(el)) {
      el.style.removeProperty('display');
      if (el.__wlbPh) { el.__wlbPh.remove(); el.__wlbPh = null; }
    }
  }
  swap();
  new MutationObserver(function (changes) {
    changes.forEach(function (c) { if (c.type === 'attributes') recheck(c.target); });
    swap();
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset'] });
  document.addEventListener('play', judge, true);
  document.addEventListener('playing', judge, true);
  document.addEventListener('loadedmetadata', judge, true);
  // Players made in code and never put on the page (many music players work this way): their events never reach
  // the page, so each is judged when it's told to play, and watched as it loads.
  var realPlay = HTMLMediaElement.prototype.play;
  if (!realPlay.__wlb) {
    var wrappedPlay = function () {
      var el = this;
      if (!el.__wlbWatched) {
        el.__wlbWatched = true;
        ['play', 'playing', 'loadedmetadata'].forEach(function (ev) { el.addEventListener(ev, function () { decide(el); }); });
      }
      if (!allowed(el)) {
        var tag = (el.tagName || '').toLowerCase(), k = kindOf(el) || (tag === 'audio' ? 'sound' : '');
        if ((off.videos && off.sound) || (k === 'sound' && off.sound)) {
          takeOff(el, k || 'video');
          return Promise.resolve();
        }
        if (k === 'video') decide(el);                               // picture hidden and/or muted, but it plays
        else if (off.sound && tag === 'video') silence(el);          // not known yet: silent until it is
      }
      return realPlay.apply(this, arguments);
    };
    wrappedPlay.__wlb = true;
    HTMLMediaElement.prototype.play = wrappedPlay;
  }
  // A video player that couldn't load (its video was blocked on the way): it can't be judged, so with videos off
  // it's replaced too, so there's something to tap to ask for it.
  document.addEventListener('error', function (e) {
    var el = e.target && e.target.tagName === 'SOURCE' ? e.target.parentNode : e.target;
    if (el && el.tagName === 'VIDEO' && off.videos && !el.hasAttribute('data-wlb-ok') && !allowed(el)) takeOff(el, 'video');
  }, true);
  // Sound off: sound made without a player (the browser's sound system, used by games and many sites) stays
  // silent too: new sound "contexts" start paused, and can't be started.
  if (off.sound) ['AudioContext', 'webkitAudioContext'].forEach(function (n) {
    var C = window[n];
    if (!C || C.__wlb) return;
    try {
      C.prototype.resume = function () { return Promise.resolve(); };
      var W = function (a) { var c = a === undefined ? new C() : new C(a); try { c.suspend(); } catch (e) {} return c; };
      W.prototype = C.prototype; W.__wlb = true;
      window[n] = W;
    } catch (e) {}
  });
})();
"""
}
