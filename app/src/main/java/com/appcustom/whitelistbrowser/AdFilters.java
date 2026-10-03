package com.appcustom.whitelistbrowser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads AdGuard's filter lists (their own syntax, which includes EasyList's) and answers three questions for the
 * browser: is this address an ad? which page elements are ads on this site? which of AdGuard's scripts and
 * scriptlets should run on this site? Plain Java, with nothing from Android, so the very same code can be tested
 * on its own. Rules it can't follow exactly are left out rather than guessed at.
 */
public final class AdFilters {

    private static final class NetRule {
        final Pattern path;          // null: the whole site
        final boolean thirdPartyOnly;
        final String[] onlyOn;       // $domain=a.com|b.com: only on pages of these sites (null: any)
        final String[] notOn;        // $domain=~c.com: not on pages of these sites
        NetRule(Pattern path, boolean thirdPartyOnly, String[] onlyOn, String[] notOn) {
            this.path = path; this.thirdPartyOnly = thirdPartyOnly; this.onlyOn = onlyOn; this.notOn = notOn;
        }
    }
    private static final class RegexRule {
        final Pattern re; final boolean thirdPartyOnly; final String[] onlyOn, notOn;
        RegexRule(Pattern re, boolean thirdPartyOnly, String[] onlyOn, String[] notOn) {
            this.re = re; this.thirdPartyOnly = thirdPartyOnly; this.onlyOn = onlyOn; this.notOn = notOn;
        }
    }
    /** $removeparam: a tracking code to strip from addresses (by name, or a pattern), on some sites or all. */
    private static final class RemoveParam {
        final String name; final Pattern re; final String site; final String[] onlyOn, notOn;
        RemoveParam(String name, Pattern re, String site, String[] onlyOn, String[] notOn) {
            this.name = name; this.re = re; this.site = site; this.onlyOn = onlyOn; this.notOn = notOn;
        }
    }
    /**
     * A rule that can match anywhere in an address ("/wp-content/" any text "/ads-"): the whole pattern, wildcards
     * and all. Its longest plain part is only a quick first check (an address without it can't match).
     */
    private static final class Text {
        final String token; final Pattern whole; final boolean thirdPartyOnly; final String[] onlyOn, notOn;
        Text(String token, Pattern whole, boolean thirdPartyOnly, String[] onlyOn, String[] notOn) {
            this.token = token; this.whole = whole; this.thirdPartyOnly = thirdPartyOnly; this.onlyOn = onlyOn; this.notOn = notOn;
        }
        boolean matches(String address, boolean third, String page) {
            if (thirdPartyOnly && !third) return false;
            if (onlyOn != null && (page == null || !under(page, onlyOn))) return false;
            if (notOn != null && page != null && under(page, notOn)) return false;
            return address.contains(token) && whole.matcher(address).find();
        }
    }

    private static final int MAX_GENERIC_CSS = 12000;    // elements hidden on every page
    private static final int MAX_ANYWHERE = 20000;       // "anywhere in the address" rules
    private static final int MAX_REGEX = 2000;           // address patterns written as regular expressions

    private final Map<String, List<NetRule>> block = new HashMap<>();
    private final Map<String, List<NetRule>> allow = new HashMap<>();
    private final List<Text> anywhere = new ArrayList<>();
    private final List<Text> anywhereAllow = new ArrayList<>();      // their exceptions ("@@/some/path/")
    private final Set<String> anywhereSeen = new HashSet<>();
    private final LinkedHashSet<String> genericCss = new LinkedHashSet<>();
    private final Map<String, List<String>> siteCss = new HashMap<>();
    private final Map<String, List<String>> siteUnhide = new HashMap<>();
    // "Everywhere except…" (~site.com##.x) and "on this site except part of it": selector -> sites where it doesn't apply.
    private final Map<String, List<String>> hideExcept = new HashMap<>();
    private final Map<String, List<String>> scripts = new HashMap<>();      // site ("" = every site) -> script rules
    private final Map<String, Set<String>> scriptsOff = new HashMap<>();    // site -> script rules switched off there
    private final List<RegexRule> regexRules = new ArrayList<>();
    private final List<RemoveParam> removeParams = new ArrayList<>();
    private final Map<String, List<String>> siteStyles = new HashMap<>();   // site -> style rules (#$#)
    private final Map<String, List<String>> siteExtended = new HashMap<>(); // site -> advanced element rules (#?#, #$?#)
    // AdGuard's per-site exceptions (how it keeps sites from breaking): site -> what's switched off there.
    private static final String NO_GENERIC = "generichide", NO_SPECIFIC = "specifichide", NO_HIDING = "elemhide",
        NO_SCRIPTS = "jsinject", NOTHING = "document";
    private final Map<String, Set<String>> siteOff = new HashMap<>();
    private static final Set<String> EXCEPTION_KINDS = new HashSet<>(java.util.Arrays.asList(
        "generichide", "ghide", "specifichide", "shide", "elemhide", "ehide", "jsinject", "document", "doc", "content", "urlblock"));

