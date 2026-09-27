// Handles what the app sends as GitHub issues:
//  - "New phone" registrations: the phone is added to docs/devices.json with the default lists.
//  - Site requests: open or block, just a page or the whole site.
// Replies understood (from the repo owner only):
//   name Emma        set or change the phone's name (on "New phone" and request issues)
//   same as Emma     on a "New phone" issue: this is a reinstall of archived phone Emma, give it back its lists
//   approve          do what was asked, in the phone's own private list (made at registration; made now if missing),
//                    unless the admin page says approvals for this phone go elsewhere
//   approve hidden   same, but no home page tile (open requests)
//   approve page / approve whole site      change the scope while approving
//   approve public / approve personal / approve <list name>   choose which list changes
//   approve no media / approve media only / approve with media / approve fully   photos and videos
//   approve without pass-throughs   leave out the addresses the link passes through on the way
//   approve for 30m / for 1h30m        open it only for a while (from now); add "use" to count only time on it
//   approve always   make it permanent (when they asked for a while)
//   deny [reason]    say no; the reason (if any) is shown on the phone
// Every final reply carries a hidden "whitelist-response" note the phone reads to tell the user the answer.
const fs = require('fs');
const DEVICES = 'docs/devices.json';
const listPath = name => name === 'public' ? 'docs/whitelist.json' : `docs/lists/${name}.json`;

function normalize(entry) {
  let s = String(entry || '').trim().toLowerCase();
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, '').replace(/^\*\./, '');
  s = s.split('/')[0].split('?')[0].split(':')[0].replace(/\.$/, '');
  return /^[a-z0-9.-]+$/.test(s) && s.includes('.') ? s : null;
}

// Page address in comparable form (same rules as the app): no scheme or #part,
// host lower-case without www./m./mobile., then path and query.
function pageKey(entry) {
  let s = String(entry || '').trim();
  if (!s) return null;
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('#')[0];
  let cut = s.search(/[/?]/); if (cut < 0) cut = s.length;
  const host = s.slice(0, cut).toLowerCase().split(':')[0].replace(/\.$/, '').replace(/^(www|m|mobile)\./, '');
  if (!/^[a-z0-9.-]+$/.test(host) || !host.includes('.')) return null;
  let rest = s.slice(cut) || '/';
  if (rest.startsWith('?')) rest = '/' + rest;
  return host + rest;
}
// A page entry covers that page and everything below it.
function pageMatches(key, entry) {
  if (!key.startsWith(entry)) return false;
  if (key.length === entry.length || '/?&='.includes(entry.slice(-1))) return true;
  return '/?&#'.includes(key[entry.length]);
}
const isPageEntry = e => e.includes('/');
const bare = d => d.replace(/^(www|m|mobile)\./, '');
const under = (host, domain) => host === domain || host.endsWith('.' + domain);

// The listed site that covers this host (respecting "subdomains": false), if any.
const siteFor = (sites, host) => sites.find(s => {
  const d = normalize(s.domain);
  if (!d) return false;
  if (host === d) return true;
  if (s.subdomains !== false) return host.endsWith('.' + d);
  return host === 'www.' + d || (d.startsWith('www.') && host === d.slice(4));
});

function loadDevices() {
  const d = fs.existsSync(DEVICES) ? JSON.parse(fs.readFileSync(DEVICES, 'utf8')) : {};
  if (!Array.isArray(d.default)) d.default = ['public'];
  if (!d.devices || typeof d.devices !== 'object') d.devices = {};
  if (!d.archived || typeof d.archived !== 'object') d.archived = {};
  return d;
}
const saveJson = (path, data) => {
  fs.mkdirSync(require('path').dirname(path), { recursive: true });
  fs.writeFileSync(path, JSON.stringify(data, null, 2) + '\n');
};

function load(name) {
  const path = listPath(name);
  if (!fs.existsSync(path)) return { refreshMinutes: 5, sites: [], block: [] };
  const data = JSON.parse(fs.readFileSync(path, 'utf8'));
  if (!Array.isArray(data.sites)) data.sites = [];
  if (!Array.isArray(data.block)) data.block = [];
  return data;
}

// How a phone is shown in messages: its name, or its model and ID if it has none.
const label = (d, id) => (d && d.name) ? d.name : `${(d && d.model) || 'phone'} ${id} (no name)`;
const slug = t => String(t || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30);
const existingLists = () => ['public', ...(fs.existsSync('docs/lists') ? fs.readdirSync('docs/lists').filter(f => f.endsWith('.json')).map(f => f.slice(0, -5)) : [])];

// ---- Archiving phones that stopped checking in, and bringing them back ----
const ARCHIVE = 'docs/lists/archive';
const today = () => new Date().toISOString().slice(0, 10);

// Moves the phone to "archived". Its private lists (used by no other phone, not a list for new phones)
// move to docs/lists/archive/. Nothing is deleted.
function archivePhone(devices, id, lastSeen) {
  const d = devices.devices[id];
  const privateLists = (d.lists || []).filter(l => l !== 'public' && !devices.default.includes(l) &&
    !Object.entries(devices.devices).some(([other, o]) => other !== id && (o.lists || []).includes(l)));
  fs.mkdirSync(ARCHIVE, { recursive: true });
  const moved = [];
  privateLists.forEach(l => {
    if (fs.existsSync(`docs/lists/${l}.json`)) { fs.renameSync(`docs/lists/${l}.json`, `${ARCHIVE}/${l}.json`); moved.push(l); }
  });
  devices.archived[id] = { ...d, lastSeen, archivedOn: today(), archivedLists: moved };
  delete devices.devices[id];
  return moved;
}

