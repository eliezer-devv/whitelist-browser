package com.appcustom.whitelistbrowser

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Translating pages on the phone (Google ML Kit): the page's text never leaves the phone. Each language's model is
 * downloaded once (about 30 MB, from Google), after asking; then it works offline.
 */
object PageTranslate {
    private const val PREFS = "translate"

    /** A language the translator knows ("he", "en"…), or null. ("iw" is the old code for Hebrew.) */
    fun supported(tag: String?): String? {
        val t = tag?.lowercase()?.substringBefore('-')?.substringBefore('_')?.let { if (it == "iw") "he" else it } ?: return null
        return TranslateLanguage.fromLanguageTag(t)
    }

    /** The language pages are translated into: chosen once, else the phone's own. */
    fun target(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("target", null)
        ?: supported(java.util.Locale.getDefault().language) ?: TranslateLanguage.ENGLISH
    fun setTarget(ctx: Context, lang: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("target", lang).apply()

    /** Languages whose pages are translated automatically. */
    fun autoLangs(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("auto", emptySet()) ?: emptySet()
    fun setAuto(ctx: Context, lang: String, on: Boolean) {
        val now = autoLangs(ctx).toMutableSet().apply { if (on) add(lang) else remove(lang) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("auto", now).apply()
    }

    /** Languages whose pages don't get the "Translate?" bar (turned off in ⋮ → Translate page). */
    fun noOffer(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("noOffer", emptySet()) ?: emptySet()
    fun setOffer(ctx: Context, lang: String, on: Boolean) {
        val now = noOffer(ctx).toMutableSet().apply { if (on) remove(lang) else add(lang) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("noOffer", now).apply()
    }

    /** "Hebrew", "English"… in the phone's language. */
    fun name(lang: String): String = java.util.Locale(lang).let { it.getDisplayLanguage(java.util.Locale.getDefault()) }
        .replaceFirstChar { it.uppercase() }

    /** Every language it can translate, by name. */
    fun all(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { name(it) }

    /** The language of [sample] (a page's text), else [declared] (its own lang tag), else null. Calls back on the main thread. */
    fun detect(sample: String, declared: String?, done: (String?) -> Unit) {
        if (sample.isBlank()) { done(supported(declared)); return }
        LanguageIdentification.getClient().identifyLanguage(sample.take(2000))
            .addOnSuccessListener { tag -> done(supported(tag.takeIf { it != "und" }) ?: supported(declared)) }
            .addOnFailureListener { done(supported(declared)) }
    }

    private val translators = HashMap<String, Translator>()
    private fun translator(from: String, to: String): Translator = synchronized(translators) {
        translators.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
    }

    /** Are both languages' models on the phone already? */
    fun ready(from: String, to: String, done: (Boolean) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val have = models.map { it.language }.toSet() + TranslateLanguage.ENGLISH    // (English is built in)
                done(from in have && to in have)
            }
            .addOnFailureListener { done(false) }
    }

    /** Downloads what's needed (any network), then calls back: null, or what went wrong. */
    fun download(from: String, to: String, done: (String?) -> Unit) {
        translator(from, to).downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { AppLog.i("Translate", "Languages ready: $from → $to"); done(null) }
            .addOnFailureListener { AppLog.e("Translate", "Download failed ($from → $to)", it); done(it.message ?: "download failed") }
    }

    /** Translates [texts] (in order), calling back with the results on the main thread (a text that fails stays as it was). */
    fun translate(from: String, to: String, texts: List<String>, done: (List<String>) -> Unit) {
        val t = translator(from, to)
        val tasks = texts.map { t.translate(it) }
        com.google.android.gms.tasks.Tasks.whenAllComplete(tasks).addOnCompleteListener {
            done(tasks.mapIndexed { i, task -> if (task.isSuccessful) task.result ?: texts[i] else texts[i] })
        }
    }

    /**
     * Put on a page to translate it: gathers its text in batches for the app, puts the translations in place (keeping
     * the originals, for Show original), and translates text that appears later. Only the app's [token] works.
     */
    const val PAGE_SCRIPT = """
(function () {
  if (window.__wlbTr) return;
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, PRE: 1, KBD: 1, SVG: 1, MATH: 1, INPUT: 1, SELECT: 1, OPTION: 1 };
  var tr = window.__wlbTr = { token: null, on: false, nodes: [], orig: [], seen: new WeakSet(), pending: [], timer: 0 };
  function wanted(n) {
    if (!n.nodeValue || !/[^\s\d\p{P}\p{S}]/u.test(n.nodeValue)) return false;
    for (var e = n.parentElement; e; e = e.parentElement) {
      if (SKIP[e.tagName] || e.isContentEditable || e.getAttribute('translate') === 'no' || e.classList.contains('notranslate')) return false;
    }
    return true;
  }
  function gather(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode()) && tr.nodes.length < 4000) {
      if (tr.seen.has(n) || !wanted(n)) continue;
      tr.seen.add(n);
      var id = tr.nodes.length;
      tr.nodes.push(n); tr.orig.push(n.nodeValue);
      tr.pending.push([id, n.nodeValue.trim()]);
    }
    send();
  }
  function send() {
    while (tr.pending.length) {
      var batch = tr.pending.splice(0, 60);
      try { window.WLBTranslate.texts(tr.token, JSON.stringify(batch)); } catch (e) {}
    }
  }
  tr.start = function (token) {
    tr.token = token; tr.on = true;
    gather(document.body);
    if (!tr.mo) {
      tr.mo = new MutationObserver(function (records) {
        if (!tr.on) return;
        clearTimeout(tr.timer);
        tr.timer = setTimeout(function () { gather(document.body); }, 400);
      });
      tr.mo.observe(document.body, { childList: true, subtree: true });
    }
  };
  tr.apply = function (pairs) {
    if (!tr.on) return;
    pairs.forEach(function (p) {
      var n = tr.nodes[p[0]]; if (!n) return;
      var o = tr.orig[p[0]], lead = o.match(/^\s*/)[0], trail = o.match(/\s*$/)[0];
      n.nodeValue = lead + p[1] + trail;
    });
  };
  tr.restore = function () {
    tr.on = false;
    tr.nodes.forEach(function (n, i) { try { n.nodeValue = tr.orig[i]; } catch (e) {} });
    tr.nodes = []; tr.orig = []; tr.seen = new WeakSet(); tr.pending = [];
  };
})();
"""
}