    private boolean off(String host, String what) {
        for (String p : withParents(host)) {
            Set<String> o = siteOff.get(p);
            if (o != null && (o.contains(what) || o.contains(NOTHING))) return true;
        }
        return false;
    }
    public int read, kept;

    private static final Pattern COSMETIC = Pattern.compile("^(.*?)(#@?%#|#@?\\$\\??#|#@?\\?#|#@?#)(.+)$");
    private static final Pattern PROCEDURAL = Pattern.compile(":(-abp-|has-text|contains|matches-|xpath|upward|remove|style|" +
        "watch-attr|min-text|nth-ancestor|others|if\\(|if-not)");
    private static final Set<String> TYPE_OPTIONS = new HashSet<>(java.util.Arrays.asList("script", "image", "stylesheet",
        "xmlhttprequest", "xhr", "subdocument", "frame", "media", "object", "other", "ping", "websocket", "font", "important",
        "third-party", "3p", "domain", "redirect", "removeparam"));

    /** Adds one list's text. */
    public void add(String listText) {
        for (String raw : listText.split("\r?\n")) {
            String l = raw.trim();
            // Comments, and headers like "[Adblock Plus 2.0]" (but not "[$path=…]" rules).
            if (l.isEmpty() || l.charAt(0) == '!' || (l.charAt(0) == '[' && !l.startsWith("[$"))) continue;
            read++;
            if (addRule(l)) kept++;
        }
    }