// Gives archived phone `fromId`'s name and lists to phone `toId` (the same phone, or a reinstall).
function restorePhone(devices, fromId, toId) {
  const a = devices.archived[fromId];
  const renamed = {};
  (a.archivedLists || []).forEach(l => {
    let name = l, n = 2;
    while (existingLists().includes(name)) name = `${l}-${n++}`; // a list with that name was made meanwhile
    if (fs.existsSync(`${ARCHIVE}/${l}.json`)) fs.renameSync(`${ARCHIVE}/${l}.json`, `docs/lists/${name}.json`);
    renamed[l] = name;
  });
  const lists = (a.lists || []).map(l => renamed[l] || l).filter(l => existingLists().includes(l));
  const target = devices.devices[toId] || { lists: [], registered: today() };
  const { lastSeen, archivedOn, archivedLists, ...kept } = a;
  devices.devices[toId] = {
    ...kept, ...target,
    name: target.name || a.name || '',
    model: target.model || a.model,
    lists: [...new Set([...(target.lists || []), ...lists])],
    ...(fromId !== toId ? { previousIds: [...(a.previousIds || []), fromId] } : {})
  };
  if (!target.requestsTo && a.requestsTo) devices.devices[toId].requestsTo = a.requestsTo;
  delete devices.archived[fromId];
  return devices.devices[toId];
}

// A new phone: its name (as typed on the phone), the lists for new phones, and its own private list
// straight away, named after the person (so sites can be added to it before they ask for anything).
const cleanName = n => String(n || '').replace(/[`*_<>\n\r]/g, '').trim().slice(0, 40);
function newPhone(devices, id, model, name, registered) {
  const d = { name: cleanName(name), model, lists: [...devices.default], registered };
  const base = slug(d.name) || slug(id) || 'phone';
  let list = base, n = 2;
  while (existingLists().includes(list)) list = `${base}-${n++}`;
  saveJson(listPath(list), { refreshMinutes: 5, sites: [], block: [] });
  d.lists.push(list);
  devices.devices[id] = d;
  return d;
}

// Finds an archived phone by name or ID.
function findArchived(devices, text) {
  const t = text.trim().toLowerCase();
  return Object.keys(devices.archived).find(id => id.toLowerCase() === t || (devices.archived[id].name || '').toLowerCase() === t);
}

// Would this phone (all its lists together) open this host / page?
// Allowed if any list allows it, blocked if any list blocks it.
function phoneAllows(lists, host, page) {
  const sites = lists.flatMap(l => l.sites), block = lists.flatMap(l => l.block);
  const matching = sites.filter(s => siteFor([s], host));
  if (!matching.length) return false;
  if (block.some(e => !isPageEntry(e) && under(host, normalize(e) || '#'))) return false;
  if (!page) return true;
  if (block.some(e => isPageEntry(e) && pageMatches(page, e))) return false;
  if (matching.some(s => !Array.isArray(s.pages) || !s.pages.length)) return true;
  return matching.some(s => s.pages.map(pageKey).some(p => p && pageMatches(page, p)));
}

// ---- The changes ----

function openSite(data, domain, hidden) {
  const before = data.block.length;
  // Remove anything on the blocked list under this site, sites and pages alike.
  data.block = data.block.filter(e => !under(isPageEntry(e) ? e.split('/')[0] : bare(normalize(e) || ''), bare(domain)));
  const unblocked = data.block.length < before;
  const listed = siteFor(data.sites, domain);
  if (listed) {
    if (Array.isArray(listed.pages) && listed.pages.length) {
      delete listed.pages;
      return `All of **${listed.domain}** is now allowed.`;
    }
    return unblocked ? `**${domain}** is unblocked.` : `**${domain}** was already allowed.`;
  }
  data.sites.push({ domain, name: domain.replace(/^www\./, ''), home: !hidden });
  return `**${domain}** is now allowed${hidden ? ' (no home page tile)' : ' and on the home page'}.`;
}

function openPage(data, domain, page, hidden) {
  const before = data.block.length;
  data.block = data.block.filter(e => !(isPageEntry(e) && (pageMatches(page, e) || pageMatches(e, page))));
  const unblocked = data.block.length < before;
  const listed = siteFor(data.sites, domain);
  if (listed) {
    if (Array.isArray(listed.pages) && listed.pages.length) {
      if (listed.pages.map(pageKey).some(p => pageMatches(page, p))) {
        return unblocked ? `The page **${page}** is unblocked.` : `The page **${page}** was already allowed.`;
      }
      listed.pages.push(page);
      return `The page **${page}** is now allowed.`;
    }
    return unblocked ? `The page **${page}** is unblocked.` : `**${page}** was already allowed (the whole site is).`;
  }
  data.sites.push({ domain, name: domain.replace(/^www\./, ''), home: !hidden, pages: [page] });
  return `Only the page **${page}** is now allowed${hidden ? '' : ', with a home page tile'}.`;
}

function blockSite(data, domain) {
  const i = data.sites.findIndex(s => normalize(s.domain) === domain || bare(normalize(s.domain) || '') === bare(domain));
  if (i >= 0) {
    const d = data.sites[i].domain;
    data.sites.splice(i, 1);
    return `**${d}** is removed from the list, so it's blocked.`;
  }
  if (siteFor(data.sites, domain) && !data.block.includes(domain)) {
    data.block.push(domain);
    return `**${domain}** is now blocked (the rest of the site stays allowed).`;
  }
  return `**${domain}** was already blocked.`;
}

// ---- Pass-throughs: addresses a link goes through before its destination ----
// What to allow for one pass-through: just that address, without its "?..." part (which changes per link),
// unless that would leave only the front page, which would open the whole site.
function passKey(url) {
  const k = pageKey(url);
  if (!k) return null;
  const base = k.split('?')[0];
  return base.endsWith('/') && base.indexOf('/') === base.length - 1 ? k : base;
}

