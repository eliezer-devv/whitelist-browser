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

## 1. One-time setup

There are **two repositories**:
- **`whitelist-browser` (public):** the app, its updates, and what the phones read (the lists, and which lists
  each phone ID uses, with no names).
- **`whitelist-browser-private` (private, only you can see it):** the requests and your replies, every phone's
  name and details, and the automation that handles it all. Section 10 explains what's visible where.

Do these steps in order. On a phone, switching the browser to **desktop site** makes GitHub's settings pages
easier to use.

**Already using the one-repository version?** Follow [Moving to the private repository](#moving-to-the-private-repository-existing-setups) below instead.

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
2. Upload all the files from `whitelist-browser-private.zip`. Its files are all in the hidden `.github` folder, so
   create them by hand as in step 1: `.github/scripts/handle-request.js` and the four files in `.github/workflows/`.

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
In the private repo: **Actions** → **Move my data (run once)** → **Run workflow**. For a new setup this creates the
starting list and publishes it. When it's green, check that this link opens:
`https://YOUR_USERNAME.github.io/whitelist-browser/whitelist.json`

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

### Moving to the private repository (existing setups)
If you set things up before the private repository existed, your lists, phone names and requests are in the public
repository. To move them:
1. **Create the private repository** (step 4 above).
2. **Make the publishing token** and add it to the private repo as `PUBLIC_REPO_TOKEN` (step 5).
3. **Replace the requests token:** make a new one for `whitelist-browser-private` (Issues: Read and write) and
   paste it into the public repo's `REQUESTS_TOKEN` secret, replacing the old value.
4. **Give your admin token access to the private repo:** Developer settings → Fine-grained tokens → your admin
   token → **Edit** → **Repository access** → add `whitelist-browser-private`, with **Contents** and **Issues** set
   to **Read and write** → **Update**.
5. **Move the data:** in the private repo, **Actions** → **Move my data (run once)** → **Run workflow**. It copies
   your lists and phones (with their names and archive) to the private repo, publishes the public parts, and
   removes `devices.json` from the public repo. It never overwrites data already in the private repo.
6. **Update the public repo** with the new files, and **delete** the files that moved: `.github/workflows/requests.yml`,
   `.github/workflows/phones.yml` and `.github/scripts/handle-request.js` (open each → **⋯** → **Delete file**).
7. **Let Build APK finish,** then update each phone from its green banner. Until a phone updates, its requests go to
   the old place and won't be answered.
8. **Old requests** are still visible in the public repo's Issues. You can delete them: open each → **Delete issue**
   (at the bottom of the right-hand column).

**Good to know:** GitHub keeps the history of every file. Old versions of `devices.json` stay visible in the public
repo's history (**Commits**). To remove that completely, you'd have to delete the public repository and create it
again, which means reinstalling the app on every phone. Most people don't need to.

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

### No photos or videos
Sites or pages can be set to open **without photos, pictures or videos**. The text, links and buttons work as usual,
but pictures don't load and videos don't play. This is off by default. It can be turned on in three ways:
- **For a whole site:** the **No photos or videos** tick box on the site's card on the admin page.
- **For single pages** of a site that's otherwise shown normally: the **No photos or videos on specific pages** box on the admin page.
- **By approving a request** (section 3).

- **On the phone:**
  - **The status line** says *"Photos and videos are off on this page."*
  - **Each blocked picture or video becomes a small placeholder,** *🖼️ Photo blocked · tap to ask* or *🎬 Video blocked · tap to ask*,
    so it's clear something is there. Tapping one, or **⋮ → Ask for photos and videos**, asks for them.
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
Pages often show things from other sites: an embedded YouTube video, a map, a "Sign in with Google" box. Pictures, scripts
and styles from other sites always load. **Embedded frames** only load if their site is allowed too, so on some sites
parts are missing.
- **For a site you trust,** turn on **Allow content embedded from other sites** on its screen on the admin page (Sites →
  the site). Then frames from any site work on its pages. Its row shows **Embeds from anywhere**.
- **Leaving the site is still blocked:** links, redirects and pop-ups to other sites are stopped as usual. The ad and adult
  filters still apply to what's embedded.
- **Only for sites you trust:** whatever the site chooses to embed will show, even from sites that aren't on your lists.
- **For a site limited to some pages,** it applies on those pages.

### What's allowed
- **Downloads.** Files are saved to the phone's **Downloads** folder, with a notification when they finish.
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
- **For how long?** (asking to open, or for photos and videos back): **Always** (the default), or **Just for a while**,
  which shows two scroll wheels, like the admin page: **hours** (0 to 24) and **minutes** (0 to 55, in steps of 5).
  Flick them up or down to choose. This is what they ask for. You decide the actual time when you answer.
- **Photos and videos:**
  - When asking to **open**, there's a **Without photos and videos** tick box.
  - When asking to **block**, there's a choice between **Block it completely** (the default) and **Only block photos and videos**,
    where the page stays open.
  - On a page where they're off, **⋮ → Ask for photos and videos** asks for them back.
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
Its title says exactly what was asked, e.g. **Open page: youtube.com/watch?v=abc123** or **Block site: scratch.mit.edu**.
A moment later a reply appears on it, and **that reply is what notifies you**, by email and by push notification in the GitHub app.
All open requests are in the repo's **Issues** tab.

### How you answer
**The easiest way is the admin page, with a tap.** At the top of the admin page, **Requests** lists every open request:
who asked, what for, when, and their note. Each one has three buttons:
- **Approve:** does what was asked, including any time they asked for. It then says e.g. *"Approve (30 min, as asked)"*.
- **For a while:** shows the **hours** and **minutes** scroll wheels and a tick box, *"Only count time while it's open on the phone"*.
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
| `approve with media` | For a "without photos and videos" open request: open it with them |
| `approve fully` | For an "only photos and videos" block request: block it completely |
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

### How the person who asked hears back
Every request gets an answer on the phone, whether it's approved or not.
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
| Open without photos and videos | Opens it as above, and adds the site or page to **No photos or videos** |
| Only block photos and videos | Adds the site or page to **No photos or videos**. It stays open. |
| Photos and videos back | Takes matching entries off **No photos or videos**. If another of the phone's lists still turns them off, the bot says which. |

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
   soon as it is. It's added to the private repo's `devices.json` with its name and the lists for new phones, and
   (without the name) to the public `phones.json`. You get a notification: *"📱 New phone registered: Emma (samsung SM-A155F), ID K7M4-Q2XP."*
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
| `approve public` | The public list, just this once, so every phone using it gets the change |
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

**Don't edit the lists in the public repository:** they're copies, replaced every time something is published.

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
  through other addresses, or a typed site the phone couldn't find).
- **Answering:**
  - **Approve**, which says e.g. **Approve for 30 min** when they asked for a time.
  - **For a while** (or **Other time**): the hour and minute wheels, with *Only count time while it's open on the phone*.
  - **Deny**, with an optional reason shown on their phone.
  - **Change it for every phone** (a tick box on each card) changes the Everyone list instead of theirs.
- **Phones with no name yet** are listed below the requests, with a box to type one.
- **After answering,** the request moves to **Answered just now**, and the phone is told within a few minutes.

**Sites.** The lists are chips at the top: **Everyone** is the public list, then one per person or group, and **+** makes a new one.
- **Temporary access** shows first, in amber, with the time left and **End now**.
- **Each site is one row,** with small tags: *On home page* / *No tile*, *1 page only*, *Exact address*, *No photos*, *Added automatically*.
- **Tapping a site** opens it with:
  - its **Address** and **Name on the home page**
  - switches for **Show on the home page**, **Include subdomains**, **No photos or videos** and **Only some pages**
    (which then lists its pages, with a box to add more)
  - **Allow content embedded from other sites** (see [Embedded content](#embedded-content-from-other-sites)), with a warning to use it only for sites you trust
  - **Open even if a filter lists it**, for a site a content filter blocks that you've checked yourself
  - **More options** → where the tile opens
  - **Open it just for a while instead**
  - **Remove this site**
- **Add site** adds one the same way.
- **The rows at the bottom:**
  - **Open something for a while:** temporary access with the scroll wheels.
  - **Always blocked:** parts of allowed sites to keep blocked, one per line.
  - **No photos on some pages:** single pages, one per line. For a whole site, use its switch instead.
  - **Import from a spreadsheet:** paste cells or choose a `.csv`/`.xlsx` file. The columns are domain, name, home page,
    tile opens, subdomains, only these pages, no photos or videos, and only the domain is needed. A header row is
    optional. **Add to the list** updates sites already on it, and **Replace the list** removes the rest. You can also
    download the list as a spreadsheet, or a template.
  - **List settings:** the start page, how often phones check for changes, and **Delete** (not for Everyone).

**Phones.** One card per phone: its name, model, lists and ad setting. Phones without a name are listed first.
- **Tapping a phone** lets you change its **Name**, the **Lists it uses** (tap to switch each on or off), where its
  approved requests go, and **Ads** (Usual / Blocked / Allowed). At the bottom:
  - **Block this phone:** it stays listed but can't open any site, for a lost phone or one that shouldn't be used.
    Its lists are set aside, and **Unblock this phone** gives exactly those back. Its card shows **Blocked**.
    While it's blocked, approving its requests changes nothing (the bot says it's blocked).
  - **Archive now:** does straight away what the daily check does after a phone is unused for a while. The phone moves
    to **Archived phones** with its name and settings, and its own list goes into the archive with it. **Restore**
    brings both back. If it's still being used, it's restored by itself the next day.
- **Archived phones:** **Restore**, **Give to another phone** (after a factory reset), or **Delete for good**.
- **Add a phone by its ID:** found on the phone under ⋮ → About this phone.

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
| `noMedia` | Sites or pages that open without photos and videos. If any of a phone's lists includes a page, it applies on that phone. | `[]` |
| `temporary` | Temporary access. `what`: `site`, `page` or `media` (photos and videos on). `mode`: `clock` (from `from`) or `use` (time on it, within 7 days). `minutes`: how long. Easiest to add on the admin page. | none |
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
| **Requests, notes and your replies** | Private repo (issues) | **Only you** |
| **Phone names, models, dates, archive** | Private repo (`devices.json`) | **Only you** |
| The lists (which sites each list allows or blocks) | Both. The private copy is the real one, the public copy is what phones read | Anyone with the link |
| Which lists each phone ID uses (`phones.json`, **no names**) | Public repo | Anyone with the link |
| The status page and the admin page | Public repo | Anyone can open them, but the admin page does nothing without your token |
| App code and releases | Public repo | Anyone |
| Signing key file | Public repo | Anyone, but it's locked by your password |
| Secrets and tokens | Repo settings | Nobody, not even you |

**Why part of it has to stay public:** GitHub Pages (where phones read their lists) is only free on public
repositories, and on paid plans the published site is public anyway. And the app downloads its lists and updates
without logging in: doing that from a private repository would need a password built into the app, and anyone
could dig it out.

**What's still public, then:** the site lists themselves, and phone IDs. The IDs are random codes that don't say
whose phone it is. List names are visible too. A phone's own list is named after its ID (e.g. `k7m4-q2xp`), so no
names show there either. Lists you name yourself (like `year-5`) show as you named them.
- **Set up before own lists used phone IDs?** Lists named after people (like `emma`) can be renamed in one go:
  admin page → **Settings** → **Privacy** → **Hide names in list names**. The public copies with names are removed
  at the next publish. (Their earlier versions stay in the public repository's history.)

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
- **Moving from the old `com.example.whitelistbrowser`** (versions before this one): on each phone, uninstall
  the old app (**Settings** → **Apps** → **Whitelist Browser** → **Uninstall**), then install the new one from the
  link in setup step 8. The phone keeps its ID, so it keeps its lists and name on the admin page. It asks for the
  person's name again, and the in-app admin needs your token again.

### Small screens
The app, its dialogs, the home page, the blocked page and the admin page all work on small phones, down to about
2.8-inch screens (240 × 320 on Android's size scale).
- **On screens narrower than a typical phone,** **Forward** and **Reload** move into the **⋮** menu, so the top bar
  has room for the site's name.
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
| The update banner says the update failed, after the package name change | Expected once: old versions (`com.example.whitelistbrowser`) can't update to the new name. Uninstall and install the new one (see [The app's package name](#the-apps-package-name)). |
| **Publish list** fails (public repo) | Settings → Pages → Source must be **GitHub Actions** (setup step 2). |
| **Publish lists** fails (private repo) | Check the `PUBLIC_REPO_TOKEN` secret there: it needs Contents: Read and write on the public repo, and not to have expired (setup step 5). |
| Any other build failure | Open the failed run, copy the red error text and ask for help with it. |
| A phone isn't in the **Phones** section | Requests must be set up for phones to register themselves, so check `REQUESTS_TOKEN`. Otherwise add it by ID (⋮ → About this phone). |
| A phone doesn't get a list's sites | Check its lists under **Phones** (tap the phone) and that you tapped **Save**. On the phone, **⋮ → About this phone** shows the lists it's using. |
| An allowed site loads but looks broken, like missing videos, maps or buttons | Two common causes. **Embedded content** (videos, maps, sign-in boxes) from sites that aren't on your lists is blocked: to allow it on this site, turn on **Allow content embedded from other sites** on the site's screen (see [Embedded content](#embedded-content-from-other-sites)). Or a **filter** may be stopping something it needs: try turning **Ads** (or a content filter) off for that phone to confirm, then add the domain to **Never block these** and turn it back on. |
| A temporary site closed early, or stayed open too long | **From now** runs on the clock. **Only while it's open on the phone** counts on-screen time, in steps of 15 seconds, within 7 days. Check which you chose under **Temporary access**. |
| Pictures or videos are missing on a page | It's on a **No photos or videos** list (the status line says so). Take it off in the admin page, or ask from the phone with **⋮ → Ask for photos and videos**. |
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
  phones.json                  Which lists each phone ID uses, and ad settings. No names.
  .source                      Which private version the copies came from
  admin.html                   The admin page
  index.html                   Status page: current list and app download link
  sites-template.csv           Spreadsheet template for bulk import
app/                           The Android app
  src/main/java/.../Config.kt          Your settings
  src/main/java/.../PrivateRepo.kt     The private repository's name (the public one's + "-private")
  src/main/java/.../MaxHeightScrollView.kt  Keeps dialogs' buttons on screen on small phones
  src/main/java/.../MainActivity.kt    Browser screen, blocking, menu, requests, permissions
  src/main/java/.../AdminActivity.kt   The admin page inside the app (7 taps on the name at the top)
  src/main/java/.../Whitelist.kt       Downloading and combining the phone's lists, checking addresses
  src/main/java/.../Device.kt          The phone's ID and the name typed on it
  src/main/java/.../HomePage.kt        Serves the home page with tiles
  src/main/java/.../Requests.kt        Sends requests, registration and check-ins to the private repo
  src/main/java/.../SiteCheck.kt       Checks a typed site exists before asking for it
  src/main/java/.../ExternalLinks.kt   Sends tel:, mailto:, app links etc. to their own app
  src/main/java/.../AdBlock.kt         Ad and tracker blocking
  src/main/java/.../MediaBlock.kt      "No photos or videos" mode
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
.github/workflows/migrate.yml  "Move my data (run once)"
.github/last-check             Touched monthly by the daily check so GitHub keeps it running
.github/scripts/handle-request.js    The logic behind all four
```