    private boolean addRule(String l) {
        // "[$path=…]site#%#…": a script for some pages of the site. Sites like YouTube move between pages without
        // loading a new one, so scripts run on the whole site (and do their work as you move around it). Other kinds
        // with such a condition (hiding elements, say) are left out rather than applied too widely.
        if (l.startsWith("[$")) {
            int close = l.indexOf(']');
            if (close < 0) return false;
            String mods = l.substring(2, close), rest = l.substring(close + 1);
            boolean onlyPath = true;
            for (String m : mods.split(",")) if (!m.trim().toLowerCase().startsWith("path=")) onlyPath = false;
            if (!onlyPath || !(rest.contains("#%#") && !rest.contains("#@%#"))) return false;
            l = rest;
        }
        Matcher cm = COSMETIC.matcher(l);
        if (cm.matches() && !l.startsWith("[$")) {
            String doms = cm.group(1), sep = cm.group(2), body = cm.group(3);
            List<String> domains = new ArrayList<>(), except = new ArrayList<>();
            for (String d : doms.split(",")) {
                d = d.trim().toLowerCase();
                if (d.isEmpty()) continue;
                if (d.startsWith("~")) except.add(d.substring(1).replaceFirst("^www\\.", "")); else domains.add(d);
            }
            for (String d : domains) if (!d.matches("[a-z0-9.*-]+")) return false;
            for (String d : except) if (!d.matches("[a-z0-9.*-]+")) return false;
            // "Except on…" only for plain element hiding (other kinds: left out, rather than applied too widely).
            if (!except.isEmpty() && !sep.equals("##")) return false;
            // AdGuard's scripts and scriptlets: #%# (on), #@%# (switched off there).
            if (sep.equals("#%#") || sep.equals("#@%#")) {
                boolean off = sep.equals("#@%#");
                if (domains.isEmpty()) domains.add("");
                for (String d : domains) {
                    String h = d.replaceFirst("^www\\.", "");
                    if (off) scriptsOff.computeIfAbsent(h, k -> new HashSet<>()).add(body);
                    else scripts.computeIfAbsent(h, k -> new ArrayList<>()).add(body);
                }
                return true;
            }
            // Style rules (#$#: CSS as written) and advanced element rules (#?#, #$?#, and ## with AdGuard's own
            // pseudo-classes: run by AdGuard's ExtendedCss code). Site by site only; their exceptions are left out.
            boolean extended = sep.equals("#?#") || sep.equals("#$?#") || (sep.equals("##") && PROCEDURAL.matcher(body).find());
            if (sep.equals("#$#") || extended) {
                if (domains.isEmpty() || body.contains("+js(") || body.length() > 500) return false;
                String rule = sep.equals("#?#") || sep.equals("##") ? body + " { display: none !important; }" : body;
                for (String d : domains) {
                    if (d.contains("*")) continue;
                    String h = d.replaceFirst("^www\\.", "");
                    List<String> list = (extended ? siteExtended : siteStyles).computeIfAbsent(h, k -> new ArrayList<>());
                    if (!list.contains(rule)) list.add(rule);                     // each rule once
                }
                return true;
            }
            if (!sep.equals("##") && !sep.equals("#@#")) return false;               // their exceptions: left out
            if (body.contains("+js(") || body.length() > 300) return false;
            if (!except.isEmpty()) hideExcept.computeIfAbsent(body, k -> new ArrayList<>()).addAll(except);
            if (domains.isEmpty()) {
                if (!sep.equals("##") || genericCss.size() >= MAX_GENERIC_CSS) return false;
                genericCss.add(body);
                return true;
            }
            for (String d : domains) {
                if (d.contains("*")) continue;
                String h = d.replaceFirst("^www\\.", "");
                (sep.equals("##") ? siteCss : siteUnhide).computeIfAbsent(h, k -> new ArrayList<>()).add(body);
            }
            return true;
        }
        if (l.startsWith("#")) return false;
        // ---- addresses ----
        boolean exception = l.startsWith("@@");
        String pattern = exception ? l.substring(2) : l;
        boolean third = false;
        String[] onlyOn = null, notOn = null;
        String removeParam = null;
        int dollar = pattern.lastIndexOf('$');
        if (exception && dollar > 0 && pattern.startsWith("||")) {
            boolean any = false;
            Set<String> kinds = new HashSet<>();
            for (String o : pattern.substring(dollar + 1).split(",")) {
                o = o.trim().toLowerCase();
                if (EXCEPTION_KINDS.contains(o)) { any = true; kinds.add(o); }
            }
            if (any) {
                Matcher hm = Pattern.compile("^[a-z0-9.-]+").matcher(pattern.substring(2).toLowerCase());
                if (!hm.find()) return false;
                String host = hm.group().replaceFirst("^www\\.", "");
                Set<String> off = siteOff.computeIfAbsent(host, k -> new HashSet<>());
                for (String k : kinds) {
                    if (k.equals("ghide")) k = NO_GENERIC; else if (k.equals("shide")) k = NO_SPECIFIC; else if (k.equals("ehide")) k = NO_HIDING;
                    else if (k.equals("doc")) k = NOTHING;
                    else if (k.equals("content") || k.equals("urlblock")) continue;
                    off.add(k);
                }
                return true;
            }
        }
        // Options after the last "$" (a rule for every address is just "$option", with nothing before it).
        if (dollar >= 0 && !(pattern.startsWith("/") && pattern.endsWith("/"))) {
            for (String o : pattern.substring(dollar + 1).split(",")) {
                o = o.trim().toLowerCase();
                String name = o.contains("=") ? o.substring(0, o.indexOf('=')) : o;
                if (!TYPE_OPTIONS.contains(name)) return false;                  // rewriting rules, other kinds…
                if (name.equals("third-party") || name.equals("3p")) third = true;
                if (name.equals("removeparam")) {
                    // As written: "removeparam=utm_source" (or a pattern). Removing every code, or all but some: left out.
                    String v = o.contains("=") ? o.substring(o.indexOf('=') + 1) : "";
                    if (v.isEmpty() || v.startsWith("~") || exception) return false;
                    removeParam = v;
                }
                if (name.equals("domain")) {
                    List<String> yes = new ArrayList<>(), no = new ArrayList<>();
                    for (String d : o.substring(7).split("\\|")) {
                        if (d.startsWith("~")) no.add(d.substring(1).replaceFirst("^www\\.", ""));
                        else if (!d.isEmpty()) yes.add(d.replaceFirst("^www\\.", ""));
                    }
                    for (String d : yes) if (d.contains("*") || d.startsWith("/")) return false;
                    if (!yes.isEmpty()) onlyOn = yes.toArray(new String[0]);
                    if (!no.isEmpty()) notOn = no.toArray(new String[0]);
                }
            }
            pattern = pattern.substring(0, dollar);
        }
        if (removeParam != null) return addRemoveParam(pattern, removeParam, onlyOn, notOn);
        // Address patterns written as regular expressions: followed as written.
        if (pattern.length() > 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            if (exception || regexRules.size() >= MAX_REGEX) return false;
            try {
                regexRules.add(new RegexRule(Pattern.compile(pattern.substring(1, pattern.length() - 1), Pattern.CASE_INSENSITIVE), third, onlyOn, notOn));
                return true;
            } catch (Exception e) { return false; }
        }
        if (pattern.startsWith("||")) {
            String rest = pattern.substring(2).toLowerCase();
            Matcher hm = Pattern.compile("^[a-z0-9.-]+").matcher(rest);
            if (!hm.find()) return false;
            String host = hm.group().replaceFirst("^www\\.", "");
            if (!host.contains(".") || (rest.length() > hm.end() && rest.charAt(hm.end()) == '*')) return false;
            String path = rest.substring(hm.end());
            if (path.equals("^") || path.equals("^|") || path.equals("|")) path = "";
            if (!path.isEmpty() && "/^:?*".indexOf(path.charAt(0)) < 0) return false;
            Pattern p = path.isEmpty() ? null : pathPattern(path);
            if (!path.isEmpty() && p == null) return false;
            (exception ? allow : block).computeIfAbsent(host, k -> new ArrayList<>()).add(new NetRule(p, third, onlyOn, notOn));
            return true;
        }
        // "Anywhere in the address" (and "starts with", "|https://…"): the whole pattern is matched, wildcards and all.
        String pat = pattern.toLowerCase();
        // The quick first check: the longest plain part, taken as the address is compared (without "https://").
        String bare = pat.replaceFirst("^\\|", "").replaceFirst("^https?://", "");
        String best = "";
        for (String part : bare.split("[*^|]")) if (part.length() > best.length()) best = part;
        if (best.length() < 4 || !best.matches(".*[a-z0-9].*") || anywhere.size() >= MAX_ANYWHERE || !anywhereSeen.add((exception ? "@" : "") + pat)) return false;
        Pattern whole = anywherePattern(pat);
        if (whole == null) return false;
        (exception ? anywhereAllow : anywhere).add(new Text(best, whole, third, onlyOn, notOn));
        return true;
    }

