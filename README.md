# Whitelist Browser

An Android browser that only opens websites you've approved. You keep the lists of approved sites in a private
GitHub repository and change them on the admin page, from anywhere. Every phone running the app picks up the change
within a few minutes.

**Features:**
- **Lists for different phones:** a public list for everyone, plus lists for individual phones or groups.
  Each phone registers itself with an ID, and you choose which lists it uses. Everything can be changed remotely.
- **Home page:** shows a tile, with the site's icon, for each approved site.
- **Requests:** users can ask for a site to be allowed or blocked, or find one by what it's about. You're notified
  (on your phone, or by email) and answer with a tap on the admin page. They're told the answer, even with the app closed.
- **The admin page:** answer requests and manage every list and phone, on the web or inside the app. You sign in with
  your **email and a password** (never a GitHub token), and can add **helper admins** for some phones only.
- **Ad blocking:** ads, trackers and annoyances are blocked with AdGuard's own lists, switchable remotely.
- **Content filters:** anything pages load from adult, gambling or malware sites is blocked. On by default.
- **Translating pages** on the phone itself, offered when a page isn't in the phone's language.
- **Messages to you:** users can send you a message (with the app's log, if they choose) from the app.
- **Self-updating:** the app installs new versions of itself from this repository.

Everything lives in two GitHub repositories (one public, one private). You don't need a computer or Android Studio.
All the steps below work in a phone's web browser.

---

## Contents

- **0.** [Checklist: still to do](#checklist-still-to-do)
- **1.** [One-time setup](#1-one-time-setup)
- **2.** [Using the app](#2-using-the-app)
- **3.** [Requests to allow or block a site](#3-requests-to-allow-or-block-a-site)
- **3b.** [Phones and lists](#3b-phones-and-lists)
- **4.** [Changing the list yourself](#4-changing-the-list-yourself)
- **5.** [The admin page](#5-the-admin-page)
- **6.** [Tokens: what they are and how to make one](#6-tokens-what-they-are-and-how-to-make-one)
- **7.** [App updates](#7-app-updates)
- **8.** [App signing](#8-app-signing)
- **9.** [Settings reference](#9-settings-reference)
- **10.** [Who can see what](#10-who-can-see-what)
- **10b.** [More about the app](#10b-more-about-the-app)
- **11.** [Limits and tips](#11-limits-and-tips)
- **12.** [Troubleshooting](#12-troubleshooting)
- **13.** [What's in the two repositories](#13-whats-in-the-two-repositories)

---

## Checklist: still to do

Tick these off as you go (edit this file, and change `[ ]` to `[x]`).

**Before 2027 (important)**
- [ ] **Register with Google's Android developer verification** (Google's developer console). From 2027, apps from
      unverified developers need a much longer install process (with a 24-hour wait) on most Android phones. It's free for apps you don't publish on the Play
      Store, but takes some days to be approved, so don't leave it late.

**Try on a real phone, once**
- [ ] **Admin sign-in:** open the admin page on your phone, sign in (email, password, then **Yes, it's me** in the
      email), choose a PIN, close it and open it again: it should only ask for the PIN.
- [ ] **The admin inside the app:** tap the name at the top 7 times, sign in the same way, and choose a PIN.
- [ ] **A helper admin:** Settings → Admins → **Add an admin** for one phone; they confirm by email and see only that phone.
- [ ] **A sealed request:** ask for a site from the phone. The issue should say only *"🔒 A request from a phone"*,
      and the admin page should show the full details (and **All the details** → **History**).
- [ ] **Approving from the admin page:** the answer should appear on the phone only once the change has arrived, and
      as a notification if the app is closed.
- [ ] **Finding a site:** **Ask for a new site** → type words (e.g. "maths practice") → **Find sites** → **Show more results**.
- [ ] **Translating:** open a page in another language: the bar under the toolbar offers **Translate**.
- [ ] **Message admin:** ⋮ → Settings → About this phone → **Message admin**, with the log attached.
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
      `docs/p`, and `a/` only encrypted files. No readable lists, no `phones.json`.

**Once**
- [ ] **Get notified on your phone:** admin page → **Phones** → your phone → **Admin phone**. Then GitHub's emails
      can go (Settings → Notifications → **Turn off GitHub emails**).
- [ ] **Delete the old admin token,** if you made one before email sign-in: github.com → Settings → Developer settings
      → Fine-grained tokens → open it → **Delete**. Nothing uses it any more.
- [ ] **Install the latest version on every phone** that uses the app.
- [ ] **Make it each phone's browser:** ⋮ → **Settings** → **Phone's browser**. For a locked-down phone, also block
      the other browsers (Family Link or the phone's parental controls).

**Keep in mind**
- **Keep `keys/request-private.pem` (private repository) private,** like a password: it unlocks the requests. If it
  ever leaks, delete the `keys` folder and run **Publish lists**: it makes a new pair and the app is rebuilt with it.
- **Keep `KEYSTORE_PASSWORD` safe** (a password manager), and a copy of `signing/release.p12`: without them you can
  never publish an update the installed apps accept.
- **Keep the Gmail account that sends the admin emails** (and its 2-Step Verification) working: sign-in on new
  devices, invitations and forgotten passwords all need it.

---

## 1. One-time setup

There are **two repositories**:
- **`whitelist-browser` (public):** the app, its updates, the admin and status pages, and each phone's lists,
  sealed so only that phone can read them (nothing readable).
- **`whitelist-browser-private` (private, only you can see it):** the lists, the requests, every phone's name and
  details, the admin accounts, and the automation that handles it all. Section 10 explains what's visible where.

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
   them by hand as in step 1: the two files in `.github/scripts/` (`handle-request.js`, `admin.js`) and the five in
   `.github/workflows/` (`requests.yml`, `phones.yml`, `sync.yml`, `admin.yml`, `admin-setup.yml`).

### Step 5: Make the tokens
Make each as described in [section 6](#6-tokens-what-they-are-and-how-to-make-one). Expiration: as long as GitHub
allows. When one expires, see section 6.

| Token | Repository access | Permissions | Where it goes |
|---|---|---|---|
| **Publishing token** | only `whitelist-browser` (public) | **Contents: Read and write** | Private repo → secret `PUBLIC_REPO_TOKEN` |
| **Requests token** | only `whitelist-browser-private` | **Issues: Read and write** | Public repo → secret `REQUESTS_TOKEN` |

The publishing token lets the private repository copy the lists to the public one. The requests token is built
into the app (and the admin page), and can only create and read issues: requests and the admin page's commands are
sealed, and the daily phone check names no one. You never need a token yourself: the admin page signs you in with your email and a password (Step 9).

### Step 6: Set up the lists
In the private repo: **Actions** → **Publish lists** → **Run workflow**. The first time, it makes the **request key
pair** (see [Who can see what](#10-who-can-see-what)): it keeps the private half in the private repo's `keys` folder
and publishes the public half to the public repo as `request-key.pem`, which starts **Build APK** there by itself, so
the app is built with it. When both are green, `request-key.pem` is in the public repo.

Until your own phone is an **admin phone** (see [Phone notifications for you](#phone-notifications-for-you-admin-phones)),
requests reach you as GitHub notifications: in the **GitHub** app's settings, turn on push notifications for
**Participating**, and **watch** the private repo (**Watch** → **All activity**).

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

### Step 9: Sign-in for the admin page (email and password)
The admin page never needs a GitHub token: you sign in with your email and a password. GitHub's automation checks
everything, and sends the emails (invitations, "is this you signing in?", forgotten passwords) from a Gmail account.
1. **Gmail's app password** (in the Gmail account that will send the emails):
   1. [myaccount.google.com](https://myaccount.google.com) → **Security** → turn on **2-Step Verification** (if it isn't).
   2. [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) → name it `Whitelist Browser` →
      **Create**. Copy the 16-letter password it shows (Google shows it once).
2. **Two secrets in the private repo** (`whitelist-browser-private`, not the public one): **Settings** → **Secrets and
   variables** → **Actions** → **New repository secret**:
   - `MAIL_USER` = the full address of **that same Gmail account** (the one the app password was made in). It's the
     sender; it can also be your own sign-in email.
   - `MAIL_APP_PASSWORD` = the 16-letter app password (spaces don't matter). Not the account's normal password.

   Nothing else is done with them: GitHub's automation reads them when it sends an email.
3. **Your account:** in the private repo, **Actions** → **Set up admin sign-in** → **Run workflow**. Type the email
   you'll sign in with (any email, not necessarily the Gmail above) and your name. You get an email: tap
   **Confirm and choose a password**, and choose one (at least 8 characters, with a letter and a number).
   (If the email can't be sent, the run's summary shows the link instead.)

That's you signed in on that device. Anywhere else, sign in with your email and password, and tap **Yes, it's me** in
the email that comes (once per device; after that, that device stays signed in, and a PIN can lock it). **Run Set up
admin sign-in again** any time to change your email, or if you can't sign in at all: it emails you a link to choose a
new password. If an email can't be sent, that run's summary says why (and shows the link instead).

Setup is done.

---

## 2. Using the app

```
 ←  →  ⟳  ⌂   Wikipedia                         ✓≡  ⋮
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
- **The list button** (a list with a tick, ✓≡) checks the lists now.
- **A slim line under the top bar** only appears when there's something to say (time left, photos/videos/sound off,
  or a problem):
  - *"Offline: using the saved list. Tap to try again."* means GitHub couldn't be reached, so the last downloaded list is in use.
  - *"No list loaded yet. Tap to try again."* means the app has never downloaded a list, so everything is blocked.
- **⋮ menu:** asking about the open page (**Blocked parts**, **Ask for photos/videos/sound** where they're off, **Ask to
  block**), **Desktop site**, **Translate page**, **Ask for a new site**, **My requests** (with how many are waiting) and
  **Settings** (see [The top bar and Settings](#the-top-bar-and-settings)).
  - **Settings → Clear cache** removes stored copies of pages and pictures **for every site**, so sites load fresh.
    Nothing else changes: logins are in **Cookies and site data**.
  - **Settings → Cookies and site data** asks which to clear. Use it to sign out, or when a site misbehaves.
    - **Just this site** signs you out of the open site only. It erases what that site has saved and resets its camera,
      microphone and location answers. "This site" means the site as it appears on the list, including its subdomains,
      so on `mail.google.com` it clears `google.com`.
    - **All sites** signs you out everywhere, erases everything sites have saved and resets all those answers (and
      signs out the admin inside the app, on that phone).
    - **On the home page or a blocked page,** there's no open site, so it clears all sites (after asking).
    - **Signing out of a site that signs in through another site** (like YouTube through Google) may need
      that other site cleared too, or **All sites**.
  - **Settings → About this phone:** the phone's name, ID, lists and version, **Message admin**, and the technical details.
- **Text and links** can be selected, copied and shared like in any app. **Hold a link** (or a picture) for **Open**,
  **Copy link**, **Copy link text** and **Share link**.
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
- **The time comes from GitHub, not the phone's clock,** so changing the phone's clock doesn't add time (see
  [Timers](#timers)). A "time on the site" grant given before the app was reinstalled counts as clock time instead
  (reinstalling forgets the time already used).

### Home page folders, settings and My activity
- **Folders:** drag one home page tile onto another to make a folder; tap it to open, tap its name to rename it, hold a
  tile to take it out. Kept on the phone.
- **⋮ → Settings** has five rows: **Appearance**; **This phone** (photos, videos and sound default, Search, My
  activity, Phone's browser); **Cookies and cache**; **App update**; **About this phone** (**Show filters** shows the
  filter details; **Share log** on admin phones).
- **Notifications:** the app asks once, when it's set up. No phone notification while the app is open (the answer
  shows in the app instead).
- **My activity:** time on each site, today or the last 7 days. Only on that phone: never sent anywhere, not to the
  admin page. Kept 30 days.
- **Asking for one photo or video:** "Just this video/photo", "This page" or "Whole site"; a video opens with its
  sound. When a video plays muted because sound is blocked, a note offers **Ask for sound**.
- **Reinstalling** gives back exactly the same settings (the phone's ID stays the same). Daily
  allowances count as used up on the day of a reinstall (the time already used went with the old install).

### Timers
Changes that switch on at set times, made on the admin page (**Timers** tab, or a phone's screen → **Timers**). As
many as you like, each with a name and an on/off switch (off = paused, not deleted).
- **For:** groups (every phone in them, now or later) and phones.
- **Three kinds:**
  - **On a schedule:** days and hours every week, e.g. Sun–Thu 16:00–18:00. 21:00 to 07:00 runs into the next morning.
  - **One time only:** from now (or a set day and time) for as long as you choose, then it's over.
  - **A daily allowance:** so much time a day on some sites or whole lists (counted only while one is on screen),
    starting again at midnight. When it's used up: **blocked until tomorrow**, or **only videos and sound blocked**.
    The phone shows *"YouTube time: 32 min left today"*, a note at 5 minutes left, then the blocked page with
    **Ask for more time**.
- **During it** (schedule and one time), each **No change** unless you pick: **Sites** (*Only some lists*, *Also these
  lists*, or *Nothing opens*), **Photos / Videos / Sound** (Open or Blocked on every site), **Search** (On / Off),
  **Asking for sites** (Can't ask), **Temporary access** (Paused).
- **Timers that overlap all apply, and the strictest wins:** an allowance that's used up blocks its sites even while
  another timer opens them; Blocked beats Open.
- **Time zones:** each timer keeps the time zone it was made in (changeable on its screen), wherever the phone is.
- **On the phone:** the home page shows only what's open, with *"Homework time until 18:00 · Ask for time"* on top;
  *Nothing opens* shows a full screen instead. 5 minutes before a timer starts, a note says so. A page that's no longer
  allowed shows the blocked page saying which timer, and until when. **Ask for time** sends a request
  (*"30 minutes off Homework time"*, on the usual wheels), answered on the admin page or with the approval PIN.
- **The clock:** every answer from GitHub carries the time. The phone keeps GitHub's time and how far its own clock is
  off, across restarts, and never goes back past the last time it was sure of. Changing the clock by hand doesn't
  help: the app notices (Android tells it), and the admin page's phone screen says *"Its clock was changed by hand"*
  or *"Automatic date and time is off"*. Until the phone hears from GitHub after a restart, timers use whichever
  reading is stricter.

### Text search
Off for every phone to start with (no search box until it's on). A phone asks for it with ⋮ → **Ask for search**,
choosing **All results** or **Only approved results**, and why; it's answered like any request, on the admin page (the
admin can change that choice) or with an approval PIN in **My requests**.
It can also be turned on or off on the admin page → **Phones** → the phone → **Text search** (by those who manage it).
- **Changing it from the phone:** ⋮ → **Settings** → **Search** shows what it's set to, and **Ask to change it**:
  **All results**, **Only approved results** or **Turn off search**, sent as a request like any other.
- **On the phone:** a **Search** box at the top of the home page. Results come from DuckDuckGo (Safe Search strict),
  shown on a page the app makes itself: **words only** (no pictures, videos, sound or ads), each result a page (several
  from one site is fine) with its site's icon, where it is (`site › path`), its title, a snippet, and **Opens on this
  phone** or **Ask to open this page**. **More results** at the bottom.
- **Left out altogether:** results on the adult, gambling or malware lists (unless approved anyway) and on the phone's
  always-blocked list. With **Only show results already approved**, only pages the phone can open.
- **Opening a result** follows the phone's lists as usual; one that isn't allowed shows the blocked page, with **Ask**.
- If DuckDuckGo asks whether a person is searching, **Answer the check** shows its check, then searches again.
- `devices.json`: `"search": true`, `"searchApprovedOnly": true`.

### No photos, no videos, no sound
Sites or pages can be set to open **without photos**, **without videos**, **without sound**, or any mix of them. The
text, links and buttons work as usual, but pictures don't load, videos don't play, and/or sound on its own (music,
podcasts, sound files, audio players) doesn't play (see below for how videos are treated).
- **Picture and sound are separate:**
  - **No videos** = no moving pictures. A video's picture is hidden (*"🎬 Picture hidden · sound only"*), and its
    sound still plays if sound is allowed: on YouTube, songs play as sound only.
  - **No sound** = no sound at all: music, podcasts and sound files are stopped, and videos play **muted** (they stay
    muted even if the page tries to unmute them).
  - **Both off:** videos are stopped (*"Video blocked"*), and so is all sound.
  - **Players embedded from other sites** (e.g. a YouTube video inside a news page) can't have their picture hidden,
    so they're blocked whenever videos are off.
- **How it tells sound from video:** by the media itself, as it starts to play: **a picture means video, no picture
  means sound**, wherever it comes from (so a music service streaming songs counts as sound, and a film counts as
  video). Only what's unmistakable is stopped before it downloads: video and sound files, video players' own servers
  (YouTube, Vimeo…), and anything labelled as video or audio. While videos are off, players stay out of sight until
  they've been judged, so no picture shows.
- **Sound made without a player** (the browser's sound system, used by games and many sites) stays silent when sound
  is off too, and so do players a site makes in code without putting them on the page (as many music players do).
- **A few sites send a video's sound as a separate .m4a or .aac file,** so while videos are allowed, those two formats
  aren't blocked.

**Each phone's default.** Every phone has a default for photos, videos and sound, each **Open** or **Blocked**
(admin page → **Phones** → the phone). It applies on every site the phone opens, except sites set to Open or Blocked
on their own. Changing it changes every site on that phone that's on Default, straight away.
- **New phones** start with **Settings → New phones start with** (all open unless you change it), and the new phone
  card in **Requests** lets you change it as you name the phone. Changing Settings doesn't change phones you already have.
- **The phone can ask to change it:** ⋮ → **Settings** → **This phone's default** (view only) → **Ask to change it**.
  It's a normal request: answered on the admin page by someone who manages that phone, or with an approval PIN in
  **My requests**, like any other.
- **Asking for a site** shows **Block** chips for kinds the phone opens by default and **Open** chips for kinds it
  blocks by default, each with a small tag saying the default. The admin page's request card does the same.
- `devices.json`: `"mediaDefault": { "photos": "blocked" }` (only the blocked ones); `"newPhoneMedia"` at the top.

**A site's own setting:** on the site's screen, **Photos**, **Videos** and **Sound** are each **Default** (the
phone's own default), **Open** (`photosOn` etc.: open for every phone using this list, whatever its default) or
**Blocked** (`noPhotos` etc.). Sites that had "No photos" before are Blocked; everything else starts on Default.

Ways to set it:
- **For a whole site:** Default / Open / Blocked on the site's screen on the admin page.
- **For single pages** of a site that's otherwise shown normally: admin page → **Sites** → the site → **Pages without
  photos, videos or sound** (or **List settings** → **Pages without photos, videos or sound (all sites)**), with a box
  each for pages without photos, videos and sound (a page in all three has them all off).
- **One photo or video anyway:** on a page where they're off, tapping a blocked one asks for **just that one** (or the
  page, or the site). Approved ones go in the list's `mediaAllow`. When the one item is an embedded player (YouTube,
  Vimeo...), its video is let through too, so it plays.
- **By approving a request** (section 3).

**Turning them back on for one phone, or one page:** "off" anywhere wins, so approving them back on adds a **back
  on** entry to the list it goes into (`photosOn`, `videosOn`, `soundOn`) whenever another of the phone's lists, or the
  whole site, still has them off. A "back on" wins over every "off", for the phones using that list. Switching a
  site's **No photos** (etc.) on in a list removes that list's "back on" for the site. See and edit them on the admin
  page → **Sites** → the site → **Pages without photos, videos or sound**.

- **On the phone:**
  - **The slim line under the top bar** says which: *"Photos are off on this page."*, *"Videos are off…"* or *"Photos and videos are off…"*
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
- **What it does:** pages on allowed sites can't load anything from known ad and tracker addresses (scripts, banners,
  ad frames, tracking pixels), and AdGuard's rules hide what's left of ads on the page. Ads don't appear, pages load
  faster and less is tracked.
- **What it can't do:** ads a site serves from its own servers in ways a browser can't tell apart (YouTube's are
  handled specially, below). Sometimes an empty space is left where an ad was.
- **Controls:** admin page → **Settings** → **Filters** for every phone, and **Phones** → the phone → **Ads** and
  **Filters** (Usual / On / Off) for one phone. **Never block these addresses** for something a site needs.
- **Check it on the phone:** **⋮ → Settings → About this phone** shows whether it's on and how much it has blocked.

### Ads, trackers and annoyances, entirely from AdGuard (updates by itself)
Three switches (admin page → **Settings** → **Filters**, for every phone, and **Usual / On / Off** on each phone),
all on unless switched off:

| Switch | AdGuard's lists | What it does |
|---|---|---|
| **Block ads** | Base (includes EasyList), Mobile Ads, plus the DNS list | Ads, including YouTube's |
| **Block trackers** | Tracking Protection, URL Tracking | Trackers and analytics; tracking codes (`utm_source`, `fbclid`…) taken out of addresses when a page opens |
| **Hide annoyances** | Cookie Notices, Popups, Mobile App Banners, Other Annoyances, Widgets, Social Media | Cookie notices, pop-ups, "get our app" banners, widgets, like and share buttons |

**If a site doesn't look or work right,** admin page → **Sites** → the site → **Filters off on this site's pages**:
switch off just the group causing it, on that site's pages only (for the phones using that list). It's usually **Annoyances**: its
rules for every site occasionally hide something a particular site needs. The app also follows AdGuard's own
per-site exceptions (`$generichide`, `$specifichide`, `$elemhide`, `$jsinject`, `$document`), which is how AdGuard
itself keeps known sites from breaking. **Never block these addresses** is different: addresses that are never
blocked wherever they're loaded (for something a site loads from another address).

What the app follows from AdGuard's lists:
- **Address rules:** particular addresses and paths, regular-expression patterns, rules for some sites only, and
  AdGuard's exceptions (e.g. Google's reCAPTCHA keeps working). AdGuard's **stand-in** rules get a harmless empty
  answer of the right type rather than a refusal, which keeps more sites working.
- **Hiding page elements:** everywhere and site by site; **style rules** as written; **advanced element rules**
  ("hide the box containing *Sponsored*") run with **AdGuard's own ExtendedCss code**.
- **AdGuard's scriptlets and site scripts,** run with **AdGuard's own code** for them (YouTube's ads especially),
  **before the page's own scripts**, as AdGuard runs them (needed for YouTube, which reads its ad data as it loads).
- **Tracking codes in addresses**, taken out when a page opens.

**YouTube's last resort (the app's own):** AdGuard's scriptlets run first, but on a phone's built-in browser some
YouTube ads still get through (AdGuard's own app stops them by editing YouTube's replies, which a browser can't). So
while YouTube's player shows an ad, the app mutes it, jumps to its end and presses **Skip**, and puts the sound and
speed back afterwards; at most a flash of the ad shows. Nothing is done when no ad shows. Each one is noted in the
log. It follows **Block ads**.

**Checking it:** **⋮ → Settings → About this phone** shows **Ad lists** (how many rules each list gave) and **Ad blocking
on this page** (for the open page: AdGuard's scriptlets and scripts there, elements hidden, whether scripts run before
the page's own, and any scriptlet AdGuard's code is missing).

**Where it all comes from: AdGuard.** Phones download its lists and its scriptlet and ExtendedCss code **once a day**,
so its updates arrive by themselves: you never update the app for them. A copy is packed into the app when it's
built, so it works from the first launch, even offline. **⋮ → Settings → About this phone → Ad blocking** says when
they were last updated.

**Not possible in a browser app:** AdGuard's rules that rewrite a page's HTML or a server's answers (its own app does
that by filtering all the phone's traffic), and its few named stand-ins (fake versions of Google Analytics and the
like), which are blocked instead. **Sites on "Never block these"** get none of it.

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
  - **Requests for it are marked:** the admin page shows *"⚠️ On the gambling list"*.
  - **The admin page's button says Approve anyway,** and asks you to confirm. (An approval PIN typed on the phone
    can never open it.)
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
- **Check it on the phone:** **⋮ → Settings → About this phone** shows each filter and how much it has blocked since the app opened.

### Embedded content from other sites
Pages often show things from other sites: an embedded YouTube or Vimeo video, a map, a "Sign in with Google" box.
Pictures, scripts and styles from other sites always load. **Embedded frames** only show if they're allowed on that
page; otherwise their space shows *"Blocked: content from vimeo.com"*.
- **On the phone,** a bar appears under the top bar: *"Parts of this page were blocked (from vimeo.com)"*, with **Ask**
  and ✕. The blocked part itself also has an **Ask for it** button, which starts with just that part. ✕ hides the bar until the page loads again, and while a page has blocked parts, **⋮** → **Blocked parts**
  does the same as **Ask**. It opens a short sheet with a tick box for each blocked site, and for each one **Just this
  one** (only that video, map or box; the default) or **Everything from it**, plus an optional note and the approval
  PIN if one is set.
- **The request** reads *"Embedded content on bbc.co.uk, from player.vimeo.com"*. **Approve** on the admin page lets
  content from those sites show **inside bbc.co.uk's pages only**. The sites themselves still don't open, and the ad
  and content filters still apply. Tick a public list under **Add it to** to do it for everyone.
  Once it's approved, reloading the page shows the blocked parts.
- **To see or change what's allowed,** admin page → **Sites** → the site → **Embedded content allowed**. You can remove any, or
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
| Home page, or ⋮ menu | **Ask for a new site** | open a site they type in, or one they find by what it's about |

Every request uses the same single screen:
- **Show on the home page:** a switch, on to start with, for whether it gets a tile.
- **Links through other addresses:** each address the link passes through gets its own **This page** / **Whole site**
  choice and **Home page tile** switch (off to start with).
- **For how long?** (asking to open, or for photos, videos or sound back): **Always** (the default) or **Temporary**,
  which shows two scroll wheels, like the admin page: **hours** (0 to 24) and **minutes** (0 to 55, in steps of 5),
  and **Only count time while the site is open**.
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
  - **Requests sent this way are marked** on the admin page: *"⚠️ The phone couldn't find this site when asking.
    It may be a typo."* So check the address before approving.
  - **Without internet** it can't check, so the request is saved and sent once the phone is online, as usual.
- **Don't know the address?** Type words instead (*"maths practice"*, *"nasa"*) and tap **Find sites** (or the
  keyboard's search key). See [Finding a website](#finding-a-website).

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
4. **The admin page shows which of those addresses aren't allowed yet.** Approving allows the destination
   **plus just those pass-through addresses**, without home page tiles, so the link works end to end.
   - **Only the exact pass-through address is allowed,** so `google.com/url` doesn't open Google search, and
     `bit.ly/3xYz` doesn't open other bit.ly links.
   - **If a pass-through's address is just the site's front page** (e.g. `go.example.net/?to=…`), its `?…` part is kept,
     so the whole site isn't opened.

A few links only redirect after running a full web page's code. The app can't follow those without opening the page,
so the request then names the address where the link was stopped.

### How you're told
- **On your phone,** once it's an **admin phone**: a notification, *"New request from Emma: open nasa.gov"*. Tapping
  it opens the admin with that request (after your PIN). See [Phone notifications for you](#phone-notifications-for-you-admin-phones).
- **From GitHub:** each request is an **issue** in the private repository, labelled **site request**. It's sealed: it
  says only *"🔒 A request from a phone"*, because what was asked, by whom, and the note are locked so only GitHub's
  automation can read them (see [Who can see what](#10-who-can-see-what)). A moment later the automation adds a short
  note to it, *"🟢 A new request: answer it on the admin page"*, which GitHub sends you as a notification (email, or
  the GitHub app), with a link that opens the admin page on that request.
- **The details are on the admin page** (and under **All the details**, with the whole history).

### How you answer
**On the admin page** (section 5), **Requests** lists every open request: who asked, what for, when, and their note.
- **Add it to:** which lists change: the phone's own list (ticked to start with) and any public lists.
- **Block:** **Photos**, **Videos** and **Sound** chips, to open it without them.
- **Approve:** does what was asked, including any time they asked for (e.g. **Approve for 30 min**). For a site on a
  filter's list, **Approve anyway**.
- **Temporary** (or **Other time**): the **hours** and **minutes** wheels, and *Only count time while it's open on the phone*.
- **Deny:** with an optional reason, shown on their phone.

GitHub's automation does it within about a minute, publishes the lists, and the phone is told.
- **Only the admin page (and the approval PIN, below) can answer.** Anything typed on the request on GitHub, or sent
  back to GitHub's emails, changes nothing.
- **Mobile and www. addresses count as the site itself:** asking from `m.youtube.com` or `www.youtube.com` adds `youtube.com`.
- **Approving a site that's already on the list without a tile** (e.g. one added as a pass-through) gives it a tile.
- **Answering a request that's already been answered** (on another device, say) changes nothing.

### Approving on the spot with a PIN
When you're with the person, you can approve their request on their phone, without the admin page, and without
signing in to the admin page on their phone.
- **Set a PIN** on the admin page: **Settings** → **Approval PIN** → **PIN for all phones**, or per phone under
  **Phones** → the phone → **Approval PIN**: **Usual** (the all-phones PIN), **Own PIN**, or **None**. Use 4 to 8
  digits (6 is best).
- **Helper admins can have their own PIN** (if you turn on **Approve on the phone with their own PIN** for them): they
  set it themselves under **Settings** → **Your account**, and it works **only on the phones they look after**. Your
  PIN works on every phone, so on a helper's phones **both yours and theirs work**; with several helpers on one group,
  **each has their own PIN and all of them work** there. **Phones** → a phone → **PINs that work here** lists them.
  - **No two admins can have the same PIN** (it's checked when one is set), so every approval says whose PIN it was:
    *"Approved on the phone with Ms Green's PIN"*, here and under **Requests** → **Answered**.
  - **None** on a phone turns PIN approvals off there for everyone. **5 wrong tries on a phone lock it** for everyone's
    PIN, whoever's was being tried.
- **On the phone, it's hidden:** the person asks as usual, then you open **⋮** → **My requests** and **tap its title
  7 times**. That's **approval mode**: tick one or more waiting requests, tap **Approve** or **Deny**, and type the PIN
  once for all of them: GitHub checks it **once**, answers each, and publishes the lists once for all of them (a wrong
  PIN counts as one wrong try). Requests waiting for their PIN to be checked can't be ticked again; if the PIN was
  wrong, they can. **Exit** (or closing My requests) leaves approval mode.
- **Approve** gives each request exactly what was asked, including a time limit or "without photos", and the site
  opens by itself within a minute or two. **Deny** tells the person *"Denied with the approval PIN"*. The request's
  history says *"Approved (or Denied) on the phone with Ms Green's PIN"* (or yours).
- **It can't open anything on the adult, gambling or malware lists.** Such a request keeps waiting for you, and the
  phone says so. Only you can open those, with **Approve anyway**.
- **It only approves requests.** It can't open the admin page or change anything else.
- **Safety:**
  - **The phone never checks or keeps the PIN.** It sends it in a hidden note on each request; GitHub deletes the note
    at once, then checks the PIN.
  - **Only a scrambled version is stored,** in the private repository. Phones only learn whether a PIN is set.
  - **A wrong PIN** leaves the requests waiting for you, and the phone says how many tries are left.
  - **After 5 wrong PINs,** PIN approvals lock on that phone for 24 hours, and you're told. Tapping the title 7 times
    then just says *"PIN approval is locked until…"*. Unlock it early under **Phones** → the phone → **Unlock**.
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
A change goes through a short relay: GitHub's automation checks and saves it (about half a minute to a minute after
you tap Save or answer on the admin page), and seals each phone's lists into the public repository. Then the phone has
to check:
- **Quick checks (usually):** while the app is open, the phone asks GitHub every minute or so whether anything is new
  (a tiny question; "nothing new" answers are free), and when something is, downloads its lists **straight from the
  repository**, at exactly that version. So a change reaches the phone **within about a minute** of being saved.
  - **With many phones, they ask less often** (each phone learns how many there are with its lists): every minute up
    to about 50 phones, every 2 minutes with 100, every 4 with 200, and so on (at most every 20 minutes), so all of
    them together stay well inside GitHub's hourly allowance, which the requests and admin pages share.
  - **If GitHub says no** (allowance used up, a hiccup, a network that blocks it), the phone goes back to GitHub
    Pages, as below, for a while: never slower than before. And it never goes back to an older copy of its lists.
- **GitHub Pages (the fallback):** **Publish list** puts the lists on GitHub Pages too (another 30 to 60 seconds, and
  Pages can hand out an older copy for a few minutes more), so through Pages it's live about **1 to 3 minutes** after
  you tap, and the phone checks:
- **After sending a request,** the phone checks every **20 seconds** for the next 10 minutes (and for a few more minutes
  once an approval arrives), so an approved site opens within moments of being published.
- **Otherwise,** it checks every few minutes while the app is open (**List settings** → **Phones check for changes
  every**; the files are tiny, so 1 or 2 minutes is fine; with quick checks working, it's the shorter of this and the
  quick-check spacing), and straight away whenever the app is opened.
- **To check now,** tap the list button (a list with a tick) in the top bar.

### How the person who asked hears back
Every request gets an answer on the phone, whether it's approved or not.
- **An approval is shown once the change has reached the phone** (its lists show it), not before, so *"can now be
  opened"* is true when it's read. The phone keeps checking quickly meanwhile. If it hasn't arrived after 10 minutes,
  the answer is shown anyway, saying it may take a few more minutes. **Denials** and notices come straight away.
- **Most approvals apply to the open page by themselves:** a site you asked for from its blocked page opens by itself,
  and photos, videos or sound switched on or off reload the page. **An embedded part or one single photo or video**
  only shows once the page is reloaded, so if you're on that page, the answer offers **Refresh now**.
- **How it gets there:** the answer includes a short, plain-language message for the phone, sealed so only that phone
  can read it. The phone checks its unanswered requests every few minutes while the app is open, and **every 15
  minutes or so when it isn't**: then the answer comes as a **phone notification** (*"Request approved"* /
  *"Request not approved"*; tapping it opens the app). There's no notification while the app is on screen.
- **When answers arrive,** a pop-up shows **"Answer to your request"** with the result. Examples:
  - ✅ *"coolmathgames.com can now be opened."*
  - ✅ *"nasa.gov can now be opened. (The whole site was approved, not just the page.)"*
  - ✅ *"scratch.mit.edu can now be opened, without photos and videos."*
  - ❌ *"Not approved: too distracting in class."* (the reason is whatever you typed when denying)
  - ❌ *"Not approved."* (denied without a reason)
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
- **Closing a request on GitHub without answering** shows as *"Not approved."* if you close it as "not planned",
  otherwise *"Closed without an answer."*
- **Requests unanswered for 60 days** stop being checked and show *"No answer after 60 days."*

### What approving does to the list
| Request | Result |
|---|---|
| Open a whole site | Adds the site, or removes a page limit if it had one. Anything under it on the **Always blocked** list is removed. |
| Open just a page | Adds the page to the site's **Only these pages** (or adds the site with just that page). If the page was on **Always blocked**, it's taken off. |
| Block a whole site | Removes it from the list. If it's part of a bigger allowed site, it goes on **Always blocked** instead. |
| Block just a page | Adds the page to **Always blocked**. If the site only allowed a few pages, that page is taken off its list instead. |
| Open without photos, videos and/or sound | Opens it as above, and adds the site or page to `noMedia` (all three), `noPhotos`, `noVideos` or `noSound` |
| Only block photos, videos and/or sound | Adds the site or page to `noMedia`, `noPhotos`, `noVideos` or `noSound`. It stays open. |
| Photos, videos and/or sound back | Takes matching entries off. Turning just photos back on for something with both off leaves videos off (it moves to `noVideos`), and the other way round. If another of the phone's lists still turns them off, the answer says so. |

Then GitHub publishes the lists, notes *"Done"* on the request and closes it.
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

### Setting up, even offline
A phone doesn't need internet to start setting up. On first launch, the app sets up locally:
- **its phone ID,** worked out on the phone itself
- **its install date**
- **the person's name,** asked for on the first screen
- **its registration,** saved in a small **outbox** on the phone

Its lists are sealed for it, so they only arrive once it's online and registered (a minute or two). Until then it
shows *"Setting up this phone…"* and opens nothing.

Anything the phone needs to send waits in that outbox: its registration, and requests made without internet.
It survives restarts. **As soon as the phone is online, the app sends everything in the order it was created**
and fetches the latest lists. It notices the connection coming back by itself, so nobody has to do anything.
- A request made offline says *"No connection right now. Your request is saved and will be sent automatically."*
  The request records when it was asked, so you can see it was sent later.
- **⋮ → Settings → About this phone** shows how many things are waiting to be sent, if any.
- If GitHub is busy, the phone simply tries again later. Something GitHub refuses for good, like an expired key,
  is dropped so it doesn't hold up the rest.
- The phone registers as of its **install date**, even if it only came online days later.

### How phones get an ID
1. The app gives the phone an ID like `K7M4-Q2XP`. It's worked out from Android's own ID for this app on this phone,
   so **it stays the same even if the app is uninstalled and reinstalled**. It's a scrambled form, so Android's ID itself isn't shared.
2. **If requests are set up** (setup step 5), the phone registers itself, straight away if it's online, otherwise as
   soon as it is, sending its **public key** (made on the phone; the private half never leaves it). It's added to the
   private repo's `devices.json` with its name, its key, the lists for new phones and **its own (empty) list**, so it
   shows under **Phones** on the admin page straight away. Its lists are published **sealed** for it (see
   [Who can see what](#10-who-can-see-what)). Until then, which takes a minute or two, it shows *"Setting up this
   phone…"* and opens nothing. You get a notification: *"📱 A phone registered."*
3. **Without requests,** add the phone by hand. On the phone, open **⋮ → Settings → About this phone** (it shows the ID and has a
   **Copy ID** button), then use **Add a phone by ID** on the admin page.
4. **When the ID does change:** after a **factory reset**, in a **different user profile** on the same phone, and of course on a
   **different phone**. Then it registers as a new phone, and you can give it the old one's name and lists (see below).
   It would also change if the app's signing key changed, which is one more reason never to touch it (section 8).

### Names
**The first time the app is opened, it asks "What's your name?"**, with the note *"So whoever manages this browser knows
whose phone this is."* This works without internet: the name is kept on the phone and sent with its registration once it's online.
That name is then the phone's name everywhere: in request notifications, on the admin page, and in the name of its private list.

**You can change a name at any time,** and your version wins over what they typed: on the admin page, **Phones** →
the phone → **First name** / **Last name**, then **Save** in the bar at the bottom. A new phone without a name is also
listed under **Requests**, with a box to type one.

A phone registered without a name (for example if requests weren't set up when it was first opened) shows as its model and ID,
e.g. *samsung SM-A155F K7M4-Q2XP (no name)*, tagged **Needs a name** under **Phones** until it's given one.

**⋮ → Settings → About this phone** also shows the phone's name, the lists it uses and the app version. It's handy for checking a phone is set up right.

### When a phone stops being used (uninstalled)
Android doesn't tell an app it's being uninstalled, so this works by **check-ins** instead:

1. **Every phone checks in** when the app is used, at most every 12 hours. It updates the phone's "New phone" issue
   with the time it was last seen. Editing an issue sends no notifications.
2. **Once a day, a GitHub job (Daily phone check) looks for phones that haven't checked in** for a number of days
   (default 14: admin page → **Settings** → **Phones that stop being used** → **Archive after**).
3. **Those phones are archived, and you get one notification listing them,** e.g.
   *"📦 Not seen for 14 days: 1 phone archived. Details on the admin page."* (Which phones, and their lists, are under
   **Phones** → **Archived phones**.)
   - **Nothing is deleted.** The phone moves to **Archived phones** in the admin page. Its **private** lists
     (used by no other phone) move to `docs/lists/archive/`.
   - **Shared lists stay where they are,** like a `year-5` list other phones use, and so does the public list.

**Getting a phone back:**
- **Not used for a while, or the app was uninstalled and reinstalled:** the ID hasn't changed, so it comes back **by itself**
  the next time the app opens, with its name and lists, and you're notified (*"📱 Emma is being used again"*).
  Until then it uses the lists for new phones.
- **Factory reset, or a replacement phone:** it has a new ID, so it registers as a new phone. On the admin page,
  **Phones** → **Archived phones** → the old one → **Give to another phone…** → the new one. The new phone gets its
  name, lists and settings, and its lists come back out of the archive.
- **From the admin page:** under **Archived phones**, each phone has these options:
  - **Restore** brings it back under its old ID.
  - **Give to another phone…** hands its name and lists to another phone (use this after a factory reset or for a replacement phone).
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
Everything about phones and lists is on the admin page (section 5). The ideas behind it:
- **Each phone has its own list,** named after its ID (e.g. `k7m4-q2xp`, since list names are published) and shown as
  the person's name. It's made when the phone registers, empty, so you can add sites before they ask for anything.
- **Public lists** (the default one, and any list you mark public) are for many phones at once, like *Year 5* or
  *Staff*. A phone's own list is never public.
- **Where approvals go:** by default, to the asking phone's own list, so a change only affects that phone
  (**Settings** → **When you approve a request**: **Just that phone** or **Every phone**; each phone can also have its
  own setting). On each request, **Add it to** shows where it will go, and you can tick other lists too.
- **Lists for new phones** (**Settings** → **New phones**): the lists a new phone starts with, besides its own.
  Untick them all to start new phones with nothing allowed until you add sites or approve requests. The app comes
  with a built-in copy of exactly these lists, for before it's first online, taken when it's built: after changing
  the ticks, run **Actions → Build APK** if new installs should start with the new choice.
- **Groups of phones** (e.g. one school): to see them together on the admin page, and to give a helper admin every
  phone in a group, now and later (section 5).
- **Because lists combine,** blocking something another of the phone's lists allows puts it on the target list's
  blocked list, and opening something another of its lists blocks gives a warning (blocks win).

### What gets stored where
| File | What's in it |
|---|---|
| `docs/whitelist.json` | The public list |
| `docs/lists/<name>.json` | The other lists |
| `docs/lists/archive/<name>.json` | Private lists of archived phones, kept until you restore or delete them |
| `devices.json` (private repo) | The phones (ID, name, model, date registered, their lists, groups, where their approvals go, their filter settings, approval PIN), archived phones, the groups (`"groups"`), the lists for new phones, where approvals go by default (`"requestsTo": "own"` or `"public"`), the days before archiving (`"inactiveDays"`), and the filters (`"adblock"`, `"adult"`…, `"adblockExceptions"`) |

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

## 3c. Other browsers (supervised protection)

On a supervised phone (a child's, say), this keeps the managed browser the only way to the web. It's set up by
whoever manages the phone, with physical access, and it's deliberately **visible**: a blocked app shows a plain
screen saying the browser is managed and by whom, nothing is hidden, and you can switch it all off from the admin
page at any time. It never touches apps Android itself needs (Phone, Settings, the system).

### What the phone does
- **Finds other browsers:** every few minutes, and whenever an app is installed, the phone lists the apps that can
  open a website and sends their **names** (only) to the admin page.
- **Asks about a new one:** with **Block new browsers straight away** on (the default), a newly installed browser is
  blocked at once and raised as a request (a notification), which you **Allow** or **keep blocked**.
- **Reports its state:** the Phones screen shows what protection is actually set up on the phone (device owner,
  screen cover, uninstall protection), so you can see it took.

### Two ways to keep it in place (Phones → a phone → Other browsers)
- **Screen cover** (no computer): an accessibility service covers a blocked app the moment it opens — in split
  screen and pop-up windows too — and covers the few settings screens where the protection could be switched off
  (Accessibility, this app's App info, and the device-admin screen). With **uninstall protection** (a device admin)
  Android won't uninstall the app until it's switched off there, and that screen is covered. It's the weaker option:
  someone who knows Android could still get round it (for example in safe mode), and you'd be told.
- **Device owner** (set up once, with a computer): the strongest. Blocked apps are **switched off** so they won't
  open at all, the app **can't be uninstalled**, and automatic date and time is **kept on** (so timers can't be
  dodged). Optionally, **new apps need your OK**. Set up from a computer with Chrome and the phone's cable; the setup
  page handles the accounts for you (it switches the account apps off for a moment and back on, so you don't sign
  out), and also shows the by-hand `adb` commands. Undo it any time from the admin page — the phone goes back to
  normal, nothing is wiped.

### Per app
Each app that can open websites has **Allow** or **Block**. **Add an app** lets you block one the phone didn't flag
as a browser (a game with its own web window, say), picked from the phone's app list. **Turn the cover off** from the
admin page without removing the setup, for when you need it off for a while.

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
| `unfiltered` | `true`: the adult, gambling and malware filters don't apply to this site (set by **Approve anyway**, or the site's switch on the admin page). | `false` |

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
| Open a site or page without photos, videos and sound | Add it to `noMedia`, e.g. `"noMedia": ["youtube.com"]` (or `noPhotos`, `noVideos`, `noSound` for one of them). On the admin page these are the site's **No photos**, **No videos** and **No sound** switches, and pages are under **Pages without photos, videos or sound**. |
| Use your own start page instead of the tiles | Add `"homepage": "https://www.example.org",` at the top. |

**Formatting rules:**
- Text goes in "double quotes".
- Put commas between entries, but **no comma after the last one** in a list.

If you make a mistake, nothing breaks: phones ignore a broken file and keep their last good list.
To check the file, open the **Publish lists** run in the private repo's **Actions** tab: a broken file makes it fail,
with the error. Fix the commas and quotes. (The admin page never makes these mistakes.)

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
2. The app checks when it's opened, every `refreshMinutes` while it's open, or right away with the list button in the top bar.
3. **Removing** a site blocks it even if it's open at the time. **Adding** a site opens it automatically if someone is on its blocked page.

---

## 5. The admin page

`https://YOUR_USERNAME.github.io/whitelist-browser/admin.html` is a web page for answering requests and managing the
lists and phones. It's made for phones (it also works on a computer), has four tabs at the bottom, and is also built
into the app (below). It never needs a GitHub token.

### Signing in
- **Your email and password** (set up in section 1, Step 9). Passwords need at least 8 characters, with a letter and
  a number, and can't be a common one.
- **A new device** (another phone, computer or browser) also gets an email, *"Is this you signing in?"*: tap **Yes,
  it's me** on any device (your phone is fine), and the page signs in by itself a moment later. Each step takes about
  half a minute, since GitHub's automation does it. **After that, that device stays signed in.**
- **A lock for the device:** after signing in, you're offered **your fingerprint (or face)**, where the device has it,
  and/or a **PIN**. Then opening the admin there only asks for that. In the app one of them is needed (anyone who
  taps the title 7 times would get in otherwise); elsewhere **Not now** skips it, and **Don't ask again on this
  device** stops the offer (set one any time in **Your account**). **5 wrong PINs** sign that device out.
- **The emailed links** (*Yes, it's me*, invitations, new passwords) work on any device. Tapped on the device that's
  signing in, it opens the admin right there. On a phone where Whitelist Browser is the phone's browser, they open
  in the app's own admin screen.
- **Forgot password?** on the sign-in screen emails you a link to choose a new one (it also signs you out everywhere
  else). After 5 wrong passwords, signing in on a new device pauses for 15 minutes (devices already signed in carry on).
- **You get an email whenever anyone signs in to the admin page on a new device.** If it wasn't them, sign that
  device out (below) and change the password.
- **Your account** (**Settings** → **Your account**): the devices you're signed in on (sign any of them out),
  **Change password**, the lock (fingerprint and/or PIN), and **Sign out on this device**.

### Saving
- **Tap Save** in the bar at the bottom after changing anything (**Undo** puts it back). Answers to requests go
  without the Save bar.
- **Then GitHub's automation checks the change and saves it,** about half a minute later: the top right says
  **Saving…**, then **Saved** as soon as it's saved (the page's own copy of the data is re-packed just after, and
  loads by itself). You can carry on meanwhile; changes made while one is saving go in the
  next one. Saving tidies every address, so `https://www.bbc.co.uk/news` becomes `www.bbc.co.uk`.
- **Not saved: tap for details** (in red) means it was refused, with why: tap it for **Try again** or **Start over**
  (reload everything from GitHub). The usual reason is that the list or the phones changed meanwhile (a request was
  approved, say): **Start over**, then redo the edit.
- **Phones pick changes up** within about a minute of **Saved** while their app is open (see [How quickly changes reach the phone](#how-quickly-changes-reach-the-phone)).
- **Your account and Admins save by themselves:** a switch (notifications, say), a PIN, or a change to an admin shows
  at once, and the same **Saving…** pill shows it going to GitHub; you can carry on, or leave the screen. If GitHub
  refuses it, the page says why and shows what's really saved.
- **Why saving takes a little while:** every change is done by GitHub's automation (that's what keeps it safe without
  a server of your own), and GitHub takes about half a minute to start it. Nothing waits for it, though.
- **Opening the page** shows what it showed last time straight away (**Updating…** at the top right), then the newest.
- **The page keeps itself up to date** while it's open: every 45 seconds (and when you come back to it), it loads the
  newest data, so a new request, a locked PIN or another admin's change shows without reloading.

### Admins: people who help (for you only)
**Settings** → **Admins** lists everyone who can open the admin page. **Add an admin** for a teacher, say, who looks
after some phones:
- **Their email** and a name, and **the phones they look after**: **All phones** (including new ones), or any
  **groups** (every phone in the group, including phones put in it later) and/or single phones.
- **What they can do** (each a switch):
  - **Answer requests** from those phones (their approvals always go to the phone's own list).
  - **Approve on the phone with their own PIN:** their personal PIN works in **My requests** on their phones (see
    [Approving on the spot with a PIN](#approving-on-the-spot-with-a-pin)).
  - **Edit their lists:** add and remove sites on those phones' own lists, and open things for a while.
  - **Manage their phones:** rename them, block or unblock them, archive and restore them, ask for a log, and unlock
    PIN approvals after wrong tries.
  - **Messages and logs** from those phones.
  - **Settings for every phone** (only with All phones): the public lists, the lists new phones start with, and where
    approvals go.
- **Always yours only:** ads, content filters, the phones' approval PIN settings, groups, admin phones, and adding
  admins. Helpers see these on a phone's screen marked **View only**. A helper with none of the list or phone switches
  (and no Messages and logs) has no **Phones** tab.
- **They get an email** to confirm their address and choose a password. Until they do, they can't sign in (the link
  works for 48 hours: **Send the invitation again** if needed). They sign in like you, with the same email check on
  each new device, and you get an email when they do.
- **What they see:** only those phones, the lists those phones use, and those phones' requests. The public lists they
  can look at but not change, and their approvals always go to the phone's own list. **GitHub's automation checks
  every change they make**, whatever the page shows.
- **Tap an admin** to change what they can do, **Pause** them (signed out everywhere until you let them back), sign
  out one of their devices, or **Remove** them. To make someone else the main admin, run **Set up admin sign-in** with
  their email.

### Requests
Open requests from phones, newest first, with a red count on the tab.
- **Each request** is a card showing who asked, when, what for, their note, and any warnings (a link that passes
  through other addresses, a typed site the phone couldn't find, a site on a filter's list). **All the details** (at
  the bottom of the card) shows everything: the phone, when, what and where, the page it was asked from, the home tile,
  how long and how time is counted, what to leave out, addresses it passes through, and its history.
- **Add it to:** tick boxes for where an approval goes: the phone's own list (or wherever its approvals usually go,
  ticked to start with) and every **public list**. If none of the ticked lists is one the asking phone uses, its own
  list is added too, so the person who asked always gets it.
- **Block:** **Photos**, **Videos** and **Sound** chips, as the phone asked; change them before approving.
- **Answering:** **Approve** (e.g. **Approve for 30 min** when they asked for a time; **Approve anyway** for a site on
  a filter's list), **Temporary** (or **Other time**) with the hour and minute wheels, or **Deny** with an optional
  reason shown on their phone.
- **New phones without a name** are listed below the requests, with a box to type one.
- **After answering,** the request moves to **Answered just now**. **Check for new requests** loads the newest.
- **Answered:** requests answered in the last 30 days (newest first), each with **who answered and how**: *On the admin
  page by Ms Green*, or *On the phone with your PIN*. A helper sees only their phones' requests. **Tap one** for all
  its details: what it changed, what the phone was told, and its history. Then:
  - **Undo the approval:** takes back exactly what it changed (anything changed since stays as it is), with an
    optional reason. The phone shows *"Changed to denied"* in My requests (and a notification), like any answer.
  - **Approve after all** (a denied one): the same card as a waiting request.
- **What the site is:** a request card shows the site's own name and description (found when the request arrives),
  and **Look at the site first** opens it in a new tab.
- **Ask for time** requests (from [Timers](#timers)): **Approve** as asked, **Other time**, or **Deny**.
- **Emails about a new request** go a minute after it arrives, and only if it's still waiting (answered with a PIN
  meanwhile: no email). Admin phones aren't notified of a request that was already answered, or while the admin
  page is open on them.

### Sites
**Search sites in every list** at the top suggests sites as you type (name, address, and which lists it's in); tap
one to open it. Under it, the lists are buttons: the **public lists** first (with a globe: **Default public list** and
any list marked public), then each phone's own list, and **+** makes a new one (with a **Public list** switch).
- **Temporary access** shows first, in amber, with the time left and **End now**.
- **Each site is one row,** with small tags: *On home page* / *No tile*, *1 page only*, *Exact address*, *No photos*,
  *No videos*, *No sound*, *Not filtered*, *Embeds from anywhere*.
- **Tapping a site** opens it with:
  - its **Address** and **Name on the home page**
  - switches for **Show on the home page**, **Include subdomains**, **No photos**, **No videos**, **No sound** and
    **Only some pages** (which then lists its pages, with a box to add more)
  - **Filters off on this site's pages** (ads, trackers or annoyances, for a site they break)
  - **Embedded content allowed**, and **Allow content embedded from other sites** (see
    [Embedded content](#embedded-content-from-other-sites)), with a warning to use it only for sites you trust
  - **Open even if a filter lists it**, for a site a content filter blocks that you've checked yourself
  - **More options** → where the tile opens; **Open it temporarily instead**; **Remove this site**
- **Add site** adds one the same way.
- **The rows at the bottom:**
  - **Open something temporarily:** temporary access with the scroll wheels.
  - **Always blocked:** parts of allowed sites to keep blocked, one per line.
  - **List settings:** **Public list** (on or off), the start page, how often phones check for changes, and **Delete**
    (not for the default public list). Also:
    - **Import from a spreadsheet:** paste cells or choose a `.csv`/`.xlsx` file. The columns are domain, name, home
      page, tile opens, subdomains, only these pages, no photos, no videos, and only the domain is needed. A header
      row is optional. **Add to the list** updates sites already on it, and **Replace the list** removes the rest. You
      can also download the list as a spreadsheet, a template, or **everything**: one file with a tab for each list
      and a **Phones** tab (also at the bottom of **Phones**).
    - **Pages without photos, videos or sound (all sites)** and **Embedded content allowed (all sites)**: every
      such entry in the list, in one place.

### Phones
One compact row per phone: its name, ID, lists and groups, **when it last used the app and its app version** (*older
app* if it hasn't updated), and tags only when something needs attention (*Blocked*, *Needs a name*, *Locked PIN*,
*Clock*, a filter switched off). A phone that hasn't opened the app for **2 days** gets a yellow note at the top (it may
have been uninstalled, or another browser is being used); its screen has **Ask it to update** for an older app, and
says if its clock was changed by hand. **Search phones** filters by name, ID, model or list as you type.
- **Groups** (e.g. everyone from one school): the chips at the top show just one group's phones. **Groups** (at the
  bottom) makes, renames and deletes them; put a phone in a group on its screen. A helper admin given a group looks
  after every phone in it, including phones added later.
- **Tapping a phone** lets you change its **First name** and **Last name**, the **Lists it uses** (tap to switch each on
  or off), where its approved requests go, its **Groups**, its **Approval PIN** (Usual / Own PIN / None; after 5
  wrong PINs a **yellow banner** at the top says *My requests PIN is locked*, with **Unlock**, which saves at once;
  locked phones also get a yellow banner at the top of **Phones**, and **Unlock PIN approvals on this phone** works
  even when the page doesn't know about the lock) with **PINs that work here** (yours, and each helper's who looks after
  it), **Ads** and **Filters** (Usual / On / Off), and **Admin phone** (every notification on that phone). A helper
  admin sees only what they're allowed to change; the rest shows under **Set by the main admin**. Then:
  - **Messages and logs:** messages its user sent you, and the app's logs (**Get log** asks the phone for one).
    **Swipe one right to archive it, or left to delete it**, like My requests on the phone (in **Show archived**:
    right puts it back, left deletes it). Or open one to **Download**, **Archive** or **Delete** it. Logs and messages go by themselves after 30 days; archived
    ones are kept until you delete them (**Show archived** under the list; open one to **Restore** or **Delete** it).
    Helpers with **Messages and logs** can do this for their phones.
  - **Block this phone:** it stays listed but can't open any site, for a lost phone or one that shouldn't be used.
    Its lists are set aside, and **Unblock this phone** gives exactly those back.
  - **Archive now:** does straight away what the daily check does after a phone is unused for a while. **Restore**
    brings it back. If it's still being used, it's restored by itself the next day.
- **Archived phones:** **Restore**, **Give to another phone** (after a factory reset), or **Delete for good**.
- **Add a phone by its ID:** found on the phone under ⋮ → Settings → About this phone, with its first and last name.
- **Questions** (delete, block, archive, discard changes) appear as the page's own dialogs: **Escape** or **Cancel**
  backs out.

### Timers
Every timer, by who it's for, with **On now / Later / Paused**, and **New timer**. See [Timers](#timers).

### Other browsers (a phone's screen)
Lists the apps that can open websites, each with **Allow** or **Block**; sets how the managed browser is kept in
place (**Device owner** / **Screen cover** / **Off**), the **screen cover** on/off, **Block new browsers straight
away**, and — as device owner — keeping the clock honest and whether new apps need your OK. See
[Other browsers](#3c-other-browsers-supervised-protection).

### Settings
- **Filters:** **Block ads**, **Block trackers**, **Hide annoyances**, **Block adult content**, **Block gambling**,
  **Block malware and scams**, and **Never block these addresses** (for all of them).
- **New phones:** the lists they start with.
- **When you approve a request:** change the list of **Just that phone** or **Every phone**.
- **Phones that stop being used:** **Archive after** a number of days.
- **Approval PIN:** the PIN for all phones (section 3).
- **Notifications:** **Turn off GitHub emails** (once your phone gets notifications).
- **Admin page:** **Your account**, **Admins** (for you only), and the status page.
- **Your account** (everyone): your approval PIN (yours is the PIN for all phones; a helper's own works on their
  phones), **Notifications** (below), the devices you're signed in on, your password, and this device's lock.
- **A helper admin** sees only **Your account** here: the settings for every phone are yours (unless you gave them
  **Settings for every phone**, which still leaves ads, filters and the PIN to you).

### Inside the app (hidden)
The same admin page is built into the app, so you can manage everything from the phone itself.
- **To open it,** tap the name at the top of the browser (e.g. **Home**) **7 times quickly**.
- **The first time,** sign in with your email and password (and tap **Yes, it's me** in the email), then choose
  **your fingerprint** (Android 10 and newer, if it's set up on the phone) or a **PIN** of 4 to 8 digits: next time
  that's all it asks. **5 wrong PINs sign the phone out** (then sign in again with your password).
- **It works like the web page:** the same tabs, buttons and saving. Downloads and outside links aren't available
  inside the app, so use a computer for those.
- **To change the lock:** **Settings** → **Your account** → **The lock**. A new PIN works straight away.
- **To leave,** tap **Close** at the top or the phone's back button. The browser checks for your changes straight away.
- **Screenshots are blocked** on the admin screen, and it doesn't appear in the recent-apps preview.
- **"Clear cookies and site data → All sites"** in the browser also signs the admin out on this phone. Just sign in again.
- **It's the version of the page from when the app was built.** Changing `docs/admin.html` starts a **Build APK**
  (a test build), and shows on the web at `admin-test.html` until you **Release to everyone** (see
  [Test first, then release to everyone](#test-first-then-release-to-everyone)).

### The admin app (Whitelist Admin)
A separate app that's **just the admin page**, for the people who manage Whitelist Browser (you, and helper admins)
on their own phones. It's built from the same code, every time **Build APK** runs.
- **Get it:** the status page's **Download the admin app**, or
  `https://github.com/YOUR_USERNAME/whitelist-browser/releases/latest/download/whitelist-for-admins.apk`
  (always the newest). It has its own icon (the tick on a shield) and name, **Whitelist Admin**, and sits next to
  the browser if both are on one phone.
- **It opens straight to the admin page:** sign in once (email, password, the emailed link), then a PIN or your
  fingerprint, the same as inside the browser. No browsing, no lists, and it doesn't ask for the camera,
  microphone or location.
- **It updates itself** (every 6 hours at most, when it's opened), from the same release as the browser. Each app
  takes only the file with its own name (`whitelist-for-admins.apk` or `whitelist-browser.apk`), so neither can pick up
  the other.
- **Notifications on the phone:** in the admin app, **Settings** → **Your account** → **On this phone** (Android
  asks to allow them). New requests, messages and logs, and crashes, only for what that admin may see, sealed so only
  that app can read them. Within a minute while it's open, and every 15 minutes or so when it isn't. Tapping one opens
  that request, message or log (after the PIN or fingerprint).

**Without the admin page,** lists can still be edited as files on github.com (section 4), but requests can only be
answered on the admin page (or with the approval PIN on the phone).

---

## 6. Tokens: what they are and how to make one

A **token** is like a spare key to your GitHub account that only opens specific doors. You choose which
repository it works on, what it may do there and when it expires. You can delete it at any time without
changing your password.

This setup uses two (you never need one yourself: the admin page signs you in with your email):

| Token | Where it goes | Permission | Needed for |
|---|---|---|---|
| **Publishing token** | Private repo secret `PUBLIC_REPO_TOKEN` | **Contents: Read and write**, public repo only | Copying the lists to the public repo |
| **Requests token** | Public repo secret `REQUESTS_TOKEN`, built into the app and the admin page | **Issues: Read and write**, private repo only | Sending requests from the app, and the admin page's sealed commands |

### How to make a token
1. On github.com, tap your profile picture → **Settings** → at the bottom of the menu, **Developer settings**.
2. **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
3. **Token name:** e.g. `Whitelist requests`. Choose an **Expiration**.
4. **Repository access:** **Only select repositories** → the repository given for that token in the table above.
5. **Permissions** → **Repository permissions:** set the permission(s) from the table to **Read and write**.
   Leave everything else as it is.
6. Tap **Generate token** and **copy it right away**. GitHub only shows it once.

### Keeping them safe and current
- **The requests token is built into the app, and the published admin page.** Someone who finds it could only create
  and read issues in your repo: requests and commands are sealed, and nothing else names a phone or person. They can't change anything: once admin sign-in is set
  up, only signed-in admins' commands (checked by GitHub's automation) change the lists. If fake requests appear, delete the token, make a new one, update the secret, and run **Build APK**
  and **Publish list**.
- **Renewing the requests token:** make a new one, then in **Settings** → **Secrets and variables** → **Actions**,
  edit `REQUESTS_TOKEN` and paste it in. Then run **Actions** → **Build APK** → **Run workflow**, and **Publish list**
  (for the admin page). Phones get the fixed app through the normal update banner.
- **An admin token from before** (pasted into the admin page) isn't used any more: delete it (below).
- **If a token is lost or leaked:** Developer settings → Fine-grained tokens → open it → **Delete**. It stops working immediately.

---

## 7. App updates

You only need this when you change the app itself. List changes and approved requests don't need an app update.

### Publishing a new version
A new version is built whenever you commit a change to the app code (anything in `app/`) or run
**Actions** → **Build APK** → **Run workflow**. Each build becomes a release named `v1.0.<number>`, with both apps.

### Test first, then release to everyone
**Every build is a test build first:** a GitHub *pre-release* titled **Test 1.0.<number>**. Phones don't get it,
except your test phones:
1. **Make a phone a test phone:** open the admin inside the app (or the Whitelist Admin app) → **Settings** →
   **Your account** → **This device** → **Get test versions of the app**. It then updates to test builds by itself
   (or straight away from ⋮ → Settings → App update). You can also install a test build by hand from its release page.
2. **Try it out.** Something wrong? Fix it and build again: nobody else got it.
3. **When it's good:** **Actions** → **Release to everyone** → **Run workflow**. The newest test build becomes the
   normal release (no rebuild: everyone gets exactly what you tested), and every phone updates from it as usual.
   To release an older test build instead, type its number (e.g. `1.0.57`).
- **In a hurry:** **Build APK** → **Run workflow** → tick **Release straight to everyone**.
- **The admin web page follows the same way:** `admin.html` on the web is the page from the last version released
  to everyone. A new `docs/admin.html` shows first at **`admin-test.html`** (same address, `admin-test.html` instead
  of `admin.html`), to try it, and becomes `admin.html` when you **Release to everyone**. (Inside the apps, the
  admin page is always the one built into that version.)
- **GitHub's automation** (the private repository's scripts) is shared by every version, so changes there work
  with the apps already out as well as new ones.

### How phones get it
1. The app checks every `UPDATE_CHECK_HOURS` (default 6), or right away from **⋮ → Settings → App update**.
2. When there's a newer version, a **green banner** appears. Tap it.
3. **First time only:** Android asks you to allow this app to install apps. Turn it on, go back and tap the banner again.
4. It downloads, then installs: on Android 12 and newer by itself (once the app has installed its own version once);
   otherwise Android asks **"Do you want to update this app?"**: tap **Update**. See
   [App updates in the background](#app-updates-in-the-background).

**Keep in mind:**
- **Don't rename or delete the Build APK workflow.** Its run count is the version number, so starting over would make new versions look older.
- **The download link always gives the newest version:**
  `https://github.com/YOUR_USERNAME/whitelist-browser/releases/latest/download/whitelist-browser.apk`
- **Each build makes the admin app too:** every release has both files, `whitelist-browser.apk` (the browser) and
  `whitelist-for-admins.apk` (Whitelist Admin), signed with the same key. Each app updates only from its own file. See
  [The admin app](#the-admin-app-whitelist-admin).

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
| `noVideos` | Sites or pages with no moving pictures: a video's picture is hidden, and its sound plays if sound is allowed. | `[]` |
| `noSound` | Sites or pages with no sound at all: music, podcasts and sound files are stopped, videos play muted. | `[]` |
| `mediaAllow` | Single photos or videos shown anyway where they're off: host and path, e.g. `ichef.bbci.co.uk/news/976/shark.jpg`. | `[]` |
| `photosOn`, `videosOn`, `soundOn` | Sites or pages where they're turned **back on** for the phones using this list, whatever other lists say (and, for a page, over its whole site's "off"). | `[]` |
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
| `REQUESTS_TOKEN` (public repo) | Lets the app and the admin page send requests and commands to the private repo. After changing it, run **Build APK** and **Publish list**. |
| `PUBLIC_REPO_TOKEN` (private repo) | Lets the private repo publish the lists to the public repo. |
| `MAIL_USER` (private repo) | The Gmail address the admin page's emails come from (section 1, Step 9). |
| `MAIL_APP_PASSWORD` (private repo) | That Gmail's app password (not its normal password). |

---

## 10. Who can see what

| Thing | Where | Who can see it |
|---|---|---|
| **Lists and phone settings** (readable) | Private repo (`docs`, `devices.json`) | **Only you** |
| **Each phone's lists, as the phone gets them** | Public repo (`docs/p/`), **sealed** | **Only that phone** can open its file |
| **Requests, notes and names** | Private repo issues, **sealed**; details in the private `requests` folder | **Only you** (and the automation) |
| **The automation's notes on requests** | Private repo issues | You; they say only what kind of update it is. The full text is in the private record |
| **The admin page's commands and answers** | Private repo issues titled *🔒 Admin* | Sealed: the commands only for GitHub's automation, its answers only for the device that asked |
| **The request key** | Private half: private repo `keys/`. Public half: `request-key.pem` in the public repo, and in the app | The public half can only lock, not unlock, so it's fine to be seen |
| The status page and the admin page | Public repo | Anyone can open them, but the admin page does nothing without signing in |
| **Admin accounts** | Private repo `admin/accounts.json` | **Only you**. Passwords only as scrypt hashes; emailed codes only as hashes |
| **What the admin page shows** | Public repo `a/`, **encrypted** with each admin's own key | Only that admin's signed-in devices (each gets the key sealed with its own device key). Mini admins only get their phones' part |
| App code and releases | Public repo | Anyone |
| Signing key file | Public repo | Anyone, but it's locked by your password |
| Secrets and tokens | Repo settings | Nobody, not even you (except `REQUESTS_TOKEN`, which is built into the app and the admin page: see section 6) |

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

---

## 10b. More about the app

### Play Protect warnings
Google Play Protect warns about apps that don't come from the Play Store, especially new ones it hasn't seen
before, from a developer it doesn't know. This app also needs permissions that installer-type apps have (it
installs its own updates, and can use the camera, microphone and location when a site asks). Each is legitimate
here, but together they make Play Protect cautious.
- **For now:** tap **More details** → **Install anyway**, and accept if it offers to scan the app.
- **Coming in 2027:** Google is requiring every developer of apps installed outside the Play Store to register with
  it (the Android Developer Console). It started in Brazil, Indonesia, Singapore and Thailand on 30 September 2026,
  and expands worldwide in 2027. Without registering, installing needs a much longer process, including a
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
- **The ⋮ menu** is a compact card, as wide as its items need: asking about this page (**Blocked parts**, with how
  many; **Ask for photos/videos/sound** where they're off; **Ask to block**; **Desktop site** and **Translate page**),
  **Ask for a new site**,
  **My requests** (with how many are waiting), and **Settings**. On tiny screens, **Forward**, **Reload** and **Check
  the list** are a row of buttons at its top.
- **⋮ → Desktop site** (on a site): shows that site's desktop version, remembered for that site until switched off
  (shown **On** in the menu). Other sites stay as phone sites. Like Chrome's, it introduces itself as a desktop
  browser **and** lays the page out at desktop width, opened **zoomed out so the whole page fits** (pinch to zoom in),
  so sites that adapt to the screen's width (most do) show their desktop layout too.
- **⋮ → Settings** holds **Appearance**, **Phone notifications** (on admin phones), **Phone's browser**, **Cookies and
  site data**, **Clear cache**, **App update** and **About this phone**.
- **Sheets that slide up from the bottom** can be dragged by their handle: down to close, up to fill the screen.
- **First launch** asks for the person's **first and last name**. **About this phone** shows the phone's own list by
  the person's name.

### Phone notifications for you (admin phones)
Make your own phone an **admin phone** (admin page → **Phones** → your phone → **Admin phone**): it then gets phone
notifications for **new requests** ("New request from Emma Smith: open nasa.gov"), **new phones**, **messages and
logs someone sent**, and **crashes**. Tapping one opens the app's admin screen, and after the PIN goes straight to that
request, message or log.
- **Only the admin page decides** which phones are admin phones (it reaches the phone in its sealed lists): using the
  admin screen on someone's phone doesn't make it one.
- **Each admin can turn on their own** (helpers too): **Settings** → **Your account** → **Notifications**.
  - **On this phone:** open the **Whitelist Admin** app on your phone (or the admin inside the browser app: tap the
    name at the top 7 times), and switch it on. That app then gets notes, like an admin phone.
  - **By email:** the same notes, to your email (from the Gmail set up for sign-in).
  - **Tell me about:** **New requests** (and new phones, for those who manage every phone), **Messages and logs**, and
    **Phone problems** (crashes). A helper only hears about **the phones they look after**, and only things they're
    allowed to do: no requests without **Answer requests**, no messages without **Messages and logs**, no crashes
    without **Manage their phones** (those switches are greyed out).
  - **Admin phone** (on a phone's screen) is different: it gets **everything**, about every phone. Only you set it.
- **How quickly:** within a minute while the app is open; otherwise Android lets the phone check every 15 minutes or
  so (instant notifications would need an outside push service such as Google's).
- **On the phone:** ⋮ → **Settings** → **Phone notifications** switches them off and on (shown on admin phones only).
- **Each note is sealed for that phone:** nobody else can read what was asked.
- **Instead of GitHub's emails:** once phone notifications work, admin page → **Settings** → **Turn off GitHub emails**
  opens GitHub's settings (under "Participating", untick Email). That also stops an email for each of the admin page's
  own changes (each is a sealed *🔒 Admin* issue, answered and closed by the automation). The admin sign-in emails
  (invitations, new devices, passwords) come from your Gmail, not GitHub, so they keep coming.

### The app's log (to see what went wrong)
The app keeps a small rolling log on the phone (about the last few days): which sites opened and how long they took,
what was blocked on them, script errors pages report, list checks, downloads, ad-blocking updates, requests, the
playing notification, app updates, and crashes. **Site names only**: never full page addresses, searches or anything
typed. It stays on the phone until it's sent to you (below). Admin phones can also share their own log (**⋮ → Settings
→ About this phone → Share log**: WhatsApp, email, Drive, or save it to a file). A shared log starts with the technical details at that moment (ad blocking and its
lists, what's playing, the last crash…), which About this phone doesn't show.

**Messages and logs sent to you (sealed, like requests),** never on a schedule:
- **Message admin** on the phone (**⋮ → Settings → About this phone → Message admin**): a **subject** and a
  **message**, for questions, comments or something not working, and a tick box to **attach the app's log**. It says
  *"Message sent"* (or that it'll go once the phone is online). An admin phone gets a notification (*"Message from
  Emma Smith"*, with the subject); tapping it opens that message.
- **Get log** on the admin page (**Phones** → the phone → **Messages and logs**): the phone sends its log at its next
  check (within a few minutes, once it's online with the app open).
- **After a crash:** the next time the app opens (admin phones get a notification).

They're kept in the private repository (`logs/<phone ID>/`) for 30 days; the admin page lists them on each phone's
screen (messages by their subject), to read or download.

### App updates in the background
Tapping the update banner downloads the new version with Android's own download manager (with its progress
notification), so it **carries on if you minimise or close the app**, and then **installs by itself**: on Android 12
and newer without asking (where the app installed its current version itself, as it does once it has updated itself
once). Installing closes the app for a moment, so if something's playing it waits until that stops. Afterwards, a
notification says **"Whitelist Browser updated to 1.0.x"** (tap to open it).

If Android wants to ask first (older Android, or the app was last installed by hand), its "Do you want to update this
app?" screen shows when the app's open, else a **"Update ready: tap to install"** notification opens it. Android
doesn't let an app open that screen by itself while it isn't on screen.

### Translating pages
Pages are translated **on the phone itself** with Google ML Kit: the page's text never leaves the phone. Each language
downloads once (about 30 MB), then it works offline.
- **When a page isn't in the phone's language,** a bar under the toolbar says *"This page is in Hebrew"*, with
  **Translate** and ✕. The first time for a language, it says *"Getting Hebrew ready to translate…"* while it downloads.
  Once translated, the bar says *"Translated from Hebrew"*, with **Show original**.
- **Tap the bar's words** (or **⋮ → Translate page**) for the options: the language to translate into, **Always
  translate Hebrew pages** (translated as they open), and **Offer to translate Hebrew pages** (switch it off to stop the bar for that language).
- **✕** hides the bar on that page only.

### Finding a website
**Ask for a new site** takes an address, or words for what they're looking for (*"maths practice"*, *"nasa"*): tap
**Find sites** (or the keyboard's search key) and matching sites appear, each with its icon, name, address and a
short description. Tapping one fills it in, ready to **Send**.
- **The results come from DuckDuckGo** (with Safe Search on strict), one per site, no ads. No account or key is needed.
- **Sites already allowed on the phone never appear** (this is for asking for new ones), and nor do sites the content
  filters or **Always blocked** block.
- **8 at a time:** **Show more results** shows the next 8 (the next page is fetched ahead). The results scroll in
  their own box, so the rest of the sheet stays put.
- **If DuckDuckGo doesn't answer** (after a second try), it says *"Couldn't search right now"*: try again in a moment.
- **"Are you a person?"** DuckDuckGo has no fixed limit, but after many searches from one internet connection (phones
  on the same Wi-Fi count together) it may ask to check a person is searching. The app then shows **DuckDuckGo's own
  check** in a small window for the person to answer (the app never answers it itself); once it's passed, the search
  carries on, and DuckDuckGo leaves that phone alone for a while. The window shows only that check: links in it go
  nowhere, it isn't an approved site, and it closes by itself. **Cancel** stops the search.
  Searching the same words again within 15 minutes is instant.
- **Icons** come from Google's public icon service (the site's first letter until it loads).

### Sound in the background
While a site plays sound, its **media notification** shows (in the app too), so it **keeps playing when you leave the
app** or the screen turns off:
a **media notification** shows what's playing as the site describes it (title, artist, artwork), with **Previous**,
**Play/Pause** and **Next** where the site has them, and **Stop**. The same controls work on the lock screen, in the
quick settings media panel, and with headphone and Bluetooth buttons. Where a site has no previous / next, there's
**back 10 s** / **forward 10 s**, and (newer Android) a progress bar to drag. No artwork from the site: the video's
preview picture, or the page's sharing picture. It goes when you come back to the app, when
nothing's left to play, or after 10 minutes paused. (Sites that stop playing when they're hidden, like YouTube, are told they're still showing.)

**Browsing elsewhere while it plays:** leaving a site that's playing (a link, an address, **Home** or **Back**) moves
it to a hidden **player tab**, where it keeps playing, and the new page opens in a fresh tab. A slim bar under the
top bar says **"Playing from youtube.com"**, with **Open** (back to it, still playing; the tab you were browsing in
closes) and **Stop**. One player at a time; it can't navigate on its own, and closes by itself after a minute of
silence.

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
- **Hold a tile and let go** for a menu (the home page's own; the link menu other pages have doesn't show here): **Open its home page**, or **Open where you left off** (the last page opened on
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
- **On tiny screens** (under 300dp wide, e.g. 2.8-inch phones), **Forward**, **Reload** and **Check the list** move into the **⋮** menu,
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
| A phone doesn't get a list's sites | Check its lists under **Phones** (tap the phone) and that you tapped **Save**. On the phone, **⋮ → Settings → About this phone** shows the lists it's using. |
| An allowed site loads but looks broken, like missing videos, maps or buttons | Two common causes. **Embedded content** (videos, maps, sign-in boxes) from sites that aren't on your lists is blocked: to allow it on this site, turn on **Allow content embedded from other sites** on the site's screen (see [Embedded content](#embedded-content-from-other-sites)). Or a **filter** may be stopping something it needs: try turning **Ads** (or a content filter) off for that phone to confirm, then add the domain to **Never block these** and turn it back on. |
| A temporary site closed early, or stayed open too long | **From now** runs on the clock. **Only while it's open on the phone** counts on-screen time, in steps of 15 seconds, within 7 days. Check which you chose under **Temporary access**. |
| Pictures or videos are missing on a page | Photos and/or videos are off there (the status line says which). Take it off in the admin page, or ask from the phone with **⋮ → Ask for photos and videos**. |
| Something still shows on a "no photos or videos" page | A few sites draw pictures in unusual ways. Block the site completely if it matters. |
| Ads still show on a site | The ads come from the site's own servers (like YouTube video ads), which a domain list can't block. |
| A site allowed in a phone's list still won't open | Another of the phone's lists blocks it, and blocks win. Check the **Always blocked** section of each of its lists. |
| A request's **Add it to** ticks the public list | The admin page's **When you approve a request** setting is on **Every phone**, or that phone's own setting is. Untick it (and tick its own list) for this time, or change the setting. |
| You weren't told about a phone that's gone | Archiving happens after the set number of days without a check-in, and only if requests are set up. Check **Actions** → **Daily phone check** is enabled and running. |
| A phone in use was archived | It wasn't opened within the set number of days (or can't check in). It returns by itself when opened. Raise **Archive after** (Settings) if that's common. |
| The same phone appears twice | It was factory reset (or used in another user profile), which gives it a new ID. Archive the old one, then **Archived phones** → it → **Give to another phone…** → the new one. A normal reinstall keeps the same ID. |
| App says *"No list loaded"* | Check that GitHub Pages is on (setup step 2), that **Publish lists** (private repo) and **Publish list** (public repo) ran green, and that `GITHUB_USERNAME` is right. |
| List changes don't show up | Check that **Publish lists** (private repo) and **Publish list** (public repo) ran green, then tap the list button (a list with a tick) in the top bar. If you edited a file by hand, check it for JSON mistakes, and make sure you edited it in the private repo. |
| A subdomain is blocked, like `mail.google.com` | The site has **Include subdomains** turned off, or the subdomain is on the **Always blocked** list. Add the subdomain as its own site, or turn the option back on. |
| A page on an allowed site is blocked | The site has **Only these pages** filled in. Add the page there, or empty the box to allow the whole site. |
| A YouTube video won't open although its channel is allowed | Videos have their own address (`youtube.com/watch?v=…`). Add each video, or allow all of YouTube. |
| A link from an allowed page is stopped, though its destination is allowed | It passes through another address first (a shortener or redirect). Tap **Ask to open**. The request lists the pass-throughs, and approving allows them. |
| A listed site is still blocked | It probably sends you to another domain, like a sign-in page. The blocked page names it. Add it with `"home": false`. |
| No request buttons in the app | `REQUESTS_TOKEN` was missing when the app was built. Add it (setup step 5), then run Build APK and update. |
| *"The request key has expired"* | It means the **requests token** (`REQUESTS_TOKEN`) expired, not the request key pair. Renew the token (section 6). |
| Requests arrive but you get no notification | Make your phone an **admin phone** (admin page → Phones → your phone), and check ⋮ → Settings → **Phone notifications** on it is on. Or check GitHub's notification settings for **Participating**, and that you're watching the private repo. Requests always show on the admin page. |
| Someone didn't hear back about a request | Answers arrive within a few minutes while the app is open and online, or as a notification within about 15 minutes when it isn't (if the phone allows the app's notifications). **⋮ → My requests** shows the status. |
| A request made offline hasn't arrived | It's sent when the phone is next online with the app open. **⋮ → Settings → About this phone** shows whether anything is still waiting and why. |
| A phone stays on *"Setting up this phone…"* | It needs to be online and registered first (a minute or two). **⋮ → Settings → About this phone → Setting up** says where it's got to. |
| *"Could not save for 5 minutes"* | Very many changes arrived at once and this one kept losing the race. Nothing was changed. Answer it again on the admin page. |
| A phone says *"GitHub is busy"* | GitHub limits how many requests one account can create per minute, which lots of phones at once can reach. Wait a few minutes and send it again. Phones registering themselves retry by themselves. |
| Something typed on a request on GitHub did nothing | Only the admin page answers requests. Answer it there. |
| Camera, mic or location doesn't work on a site | The site was blocked earlier in this session, or Android permission was refused. Reopen the app, or allow it in Android Settings → Apps → Whitelist Browser → Permissions. |
| A phone, email or app link does nothing | No app on the phone can open it. Install the app it's meant for. |
| *"Couldn't check for updates: Set GITHUB_USERNAME"* | Set your username in `Config.kt`. |
| *"Update refused: it's signed with a different key"* | The installed app was signed with a different key, which only happens if the signing key was recreated (section 8). Uninstall it and install from the download link. |
| Admin page: *"No answer from GitHub yet"* | GitHub's automation didn't run: private repo → **Actions** → **Admin page** (is it switched on? did the run fail?). Then try again. |
| Admin page: *"Couldn't send the email"* | The `MAIL_USER` / `MAIL_APP_PASSWORD` secrets are missing or wrong (section 1, Step 9). The app password must be Gmail's **app password**, not the normal one. |
| Admin page: *"GitHub turned the requests token down"* | `REQUESTS_TOKEN` expired: renew it (section 6), then run **Build APK** and **Publish list**. |
| Can't sign in at all (lost the password and the email) | Private repo → **Actions** → **Set up admin sign-in** → **Run workflow** with your email: it emails a link to choose a new password. |
| Admin page: **Not saved**, *"…changed since you loaded it"* | It changed meanwhile (a request was approved, say). Tap the red **Not saved** → **Start over**, then redo your edit. |
| Admin page: **Not saved** for another reason, or *"Not done"* on a GitHub issue | Open the private repo → **Actions** → the latest **Admin page** run → the **actions/github-script** step: a line *Not done (…)* says why (only you can see it). |
| The admin page asks for the email check every time on the same device | That browser is deleting the page's saved data: a private/incognito window, or a setting that clears site data on closing. Use a normal window, and don't clear that site's data. |
| A helper admin didn't get the invitation | Check their spam folder, then Settings → Admins → them → **Send the invitation again**. If no email goes at all, see *"Couldn't send the email"* above. |
| Find sites says *"Couldn't search right now"* | DuckDuckGo didn't answer (or is limiting the phone for a while, and its person check wasn't answered). Try again in a moment, or type the site's address. |

---

## 13. What's in the two repositories

**`whitelist-browser` (public)**
```
docs/                          Published by GitHub Pages
  p/<code>.json                One per phone: its settings and lists, sealed so only that phone can read them
  .source                      Which private version the sealed files came from
  admin.html                   The admin page (the requests token is added when it's published)
  index.html                   Status page: links to the admin page and the app download
  sites-template.csv           Spreadsheet template for bulk import
a/<code>/                      The admin page's data, encrypted: one folder per admin, a key file per signed-in device
request-key.pem                The request key's public half (published by the private repo; the app is built with it)
app/                           The Android app
  src/main/java/.../Config.kt          Your settings
  src/main/java/.../PrivateRepo.kt     The private repository's name (the public one's + "-private")
  src/main/java/.../MainActivity.kt    Browser screen, blocking, menu, requests, translation bar, finding sites
  src/main/java/.../AdminActivity.kt   The admin page inside the app (7 taps on the name at the top; the whole admin app)
  src/browser/AndroidManifest.xml      The browser app only: its launcher icon, and opening web links
  src/admin/                           The admin app only (Whitelist Admin): its name, icon, and opening to the admin page
  src/main/java/.../AdminAlerts.kt     Phone notifications for admin phones
  src/main/java/.../UserAlerts.kt      Notifications for answers to this phone's requests (app closed)
  src/main/java/.../Whitelist.kt       Downloading, unsealing and combining the phone's lists, checking addresses
  src/main/java/.../Seal.kt            The phone's own key pair, and sealing / unsealing
  src/main/java/.../Device.kt          The phone's ID and the name typed on it
  src/main/java/.../HomePage.kt        Serves the home page with tiles
  src/main/java/.../Tiles.kt           Home tiles' order and "where you left off" (kept on the phone)
  src/main/java/.../Requests.kt        Sends requests, messages, registration and check-ins to the private repo
  src/main/java/.../Outbox.kt          Keeps registration, requests and messages on the phone until they can be sent
  src/main/java/.../MyRequests.kt      This phone's requests and the answers to them
  src/main/java/.../SiteCheck.kt       Checks a typed site exists before asking for it
  src/main/java/.../Discovery.kt       Finding sites by words (DuckDuckGo), and their icons
  src/main/java/.../PageTranslate.kt   Translating pages on the phone (Google ML Kit)
  src/main/java/.../ExternalLinks.kt   Sends tel:, mailto:, app links etc. to their own app
  src/main/java/.../AdBlock.kt, AdRules.kt, AdFilters.java   Ad, tracker and annoyance blocking (AdGuard's lists)
  src/main/java/.../MediaBlock.kt      "No photos", "no videos" and "no sound"
  src/main/java/.../PlaybackService.kt Sound in the background, and the media notification
  src/main/java/.../Passthrough.kt     Finds where a stopped link really leads
  src/main/java/.../TempTime.kt        Time spent on "time on the site" temporary access
  src/main/java/.../AppLog.kt, CrashLog.kt   The app's log, and crash reports
  src/main/java/.../Updater.kt         Finds, downloads and installs app updates (with the receivers next to it)
  src/main/java/.../Ui.kt              The app's look: colours, fonts, dialogs, buttons, switches
  src/main/java/.../MaxHeightScrollView.kt  Keeps dialogs' buttons on screen on small phones
  src/main/res/drawable/ic_d_*.xml     The dialogs' icons
  src/main/assets/home.html            The home page
  src/main/assets/blocked.html         The "not on the list" page
signing/release.p12            Signing key, locked by your password (created on the first build)
.github/workflows/android.yml  "Build APK": builds, signs and publishes both apps (a test build, normally)
.github/workflows/release.yml  "Release to everyone": makes the newest test build the release every phone gets
.github/workflows/pages.yml    "Publish list": publishes docs/ to GitHub Pages
```

**`whitelist-browser-private` (private)**
```
docs/whitelist.json            The default public list: the real one (edit here, or on the admin page)
docs/lists/                    The other lists; lists/archive/ keeps archived phones' lists
devices.json                   Every phone: name, model, dates, lists, settings; archived phones; the settings for all
admin/accounts.json            Admin accounts: emails, what each can do, their devices (passwords only as hashes)
requests/<number>.json         Each request's details and history, for the admin page
logs/<phone ID>/               Messages and logs phones sent, kept 30 days
keys/                          The request key pair (keep request-private.pem private)
.github/workflows/requests.yml "Site requests": handles requests, new phones, messages and PIN approvals
.github/workflows/admin.yml    "Admin page": signs in, checks and carries out everything the admin page sends
.github/workflows/admin-setup.yml  "Set up admin sign-in": your account (run it by hand)
.github/workflows/phones.yml   "Daily phone check": archives unused phones, restores returning ones
.github/workflows/sync.yml     "Publish lists": seals each phone's lists into the public repo
.github/last-check             Touched monthly by the daily check so GitHub keeps it running
.github/scripts/handle-request.js    The logic for requests, phones and publishing
.github/scripts/admin.js       The logic for admin sign-in, accounts, emails and the admin page's data
```