// ---- Temporary access ("temporary" in a list) ----
// { id, what: "site" | "page" | "media", entry, mode: "clock" | "use", minutes, from }
//   clock: open for `minutes` from `from`.   use: `minutes` of time actually spent on it, within 7 days.
const USE_WINDOW_DAYS = 7;
function parseDuration(text) {
  let total = 0;
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m)(?![a-z])/g)) {
    total += parseFloat(m[1]) * (m[2].startsWith('h') ? 60 : 1);
  }
  return Math.round(total);
}
function fmtMinutes(min) {
  const h = Math.floor(min / 60), m = min % 60;
  return [h && `${h} hour${h === 1 ? '' : 's'}`, m && `${m} minute${m === 1 ? '' : 's'}`].filter(Boolean).join(' ');
}
function tempEnd(t) {
  const from = Date.parse(t.from) || 0;
  return from + (t.mode === 'use' ? USE_WINDOW_DAYS * 1440 : Number(t.minutes) || 0) * 60000;
}
function addTemporary(data, what, entry, mode, minutes) {
  data.temporary = (Array.isArray(data.temporary) ? data.temporary : []).filter(t => !(t.what === what && t.entry === entry));
  data.temporary.push({ id: 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5), what, entry, mode, minutes,
    from: new Date().toISOString() });
}
// Removes expired temporary access from a list. Returns how many were removed.
function dropExpired(data, now = Date.now()) {
  if (!Array.isArray(data.temporary)) return 0;
  const before = data.temporary.length;
  data.temporary = data.temporary.filter(t => tempEnd(t) > now);
  if (!data.temporary.length) delete data.temporary;
  return before - (data.temporary ? data.temporary.length : 0);
}

// ---- Photos and videos ("noMedia": sites or pages that open without them) ----
function mediaOff(data, entry) {
  if (!Array.isArray(data.noMedia)) data.noMedia = [];
  if (data.noMedia.includes(entry)) return `Photos and videos were already off for **${entry}**.`;
  data.noMedia.push(entry);
  return `Photos and videos are now off for **${entry}**.`;
}
function mediaOn(data, scope, domain, page) {
  const list = Array.isArray(data.noMedia) ? data.noMedia : [];
  const kept = scope === 'site'
    ? list.filter(e => !under(isPageEntry(e) ? e.split('/')[0] : bare(normalize(e) || ''), bare(domain)))
    : list.filter(e => !(isPageEntry(e) && (pageMatches(page, e) || pageMatches(e, page))));
  data.noMedia = kept;
  return kept.length < list.length;
}
// Which of these lists (name -> data) still turns photos and videos off for this host / page, if any?
function mediaOffBy(named, host, page) {
  for (const [name, d] of named) {
    const hit = (d.noMedia || []).find(e => isPageEntry(e)
      ? (page && pageMatches(page, e))
      : under(host, normalize(e) || '#'));
    if (hit) return { name, entry: hit };
  }
  return null;
}

function blockPage(data, domain, page) {
  const listed = siteFor(data.sites, domain);
  if (!listed) return `**${page}** was already blocked.`;
  if (Array.isArray(listed.pages) && listed.pages.length) {
    const exact = listed.pages.findIndex(p => pageKey(p) === page);
    if (exact >= 0) {
      if (listed.pages.length > 1) {
        listed.pages.splice(exact, 1);
        return `The page **${page}** is removed from the list, so it's blocked.`;
      }
      data.sites.splice(data.sites.indexOf(listed), 1);   // it was the only page: remove the site
      return `The page **${page}** was the only one allowed on **${listed.domain}**, so the site is removed.`;
    }
  }
  if (data.block.includes(page)) return `**${page}** was already blocked.`;
  data.block.push(page);
  return `The page **${page}** is now blocked (the rest of the site stays as it was).`;
}

// ---- Main ----

// ---- Saving safely ----
// Several runs can happen at once (many phones registering, requests and approvals together).
// Each attempt starts from the very latest files, makes the change with `mutate`, and pushes.
// If another run saved in between, the push is refused and we simply try again from their version,
// waiting a random, growing moment so runs retrying together spread out. Gives up after 5 minutes.
async function saveWithRetry({ exec, github, owner, repo, branch }, message, mutate) {
  await exec.exec('git', ['config', 'user.name', 'github-actions[bot]']);
  await exec.exec('git', ['config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com']);
  const deadline = Date.now() + 5 * 60 * 1000;
  for (let attempt = 1; ; attempt++) {
    await exec.exec('git', ['fetch', '--quiet', 'origin', branch]);
    await exec.exec('git', ['reset', '--quiet', '--hard', `origin/${branch}`]);
    const out = await mutate();
    await exec.exec('git', ['add', '-A', 'docs']);
    if (fs.existsSync('.github/last-check')) await exec.exec('git', ['add', '.github/last-check']);
    if (await exec.exec('git', ['diff', '--cached', '--quiet'], { ignoreReturnCode: true }) === 0) return out; // nothing changed
    await exec.exec('git', ['commit', '--quiet', '-m', typeof message === 'function' ? message(out) : message]);
    if (await exec.exec('git', ['push', '--quiet', 'origin', `HEAD:${branch}`], { ignoreReturnCode: true }) === 0) {
      // Pushes made by workflows don't start other workflows, so start "Publish list" directly.
      await github.rest.actions.createWorkflowDispatch({ owner, repo, workflow_id: 'pages.yml', ref: branch });
      return out;
    }
    if (Date.now() > deadline) throw new Error('Could not save for 5 minutes: the repository kept changing. Reply again to retry.');
    const wait = Math.min(15000, 500 * 2 ** Math.min(attempt, 5)) * (0.5 + Math.random());
    await new Promise(r => setTimeout(r, wait));
  }
}