    private boolean addRemoveParam(String pattern, String value, String[] onlyOn, String[] notOn) {
        String site = "";
        if (pattern.startsWith("||")) {
            Matcher hm = Pattern.compile("^[a-z0-9.-]+").matcher(pattern.substring(2).toLowerCase());
            if (!hm.find()) return false;
            site = hm.group().replaceFirst("^www\\.", "");
        } else if (!pattern.isEmpty() && !pattern.equals("*")) return false;
        Pattern re = null;
        String name = null;
        if (value.length() > 2 && value.startsWith("/") && value.endsWith("/")) {
            try { re = Pattern.compile(value.substring(1, value.length() - 1)); } catch (Exception e) { return false; }
        } else name = value;
        removeParams.add(new RemoveParam(name, re, site, onlyOn, notOn));
        return true;
    }

    /**
     * An "anywhere" pattern against the whole address (without "https://"): a star is any text, "^" a separator or the
     * end; "|" at the start: the address starts there (with "https://" or "http://" in the rule, as written); at the end:
     * it ends there.
     */
    private static Pattern anywherePattern(String p) {
        boolean start = p.startsWith("|"), end = p.endsWith("|") && p.length() > 1;
        if (start) p = p.substring(1);
        if (end) p = p.substring(0, p.length() - 1);
        p = p.replaceFirst("^https?://", "");                   // the address is matched without its scheme
        StringBuilder sb = new StringBuilder(start ? "^" : "");
        for (char c : p.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '^') sb.append("(?:[/?&=:;]|$)");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        if (end) sb.append("$");
        try { return Pattern.compile(sb.toString()); } catch (Exception e) { return null; }
    }

