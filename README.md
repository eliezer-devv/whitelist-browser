# Whitelist Browser

An Android browser that only opens websites you've approved. You keep the list of approved sites in this
GitHub repository and can change it from anywhere. Every phone running the app picks up the change within
a few minutes.

**Features:**
- **Lists for different phones:** a public list for everyone, plus lists for individual phones or groups.
  Each phone registers itself with an ID, and you choose which lists it uses. Everything can be changed remotely.
- **Home page:** shows a tile, with the site's icon, for each approved site.
- **Requests:** users can ask for a site to be allowed or blocked. You're notified and approve with a one-word reply.
- **Ad blocking:** ads and trackers are blocked using AdGuard's DNS filter list, and you can switch it on or off remotely.
- **Content filters:** anything pages load from adult, gambling or malware sites is blocked, using the lists Mullvad's DNS used. On by default.
- **Self-updating:** the app installs new versions of itself from this repository.

Everything lives in this one repository. You don't need a computer or Android Studio. All the steps below
work in a phone's web browser.

---

## Contents

0. [Checklist: still to do](#checklist-still-to-do)
1. [One-time setup](#1-one-time-setup)
2. [Using the app](#2-using-the-app)
3. [Requests to allow or block a site](#3-requests-to-allow-or-block-a-site)
3b. [Phones and lists](#3b-phones-and-lists)
4. [Changing the list yourself](#4-changing-the-list-yourself)
5. [The admin page](#5-the-admin-page)
6. [Tokens: what they are and how to make one](#6-tokens-what-they-are-and-how-to-make-one)
7. [App updates](#7-app-updates)
8. [App signing](#8-app-signing)
9. [Settings reference](#9-settings-reference)
10. [Who can see what](#10-who-can-see-what)
11. [Limits and tips](#11-limits-and-tips)
12. [Troubleshooting](#12-troubleshooting)
13. [What's in this repository](#13-whats-in-this-repository)

---

## Checklist: still to do

Tick these off as you go (edit this file, and change `[ ]` to `[x]`).

**Before 2027 (important)**
- [ ] **Register with Google's Android developer verification** (Google's developer console). From 2027, apps from
      unverified developers won't install on most Android phones. It's free for apps you don't publish on the Play
      Store, but takes some days to be approved, so don't leave it late.

**Try on a real phone, once**
- [ ] **A sealed request:** ask for a site from the phone. The issue should say only *"🔒 A request from a phone"*,
      and the admin page should show the full details (and **All the details** → **History**).
- [ ] **Approving from the admin page:** the answer should appear on the phone only once the change has arrived.
- [ ] **Refresh now:** stay on a page while an embedded part (or one single photo) you asked for is approved: the
      answer should offer **Refresh now**.
- [ ] **Approval mode:** tap the **My requests** title 7 times, tick a waiting request, approve it with the PIN.
      Also try a wrong PIN: it should say how many tries are left.
- [ ] **Embedded content:** open a page with an embedded video or map from another site. Check the *"Parts of this
      page were blocked"* bar, the **Ask for it** button inside the blocked part, and **Just this one**.
- [ ] **Single photos, videos and sound:** on a page with photos off, tap a blocked photo → **Just this one**.
- [ ] **Downloads from GitHub:** a file's download button, a release file, and **Download ZIP**.
- [ ] **Home tiles:** hold one (the menu shows while still holding), and drag one to a new place.
- [ ] **My requests:** swipe right to archive, left to delete; swipe a waiting one left to cancel it.
- [ ] **Look and feel:** the dialogs, dark mode, and (Android 12 and newer) **Use my phone's colours**.
- [ ] **The public repository:** `docs` should hold only the admin page, the status page and the sealed files in
      `docs/p`. No readable lists, no `phones.json`.

**Once**
- [ ] **Old requests from before sealing** are still readable in their issues: admin page → **Settings** →
      **Privacy** → **Old readable requests** deletes them (editing isn't enough: GitHub keeps edit history). If GitHub
      doesn't allow your admin token to delete issues, the page says how to do it instead.
- [ ] **Get notified:** in the private repository, **Watch** → **All activity**, and in the GitHub app turn on push
      notifications for **Participating** (see setup step 6).
- [ ] **Install the latest version on every phone** that uses the app.
- [ ] **Make it each phone's browser:** ⋮ → **Settings** → **Phone's browser**. For a locked-down phone, also block
      the other browsers (Family Link or the phone's parental controls).

**Keep in mind**
- **Keep `keys/request-private.pem` (private repository) private,** like a password: it unlocks the requests. If it
  ever leaks, delete the `keys` folder and run **Publish lists**: it makes a new pair and the app is rebuilt with it.
- **Keep `KEYSTORE_PASSWORD` safe** (a password manager), and a copy of `signing/release.p12`: without them you can
  never publish an update the installed apps accept.

---

## 1. One-time setup

There are **two repositories**:
- **`whitelist-browser` (public):** the app, its updates, and what the phones read (the lists, and which lists
  each phone ID uses, with no names).
- **`whitelist-browser-private` (private, only you can see it):** the requests and your replies, every phone's
  name and details, and the automation that handles it all. Section 10 explains what's visible where.

Do these steps in order. On a phone, switching the browser to **desktop site** makes GitHub's settings pages
easier to use.

### Step 1: Create the public repository
1. On github.com, tap **+** → **New repository**.
2. Name it **`whitelist-browser`** and set it to **Public**.
3. Upload all the files from `whitelist-browser.zip`, keeping the folder structure. GitHub's upload page skips the
   hidden `.github` folder, so create its files by hand (**Add file** → **Create new file**, type the path, paste).

### Step 2: Turn on GitHub Pages (this publishes the lists)
1. In the public repo: **Settings** → **Pages**.
2. Under **Build and deployment** → **Source**, choose **GitHub Actions**.

### Step 3: Set the signing password
The app needs a digital signature so that only your updates can be installed on it (see section 8).

1. In the public repo: **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
2. **Name:** `KEYSTORE_PASSWORD`
3. **Secret:** a long random password of **at least 20 characters**, letters and numbers. A password manager can generate one.
4. **Also save it in your password manager.** GitHub won't show it again.

### Step 4: Create the private repository
1. **+** → **New repository**. Name it **`whitelist-browser-private`**, exactly the public one's name with
   `-private` on the end, and set it to **Private**.
2. Upload all the files from `whitelist-browser-private.zip`: **`devices.json`**, the **`docs`** folder (the starting
   list) and `README.md` with **Add file** → **Upload files**. The rest are in the hidden `.github` folder, so create
   them by hand as in step 1: `.github/scripts/handle-request.js` and the three files in `.github/workflows/`.

### Step 5: Make the tokens
Make each as described in [section 6](#6-tokens-what-they-are-and-how-to-make-one). Expiration: as long as GitHub
allows. When one expires, see section 6.

| Token | Repository access | Permissions | Where it goes |
|---|---|---|---|
| **Publishing token** | only `whitelist-browser` (public) | **Contents: Read and write** | Private repo → secret `PUBLIC_REPO_TOKEN` |
| **Requests token** | only `whitelist-browser-private` | **Issues: Read and write** | Public repo → secret `REQUESTS_TOKEN` |
| **Admin token** | only `whitelist-browser-private` | **Contents** and **Issues: Read and write** | Pasted into the admin page |

The publishing token lets the private repository copy the lists to the public one. The requests token is built
into the app, and can only create and read requests. The admin token is for you.

### Step 6: Set up the lists
In the private repo: **Actions** → **Publish lists** → **Run workflow**. The first time, it makes the **request key
pair** (see [Who can see what](#10-who-can-see-what)): it keeps the private half in the private repo's `keys` folder
and publishes the public half to the public repo as `request-key.pem`, which starts **Build APK** there by itself, so
the app is built with it. When both are green, `request-key.pem` is in the public repo.

Turn on notifications so you hear about requests: in the **GitHub** app's settings, turn on push notifications for
**Participating**, and check under github.com → **Settings** → **Notifications** that **Email** is ticked.
You'll also want to **watch** the private repo (**Watch** → **All activity**) so its requests reach you.

### Step 7: Put your username in the app
1. In the public repo, open `app/src/main/java/com/appcustom/whitelistbrowser/Config.kt` and tap the pencil icon.
2. Change `YOUR_USERNAME` to your GitHub username, keeping the quotes:
   ```kotlin
   const val GITHUB_USERNAME = "your-name-here"
   ```
3. **Commit changes**. This starts the first app build.

### Step 8: Wait for the build, then install
1. **Actions** tab (public repo): a **Build APK** run takes about 5 to 10 minutes. A green tick means it worked,
   a release appears under **Releases**, and `signing/release.p12` (your locked signing key) appears in the repo.
2. On the phone, open
   `https://github.com/YOUR_USERNAME/whitelist-browser/releases/latest/download/whitelist-browser.apk`,
   allow installing from the browser when Android asks, and tap **Install**.
3. **If Google Play Protect warns about it,** tap **More details** → **Install anyway**. If it offers to send the
   app for scanning, accept. See [Play Protect warnings](#play-protect-warnings) for why this happens.

Setup is done.

---

## 2. Using the app

```
 ←  →  ⟳  ⌂   Wikipedia                              ⋮
 12 sites allowed, list checked 2 min ago. Tap to check now.
 ┌──────────────────────────────────────────────────────┐
 │   [W]        [K]        [S]                          │
 │ Wikipedia  Khan Acad.  Scratch                       │
 │                                                      │
 │              Ask for a new site                      │
```

### Toolbar and screen
- **Home page:** the app opens on the home page. It shows a tile for each site marked **Home page** in the list,
  with the site's icon and name. Tapping a tile opens the site. Sites that are only needed in the background, like
  sign-in pages, can be left off (see section 4).
- **← → ⟳ ⌂:** back, forward, reload and home.
- **No address bar:** the top bar shows the name of the open site, so it looks like an app rather than a web browser.
  Sites are opened from the home page tiles, or from links within them. To get a new site, use **Ask for a new site**.
- **Status line:** shows how many sites are allowed and when the list was last checked. Tap it to check right now.
  - *"Offline, using saved list"* means GitHub couldn't be reached, so the last downloaded list is in use.
  - *"No list loaded"* means the app has never downloaded a list, so everything is blocked.
- **⋮ menu:**
  - **Ask for a new site** and **Ask to block** send requests (section 3). **My requests** shows them all with their answers.
  - **Clear cache** removes stored copies of pages and pictures, so sites load fresh. Nothing else changes.
  - **Clear cookies and site data** asks which to clear. Use it to sign out, or when a site misbehaves.
    - **Just this site** signs you out of the open site only. It erases what that site has saved and resets its camera,
      microphone and location answers. "This site" means the site as it appears on the list, including its subdomains,
      so on `mail.google.com` it clears `google.com`.
    - **All sites** signs you out everywhere, erases everything sites have saved and resets all those answers.
    - **On the home page or a blocked page,** there's no open site, so it clears all sites (after asking).
    - **Signing out of a site that signs in through another site** (like YouTube through Google) may need
      that other site cleared too, or **All sites**. Android doesn't let apps see every cookie a site uses,
      so **Just this site** clears everything the site can reach. If you're somehow still signed in, **All sites** always works.
  - **Check for app update**, **Check list now**, and the installed version number.
- **Green banner:** a newer app version is available. Tap it to update (section 7).
- **Blocked page:** shown when a site isn't on the list, with an **Ask to open** button (section 3).
  If the site is on the list but only some of its pages are, it says *"This page isn't on the list"*.
  If the site gets approved, the page opens by itself.

### What's blocked
- Any website not on the list, including links, pop-ups and redirects to other sites.
- Anything on the **Always blocked** list, even if it's part of an allowed site.
- Other web browsers. A link can never hand off to Chrome or another browser, because that would get around the list.

### Temporary access
You can keep something blocked but open it **for a while**: a whole site, a single page, or photos and videos.
- **Two ways to count the time:**
  - **From now:** open until a set time, e.g. 45 minutes from when you approve.
  - **Only while it's open on the phone:** e.g. 1 hour of actual time on the site, to be used within 7 days.
    Time only counts while the site is on screen.
- **Temporary access wins over blocks** while it lasts, so it works even for something on **Always blocked**.
- **On the phone:**
  - **Time left** shows in the status line, e.g. *"Temporary: 23 min left."* or *"Temporary: 40 min of use left."*
  - **A temporarily open site** gets a home page tile with a ⏱ badge showing the time left. When the time is up,
    the tile disappears by itself, even if the home page is open at the time.
  - **A warning pops up** 5 minutes before the end.
  - **When time runs out,** the page is replaced by **"Time's up"**, with **Ask to open** to ask for more.
- **Expired entries are removed from the lists** by the daily check.

### No photos, no videos, no sound
Sites or pages can be set to open **without photos**, **without videos**, **without sound**, or any mix of them. The
text, links and buttons work as usual, but pictures don't load, videos don't play, and/or sound on its own (music,
podcasts, sound files, audio players) doesn't play. Videos that are allowed keep their own sound.
- **How it tells sound from video:** by the media itself, as it starts to play: **a picture means video, no picture
  means sound**, wherever it comes from (so a music service streaming songs counts as sound, and a film counts as
  video). Only what's unmistakable is stopped before it downloads: video and sound files, video players' own servers
  (YouTube, Vimeo…), and anything labelled as video or audio. While videos are off, players stay out of sight until
  they've been judged, so no picture shows.
- **Sound made without a player** (the browser's sound system, used by games and many sites) stays silent when sound
  is off too. (A few sites send a
video's sound as a separate .m4a or .aac file, so while videos are allowed, those two formats aren't blocked.) This is off by default. It can be turned on
in three ways:
- **For a whole site:** the **No photos**, **No videos** and **No sound** switches on the site's screen on the admin page.
- **For single pages** of a site that's otherwise shown normally: admin page → **Sites** → **No photos or videos on
  some pages**, which has a box each for pages without photos, videos and sound (a page in all three has them all off).
- **One photo or video anyway:** on a page where they're off, tapping a blocked one asks for **just that one** (or the
  page, or the site). Approved ones go in the list's `mediaAllow`, also shown on that admin screen. When the one item
  is an embedded player (YouTube, Vimeo...), its video is let through too, so it plays.
- **By approving a request** (section 3).

- **On the phone:**
  - **The status line** says which: *"Photos are off on this page."*, *"Videos are off…"* or *"Photos and videos are off…"*
  - **Each blocked picture or video becomes a small placeholder,** *🖼️ Photo blocked · tap to ask* or *🎬 Video blocked · tap to ask*,
    so it's clear something is there (and *🔇 Sound blocked* for audio players). Tapping one asks for that kind,
    and offers **Just this one**, **This page** or **Whole site**.
    **⋮ → Ask for photos** (or videos, or both) does the same.
  - **Tiny images** like icons and logos are simply hidden rather than given placeholders.
- **What's covered:**
  - photos and pictures, including background pictures
  - videos and audio, including streaming video
  - embedded players like YouTube and Vimeo
- **It's applied in three layers:** pictures are switched off in the browser itself, video and audio are refused as they're requested,
  and a small script hides picture and video elements and stops any player. Very occasionally a site finds another way to show something.
- **Icons and logos drawn as graphics** (not photos) may still show.

### Ad blocking
- **What it does:** pages on allowed sites can't load anything from known ad and tracker domains, like scripts, banner images, ad frames
  and tracking pixels. Those requests get an empty answer, so ads don't appear, pages load faster and less is tracked.
- **The list:** the [AdGuard DNS filter](https://github.com/AdguardTeam/AdGuardSDNSFilter), the list behind AdGuard DNS and AdGuard Home.
  It combines AdGuard's own filters with EasyList and EasyPrivacy, and AdGuard maintains it.
  Its built-in exceptions (`@@` rules) are honoured, so domains AdGuard knows would break sites stay unblocked.
  See its [licence](https://github.com/AdguardTeam/AdGuardSDNSFilter/blob/master/LICENSE).
- **Keeping it current:** a copy is packed into the app when it's built, so blocking works from the first launch, even offline.
  Each phone downloads a fresh copy about once a week.
- **What it can't use from AdGuard:** AdGuard rules that depend on a condition, a path or a wildcard are skipped.
  This blocker works by whole domains, like AdGuard DNS does, so skipping them means not blocking, which is the safe direction.
- **What it can't do:**
  - It can't block ads a site serves from its own servers. YouTube's video ads are the main example.
  - Sometimes an empty space is left where an ad was.
- **Controls** (admin page → **Settings** → **Filters**):
  - **Block ads and trackers:** on by default, for all phones.
  - **Each phone's Ads setting** (Phones → the phone): Usual, Blocked or Allowed. It overrides the setting for all phones.
  - **Never block these (both filters):** if a site stops working properly, something it needs may be on a list. Add that domain here.
- **Check it on the phone:** **⋮ → About this phone** shows whether ad blocking is on and how many requests it has blocked since the app opened.

### Content filters: adult, gambling, malware
Three filters, each **on by default**, block anything pages load from listed sites: pictures, videos, embedded frames,
scripts. They matter most on sites that show content from elsewhere (search results, embeds, forums), since the whitelist
already keeps phones off every site you haven't allowed.

| Filter | Blocks | Lists (the same sources Mullvad's DNS used) |
|---|---|---|
| **Adult content** | Adult and shock sites | [HaGeZi NSFW](https://github.com/hagezi/dns-blocklists) and [OISD NSFW](https://oisd.nl). A site on either is blocked. |
| **Gambling** | Gambling and betting sites | HaGeZi gambling |
| **Malware and scams** | Sites known for malware, phishing and scams | HaGeZi threat intelligence (mini) |

- **The filters win over your lists.** A site on a filter's list is blocked on the phone even if a list allows it,
  and the phone says so: *"Blocked by a filter: example.com is on the gambling list"*.
- **Asking for it anyway:** the request screen repeats which list it's on (*"⚠️ example.com is on the gambling list, so
  it's blocked. You can still ask…"*), and the **Send** button becomes **Ask anyway**. For a site typed into **Ask for a
  new site**, the warning appears on the first tap of **Send**. Changing the address clears it.
- **To open a listed site anyway, you have to say so:**
  - **Requests for it are marked:** GitHub's notification and the admin page show *"⚠️ On the gambling list"*.
  - **A plain `approve` changes nothing,** and the bot explains which list it's on. Reply **`approve anyway`**
    (or e.g. `approve for 30m anyway`). On the admin page, the button says **Approve anyway** and asks you to confirm.
  - **The site is then marked as not filtered** (its row on the admin page shows **Not filtered**). You can switch that
    on or off on the site's screen: **Open even if a filter lists it**.
- **Keeping them current:** each list is packed into the app at every build (the **Build APK** log shows how many sites
  each has), and phones download fresh copies about once a week. A filter's lists are only loaded while it's on, since
  they're large.
- **Controls** (admin page):
  - **Settings** → **Filters:** a switch for each, for all phones.
  - **Phones** → the phone → **Filters:** Usual, On or Off for each, overriding the switch for that phone.
  - **Never block these (all filters):** if a site breaks because something it needs is filtered, add that address here.
- **What they can't do:** they filter by site, not by looking at pictures, so they can't catch content on a site's own
  servers, or on sites the lists don't know yet.
- **Check it on the phone:** **⋮ → About this phone** shows each filter and how much it has blocked since the app opened.

### Embedded content from other sites
Pages often show things from other sites: an embedded YouTube or Vimeo video, a map, a "Sign in with Google" box.
Pictures, scripts and styles from other sites always load. **Embedded frames** only show if they're allowed on that
page; otherwise their space shows *"Blocked: content from vimeo.com"*.
- **On the phone,** a bar appears under the top bar: *"Parts of this page were blocked (from vimeo.com)"*, with **Ask**
  and ✕. The blocked part itself also has an **Ask for it** button, which starts with just that part. ✕ hides the bar until the page loads again, and while a page has blocked parts, **⋮** → **Ask for blocked parts**
  does the same as **Ask**. It opens a short sheet with a tick box for each blocked site, and for each one **Just this
  one** (only that video, map or box; the default) or **Everything from it**, plus an optional note and the approval
  PIN if one is set.
- **The request** reads *"Embedded content on bbc.co.uk, from player.vimeo.com"*. Replying **`approve`** (or tapping
  **Approve** on the admin page) lets content from those sites show **inside bbc.co.uk's pages only**. The sites
  themselves still don't open, and the ad and content filters still apply. `approve public` does it for everyone.
  Once it's approved, reloading the page shows the blocked parts.
- **To see or change what's allowed,** admin page → **Sites** → **Embedded content allowed**. You can remove any, or
  add one yourself (a site, and what may show inside it: a whole site like `player.vimeo.com`, or one exact part like
  `youtube.com/embed/abc`). They're stored in the list's `embeds`.
- **For a site you trust completely,** turn on **Allow content embedded from other sites** on its screen instead:
  then frames from any site work on its pages. Its row shows **Embeds from anywhere**. Leaving the site is still
  blocked either way.

### What's allowed
- **Downloads.** Files are saved to the phone's **Downloads** folder, with a notification when they finish. Each keeps its real
  name (the one the site gives, or the end of its address), never a made-up "….bin".
  - **Files kept on another site** work too, when the download starts on an allowed page. GitHub, for example, keeps its
    downloads on `objects.githubusercontent.com` and `codeload.github.com`. The app checks the address first: if it's
    a file, it downloads it; if it's a web page, it's blocked as usual and never shown, so this can't be used to open
    other sites.
  - **Never from a site on the malware, adult or gambling list.**
  - **Files a page makes itself** (GitHub's download button on a file does this: the address starts with `blob:`) are
    saved by the app into **Downloads** too.
- **Nothing fails silently.** Anything stopped because it isn't on the list (a link, a redirect, a form sent to another
  site) shows the blocked page with **Ask to open**. Links that can only work inside the phone (`file:`, `content:`,
  `view-source:`) say they can't be opened here.
- **Camera and microphone,** for example for video calls. The first time a site asks, the app shows
  *"example.com wants to use your camera. Allow / Block"*. Android then asks once for the app itself.
  The answer for each site is remembered until the app is closed.
- **Location.** It works the same way: the app asks per site, then Android asks once.
- **Links for other apps,** like `tel:` (phone), `mailto:` (email), `sms:`, `geo:` (maps), `market:` (Play Store),
  WhatsApp and Zoom links. These always open in their own app, never inside this browser.
  - If several apps can open a link, Android lets the user choose, with browsers left out of the list.
  - If a page tries to open another app without a tap, the app asks first.
  - If no app can open the link, the message *"No app on this phone can open this link"* appears.

---

## 3. Requests to allow or block a site

### How the user asks
| Where | Button | Asks to |
|---|---|---|
| Blocked page | **Ask to open** | open the page they tried to visit, or its whole site |
| ⋮ menu, on any open site | **Ask to block** | block the page they're on, or its whole site |
| Home page, or ⋮ menu | **Ask for a new site** | open a site they type in |

Every request uses the same single screen:
- **Show on the home page:** a switch, on to start with, for whether it gets a tile.
- **Links through other addresses:** each address the link passes through gets its own **This page** / **Whole site**
  choice and **Home page tile** switch (off to start with).
- **For how long?** (asking to open, or for photos, videos or sound back): **Always** or **Temporary**, with **Only count
  time while the site is open** under the time wheels. The rest is as before: **Always** (the default), or **Just for a while**,
  which shows two scroll wheels, like the admin page: **hours** (0 to 24) and **minutes** (0 to 55, in steps of 5).
  Flick them up or down to choose. This is what they ask for. You decide the actual time when you answer.
- **Photos, videos and sound, separately:**
  - When asking to **open**, **Without** has a chip each for **Photos**, **Videos** and **Sound** (any of them).
  - When asking to **block**: **Completely** (the default), or **Only some of it**, then the chips for which.
  - On a page where they're off, **⋮ → Ask for photos** (or videos) asks for them back. Where both are off, the request
    screen asks which: **Photos**, **Videos** or **Both**.
- **Just this page / Whole site:** the user picks one. **Just this page** is selected by default.
  The choice only appears when there's a specific page. On a site's front page it simply asks about the whole site.
- **Why? (optional):** a note, like *"for homework"*.
- **Send.** The same request can't be sent again for 30 minutes.
- **A site typed into "Ask for a new site" is checked first:** it must be written like a website address,
  and the site must actually exist on the internet. While it checks, the box shows *"Checking scratch.mit.edu…"*.
  If the site can't be found, the box stays open with *"Couldn't find … Check the spelling, or tap Send anyway if you're sure it's right."*,
  and nothing is sent yet.
  - **Send anyway** (the Send button changes to this) sends it regardless. A real site can look missing when the phone's
    Wi-Fi hides the sites it filters, when it's a school-only site, when it's brand new, or when the address needs a
    beginning other than `www.`. Changing the address turns the button back into a normal **Send**, which checks again.
  - **Requests sent this way are marked** on GitHub and on the admin page: *"⚠️ The phone couldn't find this site when asking.
    It may be a typo."* So check the address before approving.
  - **Without internet** it can't check, so the request is saved and sent once the phone is online, as usual.

"Whole site" means the site as it appears on your list. On `mail.google.com`, for example, it's `google.com`.
For a site that isn't on the list yet, it's the address without `www.`.

### Links that pass through other addresses
Many links don't go straight to their page. They pass through other addresses first, like link shorteners (`bit.ly/…`),
*"you are leaving this site"* pages, and search or social-media redirects (`google.com/url?…`, `l.facebook.com/l.php?…`).
The whitelist stops such a link at the first address that isn't allowed, even if the destination itself would be fine.

The app sorts this out for you:
1. **It remembers the route.** From the moment a link is tapped, the app notes every address it passes through.
2. **It finds the rest of the route.** When a link is stopped, the app quietly follows the rest of the route in the background,
   without showing anything, until it reaches the real destination. It follows:
   - ordinary redirects
   - refresh tags
   - the small script-based "redirecting…" pages shorteners use
3. **"Ask to open" asks for the real destination.** The request screen shows the route, e.g.
   *"This link passes through: → bit.ly/3xYz → google.com/url"*, and the request lists every address on the way.
4. **The bot tells you which of those addresses aren't allowed yet.** `approve` allows the destination
   **plus just those pass-through addresses**, without home page tiles, so the link works end to end.
   - **Only the exact pass-through address is allowed,** so `google.com/url` doesn't open Google search, and
     `bit.ly/3xYz` doesn't open other bit.ly links.
   - **If a pass-through's address is just the site's front page** (e.g. `go.example.net/?to=…`), its `?…` part is kept,
     so the whole site isn't opened.
   - **To leave them out,** reply `approve without pass-throughs`.

A few links only redirect after running a full web page's code. The app can't follow those without opening the page,
so the request then names the address where the link was stopped.

### How you're told
Each request becomes an **issue** (GitHub's name for a to-do item) in your repository, labelled **site request**.
**It's sealed:** its title is *Request from a phone* and it says only *"🔒 A request from a phone"*, because what was
asked, by whom, and the note are locked so only GitHub's automation can read them (see
[Who can see what](#10-who-can-see-what)). A moment later a reply appears on it, *"🟢 A new request: answer it on the
admin page, or reply approve or deny"*, and **that reply is what notifies you**, by email and by push notification in
the GitHub app. **The details are on the admin page** (and under **All the details**, with every reply in full).
Replying `approve` or `deny` on the issue, or by email, still works. All open requests are in the repo's **Issues** tab.

### How you answer
**The easiest way is the admin page, with a tap.** At the top of the admin page, **Requests** lists every open request:
who asked, what for, when, and their note. Each one has three buttons:
- **Approve:** does what was asked, including any time they asked for. It then says e.g. *"Approve (30 min, as asked)"*.
- **Temporary:** shows the **hours** and **minutes** scroll wheels and a tick box, *"Only count time while the site is open"*.
  Then tap **Approve for 1 h 30 min**.
- **Deny:** shows a box for an optional reason, which is shown on their phone. Then tap **Deny**.

**Change it for everyone (public list)** is a tick box on each request, for when a change should go to every phone rather than just that one.
After a tap, the request shows *"✓ Approved. The phone will be told in a few minutes."*
The link in GitHub's notification (**Answer it on the admin page**) opens the page with that request highlighted.
**New phones** waiting for a name are listed there too, with a box to type it in.

This needs your admin token to have the **Issues** permission as well (section 6).

**Or reply on GitHub** (in the GitHub app, on github.com, or **by replying to the notification email**). The buttons above post these same replies for you.
How replies are read, so nothing happens by accident:
- **Only the first line is read,** as a command. You can write notes on the lines below.
- **When you reply by email,** only what you write above the quoted notification is read, so the options listed in
  the quote don't count.
- **A reply with words it doesn't recognise changes nothing.** The bot asks you to reply with just a command instead.
  For example, *"Yes, she needs it for the public library"* won't change the public list.
  Polite words are fine: *"Yes please"* and *"approve, thanks"* work.
- **Only a plain `no` counts as a no.** *"No problem, go ahead"* doesn't deny the request.
- **`block` only answers a request to block something.** On a request to open a site, it changes nothing and the bot explains.
- **Replying to a request that's already been answered** changes nothing, and the bot says so.
- **Approving a site that's already on the list without a tile** (e.g. one added as a pass-through) gives it a tile,
  unless you reply `approve hidden`.
- **Mobile and www. addresses count as the site itself:** asking from `m.youtube.com` or `www.youtube.com` adds `youtube.com`.

| Reply | What happens |
|---|---|
| `approve` | Does exactly what was asked: opens or blocks that page, or the whole site |
| `approve hidden` | For open requests: the same, but without a home page tile |
| `approve no media` | For open requests: open it, but without photos and videos. For block requests: only block its photos and videos (the same as `approve media only`). |
| `approve no photos` / `approve no videos` / `approve no sound` | The same, for just that one. Mix them: `approve no photos and sound`. For block requests, `approve photos only`, `videos only`, `sound only` (or e.g. `photos and sound only`). |
| `approve tile` | Give it a home page tile, even if the phone asked for none |
| `approve with media` | For a "without photos/videos" open request: open it with them |
| `approve fully` | For an "only photos/videos" block request: block it completely |
| `approve for 30m`, `approve for 1h30m`, `approve for 2h` | Open it only for that long, from now (or give that long instead of what they asked for) |
| `approve for 1h use` | Open it for 1 hour of time actually spent on it, to use within 7 days |
| `approve always` | When they asked for a while: make it permanent instead |
| `approve anyway` | For a site on the adult, gambling or malware filter's list: open it despite that. A plain `approve` changes nothing for such a site. Combines with the others, e.g. `approve for 30m anyway`. |
| `approve without pass-throughs` | For a link that passes through other addresses: allow only the destination, not the addresses on the way |
| `deny` | Nothing changes |
| `deny` + a reason, e.g. `deny too distracting in class` | Nothing changes, and the phone is told why |

**If you want to approve something different from what was asked:**
- `approve whole site` approves all of the site, even though they asked for one page.
- `approve page` approves just the page, even though they asked for the whole site.

The bot's reply lists these for each request, so you don't need to remember them.
You can also write `yes` or `ok` instead of `approve`, and `no` instead of `deny`.
Any other reply is treated as an ordinary comment. Only your own replies count.

### Approving on the spot with a PIN
When you're with the person, you can approve their request on their phone, without answering it on GitHub or the
admin page, and without signing in to the admin page on their phone.
- **Set a PIN** on the admin page: **Settings** → **Approval PIN** → **PIN for all phones**, or per phone under
  **Phones** → the phone → **Approval PIN**: **Usual** (the all-phones PIN), **Own PIN**, or **None**. Use 4 to 8
  digits (6 is best).
- **On the phone, it's hidden:** the person asks as usual, then you open **⋮** → **My requests** and **tap its title
  7 times**. That's **approval mode**: tick one or more waiting requests, tap **Approve** or **Deny**, and type the PIN
  once for all of them. **Exit** (or closing My requests) leaves approval mode.
- **Approve** gives each request exactly what was asked, including a time limit or "without photos", and the site
  opens by itself within a minute or two. **Deny** tells the person *"Denied with the approval PIN"*. You still get the
  notifications, marked *"Approved (or Denied) on the phone with the approval PIN"*.
- **It can't open anything on the adult, gambling or malware lists.** Such a request keeps waiting for you, and the
  phone says so. Only you can open those, with **Approve anyway**.
- **It only approves requests.** It can't open the admin page or change anything else.
- **Safety:**
  - **The phone never checks or keeps the PIN.** It sends it in a hidden note on each request; GitHub deletes the note
    at once, then checks the PIN.
  - **Only a scrambled version is stored,** in the private repository. Phones only learn whether a PIN is set.
  - **A wrong PIN** leaves the requests waiting for you, and the phone says how many tries are left.
  - **After 5 wrong PINs,** PIN approvals lock on that phone for 24 hours, and you're told. Unlock it early under
    **Phones** → the phone → **Unlock**.
  - **Choose a PIN nobody can guess,** and don't type it where it can be watched.

### My requests (on the phone)
**⋮** → **My requests** lists the phone's requests: waiting, answered, and any not sent yet.
- **Swipe an answered request right to archive it** (a green trail shows behind it), **or left to delete it** (a red
  trail). Let go past about a third of the way to do it; let go sooner and it springs back. Answered requests also move
  to the archive by themselves after 30 days.
- **Archived** (at the bottom) shows the archive. There, swipe right to **put one back** (green) or left to **delete
  it** (red). **Delete all** empties it.
- **A request still waiting** can be withdrawn: swipe it left (**Cancel**). It's closed on GitHub too, marked as
  cancelled on the phone, so nobody answers it for nothing. The card stays until that's done (it needs internet).
- **A request not sent yet** (no connection when it was made): swipe it left (**Don't send**).
- **Deleting only removes this phone's copy:** an answered request is still on GitHub. The phone keeps up to 100
  requests.

### How quickly changes reach the phone
A change goes through a short relay: the private repository saves it (instantly), copies it to the public repository
(**Publish lists**, about 30 to 60 seconds), and GitHub Pages publishes it (**Publish list**, another 30 to 60 seconds).
So it's live about **1 to 2 minutes** after you save or reply. Then the phone has to check:
- **After sending a request,** the phone checks every **20 seconds** for the next 10 minutes (and for a few more minutes
  once an approval arrives), so an approved site opens within moments of being published.
- **Otherwise,** it checks every few minutes while the app is open (**List settings** → **Phones check for changes
  every**; the files are tiny, so 1 or 2 minutes is fine), and straight away whenever the app is opened.
- **To check now,** tap the list button (a list with a tick) in the top bar.

### How the person who asked hears back
Every request gets an answer on the phone, whether it's approved or not.
- **An approval is shown once the change has reached the phone** (its lists show it), not before, so *"can now be
  opened"* is true when it's read. The phone keeps checking quickly meanwhile. If it hasn't arrived after 10 minutes,
  the answer is shown anyway, saying it may take a few more minutes. **Denials** and notices come straight away.
- **Most approvals apply to the open page by themselves:** a site you asked for from its blocked page opens by itself,
  and photos, videos or sound switched on or off reload the page. **An embedded part or one single photo or video**
  only shows once the page is reloaded, so if you're on that page, the answer offers **Refresh now**.
- **How it gets there:** your final reply (or the bot's, on your behalf) includes a short, plain-language answer for the phone.
  The phone checks its unanswered requests every few minutes while the app is open.
- **When answers arrive,** a pop-up shows **"Answer to your request"** with the result. Examples:
  - ✅ *"coolmathgames.com can now be opened."*
  - ✅ *"nasa.gov can now be opened. (The whole site was approved, not just the page.)"*
  - ✅ *"scratch.mit.edu can now be opened, without photos and videos."*
  - ❌ *"Not approved: too distracting in class."* (the reason is whatever you wrote after `deny`)
  - ❌ *"Not approved."* (a plain `deny`)
  - ✅ *"coolmathgames.com can be opened for 30 minutes, starting now."* / *"…for 1 hour of time spent on it."*
- **If you approved something different from what was asked,** like the whole site instead of a page, or without photos and videos,
  the answer says so.
- **If the phone still can't open it** because another of its lists blocks it, the answer says it may still be blocked, so
  they know to ask you rather than try again.
- **⋮ → My requests** lists every request from that phone, newest first:
  - 📤 not sent yet (no connection)
  - ⏳ waiting for an answer
  - ✅ approved
  - ❌ not approved, with the reason
  - ⚠️ couldn't be sent, with why
- **Closing a request on GitHub without replying** shows as *"Not approved."* if you close it as "not planned",
  otherwise *"Closed without an answer."*
- **Requests unanswered for 60 days** stop being checked and show *"No answer after 60 days."*

### What approving does to the list
| Request | Result |
|---|---|
| Open a whole site | Adds the site, or removes a page limit if it had one. Anything under it on the **Always blocked** list is removed. |
| Open just a page | Adds the page to the site's **Only these pages** (or adds the site with just that page). If the page was on **Always blocked**, it's taken off. |
| Block a whole site | Removes it from the list. If it's part of a bigger allowed site, it goes on **Always blocked** instead. |
| Block just a page | Adds the page to **Always blocked**. If the site only allowed a few pages, that page is taken off its list instead. |
| Open without photos and/or videos | Opens it as above, and adds the site or page to `noMedia` (both), `noPhotos` or `noVideos` |
| Only block photos and/or videos | Adds the site or page to `noMedia`, `noPhotos` or `noVideos`. It stays open. |
| Photos and/or videos back | Takes matching entries off. Turning just photos back on for something with both off leaves videos off (it moves to `noVideos`), and the other way round. If another of the phone's lists still turns them off, the bot says which. |

Then GitHub publishes the list, replies *"Done"* and closes the request.
The phone picks up the change within a few minutes, and if the user is on the blocked page for it, it opens by itself.
You can always adjust things yourself afterwards (section 4).

## 3b. Phones and lists

### The idea
- **The public list** (`docs/whitelist.json`) is the general list. By default, every phone uses it.
- **Other lists** (`docs/lists/<name>.json`) are for individual phones or groups, like `emma` or `year-5`.
  They're written and edited exactly like the public list.
- **Each phone uses one or more lists,** which you choose: only the public list, only a personal list, both, or any mix.
- **How lists combine:** a site opens if **any** of the phone's lists allows it, and is blocked if **any** of them blocks it.
  - A personal list can **add** sites on top of the public list.
  - It can also **take sites away** with its **Always blocked** section. For example, public allows YouTube,
    and Emma's list blocks it, so Emma's phone can't open YouTube but everyone else's can.
  - A phone that uses **only** a personal list sees nothing from the public list.

### Works offline from the first launch
A phone doesn't need internet to be set up. On first launch, the app sets up locally:
- **its phone ID,** worked out on the phone itself
- **its install date**
- **the person's name,** asked for on the first screen
- **its registration,** saved in a small **outbox** on the phone
- **starter lists:** the lists new phones start with, as they were when the app was built, so sites work straight away

Anything the phone needs to send waits in that outbox: its registration, and requests made without internet.
It survives restarts. **As soon as the phone is online, the app sends everything in the order it was created**
and fetches the latest lists. It notices the connection coming back by itself, so nobody has to do anything.
- A request made offline says *"No connection right now. Your request is saved and will be sent automatically."*
  The request records when it was asked, so you can see it was sent later.
- **⋮ → About this phone** shows how many things are waiting to be sent, if any.
- If GitHub is busy, the phone simply tries again later. Something GitHub refuses for good, like an expired key,
  is dropped so it doesn't hold up the rest.
- The phone registers as of its **install date**, even if it only came online days later.

### How phones get an ID
1. The app gives the phone an ID like `K7M4-Q2XP`. It's worked out from Android's own ID for this app on this phone,
   so **it stays the same even if the app is uninstalled and reinstalled**. It's a scrambled form, so Android's ID itself isn't shared.
2. **If requests are set up** (setup step 5), the phone registers itself, straight away if it's online, otherwise as
   soon as it is, sending its **public key** (made on the phone; the private half never leaves it). It's added to the
   private repo's `devices.json` with its name, its key and the lists for new phones, and its lists are published
   **sealed** for it (see [Who can see what](#10-who-can-see-what)). Until then, which takes a minute or two, it shows
   *"Setting up this phone…"* and opens nothing. You get a notification: *"📱 A phone registered."*, with the details
   on the admin page.
3. **Without requests,** add the phone by hand. On the phone, open **⋮ → About this phone** (it shows the ID and has a
   **Copy ID** button), then use **Add a phone by ID** on the admin page.
4. **When the ID does change:** after a **factory reset**, in a **different user profile** on the same phone, and of course on a
   **different phone**. Then it registers as a new phone, and you can give it the old one's name and lists (see below).
   It would also change if the app's signing key changed, which is one more reason never to touch it (section 8).

### Names
**The first time the app is opened, it asks "What's your name?"**, with the note *"So whoever manages this browser knows
whose phone this is."* This works without internet: the name is kept on the phone and sent with its registration once it's online.
That name is then the phone's name everywhere: in request notifications, on the admin page, and in the name of its private list.

**You can change a name at any time,** and your version wins over what they typed:
- **On the admin page, under Phones:** tap the phone and change its **Name**, then tap **Save** in the bar at the bottom.
- **By replying** `name Emma` to the phone's "New phone" notification or to any of its requests (then `approve` or `deny` as usual).

A phone registered without a name (for example if requests weren't set up when it was first opened) shows as its model and ID,
e.g. *samsung SM-A155F K7M4-Q2XP (no name)*. It's listed under **Phones without a name** at the top of the admin page, and
outlined in yellow under **Phones**, until it's given one. A private list keeps the name it was given when it was made.

**⋮ → About this phone** also shows the phone's name, the lists it uses and the app version. It's handy for checking a phone is set up right.

### When a phone stops being used (uninstalled)
Android doesn't tell an app it's being uninstalled, so this works by **check-ins** instead:

1. **Every phone checks in** when the app is used, at most every 12 hours. It updates the phone's "New phone" issue
   with the time it was last seen. Editing an issue sends no notifications.
2. **Once a day, a GitHub job (Daily phone check) looks for phones that haven't checked in** for a number of days
   (default 14, set under **Archive phones not seen for** in the admin page).
3. **Those phones are archived, and you get one notification listing them,** e.g.
   *"📦 Not seen for 14 days: Emma (K7M4-Q2XP), last seen 2026-09-03, private lists archived: emma"*.
   - **Nothing is deleted.** The phone moves to **Archived phones** in the admin page. Its **private** lists
     (used by no other phone) move to `docs/lists/archive/`.
   - **Shared lists stay where they are,** like a `year-5` list other phones use, and so does the public list.

**Getting a phone back:**
- **Not used for a while, or the app was uninstalled and reinstalled:** the ID hasn't changed, so it comes back **by itself**
  the next time the app opens, with its name and lists, and you're notified (*"📱 Emma is being used again"*).
  Until then it uses the lists for new phones.
- **Factory reset, or a replacement phone:** it has a new ID, so it registers as a new phone. The "New phone" notification lists
  archived phones, marking those of the same model, e.g. *"`same as Emma`: samsung SM-A155F, last seen 2026-09-03 ← same model"*.
  Reply **`same as Emma`**. The new phone gets Emma's name, lists and settings, and her lists come back out of the archive.
- **From the admin page:** under **Archived phones**, each phone has these options:
  - **Restore** brings it back under its old ID.
  - **Give to phone…** hands its name and lists to another phone (use this after a factory reset or for a replacement phone).
  - **Delete for good** removes it and its archived lists, after asking.

**Things to know:**
- **"Not seen" means the app wasn't opened.** A phone that's still installed but unused for longer than the setting gets archived too,
  and comes back by itself when it's used. Raise the number of days for phones that are only used now and then.
- **Check-ins need requests set up** (the `REQUESTS_TOKEN`). A phone that can't check in looks unused and would be archived
  14 days after it was added. So without requests, set the days very high, or ignore the archive.
- **Don't delete the "New phone" issues.** They hold each phone's last check-in. It's fine that they're closed.
- **Scheduled jobs can be paused by GitHub.** It pauses them in repos with no activity for 60 days. The daily check makes a
  small commit once a month to prevent that. If it ever stops, go to **Actions** → **Daily phone check** → **Enable workflow**.

### Managing it all (admin page)
- **Lists:** the **List to edit** menu at the top switches between lists. **New list** creates one, and **Delete** removes one
  (phones using it simply stop using it). Everything else on the page works on the list shown there, including bulk import.
  Under the menu it says which phones use that list.
- **Phones:** the **Phones** section has a card for every phone, showing its ID, model and date registered, plus:
  - **Name:** so you know whose phone it is (see above).
  - **List tick boxes:** which lists this phone uses.
  - **Approved requests go to:** which list changes when you approve this phone's requests.
    **Default** follows the **Approved requests** setting below. You can also choose **its own private list**
    or a specific list, like a group list.
  - **Remove:** takes the phone off. It then uses the lists for new phones.
- **Approved requests:** where `approve` puts changes, for every phone that's on **Default**:
  - **Each phone's own private list** (the default and the recommended choice): a change only affects the phone that asked.
    Every phone gets one when it registers. (A phone added by hand on the admin page gets one the first time you approve something for it.)
  - **The public list:** every phone using it gets the change.
- **Archived phones:** phones that stopped checking in, with **Restore**, **Give to phone…** and **Delete for good** (see above).
  **Archive phones not seen for** sets how many days that takes.
- **When a new phone is set up:**
  - **Tick boxes for the lists a new phone starts with,** besides its own private list (just **public** at first).
    Untick them all to start new phones with only their own, empty, list: nothing allowed until you add sites or approve requests. Unregistered phones use these lists too.
  - **The app comes with a built-in copy of exactly these lists,** for before it's first online. The copy is taken when the app is
    built, so after changing the ticks, run **Actions → Build APK** if new installs should start with the new choice.
  - **Every new phone also gets its own private list** as soon as it registers. It's named after the phone's ID
    (e.g. `k7m4-q2xp`), not the person, because list names are public. The admin page shows it as the person's name.
    It starts empty, so you can add sites to it before they ask for anything. A phone that registers again gets its
    old list back.
- **Saving:** tap **Save** in the bar at the bottom after changing anything. Phones pick up changes within a few minutes.

### Requests from phones with their own lists
The request tells you which phone asked, which lists it uses and **which list approving will change**:
> 🟢 Open the whole site: **coolmathgames.com**
> 📱 From **Emma**, which uses: public, emma
> Approving changes **emma** (Emma's private list).

**By default, approving never changes the public list.** It changes the phone's own private list, making one if needed.
Only you can decide otherwise, either once or as a setting:

| Reply | Changes |
|---|---|
| `approve` | The list named in the message: the phone's private list, unless you changed the setting in the admin page |
| `approve public` | The default public list, just this once, so every phone using it gets the change |
| `approve own year-5` | Any lists by name, several at once (`own` is the phone's own list). The admin page's **Add it to** tick boxes send this. |
| `approve personal` | The phone's private list, even if the setting says public |
| `approve emma` (any list name) | That list |
| `name Emma` | Doesn't approve anything. It names the phone, and you can then reply `approve` or `deny`. |

On a **"New phone"** notification you can reply `name Emma`, or `same as Emma` if it's an archived phone after a factory reset, or its replacement.

These combine with the other words, e.g. `approve public whole site` or `approve personal hidden`.

**Because lists combine, the bot handles two cases for you:**
- **Blocking something another of the phone's lists allows:** it goes on the target list's blocked list,
  so that phone can't open it while other phones using the other list are unaffected.
- **Opening something another of the phone's lists blocks:** blocks win, so the bot warns you which list is blocking it.

### What gets stored where
| File | What's in it |
|---|---|
| `docs/whitelist.json` | The public list |
| `docs/lists/<name>.json` | The other lists |
| `docs/lists/archive/<name>.json` | Private lists of archived phones, kept until you restore or delete them |
| `devices.json` (private repo) | The phones (ID, name, model, date registered, their lists, where their approvals go, their ad setting), archived phones, the lists for new phones, where approvals go by default (`"requestsTo": "own"` or `"public"`), the days before archiving (`"inactiveDays"`), and ad blocking (`"adblock"`, `"adblockExceptions"`) |

The private repo's `devices.json` looks like this:
```json
{
  "default": ["public"],
  "requestsTo": "own",
  "adblock": true,
  "adblockExceptions": [],
  "devices": {
    "K7M4-Q2XP": { "name": "Emma's phone", "model": "samsung SM-A155F",
                   "lists": ["public", "emma"], "registered": "2026-09-23" },
    "H8PA-7KD3": { "name": "Sam's tablet", "lists": ["year-5"], "requestsTo": "year-5" }
  }
}
```

## 4. Changing the list yourself

The list is the file **`docs/whitelist.json`** in the **private** repository (other lists are in `docs/lists/`).
There are three ways to change it:
- **The admin page** (section 5): easiest, with no special format to get right.
- **Edit the file on github.com:** open the **private** repo → `docs` → `whitelist.json` → pencil icon, then
  **Commit changes**. Its **Publish lists** workflow then copies it to the public repo for the phones.
- **Git on a computer:** only worth it if you already use git.

**The public repository has no readable lists:** each phone's lists are published there sealed (see
[Who can see what](#10-who-can-see-what)). Edit them in the private repository, or on the admin page.

### The file format
```json
{
  "refreshMinutes": 5,
  "sites": [
    { "domain": "wikipedia.org", "name": "Wikipedia", "home": true },
    { "domain": "khanacademy.org", "name": "Khan Academy", "home": true },
    { "domain": "scratch.mit.edu", "name": "Scratch", "url": "https://scratch.mit.edu/explore", "home": true },
    { "domain": "google.com", "name": "Google", "home": true, "subdomains": false },
    { "domain": "youtube.com", "name": "Octopus video", "home": true,
      "pages": ["youtube.com/watch?v=abc123"] },
    { "domain": "accounts.google.com", "home": false }
  ],
  "block": ["fr.wikipedia.org", "en.wikipedia.org/wiki/Fortnite"],
  "noMedia": ["youtube.com", "en.wikipedia.org/wiki/Shark"],
  "temporary": [
    { "id": "t1", "what": "site", "entry": "coolmathgames.com", "mode": "clock", "minutes": 45, "from": "2026-09-24T15:00:00Z" },
    { "id": "t2", "what": "media", "entry": "youtube.com", "mode": "use", "minutes": 60, "from": "2026-09-24T15:00:00Z" }
  ]
}
```

Each site in `sites` has:

| Field | Meaning | Required? |
|---|---|---|
| `domain` | The site to allow. It also allows its subdomains. | Yes |
| `name` | The name under its home page tile. If left out, the domain is used. | No |
| `home` | `true` shows a tile on the home page. `false` means allowed but no tile. | No (default `true`) |
| `url` | The page the tile opens. If left out, it's `https://` + domain. | No |
| `subdomains` | `true` also allows every subdomain (`mail.`, `maps.`, `en.` ...). `false` allows only this exact site and its `www.` version. | No (default `true`) |
| `pages` | Allow **only these pages** of the site, instead of all of it. Each also allows the pages below it. The first one is where the tile opens. | No (default: the whole site) |
| `frames` | `true`: content embedded from any site (videos, maps, sign-in boxes) works on this site's pages. Leaving the site is still blocked. Only for sites you trust. | `false` |
| `unfiltered` | `true`: the adult, gambling and malware filters don't apply to this site (set by `approve anyway`, or the site's switch on the admin page). | `false` |

A list can also have an **`embeds`** section: for a site, the sites whose embedded content may show inside its pages,
e.g. `"embeds": {"bbc.co.uk": ["player.vimeo.com"]}`. It's filled in by approving a request for blocked parts of a page.

**Common edits:**

| To... | Do this |
|---|---|
| Add a site | Add a line like `{ "domain": "scratch.mit.edu", "name": "Scratch", "home": true },` |
| Remove a site | Delete its line. |
| Hide a site from the home page | Change its `"home": true` to `"home": false`. |
| Allow only the exact site, not its subdomains | Add `"subdomains": false` to it. |
| Allow only certain pages of a site | Add `"pages": ["khanacademy.org/math", "khanacademy.org/science"]` to it. |
| Block part of an allowed site | Add it to `block`, e.g. `"block": ["maps.google.com"]`. |
| Block a single page | Add the page to `block`, e.g. `"block": ["en.wikipedia.org/wiki/Fortnite"]`. It also blocks the pages below it. |
| Open a site or page without photos and videos | Add it to `noMedia`, e.g. `"noMedia": ["youtube.com"]`. On the admin page, a site in `noMedia` shows as its card's **No photos or videos** tick box, and pages show in the **specific pages** box. |
| Use your own start page instead of the tiles | Add `"homepage": "https://www.example.org",` at the top. |

**Formatting rules:**
- Text goes in "double quotes".
- Put commas between entries, but **no comma after the last one** in a list.

If you make a mistake, nothing breaks: phones ignore a broken file and keep their last good list.
To check the file, open the `whitelist.json` link from setup step 2. If it shows an error, fix the commas and quotes.

### How matching works
| On the list | Allowed | Not allowed |
|---|---|---|
| `wikipedia.org` | `wikipedia.org`, `en.wikipedia.org`, `www.wikipedia.org` | `wikipedia.com`, `notwikipedia.org` |
| `en.wikipedia.org` | `en.wikipedia.org` only | `fr.wikipedia.org` |
| `wikipedia.org` + block `fr.wikipedia.org` | everything on Wikipedia except French | `fr.wikipedia.org` |
| `google.com` with `"subdomains": false` | `google.com`, `www.google.com` | `mail.google.com`, `maps.google.com`, `news.google.com` |
| `google.com` with `"subdomains": false`, plus `maps.google.com` | `google.com`, `www.google.com`, `maps.google.com` | `mail.google.com` and every other subdomain |
| `khanacademy.org` with `"pages": ["khanacademy.org/math"]` | `…/math`, `…/math/algebra`, `…/math/geometry` | `…/science`, `…/mathematics`, the front page |
| `youtube.com` with `"pages": ["youtube.com/watch?v=abc123"]` | that video, including at a start time (`&t=42s`) | every other video, the YouTube front page, search |

- **There are two ways to block subdomains:**
  - **Some subdomains:** put them on the `block` list. For example, block `fr.wikipedia.org` and the rest of Wikipedia stays allowed.
  - **Every subdomain except the ones you name:** set `"subdomains": false` on the site, then add any subdomains you do want as their own sites.
  - **The `www.` version always counts as the same site,** so `google.com` with `"subdomains": false` still allows `www.google.com`.
- **Allowing only certain pages (`pages`):**
  - **What an entry covers:** the page and everything below it, so `khanacademy.org/math` covers `/math/algebra`.
    It doesn't cover look-alikes, so it doesn't cover `/mathematics`.
  - **How to write entries:** copy a page's address from a browser on your own phone or computer. `https://`, `www.` and `m.` don't matter,
    so `https://m.youtube.com/watch?v=abc123` and `youtube.com/watch?v=abc123` are the same entry.
  - **Everything the page itself loads still works,** like pictures, videos and embedded players.
    Only moving to another page is checked, including on sites like YouTube that change pages without reloading.
  - **YouTube tip:** videos live at `youtube.com/watch?v=…`, not under the channel's address.
    Allowing a channel page lets someone browse the channel, but each video needs its own entry.
  - **Leave `pages` out to allow the whole site.**
- **Paths can't be allowed without a site entry.** A page rule always belongs to a site in the list. For example, you can't allow a single YouTube channel.
- **Images, videos and scripts a page loads from other domains still work.** Only the pages you visit are checked.
- **Some sites send you to another domain to sign in,** like `accounts.google.com`. Add that domain with `"home": false`.
  If a site keeps getting blocked, the blocked page shows which domain it tried to open.

### How fast changes arrive
1. GitHub publishes the change in about **1 to 2 minutes**: **Publish lists** runs in the private repo's Actions tab, then **Publish list** in the public repo's.
2. The app checks when it's opened, every `refreshMinutes` while it's open, or right away when the status line is tapped.
3. **Removing** a site blocks it even if it's open at the time. **Adding** a site opens it automatically if someone is on its blocked page.

---

## 5. The admin page

`https://YOUR_USERNAME.github.io/whitelist-browser/admin.html` is a web page for answering requests and editing the
lists. It's made for phones (it also works on a computer) and has four tabs at the bottom.

**First use:** paste your **admin token** (section 6) and tap **Connect**. Your username and the repository fill in by
themselves. Tick **Remember on this device** only on a device nobody else uses. Anyone can open the page, but it does
nothing without your token.

**Requests.** Open requests from phones, newest first, with a red count on the tab.
- **Each request** is a card showing who asked, when, what for, their note, and any warnings (like a link that passes
  through other addresses, or a typed site the phone couldn't find). **All the details** (at the bottom of the card)
  shows everything about it: the phone, when, what and where, the page it was asked from, the home tile, how long and
  how time is counted, what to leave out, addresses it passes through, and a link to it on GitHub.
- **Add it to:** tick boxes for where an approval goes: the phone's own list (or wherever its approvals usually go,
  ticked to start with) and every **public list**. Tick several to add it to all of them. If none of the ticked lists
  is one the asking phone uses, its own list is added too, so the person who asked always gets it.
- **Without:** **Photos**, **Videos** and **Sound** chips, as the phone asked; change them before approving.
- **Answering:**
  - **Approve**, which says e.g. **Approve for 30 min** when they asked for a time.
  - **Temporary** (or **Other time**): the hour and minute wheels, with *Only count time while it's open on the phone*.
  - **Deny**, with an optional reason shown on their phone.
- **Phones with no name yet** are listed below the requests, with a box to type one.
- **After answering,** the request moves to **Answered just now**, and the phone is told within a few minutes.

**Sites.** **Search sites in every list** at the top suggests sites as you type (name, address, and which lists it's
in); tap one to open it. Under it, the lists are buttons: the **public lists** first (with a globe: **Default public
list** and any list marked public), then each phone's own list, and **+** makes a new one (with a **Public list**
switch).
- **Public lists** can be ticked on any request, and given to groups of phones (like Year 5 or Staff). A list becomes
  public (or stops being) in its **List settings**. A phone's own list is never public.
- **Temporary access** shows first, in amber, with the time left and **End now**.
- **Each site is one row,** with small tags: *On home page* / *No tile*, *1 page only*, *Exact address*, *No photos*, *No videos* or *No photos or videos*, *Added automatically*.
- **Tapping a site** opens it with:
  - its **Address** and **Name on the home page**
  - switches for **Show on the home page**, **Include subdomains**, **No photos**, **No videos** and **Only some pages**
    (which then lists its pages, with a box to add more)
  - **Allow content embedded from other sites** (see [Embedded content](#embedded-content-from-other-sites)), with a warning to use it only for sites you trust
  - **Open even if a filter lists it**, for a site a content filter blocks that you've checked yourself
  - **More options** → where the tile opens
  - **Open it temporarily instead**
  - **Remove this site**
- **Add site** adds one the same way.
- **The rows at the bottom:**
  - **Open something for a while:** temporary access with the scroll wheels.
  - **Always blocked:** parts of allowed sites to keep blocked, one per line.
  - **No photos or videos on some pages:** single pages, one per line, in a box for photos and a box for videos. For a whole site, use its switches instead.
  - **Import from a spreadsheet:** paste cells or choose a `.csv`/`.xlsx` file. The columns are domain, name, home page,
    tile opens, subdomains, only these pages, no photos, no videos (an older single "no photos or videos" column also works, for both), and only the domain is needed. A header row is
    optional. **Add to the list** updates sites already on it, and **Replace the list** removes the rest. You can also
    download the list as a spreadsheet, a template, or **everything**: one file with a tab for each list and a
    **Phones** tab (names, IDs, models, lists, filters, approval PIN and more). That's also at the bottom of **Phones**.
  - **List settings:** **Public list** (on or off), the start page, how often phones check for changes, and **Delete**
    (not for the default public list).

**Phones.** One compact row per phone: its name, ID and lists, and tags only when something needs attention
(*Blocked*, *Needs a name*, *Locked PIN*, a filter switched off). **Search phones** filters by name, ID, model or list as
you type. Phones without a name are listed first.
- **Tapping a phone** lets you change its **Name**, the **Lists it uses** (tap to switch each on or off), where its
  approved requests go, and **Ads** (Usual / Blocked / Allowed). At the bottom:
  - **Block this phone:** it stays listed but can't open any site, for a lost phone or one that shouldn't be used.
    Its lists are set aside, and **Unblock this phone** gives exactly those back. Its card shows **Blocked**.
    While it's blocked, approving its requests changes nothing (the bot says it's blocked).
  - **Archive now:** does straight away what the daily check does after a phone is unused for a while. The phone moves
    to **Archived phones** with its name and settings, and its own list goes into the archive with it. **Restore**
    brings both back. If it's still being used, it's restored by itself the next day.
- **Archived phones:** **Restore**, **Give to another phone** (after a factory reset), or **Delete for good**.
- **Add a phone by its ID:** found on the phone under ⋮ → Settings → About this phone, with its first and last name.
- **Questions** (delete, block, archive, discard changes) appear as the page's own dialogs: **Escape** or **Cancel**
  backs out.

**Settings.**
- **Filters:** **Block ads and trackers**, **Block adult content**, **Block gambling**, **Block malware and scams**, and **Never block these** (for all of them).
- **New phones:** the lists they start with.
- **When you approve a request:** change the list of **Just that phone** or **Every phone**.
- **Archive after** a number of days.
- **GitHub:** the connection (**Change** the token, or **Forget** it on this device) and a link to the status page.

**Saving:** changes aren't sent straight away. A bar at the bottom says *"Changes to Emma not saved yet"*, with **Undo**
and **Save**. Saving checks every address and tidies it, so `https://www.bbc.co.uk/news` becomes `www.bbc.co.uk`.
Phones pick changes up within a few minutes. If something changed on GitHub in the meantime (say, a request was
approved), saving says so: tap **Undo** to reload, then redo the edit. Answering requests is sent straight away,
without the Save bar.

### Inside the app (hidden)
The same admin page is built into the app, so you can manage everything from the phone itself.
- **To open it,** tap the name at the top of the browser (e.g. **Home**) **7 times quickly**.
- **The first time,** paste your admin token. Tick **Remember with a PIN** and choose a PIN of 4 to 8 digits, and
  next time you'll only type the PIN. The token is stored on the phone encrypted with the PIN, never as it is, and
  **5 wrong PINs wipe it** (then just paste the token again). Untick it to paste the token every time.
- **It works like the web page:** the same tabs, buttons and saving. Downloads and outside links aren't available
  inside the app, so use a computer for those.
- **To change the PIN** (or set one, if you chose to paste the token each time): **Settings** → **GitHub** →
  **Change the PIN**. Type the new one twice. The old PIN stops working straight away.
- **To leave,** tap **Close** at the top or the phone's back button. The browser checks for your changes straight away.
- **Screenshots are blocked** on the admin screen, and it doesn't appear in the recent-apps preview.
- **"Clear cookies and site data → All sites"** in the browser also removes the saved PIN and token. Just paste the token again.
- **It's the version of the page from when the app was built.** Changing `docs/admin.html` updates the web page
  straight away, and starts a **Build APK** so the in-app one follows with the next app update.

**Not needed?** You can manage everything through request replies (section 3) and by editing the files on github.com.

---

## 6. Tokens: what they are and how to make one

A **token** is like a spare key to your GitHub account that only opens specific doors. You choose which
repository it works on, what it may do there and when it expires. You can delete it at any time without
changing your password.

This setup uses up to two:

| Token | Where it goes | Permission | Needed for |
|---|---|---|---|
| **Publishing token** | Private repo secret `PUBLIC_REPO_TOKEN` | **Contents: Read and write**, public repo only | Copying the lists to the public repo |
| **Requests token** | Public repo secret `REQUESTS_TOKEN`, built into the app | **Issues: Read and write**, private repo only | Sending requests from the app |
| **Admin token** (optional) | Pasted into the admin page | **Contents** and **Issues: Read and write**, private repo only | Editing the lists, and answering requests, on the admin page |

### How to make a token
1. On github.com, tap your profile picture → **Settings** → at the bottom of the menu, **Developer settings**.
2. **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
3. **Token name:** e.g. `Whitelist requests`. Choose an **Expiration**.
4. **Repository access:** **Only select repositories** → pick `whitelist-browser`.
5. **Permissions** → **Repository permissions:** set the permission(s) from the table to **Read and write**.
   Leave everything else as it is.
6. Tap **Generate token** and **copy it right away**. GitHub only shows it once.

### Keeping them safe and current
- **The requests token is built into the app.** Someone who digs it out could only create issues in your repo,
  meaning fake requests. They can't change the list, since only your own replies approve anything.
  If fake requests appear, delete the token, make a new one, update the secret and rebuild the app.
- **The admin token can change anything in the repository,** including the app. Treat it like a password.
- **Renewing the requests token:** make a new one, then in **Settings** → **Secrets and variables** → **Actions**,
  edit `REQUESTS_TOKEN` and paste it in. Then run **Actions** → **Build APK** → **Run workflow**.
  Phones get the fixed app through the normal update banner.
- **Renewing the admin token:** make a new one and paste it into the admin page.
- **Adding the Issues permission to an existing admin token:** Developer settings → Fine-grained tokens → open the token → **Edit** →
  **Repository permissions** → **Issues** → **Read and write** → **Update**. The token itself stays the same.
- **If a token is lost or leaked:** Developer settings → Fine-grained tokens → open it → **Delete**. It stops working immediately.

---

## 7. App updates

You only need this when you change the app itself. List changes and approved requests don't need an app update.

### Publishing a new version
A new version is built whenever you commit a change to the app code (anything in `app/`) or run
**Actions** → **Build APK** → **Run workflow**. Each build becomes a release named `v1.0.<number>`.

### How phones get it
1. The app checks every `UPDATE_CHECK_HOURS` (default 6), or right away from **⋮ → Check for app update**.
2. When there's a newer version, a **green banner** appears. Tap it.
3. **First time only:** Android asks you to allow this app to install apps. Turn it on, go back and tap the banner again.
4. Android shows **"Do you want to update this app?"**. Tap **Update**. The app restarts on the new version.

Android always asks for that final tap, so updates can't install silently.

**Keep in mind:**
- **Don't rename or delete the Build APK workflow.** Its run count is the version number, so starting over would make new versions look older.
- **The download link always gives the newest version:**
  `https://github.com/YOUR_USERNAME/whitelist-browser/releases/latest/download/whitelist-browser.apk`

---

## 8. App signing

**What it is:** every Android app is signed with a private key, like a wax seal. Android only lets an app be
updated by a version with the same seal. This stops anyone else from pushing a fake update to your phones.

**How it's set up here:**
- **The key is created once, on the first build.** It's saved as `signing/release.p12`.
- **The key is locked with your `KEYSTORE_PASSWORD`.** The file is useless without that password, which is why it must be long and random.
- **Every build unlocks the key and signs the app.** The password is stored as a GitHub secret. It's never shown and can't be read back, even by you.

**Important:**
- **Never delete `signing/release.p12` or the `KEYSTORE_PASSWORD` secret.** Without them, phones can't receive updates and would need a reinstall.
- **Keep your own copy of the password** in a password manager.
- **Never change the password secret after the first build.** The key only opens with the original password.

---

## 9. Settings reference

### `docs/whitelist.json` in the private repo (change anytime, reaches all phones in minutes)
| Field | Meaning | Default |
|---|---|---|
| `sites` | The allowed sites (see section 4 for the fields of each). | none (everything blocked) |
| `block` | Sites (`maps.google.com`) or pages (`en.wikipedia.org/wiki/Fortnite`) to always block, even inside allowed sites. | `[]` |
| `noMedia` | Sites or pages that open without photos, videos and sound. If any of a phone's lists includes a page, it applies on that phone. | `[]` |
| `noPhotos` | Sites or pages that open without photos only (the same format). | `[]` |
| `noVideos` | Sites or pages that open without videos only. | `[]` |
| `noSound` | Sites or pages where sound on its own (music, podcasts, sound files) is blocked. Allowed videos keep their sound. | `[]` |
| `mediaAllow` | Single photos or videos shown anyway where they're off: host and path, e.g. `ichef.bbci.co.uk/news/976/shark.jpg`. | `[]` |
| `temporary` | Temporary access. `what`: `site`, `page`, `media` (photos, videos and sound on), `photos`, `videos` or `sound`. `mode`: `clock` (from `from`) or `use` (time on it, within 7 days). `minutes`: how long. Easiest to add on the admin page. | none |
| `homepage` | A web address to start on instead of the home page with tiles. | the home page |
| `refreshMinutes` | How often open apps check for changes (minimum 1). | `5` |

### `app/src/main/java/com/appcustom/whitelistbrowser/Config.kt` (changing it builds a new app version)
| Setting | Meaning | Default |
|---|---|---|
| `GITHUB_USERNAME` | Your GitHub username. **Must be set.** | `"YOUR_USERNAME"` |
| `REPO_NAME` | The repository name, if you didn't use `whitelist-browser`. | `"whitelist-browser"` |
| `UPDATE_CHECK_HOURS` | How often the app looks for a new version by itself. | `6` |
| `LOCK_TASK` | `true` pins the app to the screen (see section 11). | `false` |

### Repository secrets (Settings → Secrets and variables → Actions)
| Secret | Meaning |
|---|---|
| `KEYSTORE_PASSWORD` | Unlocks the signing key. Set once, never change. |
| `REQUESTS_TOKEN` (public repo) | Lets the app send requests to the private repo. After changing it, rebuild the app. |
| `PUBLIC_REPO_TOKEN` (private repo) | Lets the private repo publish the lists to the public repo. |

---

## 10. Who can see what

| Thing | Where | Who can see it |
|---|---|---|
| **Lists and phone settings** (readable) | Private repo (`docs`, `devices.json`) | **Only you** |
| **Each phone's lists, as the phone gets them** | Public repo (`docs/p/`), **sealed** | **Only that phone** can open its file |
| **Requests, notes and names** | Private repo issues, **sealed**; details in the private `requests` folder | **Only you** (and the automation) |
| **Bot replies on requests** | Private repo issues | You; they say only what kind of update it is. The full text is in the private record |
| **The request key** | Private half: private repo `keys/`. Public half: `request-key.pem` in the public repo, and in the app | The public half can only lock, not unlock, so it's fine to be seen |
| The status page and the admin page | Public repo | Anyone can open them, but the admin page does nothing without your token |
| App code and releases | Public repo | Anyone |
| Signing key file | Public repo | Anyone, but it's locked by your password |
| Secrets and tokens | Repo settings | Nobody, not even you |

**How the sealing works:**
- **Each phone has its own key pair.** The phone makes it in Android's secure key storage the first time it runs; the
  private half never leaves the phone (not even the app can read it out). It sends the public half when it registers.
- **Lists:** when lists are published, the private repo makes one file per phone (named from its ID, without showing
  it) with that phone's settings and every list it uses, locked with that phone's key. Only that phone can open it.
  Nothing readable is published: no list names, no sites, no phone IDs.
- **Requests:** the app locks each request (and registrations, check-ins and PIN notes) with the **request key**'s
  public half. Only the private repo's automation has the private half. It unlocks each request, keeps the details in
  a private record (`requests/` in the private repo) for the admin page, and locks its answer to the phone with the
  phone's own key. Someone who digs `REQUESTS_TOKEN` out of the app sees only *"🔒 A request from a phone"*.
- **The locking:** AES-256-GCM for the message, with its key locked by RSA-OAEP: the same standard methods on the
  phone and on GitHub.

**A new phone, in its first minute or two:** until its registration is processed and its sealed file published, it
shows *"Setting up this phone…"* and opens nothing. **⋮ → Settings → About this phone → Setting up** shows where it's got to (e.g. *"Key
sent: waiting for its lists"*, or what's wrong). **A phone whose key changed** (e.g. after a reinstall, which
clears Android's key storage) notices its file won't open and registers its new key by itself.

**Why part of it is in a public repository at all:** GitHub Pages (where phones get their files) is only free on
public repositories, and the app downloads them without logging in. Sealing each phone's file means that doesn't
matter: what's published can't be read.

**Requests from before sealing** (made by an older app version) stay readable in their issues; close or delete them
if you want them gone.

---

### Play Protect warnings
Google Play Protect warns about apps that don't come from the Play Store, especially new ones it hasn't seen
before, from a developer it doesn't know. This app also needs permissions that installer-type apps have (it
installs its own updates, and can use the camera, microphone and location when a site asks). Each is legitimate
here, but together they make Play Protect cautious.
- **For now:** tap **More details** → **Install anyway**, and accept if it offers to scan the app.
- **Coming in 2027:** Google is requiring every developer of apps installed outside the Play Store to register with
  it (the Android Developer Console). It starts in Brazil, Indonesia, Singapore and Thailand from 30 September 2026,
  and expands worldwide in 2027. Without registering, installing will need a much longer process, including a
  24-hour wait. Registering doesn't change the app. When it applies where you live, register, and add the
  app's package name, `com.appcustom.whitelistbrowser`.

### The app's package name
Android identifies the app by its package name, `com.appcustom.whitelistbrowser` (in `app/build.gradle.kts`).
People never see it.
- **Don't change it.** A different package name is a different app: phones can't update across it, and every
  phone would need uninstalling and installing again.

### The top bar and Settings
- **The top bar** shows the open site's name. **Hold the name** to see it in full (the page's title and its site).
- **The list button** (a list with a tick, next to Reload) checks the list now and says whether it changed. Reload
  only reloads the page.
- **A slim line under the top bar** appears only when there's something to say: time left on something open for a
  while, photos, videos or sound being off (tap it to ask for them), or the list couldn't be checked (tap it to try
  again). Otherwise it's hidden.
- **The ⋮ menu** is a compact card: asking about this page (**Ask for blocked parts**, with how many; **Ask for
  photos/videos/sound** where they're off; **Ask to block** and the site's name), **Ask for a new site**,
  **My requests** (with how many are waiting), and **Settings**. On tiny screens, **Forward**, **Reload** and **Check
  the list** are a row of buttons at its top.
- **⋮ → Settings** holds **Appearance**, **Phone's browser**, **Cookies and site data**, **Clear cache**, **App update**
  and **About this phone**.
- **Sheets that slide up from the bottom** can be dragged by their handle: down to close, up to fill the screen.
- **First launch** asks for the person's **first and last name**. **About this phone** shows the phone's own list by
  the person's name.

### Websites' own messages, and uploading files
- **A website's own pop-ups** (an alert, *"Are you sure?"*, a box to type in, or *"Leave this page?"*) show in the
  app's look, headed *"nasa.gov says"*, so it's clear they come from the site. A site that keeps firing them has the
  rest quietly dismissed after a few.
- **Uploading files:** **Choose file** / **Upload** on a website opens the phone's file picker (one file, or several).
- **Short messages** (*"Request sent"*, *"Saved to Downloads"*…) appear as a small bar near the bottom, or inside
  a dialog if one is open.

### The phone's browser (links from other apps)
- **Web links from other apps** (WhatsApp, email, Messages, a PDF…) can open in Whitelist Browser: it's offered in
  "Open with". Each link is checked like anything typed or tapped: not on the list, and it shows the blocked page with
  **Ask to open**.
- **Make it the phone's default browser:** ⋮ → **Settings** → **Phone's browser** (Android asks *"Set as default
  browser?"*; on older phones it opens the right settings page: choose **Whitelist Browser** under **Browser**). Then
  every web link from every app opens here, through the lists.
- **For a locked-down phone,** also hide or block the other browsers (Google Family Link, the phone's own parental
  controls, or a device-management app): then this is the only way onto the web. Being the default alone doesn't stop
  someone opening another browser directly.
- **Sign-ins in other apps** that go through a website (and then back to the app) need that website on the list.

### Home page tiles
- **Tap a tile** to open the site.
- **Hold a tile and let go** for a menu: **Open its home page**, or **Open where you left off** (the last page opened on
  that site, by name).
- **Hold a tile and drag it** to move it. The others make room, and the new order is kept on that phone (each person
  can arrange their own). Sites added later go at the end.

### The app's look
The app, its dialogs, the home page, the blocked page and the admin page share one look: a warm off-white background,
the app's deep green, rounded corners, and two fonts, **Figtree** for text and **Bricolage Grotesque** for headings.
- **Longer screens** (asking for a site, My requests, About this phone) slide up from the bottom. Short questions
  (camera access, opening another app, clear cookies, answers) are small cards in the middle.
- **Light or dark:** the app follows the phone's own setting, and switches when the phone does. Anyone can change it
  on the phone: **⋮** → **Settings** → **Appearance** → **Phone's setting**, **Light** or **Dark** (remembered on that
  phone). On Android 12 and newer, **Use my phone's colours** swaps the app's greens for the phone's own colours
  (from its wallpaper), in light and dark; it's off unless chosen. It covers
  the dialogs, menus, home page, blocked page and the in-app admin. The admin page on the web follows the setting of
  the device it's opened on.
- **The fonts** are free (SIL Open Font License), and each **Build APK** downloads them from Google's font collection
  and packs them into the app (the log shows **Download fonts**). If a download fails, the build still works and the
  app uses the phone's own font. On Android 7 phones the fonts show in their default style.

### Small screens
The app, its dialogs, the home page, the blocked page and the admin page all work on small phones, down to about
2.8-inch screens (240 × 320 on Android's size scale).
- **On tiny screens** (under 300dp wide, e.g. 2.8-inch phones), **Forward** and **Reload** move into the **⋮** menu,
  so the top bar has room for the site's name. Other phones keep all the buttons.
- **Dialogs scroll** when they don't fit, and their buttons (**Send**, **Cancel**, **OK**) always stay on screen, even with
  the keyboard open. On small screens the dialog's title scrolls with its content, to leave room.
- **The home page** fits three tiles per row, and the admin page's tabs, wheels and buttons shrink to fit.

## 11. Limits and tips

- **The app only restricts itself.** Anyone can still open Chrome or another browser. On a child's phone, use
  **Google Family Link** to block other browsers and stop new apps being installed.
- **Pin the app to the screen:** set `LOCK_TASK = true` in `Config.kt`. Then in Android settings (Security → App pinning),
  turn on **Ask for PIN before unpinning**. While it's pinned, links for other apps can't open.
- **Other apps are outside the list.** Links for other apps open those apps, and what happens inside them isn't
  controlled here. For example, an email app might open a web link in Chrome. Family Link controls which apps exist on the phone.
- **Downloads can include app installers (APKs).** Family Link's install blocking stops them from being installed.
- **Home page icons come from Google's public icon service.** It sees which site names are on the home page, but nothing else.
  If an icon can't be found, the tile shows the site's first letter instead.
- **Don't allow `github.com` or `github.io`** unless you need them, so the phone can't browse your repository.

---

## 12. Troubleshooting

| Problem | Fix |
|---|---|
| Build fails: *"Add a repository secret named KEYSTORE_PASSWORD"* | Do setup step 3 (20+ characters), then Actions → Build APK → Run workflow. |
| Build fails at *Build signed APK* with a keystore or password error | The password secret doesn't match the key. Set `KEYSTORE_PASSWORD` back to the original. |
| Play Protect warns or blocks the install | Tap **More details** → **Install anyway** (see [Play Protect warnings](#play-protect-warnings)). |
| **Publish list** fails (public repo) | Settings → Pages → Source must be **GitHub Actions** (setup step 2). |
| **Publish lists** fails (private repo) | Check the `PUBLIC_REPO_TOKEN` secret there: it needs Contents: Read and write on the public repo, and not to have expired (setup step 5). |
| Any other build failure | Open the failed run, copy the red error text and ask for help with it. |
| A phone isn't in the **Phones** section | Requests must be set up for phones to register themselves, so check `REQUESTS_TOKEN`. Otherwise add it by ID (⋮ → About this phone). |
| A phone doesn't get a list's sites | Check its lists under **Phones** (tap the phone) and that you tapped **Save**. On the phone, **⋮ → About this phone** shows the lists it's using. |
| An allowed site loads but looks broken, like missing videos, maps or buttons | Two common causes. **Embedded content** (videos, maps, sign-in boxes) from sites that aren't on your lists is blocked: to allow it on this site, turn on **Allow content embedded from other sites** on the site's screen (see [Embedded content](#embedded-content-from-other-sites)). Or a **filter** may be stopping something it needs: try turning **Ads** (or a content filter) off for that phone to confirm, then add the domain to **Never block these** and turn it back on. |
| A temporary site closed early, or stayed open too long | **From now** runs on the clock. **Only while it's open on the phone** counts on-screen time, in steps of 15 seconds, within 7 days. Check which you chose under **Temporary access**. |
| Pictures or videos are missing on a page | Photos and/or videos are off there (the status line says which). Take it off in the admin page, or ask from the phone with **⋮ → Ask for photos and videos**. |
| Something still shows on a "no photos or videos" page | A few sites draw pictures in unusual ways. Block the site completely if it matters. |
| Ads still show on a site | The ads come from the site's own servers (like YouTube video ads), which a domain list can't block. |
| A site allowed in a phone's list still won't open | Another of the phone's lists blocks it, and blocks win. Check the **Always blocked** section of each of its lists. |
| A request says approving changes the public list | Either the admin page's **Approved requests** setting is on **public**, or that phone's own setting is. Reply `approve personal` to use its private list this time. |
| You weren't told about a phone that's gone | Archiving happens after the set number of days without a check-in, and only if requests are set up. Check **Actions** → **Daily phone check** is enabled and running. |
| A phone in use was archived | It wasn't opened within the set number of days (or can't check in). It returns by itself when opened. Raise **Archive phones not seen for** if that's common. |
| The same phone appears twice | It was factory reset (or used in another user profile), which gives it a new ID. Reply `same as <name>` on its "New phone" notification, or use **Give to phone…**, then remove the old entry. A normal reinstall keeps the same ID. |
| App says *"No list loaded"* | Check the `whitelist.json` link from setup step 2, and that `GITHUB_USERNAME` is right. |
| List changes don't show up | Check that **Publish lists** (private repo) and **Publish list** (public repo) ran green, then tap the status line. If you edited a file by hand, check it for JSON mistakes, and make sure you edited it in the private repo. |
| A subdomain is blocked, like `mail.google.com` | The site has **Include subdomains** turned off, or the subdomain is on the **Always blocked** list. Add the subdomain as its own site, or turn the option back on. |
| A page on an allowed site is blocked | The site has **Only these pages** filled in. Add the page there, or empty the box to allow the whole site. |
| A YouTube video won't open although its channel is allowed | Videos have their own address (`youtube.com/watch?v=…`). Add each video, or allow all of YouTube. |
| A link from an allowed page is stopped, though its destination is allowed | It passes through another address first (a shortener or redirect). Tap **Ask to open**. The request lists the pass-throughs, and `approve` allows them. |
| A listed site is still blocked | It probably sends you to another domain, like a sign-in page. The blocked page names it. Add it with `"home": false`. |
| No request buttons in the app | `REQUESTS_TOKEN` was missing when the app was built. Add it (setup step 5), then run Build APK and update. |
| *"The request key has expired"* | Renew the requests token (section 6). |
| Requests arrive but you get no notification | Check GitHub notification settings for **Participating**, and that you're watching the private repo (setup step 6). They still show in the private repo's **Issues** tab. |
| Someone didn't hear back about a request | Answers arrive while the app is open and online, within a few minutes of your reply. **⋮ → My requests** shows the status. Make sure you replied `approve` or `deny` (a plain comment isn't an answer). |
| A request made offline hasn't arrived | It's sent when the phone is next online with the app open. **⋮ → About this phone** shows whether anything is still waiting and why. |
| A new phone only has the sites from when the app was built | Those are the starter lists, used until it first goes online. It then fetches its real lists. |
| The bot says *"Could not save for 5 minutes"* | Very many changes arrived at once and this one kept losing the race. Nothing was changed. Reply `approve` again. |
| A phone says *"GitHub is busy"* | GitHub limits how many requests one account can create per minute, which lots of phones at once can reach. Wait a few minutes and send it again. Phones registering themselves retry by themselves. |
| You replied `approve` but nothing happened | The reply must start with the word, and must come from your own account. Check the **Site requests** run in Actions. |
| Camera, mic or location doesn't work on a site | The site was blocked earlier in this session, or Android permission was refused. Reopen the app, or allow it in Android Settings → Apps → Whitelist Browser → Permissions. |
| A phone, email or app link does nothing | No app on the phone can open it. Install the app it's meant for. |
| *"Couldn't check for updates: Set GITHUB_USERNAME"* | Set your username in `Config.kt`. |
| *"Update refused: it's signed with a different key"* | The installed app was signed with a different key, which only happens if the signing key was recreated (section 8). Uninstall it and install from the download link. |
| Admin page: *"To answer requests here, your admin token needs one more permission"* | Add **Issues: Read and write** to the admin token (section 6). Or reply on GitHub instead. |
| Admin page: *"GitHub rejected the token"* | The admin token expired or was deleted. Make a new one (section 6). |
| Admin page: *"The list changed on GitHub since you loaded it"* | Someone edited it meanwhile, or a request was approved. Tap **Discard changes** and redo your edit. |

---

## 13. What's in the two repositories

**`whitelist-browser` (public)**
```
docs/                          Published by GitHub Pages
  whitelist.json               Copy of the public list (published from the private repo: don't edit here)
  lists/                       Copies of the other lists (e.g. emma.json)
  p/<code>.json                One per phone: its settings and lists, sealed so only that phone can read them
  .source                      Which private version the copies came from
  admin.html                   The admin page
  index.html                   Status page: current list and app download link
  sites-template.csv           Spreadsheet template for bulk import
app/                           The Android app
  src/main/java/.../Config.kt          Your settings
  src/main/java/.../PrivateRepo.kt     The private repository's name (the public one's + "-private")
  src/main/java/.../MaxHeightScrollView.kt  Keeps dialogs' buttons on screen on small phones
  src/main/java/.../Tiles.kt           Home tiles' order and "where you left off" (kept on the phone)
  src/main/java/.../Ui.kt              The app's look: colours, fonts, dialogs, buttons, switches
  src/main/res/drawable/ic_d_*.xml     The dialogs' icons
  src/main/java/.../MainActivity.kt    Browser screen, blocking, menu, requests, permissions
  src/main/java/.../AdminActivity.kt   The admin page inside the app (7 taps on the name at the top)
  src/main/java/.../Whitelist.kt       Downloading and combining the phone's lists, checking addresses
  src/main/java/.../Device.kt          The phone's ID and the name typed on it
  src/main/java/.../HomePage.kt        Serves the home page with tiles
  src/main/java/.../Requests.kt        Sends requests, registration and check-ins to the private repo
  src/main/java/.../SiteCheck.kt       Checks a typed site exists before asking for it
  src/main/java/.../ExternalLinks.kt   Sends tel:, mailto:, app links etc. to their own app
  src/main/java/.../AdBlock.kt         Ad and tracker blocking
  src/main/java/.../MediaBlock.kt      "No photos" and "no videos" modes
  src/main/java/.../Passthrough.kt     Finds where a stopped link really leads
  src/main/java/.../Outbox.kt          Keeps registration and requests on the phone until they can be sent
  src/main/java/.../MyRequests.kt      This phone's requests and the answers to them
  src/main/java/.../TempTime.kt        Time spent on "time on the site" temporary access
  src/main/java/.../Updater.kt         Finds, downloads and installs app updates
  src/main/java/.../InstallReceiver.kt Shows Android's update confirmation
  src/main/assets/home.html            The home page
  src/main/assets/blocked.html         The "not on the list" page
signing/release.p12            Signing key, locked by your password (created on the first build)
.github/workflows/android.yml  Builds, signs and publishes the app
.github/workflows/pages.yml    Publishes docs/ to GitHub Pages
```

**`whitelist-browser-private` (private)**
```
docs/whitelist.json            The public list: the real one (edit here, or on the admin page)
docs/lists/                    The other lists; lists/archive/ keeps archived phones' lists
devices.json                   Every phone: name, model, dates, lists, settings; archived phones
.github/workflows/requests.yml Handles requests, new phones and your replies
.github/workflows/phones.yml   Daily phone check: archives unused phones, restores returning ones
.github/workflows/sync.yml     "Publish lists": copies the public parts to the public repo
.github/last-check             Touched monthly by the daily check so GitHub keeps it running
.github/scripts/handle-request.js    The logic behind all three
```