const PHONE_ID = /^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$/;

module.exports = async ({ github, context, core, exec }) => {
  const { owner, repo } = context.repo;
  const issue = context.payload.issue;
  const number = issue.number;

  // Only requests made with the owner's key (i.e. from the app) count. Strangers' issues are ignored.
  if (issue.author_association !== 'OWNER') return;

  const m = (issue.body || '').match(/<!-- whitelist-request\s*([\s\S]*?)-->/);
  if (!m) return;
  let req;
  try { req = JSON.parse(m[1]); } catch { return; }
  const comment = body => github.rest.issues.createComment({ owner, repo, issue_number: number, body });
  const close = reason => github.rest.issues.update({ owner, repo, issue_number: number, state: 'closed', state_reason: reason });
  const branch = context.payload.repository.default_branch;
  const save = (message, mutate) => saveWithRetry({ exec, github, owner, repo, branch }, message, mutate);

  // "name Emma" (or "nickname Emma") from the owner: set the phone's name.
  async function handleNickname(body, id, closeAfter) {
    const mm = String(body || '').trim().match(/^(?:nickname|nick|name)\s*[:=]?\s+(.{1,40})$/i);
    if (!mm) return false;
    const nick = mm[1].trim().replace(/[`*_<>]/g, '');
    const found = await save(`Name ${id}: ${nick}`, () => {
      const devs = loadDevices();
      if (!devs.devices[id]) return false;
      devs.devices[id].name = nick;
      saveJson(DEVICES, devs);
      return true;
    });
    if (!found) { await comment('I can\'t find this phone in the phone list. Set the name in the admin page.'); return true; }
    await comment(`✅ This phone's name is now **${nick}**.` + (closeAfter ? '' : ' Now reply `approve` or `deny` to the request.'));
    if (closeAfter) await close('completed');
    return true;
  }

  // ---- A phone registering itself (or its check-in record) ----
  if (req.type === 'register') {
    const id = String(req.id || '').toUpperCase();
    if (!PHONE_ID.test(id)) return;
    if (context.eventName !== 'issues') {
      // Replies on a "New phone" issue: a name, or "same as <archived phone>".
      const c = context.payload.comment;
      if (issue.state !== 'open' || c.author_association !== 'OWNER') return;
      const same = String(c.body || '').trim().match(/^(?:same as|restore|reinstall of)\s+(.{1,40})$/i);
      if (same) {
        const wanted = same[1].replace(/[`*]/g, '');
        const out = await save(o => `Phone ${id} is a reinstall of ${o && o.from}: restore its lists`, () => {
          const devs = loadDevices();
          const from = findArchived(devs, wanted);
          if (!from) return null;
          const d = restorePhone(devs, from, id);
          saveJson(DEVICES, devs);
          return { from, d };
        });
        if (!out) { await comment(`I can't find an archived phone called **${wanted}**. Check the admin page → **Archived phones**.`); return; }
        await comment(`✅ This phone is now **${label(out.d, id)}** again, with its lists: **${out.d.lists.join(', ') || 'none'}**.`);
        await close('completed');
        return;
      }
      await handleNickname(c.body, id, true);
      return;
    }
    await github.rest.issues.addLabels({ owner, repo, issue_number: number, labels: ['new phone'] }).catch(() => {});
    const model = String(req.model || 'Phone').slice(0, 60);
    const out = await save(o => o.kind === 'restored' ? `Restore phone ${id} (used again)` : `Register phone ${id} (${model})`, () => {
      const devices = loadDevices();
      if (devices.devices[id]) return { kind: 'known' };
      if (devices.archived[id]) {
        const d = restorePhone(devices, id, id);
        saveJson(DEVICES, devices);
        return { kind: 'restored', d };
      }
      // The phone registers itself as soon as it's online; "installed" is the day it was set up (maybe offline).
      const installed = /^\d{4}-\d{2}-\d{2}$/.test(String(req.installed || '')) ? req.installed : today();
      const d = newPhone(devices, id, model, req.name, installed);
      saveJson(DEVICES, devices);
      return { kind: 'new', devices, d };
    });
    if (out.kind === 'known') {
      // Already known: this is just its check-in record. File it away quietly.
      await close('completed');
      return;
    }
    if (out.kind === 'restored') {
      await comment(`@${owner} 📱 **${label(out.d, id)}** is being used again, so it's back from the archive ` +
        `with its lists: **${out.d.lists.join(', ') || 'none'}**.`);
      await close('completed');
      return;
    }
    // A reinstall keeps its ID, so a new ID means a factory reset, another user profile or another phone.
    // Archived phones of the same model might be this one.
    const devices = out.devices;
    const lookalikes = Object.entries(devices.archived).filter(([, a]) => (a.model || '').toLowerCase() === model.toLowerCase());
    const others = Object.entries(devices.archived).filter(([aid]) => !lookalikes.some(([l]) => l === aid));
    let reinstall = '';
    if (lookalikes.length || others.length) {
      reinstall = '\n\n**Is this an archived phone after a factory reset, or its replacement?** Reply `same as` and its name to give it back its name and lists:\n' +
        [...lookalikes, ...others].slice(0, 8).map(([aid, a]) =>
          `- \`same as ${a.name || aid}\`: ${a.model || 'phone'}, last seen ${String(a.lastSeen || '?').slice(0, 10)}` +
          (lookalikes.some(([l]) => l === aid) ? ' ← same model' : '')).join('\n');
    }
    const d = out.d;
    const admin = `https://${owner.toLowerCase()}.github.io/${repo}/admin.html`;
    await comment(`📱 New phone registered: ${d.name ? `**${d.name}** (${model})` : `**${model}**`}, ID \`${id}\`.\n` +
      `It uses: **${d.lists.join(', ') || 'no lists'}**.\n\n` +
      (d.name
        ? `They entered their name as **${d.name}**. To change it, reply \`name …\` or edit it on the [admin page](${admin}) → **Phones**.`
        : `**Give it a name so you know whose it is.** Reply, for example, \`name Emma\`, or type it under **New phones** on the [admin page](${admin}).`) +
      reinstall);
    if (d.name && !reinstall) await close('completed'); // named already: nothing to do
    return; // otherwise stays open until it has a name (or is matched to an archived phone)
  }

  // ---- A site request ----
  const domain = normalize(req.domain);
  const action = req.action === 'block' ? 'block' : 'allow';
  if (!domain) return;
  const page = pageKey(req.url);
  const hasPage = !!page && page.split('/').slice(1).join('/') !== '';
  const asked = req.scope === 'page' && hasPage ? 'page' : 'site';
  // Photos and videos: 'off' = open without them / block only them; 'on' = turn them back on.
  const askedMedia = req.media === 'off' ? 'off' : req.media === 'on' ? 'on' : '';
  // Asked "just for a while": minutes (0 = always). Only for opening, and photos and videos back on.
  const askedMinutes = req.action !== 'block' ? Math.max(0, Math.min(24 * 60 + 55, Number(req.minutes) || 0)) : 0;
  const describe = (scope, media = askedMedia) => {
    const where = scope === 'page' ? `just this page: **${page}**` : `the whole site: **${domain}**`;
    if (media === 'on') return `Turn photos and videos back on for ${where}`;
    if (media === 'off') return action === 'allow' ? `Open ${where}, without photos and videos` : `Block only the photos and videos on ${where}`;
    return `${action === 'allow' ? 'Open' : 'Block'} ${where}`;
  };
  const deviceId = String(req.device || '').toUpperCase();

  // A phone that's archived (clearly in use again) is restored; one that isn't registered is registered,
  // so it can have its own list.
  if (PHONE_ID.test(deviceId)) {
    await save(`Register or restore phone ${deviceId} (from a request)`, () => {
      const devs = loadDevices();
      if (devs.devices[deviceId]) return;
      if (devs.archived[deviceId]) restorePhone(devs, deviceId, deviceId);
      else newPhone(devs, deviceId, String(req.model || 'Phone').slice(0, 60), req.name, today());
      saveJson(DEVICES, devs);
    });
  }

  // Addresses the link passed through on the way (allow requests from a stopped link), and which of
  // them this phone couldn't open.
  const hops = (Array.isArray(req.hops) ? req.hops : []).slice(0, 10).map(passKey).filter(Boolean);
  function missingHops(lists) {
    return [...new Set(hops)].filter(k => !phoneAllows(lists, k.split('/')[0], k));
  }

  // What the phone looks like now, and where approvals go.
  function phoneInfo() {
    const devices = loadDevices();
    const device = devices.devices[deviceId];
    const phoneLists = device ? (device.lists || []) : devices.default;
    const phoneName = device ? label(device, deviceId) : 'a phone';
    const personal = phoneLists.find(l => l !== 'public');
    const usersOf = name => Object.values(devices.devices).filter(d => (d.lists || []).includes(name)).length;
    // By default the phone's own private list ('own' = made when first needed), unless the admin page
    // says otherwise for this phone or for all phones.
    const setting = (device && device.requestsTo) || devices.requestsTo || 'own';
    const defaultTarget = !device ? 'public'
      : setting === 'own' ? (personal || 'own')
      : (existingLists().includes(setting) ? setting : (personal || 'own'));
    return { devices, device, phoneLists, phoneName, personal, usersOf, defaultTarget };
  }

  // ---- New request: label it and post instructions (this comment is what notifies you) ----
  if (context.eventName === 'issues') {
    const { devices, device, phoneLists, phoneName, personal, usersOf, defaultTarget } = phoneInfo();
    const describeTarget = t => t === 'own'
      ? `a new private list just for **${phoneName}** (made now)`
      : t === 'public' ? `the **public** list (every phone using it: ${usersOf('public')})`
      : `**${t}**` + (t === personal ? ` (${phoneName}'s private list)` : ` (used by ${usersOf(t)} phone${usersOf(t) === 1 ? '' : 's'})`);
    await github.rest.issues.addLabels({ owner, repo, issue_number: number, labels: ['site request'] }).catch(() => {});
    let text = `${askedMedia === 'on' ? '🖼️' : action === 'allow' ? '🟢' : '🔴'} ${describe(asked)}\n` +
      `📱 From **${phoneName}**, which uses: ${phoneLists.join(', ') || 'no lists'}\n` +
      (req.unverified ? `⚠️ **The phone couldn't find ${domain} when asking.** It may be a typo, or a site the phone's network hides. Check it before approving.\n` : '') + '\n' +
      `Approving changes ${describeTarget(defaultTarget)}.\n\n` +
      'Reply:\n- `approve` to do this\n' +
      (action === 'allow' && askedMedia !== 'on' ? '- `approve hidden` to do this without a home page tile\n' : '') +
      '- `deny` to say no\n\nYou can add to `approve`:\n';
    if (hasPage) text += `- \`${asked === 'page' ? 'whole site' : 'page'}\` for ${asked === 'page' ? `all of ${domain}` : `just ${page}`}\n`;
    if (askedMedia !== 'on') {
      text += action === 'allow'
        ? (askedMedia === 'off' ? '- `with media` to open it with photos and videos\n' : '- `no media` to open it without photos and videos\n')
        : (askedMedia === 'off' ? '- `fully` to block it completely instead\n' : '- `media only` to only block its photos and videos\n');
    }
    text += defaultTarget === 'public'
      ? (device ? '- `personal` to change only this phone (its own private list)\n' : '')
      : '- `public` to change the public list for everyone instead\n';
    if (action === 'allow' && askedMedia !== 'on' && hops.length) {
      const lists = phoneLists.map(n => load(n));
      const missing = missingHops(lists);
      text += `\n🔀 **This link passes through ${hops.length} other address${hops.length === 1 ? '' : 'es'} on the way:**\n` +
        hops.map((k, i) => `${i + 1}. \`${k}\`` + (missing.includes(k) ? ' (not allowed yet)' : ' (already allowed)')).join('\n') + '\n' +
        (missing.length
          ? `\n\`approve\` also allows ${missing.length === 1 ? 'that address' : 'those addresses'} (just ${missing.length === 1 ? 'it' : 'them'}, with no home page tile), so the link works. ` +
            'Add `without pass-throughs` to leave them out.\n'
          : '');
    }
    if (action === 'allow') {
      const tempMode = devices.tempMode === 'use' ? 'use' : 'clock';
      const how = tempMode === 'use' ? 'of time on it' : 'from when you approve';
      text += askedMinutes
        ? `\n⏱ **Asked for ${fmtMinutes(askedMinutes)} only.** \`approve\` gives ${fmtMinutes(askedMinutes)} ${how}. ` +
          'Change the time with e.g. `approve for 1h`, add `use` to count only time spent on it, or `approve always` to make it permanent.\n'
        : '\n⏱ To open it **only for a while**: e.g. `approve for 30m` or `approve for 1h30m`, and add `use` to count only time spent on it.\n';
    }
    if (device && !device.name) text += '\n💡 This phone has no name. Reply `name Emma` (for example) to set it.\n';
    // The easiest way to answer: buttons on the admin page, opened at this request.
    text += `\n👉 **[Answer it on the admin page](https://${owner.toLowerCase()}.github.io/${repo}/admin.html?request=${number})** ` +
      'with a tap: Approve, For a while (hour and minute wheels) or Deny.\n';
    await comment(text + '\nPhones pick up the change within a few minutes.');
    return;
  }

  // ---- A reply ----
  if (issue.state !== 'open') return;
  const c = context.payload.comment;
  if (c.author_association !== 'OWNER') return;
  if (await handleNickname(c.body, deviceId, false)) return;
  const text = c.body.trim().toLowerCase();
  const words = text.split(/[\s,.!]+/).filter(Boolean);
  const word = (words[0] || '').replace(/[^a-z]/g, '');
  // The answer the phone shows the person who asked, in a hidden note at the end of the reply.
  const answer = (outcome, message) =>
    `\n\n<!-- whitelist-response\n${JSON.stringify({ outcome, message })}\n-->`;
  if (['deny', 'denied', 'no', 'reject'].includes(word)) {
    // Anything after "deny" is the reason, e.g. "deny too distracting in class".
    const reason = c.body.trim().replace(/^\S+\s*/, '').replace(/^(because|as|:|-|,)\s*/i, '').replace(/[`<>]/g, '').slice(0, 200);
    const shown = reason && !/[.!?]$/.test(reason) ? reason + '.' : reason;
    await comment(`❌ Denied. Nothing changed.${reason ? ` Reason given to the phone: *${reason}*` : ''}` +
      answer('denied', 'Not approved' + (shown ? `: ${shown}` : '.')));
    await close('not_planned');
    return;
  }
  if (!['approve', 'approved', 'yes', 'ok', 'allow', 'block'].includes(word)) return; // an ordinary comment

  const hidden = /\b(hidden|hide)\b/.test(text);
  // Temporary: "approve for 30m", "approve 1h use", or what they asked for; "approve always" = permanent.
  let minutes = askedMinutes;
  const said = parseDuration(text);
  if (said) minutes = Math.min(24 * 60 + 55, said); // up to 24 hours 55 minutes
  if (/\b(always|permanent|permanently|forever)\b/.test(text)) minutes = 0;
  if (action === 'block') minutes = 0;
  const tempMode = /\b(use|using|used|screen)\b/.test(text) ? 'use'
    : /\b(clock|now)\b/.test(text) ? 'clock'
    : (loadDevices().tempMode === 'use' ? 'use' : 'clock');
  let scope = asked;
  if (/\b(whole|site|all|everything)\b/.test(text)) scope = 'site';
  else if (/\bpage\b/.test(text) && hasPage) scope = 'page';
  let media = askedMedia;
  if (askedMedia !== 'on') {
    if (/\b(no media|without media|media only|no photos|text only)\b/.test(text)) media = 'off';
    else if (/\b(with media|fully|completely)\b/.test(text)) media = '';
  }

  // The whole change, made on the latest files (and made again if someone else saved first).
  function applyApproval() {
    const { devices, device, personal } = phoneInfo();
    let phoneLists = device ? (device.lists || []) : devices.default;

    // Which list to change.
    let target = phoneInfo().defaultTarget;
    const named = words.slice(1).find(w => existingLists().includes(w));
    if (/\b(public|everyone|everybody)\b/.test(text)) target = 'public';
    else if (named) target = named;
    else if (/\b(personal|private|phone|own)\b/.test(text)) target = personal || 'own';
    if (target === 'own') {
      if (!device) return { error: 'This request didn\'t come from a known phone, so it can\'t have a private list. Reply `approve public` to change the public list.' };
      // Make this phone's private list, named after its name (or ID), and give it to the phone.
      const base = slug(device.name) || slug(deviceId) || 'phone';
      target = base; let n = 2;
      while (existingLists().includes(target)) target = `${base}-${n++}`;
      saveJson(listPath(target), { refreshMinutes: 5, sites: [], block: [] });
      device.lists = [...(device.lists || []), target];
      phoneLists = device.lists;
      saveJson(DEVICES, devices);
    }

    const data = load(target);
    const where = scope === 'page' ? page : domain;

    // Temporary: add a time-limited grant instead of changing the list for good.
    if (action === 'allow' && minutes > 0) {
      const what = media === 'on' ? 'media' : scope;
      addTemporary(data, what, where, tempMode, minutes);
      if (media === 'off') mediaOff(data, where);
      let result = `**${where}**: ${what === 'media' ? 'photos and videos on' : 'open'} for **${fmtMinutes(minutes)}**` +
        (tempMode === 'use' ? ` of time spent on it (within ${USE_WINDOW_DAYS} days).` : ' from now.');
      if (media === 'off') result += ' Without photos and videos.';
      if (hops.length && !/\bwithout pass/.test(text)) {
        const lists = [...new Set([...phoneLists, target])].map(n => n === target ? data : load(n));
        const added = missingHops(lists);
        added.forEach(k => openPage(data, k.split('/')[0], k, true));
        if (added.length) result += `\n\nPass-throughs allowed so the link works: ${added.map(k => `\`${k}\``).join(', ')}.`;
      }
      saveJson(listPath(target), data);
      return { target, result, temporary: true };
    }

    // Photos and videos back on: take matching "noMedia" entries off, then check the phone's other lists.
    if (media === 'on') {
      const removed = mediaOn(data, scope, domain, page);
      saveJson(listPath(target), data);
      let result = removed ? `Photos and videos are back on for **${where}**.` : `Photos and videos weren't off for **${where}** in this list.`;
      const still = mediaOffBy([...new Set([...phoneLists, target])].map(n => [n, n === target ? data : load(n)]), domain, scope === 'page' ? page : null);
      if (still) result += `\n\n⚠️ They're still off on this phone: the **${still.name}** list turns them off for **${still.entry}**` +
        (still.name === target && scope === 'page' ? '. Reply `approve whole site` to turn them back on for the whole site.' : '. Change it in the admin page.');
      return { target, result, stillBlocked: !!still };
    }
    // Block only photos and videos: the page stays open.
    if (action === 'block' && media === 'off') {
      const result = mediaOff(data, where);
      saveJson(listPath(target), data);
      return { target, result };
    }

    let result = action === 'allow'
      ? (scope === 'page' ? openPage(data, domain, page, hidden) : openSite(data, domain, hidden))
      : (scope === 'page' ? blockPage(data, domain, page) : blockSite(data, domain));
    if (action === 'allow' && media === 'off') result += ' ' + mediaOff(data, where);

    // Pass-throughs: allow just those addresses (no tiles), so the link works end to end.
    if (action === 'allow' && hops.length && !/\bwithout pass/.test(text)) {
      const lists = [...new Set([...phoneLists, target])].map(n => n === target ? data : load(n));
      const added = missingHops(lists);
      added.forEach(k => openPage(data, k.split('/')[0], k, true));
      if (added.length) result += `\n\nPass-throughs allowed so the link works: ${added.map(k => `\`${k}\``).join(', ')}.`;
    }
    saveJson(listPath(target), data);

    let stillBlocked = false;
    // Lists combine, so check the phone's overall result and fix it up in the target list.
    const phoneData = [...new Set([...phoneLists, target])].map(n => n === target ? data : load(n));
    const nowAllowed = phoneAllows(phoneData, domain, scope === 'page' ? page : null);
    if (action === 'block' && nowAllowed) {
      // Another list still allows it: a block entry in this list wins for this phone.
      const entry = scope === 'page' ? page : domain;
      if (!data.block.includes(entry)) { data.block.push(entry); saveJson(listPath(target), data); }
      const others = phoneLists.filter(n => n !== target).join(', ');
      result = `**${entry}** is now blocked for this phone. It's added to **${target}**'s blocked list, ` +
        `because the **${others}** list allows it` + (target !== 'public' ? ' (other phones using that list are unaffected).' : '.');
    }
    if (action === 'allow' && !nowAllowed && phoneLists.includes(target)) {
      const blocker = phoneLists.find(n => n !== target && load(n).block.some(e =>
        isPageEntry(e) ? (page && pageMatches(page, e)) : under(domain, normalize(e) || '#')));
      if (blocker) result += `\n\n⚠️ The **${blocker}** list blocks it, and blocks win, so this phone still can't open it. Remove it from **${blocker}**'s blocked list in the admin page.`;
      if (blocker) stillBlocked = true;
    }
    if (!phoneLists.includes(target) && device) {
      result += `\n\nNote: this phone doesn't use the **${target}** list, so it won't see this change.`;
      stillBlocked = true;
    }
    return { target, result, stillBlocked };
  }

  const out = await save(o => `${describe(scope, media).replace(/\*\*/g, '')}${minutes ? ` for ${fmtMinutes(minutes)}` : ''} in list ${o.target || '-'} (request #${number})`, applyApproval);
  if (out.error) { await comment(out.error); return; }

  // What the phone tells the person who asked, in plain words.
  const what = scope === 'page' ? `The page ${page}` : domain;
  let phoneMessage =
    media === 'on' ? `Photos and videos are back on for ${scope === 'page' ? `the page ${page}` : domain}.`
    : action === 'block' && media === 'off' ? `Photos and videos are now off on ${scope === 'page' ? `the page ${page}` : domain}.`
    : action === 'block' ? `${what} is now blocked.`
    : media === 'off' ? `${what} can now be opened, without photos and videos.`
    : `${what} can now be opened.`;
  if (minutes > 0 && action === 'allow') {
    const what2 = scope === 'page' ? `the page ${page}` : domain;
    phoneMessage = (media === 'on' ? `Photos and videos are on for ${what2}` : `${what} can be opened`) +
      ` for ${fmtMinutes(minutes)}` + (tempMode === 'use' ? ' of time spent on it.' : ', starting now.') +
      (media === 'off' ? ' (Without photos and videos.)' : '');
    if (askedMinutes && minutes !== askedMinutes) phoneMessage += ` (You asked for ${fmtMinutes(askedMinutes)}.)`;
  } else if (askedMinutes && action === 'allow') {
    phoneMessage += ' (Permanently, not just for a while.)';
  }
  if (scope !== asked) phoneMessage += scope === 'site' ? ' (The whole site was approved, not just the page.)' : ' (Only that page was approved, not the whole site.)';
  if (media === '' && askedMedia === 'off') phoneMessage += ' (With photos and videos.)';
  if (out.stillBlocked) phoneMessage += media === 'on'
    ? ' They may still be off because of another setting. Ask whoever manages this browser.'
    : ' It may still be blocked by another setting. Ask whoever manages this browser.';

  await comment(`✅ Done in the **${out.target}** list. ${out.result}\n\nPhones pick up the change within a few minutes.` +
    answer('approved', phoneMessage));
  await close('completed');
};

// ---- Daily check (phones.yml): archive phones that stopped checking in, restore ones that came back ----
module.exports.checkPhones = async ({ github, context, core, exec }) => {
  const { owner, repo } = context.repo;
  const branch = context.payload.repository ? context.payload.repository.default_branch : 'main';

  // When each phone was last seen: its check-in records (the "new phone" issues).
  const issues = await github.paginate(github.rest.issues.listForRepo, { owner, repo, labels: 'new phone', state: 'all', per_page: 100 });
  const seen = {};
  issues.forEach(i => {
    const mm = (i.body || '').match(/<!-- whitelist-request\s*([\s\S]*?)-->/);
    if (!mm) return;
    let r; try { r = JSON.parse(mm[1]); } catch { return; }
    const id = String(r.id || '').toUpperCase();
    const t = Date.parse(r.lastSeen || i.created_at);
    if (id && t && (!seen[id] || t > seen[id])) seen[id] = t;
  });
  const now = Date.now();

  const out = await saveWithRetry({ exec, github, owner, repo, branch },
    o => o.archived.length || o.restored.length ? `Daily phone check: ${o.archived.length} archived, ${o.restored.length} restored` : 'Daily phone check',
    () => {
      const devices = loadDevices();
      const days = Math.max(1, Number(devices.inactiveDays) || 14);
      const archived = [], restored = [];
      for (const [id, d] of Object.entries(devices.devices)) {
        const last = seen[id] || Date.parse(d.registered || '') || now; // a phone added by hand counts from its date
        if (now - last > days * 86400000) {
          const lists = archivePhone(devices, id, new Date(last).toISOString());
          archived.push({ id, d, last, lists });
        }
      }
      for (const [id, a] of Object.entries(devices.archived)) {
        if (seen[id] && seen[id] > Date.parse(a.archivedOn || 0) + 86400000) {
          restored.push({ id, d: restorePhone(devices, id, id) });
        }
      }
      if (archived.length || restored.length) saveJson(DEVICES, devices);
      // Temporary access that has run out is removed from every list.
      for (const name of existingLists()) {
        if (!fs.existsSync(listPath(name))) continue;
        const data = JSON.parse(fs.readFileSync(listPath(name), 'utf8'));
        if (dropExpired(data)) saveJson(listPath(name), data);
      }
      // Keep scheduled runs alive: GitHub pauses them in repos with no commits for 60 days.
      const stamp = '.github/last-check';
      const lastStamp = fs.existsSync(stamp) ? Date.parse(fs.readFileSync(stamp, 'utf8').trim()) : 0;
      if (now - (lastStamp || 0) > 30 * 86400000) {
        fs.mkdirSync('.github', { recursive: true });
        fs.writeFileSync(stamp, new Date().toISOString() + '\n');
      }
      return { days, archived, restored };
    });

  if (!out.archived.length && !out.restored.length) { core.info('No phones to archive or restore.'); return; }

  // One notification for everything found today.
  const { days, archived, restored } = out;
  let body = `@${owner}\n\n`;
  if (archived.length) {
    body += `### 📦 Not seen for ${days} days\nThese phones haven't opened Whitelist Browser in ${days} days: probably uninstalled, or just not being used. ` +
      'They are **archived**. Nothing was deleted: their private lists moved to `docs/lists/archive/`.\n\n' +
      archived.map(x => `- **${label(x.d, x.id)}** (\`${x.id}\`), last seen ${new Date(x.last).toISOString().slice(0, 10)}` +
        (x.lists.length ? `, private lists archived: ${x.lists.join(', ')}` : ', no private lists')).join('\n') +
      '\n\n**If a phone is used again, or the app is reinstalled,** it keeps its ID and comes back automatically with its lists.\n' +
      '**After a factory reset, or on a replacement phone,** it has a new ID and shows up as a new phone. Reply `same as <name>` on its "New phone" notification, ' +
      'or use the admin page → **Archived phones** → **Give to phone**.\n\n';
  }
  if (restored.length) {
    body += '### 📱 Back from the archive\n' +
      restored.map(x => `- **${label(x.d, x.id)}** is being used again. Lists: ${x.d.lists.join(', ') || 'none'}`).join('\n') + '\n';
  }
  const title = archived.length
    ? `Phones archived: ${archived.map(x => x.d.name || x.id).join(', ')}`
    : `Phones back: ${restored.map(x => x.d.name || x.id).join(', ')}`;
  const created = await github.rest.issues.create({ owner, repo, title, body, labels: ['phone check'] });
  await github.rest.issues.update({ owner, repo, issue_number: created.data.number, state: 'closed', state_reason: 'completed' });
};