    /** "/ads/" + any text + ".js", "/api/stats/ads^": a star is any text; "^" a separator or the end. */
    private static Pattern pathPattern(String p) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : p.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '^') sb.append("(?:[/?&=:;]|$)");
            else if (c == '|') sb.append("$");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        try { return Pattern.compile(sb.toString()); } catch (Exception e) { return null; }
    }

    /** The site and the sites it's under: "a.b.example.com" -> it, "b.example.com", "example.com". */
    private static List<String> withParents(String host) {
        List<String> out = new ArrayList<>();
        String h = host;
        out.add(h);
        while (true) {
            int i = h.indexOf('.');
            if (i <= 0 || h.indexOf('.', i + 1) < 0) break;
            h = h.substring(i + 1);
            out.add(h);
        }
        return out;
    }

    private static String siteOf(String host) {
        String[] p = host.split("\\.");
        return p.length <= 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    private static boolean under(String host, String[] sites) {
        for (String s : sites) if (host.equals(s) || host.endsWith("." + s)) return true;
        return false;
    }

    private static boolean appliesOn(NetRule r, String page) {
        if (r.onlyOn != null && (page == null || !under(page, r.onlyOn))) return false;
        return r.notOn == null || page == null || !under(page, r.notOn);
    }

    /** Is [host] + [rest] (path and query) an ad, when loaded by a page on [pageHost]? */
    public boolean blocks(String host, String rest, String pageHost) { return blocks(host, rest, pageHost, null); }

    /** The same, with the whole address [url] (for the rules written as regular expressions). */
    public boolean blocks(String host, String rest, String pageHost, String url) {
        host = host.toLowerCase().replaceFirst("^www\\.", "");
        String page = pageHost == null ? null : pageHost.toLowerCase().replaceFirst("^www\\.", "");
        boolean third = page == null || !siteOf(page).equals(siteOf(host));
        if (page != null && off(page, NOTHING)) return false;
        List<String> hosts = withParents(host);
        for (String h : hosts) {
            List<NetRule> rs = allow.get(h);
            if (rs != null) for (NetRule r : rs) if (appliesOn(r, page) && (r.path == null || r.path.matcher(rest).find())) return false;
        }
        for (String h : hosts) {
            List<NetRule> rs = block.get(h);
            if (rs == null) continue;
            for (NetRule r : rs) {
                if (r.thirdPartyOnly && !third) continue;
                if (!appliesOn(r, page)) continue;
                if (r.path == null || r.path.matcher(rest).find()) return true;
            }
        }
        String whole = (host + rest).toLowerCase();
        boolean hit = false;
        for (Text t : anywhere) if (t.matches(whole, third, page)) { hit = true; break; }
        if (hit) {
            for (Text t : anywhereAllow) if (t.matches(whole, third, page)) return false;   // an exception wins
            return true;
        }
        if (url != null) for (RegexRule r : regexRules) {
            if (r.thirdPartyOnly && !third) continue;
            if (r.onlyOn != null && (page == null || !under(page, r.onlyOn))) continue;
            if (r.notOn != null && page != null && under(page, r.notOn)) continue;
            if (r.re.matcher(url).find()) return true;
        }
        return false;
    }

    /**
     * The address without its tracking codes ($removeparam: "utm_source", "fbclid"…), for a page being opened. The
     * same address if there are none.
     */
    public String cleanUrl(String url) {
        if (removeParams.isEmpty()) return url;
        int q = url.indexOf('?');
        if (q < 0) return url;
        int hash = url.indexOf('#', q);
        String base = url.substring(0, q), query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash), frag = hash < 0 ? "" : url.substring(hash);
        Matcher hm = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)").matcher(base);
        if (!hm.find()) return url;
        String host = hm.group(1).toLowerCase().replaceFirst("^www\\.", "");
        List<RemoveParam> here = new ArrayList<>();
        for (RemoveParam r : removeParams) {
            if (!r.site.isEmpty() && !(host.equals(r.site) || host.endsWith("." + r.site))) continue;
            if (r.onlyOn != null && !under(host, r.onlyOn)) continue;
            if (r.notOn != null && under(host, r.notOn)) continue;
            here.add(r);
        }
        if (here.isEmpty()) return url;
        StringBuilder kept = new StringBuilder();
        boolean changed = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            boolean drop = false;
            for (RemoveParam r : here) {
                if (r.name != null ? r.name.equalsIgnoreCase(name) : r.re.matcher(pair).find()) { drop = true; break; }
            }
            if (drop) { changed = true; continue; }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        if (!changed) return url;
        return base + (kept.length() > 0 ? "?" + kept : "") + frag;
    }

    /** Advanced element rules for [host]'s pages, for AdGuard's ExtendedCss code ("selector { display: none … }"). */
    public List<String> extendedFor(String host) {
        String h = host.toLowerCase().replaceFirst("^www\\.", "");
        if (off(h, NO_HIDING) || off(h, NO_SPECIFIC)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String p : withParents(h)) { List<String> e = siteExtended.get(p); if (e != null) out.addAll(e); }
        return out;
    }

    /**
     * The style hiding ad elements on [host]'s pages: everywhere-rules and its own, minus the ones AdGuard says not to
     * apply there (its exceptions, and "except on…" rules): those simply aren't hidden, as AdGuard does.
     */
    public String hideCss(String host) {
        String h = host.toLowerCase().replaceFirst("^www\\.", "");
        StringBuilder css = new StringBuilder();
        if (off(h, NO_HIDING)) return "";
        List<String> sites = withParents(h);
        Set<String> notHere = new HashSet<>();
        for (String p : sites) { List<String> un = siteUnhide.get(p); if (un != null) notHere.addAll(un); }
        if (!off(h, NO_GENERIC)) for (String s : genericCss) if (!notHere.contains(s) && !exceptedHere(s, h)) css.append(s).append("{display:none!important}");
        if (!off(h, NO_SPECIFIC)) for (String p : sites) {
            List<String> own = siteCss.get(p);
            if (own != null) for (String s : own) if (!notHere.contains(s) && !exceptedHere(s, h)) css.append(s).append("{display:none!important}");
        }
        for (String p : withParents(h)) {
            List<String> st = siteStyles.get(p);
            if (st != null) for (String s : st) css.append(s);
        }
        return css.toString();
    }

    private boolean exceptedHere(String selector, String host) {
        List<String> ex = hideExcept.get(selector);
        if (ex == null) return false;
        for (String e : ex) if (host.equals(e) || host.endsWith("." + e)) return true;
        return false;
    }

    /** AdGuard's script rules for [host] ("//scriptlet('name', 'arg'…)" or a script), minus those switched off there. */
    public List<String> scriptsFor(String host) {
        String h = host.toLowerCase().replaceFirst("^www\\.", "");
        if (off(h, NO_SCRIPTS)) return new ArrayList<>();
        List<String> sites = withParents(h);
        Set<String> off = new HashSet<>();
        for (String p : sites) { Set<String> o = scriptsOff.get(p); if (o != null) off.addAll(o); }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> everywhere = scripts.get("");
        if (everywhere != null) for (String s : everywhere) if (!off.contains(s)) out.add(s);
        for (String p : sites) { List<String> own = scripts.get(p); if (own != null) for (String s : own) if (!off.contains(s)) out.add(s); }
        return new ArrayList<>(out);
    }

    /** A scriptlet rule's name and arguments: "//scriptlet('json-prune', 'a b')" -> [json-prune, a b]; null if not one. */
    public static List<String> scriptletParts(String rule) {
        String r = rule.trim();
        if (!r.startsWith("//scriptlet(") || !r.endsWith(")")) return null;
        String inner = r.substring("//scriptlet(".length(), r.length() - 1);
        List<String> parts = new ArrayList<>();
        int i = 0, n = inner.length();
        while (i < n) {
            char c = inner.charAt(i);
            if (c == ' ' || c == ',') { i++; continue; }
            if (c != '\'' && c != '"') return null;
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < n && inner.charAt(i) != c) {
                if (inner.charAt(i) == '\\' && i + 1 < n) { sb.append(inner.charAt(i + 1)); i += 2; } else sb.append(inner.charAt(i++));
            }
            if (i >= n) return null;                      // an unclosed quote
            parts.add(sb.toString());
            i++;
        }
        return parts.isEmpty() ? null : parts;
    }

    public String summary() {
        int b = 0, s = 0, sc = 0;
        for (List<NetRule> l : block.values()) b += l.size();
        for (List<String> l : siteCss.values()) s += l.size();
        for (List<String> l : scripts.values()) sc += l.size();
        int st = 0, ex = 0;
        for (List<String> l : siteStyles.values()) st += l.size();
        for (List<String> l : siteExtended.values()) ex += l.size();
        return b + " address rules, " + regexRules.size() + " patterns, " + anywhere.size() + " texts, " + removeParams.size() +
            " tracking codes, " + genericCss.size() + " elements everywhere, " + s + " site elements, " + st + " styles, " +
            ex + " advanced, " + sc + " scripts";
    }
}
