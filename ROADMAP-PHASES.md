# Wtube — Multi-Phase Advanced Feature Plan

> Derived from the full audit in [`ANALYSIS.md`](ANALYSIS.md). Every item below is anchored to
> code that already exists in `mytube-web/` — nothing here is a wish-list fantasy.
> 7 phases · 38 features · ordered by value-per-effort, not by novelty.

---

## How to read this

**The strategic problem with Wtube today.** The app has three strong engines (web shell, native
player, downloader) and **no middle layer**. You can browse YouTube *in a WebView* and you can
play *one video natively* and you can *download that video* — but there is no native way to
**search**, **browse channels**, **see a playlist as data**, or **find anything you've already
saved**. Everything between "the web page" and "the player" is a page load away.

That middle layer is where all the competitive value is, and it's the single biggest unlock
available. It also removes your app's largest recurring liability: your entire DEVLOG is a record
of YouTube changing its DOM out from under `inject.js`. Native InnerTube calls don't have that
problem — they break on *schema* changes, which are rare, testable, and recoverable.

**What makes this app different from NewPipe/LibreTube/ReVanced:** it owns the *whole* pipeline —
the raw ExoPlayer, the native muxer/encoder, the WebView session, the ad-block engine, and a
local history. That combination enables a class of features no competitor has, because they all
run a thin player over a thin downloader. Phase 5 exists specifically to exploit that.

### Phase summary

| Phase | Theme | Features | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| **0** | Stabilize | 8 items | ~1 week | — | — |
| **1** | Native discovery | 6 | 2–3 wks | Med | 0 |
| **2** | Player 2.0 | 8 | 2–3 wks | Med | 0, 1 |
| **3** | Downloader 2.0 | 9 | 3–4 wks | High¹ | 0, 2 |
| **4** | Local library | 8 | 2–3 wks | Med | 0, 1 |
| **5** | On-device intelligence | 6 | 3–4 wks | Med | 1, 2, 4 |
| **6** | Transparency & privacy | 6 | 1–2 wks | Low | 0 |
| **7** | Platform & release | 6 | 2–4 wks | Med | 0 |

¹ Phase 3's risk is concentrated in the native rebuild (NDK), not in the Java.

**Total:** ~16–24 weeks of focused work for one developer. Phases 0, 1, 2, 4 and 6 are
independent of each other and can run in parallel across multiple people.

---

## Phase 0 — Stabilize (mandatory, ~1 week)

> You cannot build advanced features on a codebase with 168 silently-swallowed exceptions, two
> god classes, zero tests, and a downloader that corrupts files. This phase is not optional
> ceremony — every later phase is *cheaper* after it and *more expensive* without it.

| # | Item | Anchor | Why now |
|---|---|---|---|
| 0.1 | Fix the 4 P0 defects (truncation race, null-Intent NPE, FGS violation, service-never-stops) | `DownloadService.java:1171`, `PlayerService.java:247,258`, `DownloadService.java:122` | Ships broken data to users |
| 0.2 | 8 unit tests (6 pure-JVM, 2 Robolectric) | ANALYSIS §10 | 3 of 4 P0s would have been caught |
| 0.3 | Replace ~168 empty `catch` blocks with `StreamStore.note()`; narrow `Throwable`→`Exception` | all files | Silent failures are why you debug by reading source |
| 0.4 | Split `buildPlayback()` (595 lines) into 6 sub-builders | `PlayerActivity.java:1084` | Every Phase 2 feature touches this file |
| 0.5 | Extract `MTApp` bridge → `MtBridge` class + origin check | `MainActivity.java:2458` | Phase 1 adds many bridge methods |
| 0.6 | Delete duplicate `assets/libwtcodec.so` + `NativeLoader` assets fallback | `NativeLoader.java:43` | −2.1 MB APK (~40% of payload) |
| 0.7 | Idempotent cosmetic-CSS injection (stable marker node) | `MainActivity.java:302` | DOM bloat + 200 KB JS per navigation |
| 0.8 | `signing.properties` (git-ignored) + checksum-verified downloads + `verify-apk.sh` | `build.sh:129` | Reproducible builds; scripts the checks you do by hand 40+ times |

**Exit criteria:** `./build.sh` works from a clean clone on two machines; `verify-apk.sh` is green;
the 8 tests pass; zero empty catch blocks remain outside deliberate best-effort helpers.

---

## Phase 1 — Native Discovery (2–3 weeks) ★ highest value

> **The unlock.** Replace WebView page loads with native InnerTube calls for everything
> *discoverable*. The `Resolver` already speaks InnerTube over POST and already has a working
> multi-client fallback chain — it just only calls 4 endpoints. Adding 5 more is the same
> technique, not a new one.

### Endpoints to add (all POST to `youtubei.googleapis.com/youtubei/v1/`)

| Endpoint | Client | Gives you | Already have a parser for? |
|---|---|---|---|
| `/search` | ANDROID or WEB | Full text search + suggestions | No — new |
| `/browse` | WEB / ANDROID | Channel pages, playlists | No — new |
| `/guide` (navigation) | WEB | Sidebar categories | No — new |
| `/subscription` | ANDROID / MWEB | Subscriptions feed | No — new |
| `/notifications` | WEB | Notifications feed | No — new |
| `/playlist` (via `/browse`) | WEB | Playlist items | No — new |

`Resolver.post()` (`Resolver.java:570`) already sends `X-Goog-Api-Format-Version: 2` and a
per-client `context`; each new endpoint = a new `xxxBody()` helper (copy the pattern from
`webNextBody()`, `Resolver.java:147`) + a new parser + fixtures.

### Features

| # | Feature | Detail | Value |
|---|---|---|---|
| 1.1 | **Native search screen** | Instant results, type-ahead suggestions, filters (All / Videos / Channels / Playlists / Shorts / Live), recent searches, zero WebView | ★★★★★ |
| 1.2 | **Channel page** | Banner + avatar + tabs (Videos / Shorts / Live / Playlists / About), subscriber count, subscribe toggle, "Latest" sort | ★★★★★ |
| 1.3 | **Playlist page** | Full item list, total duration, shuffle, **"Download all"** (feeds Phase 3), remove-from-list (if synced) | ★★★★★ |
| 1.4 | **Subscriptions feed** | Newest-first grid, unread markers, "continue watching" row | ★★★★☆ |
| 1.5 | **Live-now detection** | `isLive`/`isUpcoming` flags from the browse response → a "Live" tab and a red badge | ★★★☆☆ |
| 1.6 | **5th nav tab** | Home / Shorts / Subs / Search / Library — Search is the natural 4th, Library 5th | ★★★☆☆ |

### Technical notes

- **Pagination** uses `continuationCommand.token` — you already parse that pattern for comments
  (`Resolver.java:359`). Reuse it.
- **Schema tolerance** is mandatory. Write parsers that look for *several* possible renderer
  shapes (`lockupViewModel` vs `videoRenderer` vs `compactVideoRenderer`) and skip unknown ones
  instead of throwing. Your `deepFind()` helper (`Resolver.java:307`) is the right tool.
- **Fixtures are mandatory.** Save one real response per endpoint per client shape into
  `mytube-web/tools/fixtures/` and assert against them. YouTube will change these schemas
  several more times; fixtures turn "the app broke" into "one test failed".
- **Keep the WebView as a fallback.** If native search returns nothing, open the web search
  results — same philosophy as your existing resolve fallback chain.
- **New files:** `Search.java`, `Browse.java`, `SearchActivity.java`, `ChannelActivity.java`,
  `PlaylistActivity.java`, `tools/fixtures/*.json`.

---

## Phase 2 — Player 2.0 (2–3 weeks)

> You own a real ExoPlayer. That's the expensive part, and it unlocks a lot.

| # | Feature | Detail | Anchor / effort |
|---|---|---|---|
| 2.1 | **Storyboard scrub preview** ★ | YouTube-style thumbnail that follows the seek bar. Parse `playerStoryboardSpecRenderer.spec` from the player response (currently **ignored** — `Resolver.takeTitle` only reads `videoDetails`). Then request `M0.jpg`/`M1.jpg` sheets, slice the sprite by index, and draw into a `SeekBar` overlay. | New `Storyboard.java`; needs a tiny sprite-index math + an LRU image cache. ~4–5 days |
| 2.2 | **Chapter thumbnails** | Combine 2.1 with the chapter list → each chapter row shows its frame | Reuses 2.1 |
| 2.3 | **Skip silence** | Media3 `SilenceSkippingAudioProcessor` (already in `media3-exoplayer`) → auto-jump over silent gaps in lectures/podcasts. Toggle in the More menu. | ~1 day, big UX win |
| 2.4 | **Audio waveform + scrub-by-waveform** | Decode a short PCM window via the existing `MediaCodec` audio path (you already do this for MP3) → draw a waveform into the seek bar; drag on the waveform to seek | ~3–4 days |
| 2.5 | **Smart default quality** | Today `defQ()` is a fixed 360/480/720/1080 setting (`PlayerActivity.java:728`). Replace with: device max decode capability (`MediaCodecList` codec-info query) + screen height + metered-network profile. Auto-pick the best ≤ device max. | ~2 days |
| 2.6 | **Codec awareness (HEVC/AV1)** | `Resolver.pickArr()` hard-filters to `mime.contains("mp4") && contains("avc")` (`Resolver.java:940`) — so HEVC and AV1 variants are *never even considered*, even on devices that decode them natively. Add a device-capability filter and prefer HEVC/VP9/AV1 when supported (smaller file, same quality). | ~2 days; needs a codec-info query helper |
| 2.7 | **Live streams (HLS)** | `media3-exoplayer-hls` is **already in your build** (`build.sh:55`) and `takeHls()` already collects `hlsManifestUrl` (`Resolver.java:835`) — so HLS playback partly works. Add: live badge, DVR seek window, reconnection/backoff, "live edge" button, and prefer HLS for `isLive` content. | ~3 days |
| 2.8 | **"Stats for nerds" panel** | Resolution, dropped frames, buffer health, bitrate, decoder, load time. Cheap, and your audience (tech-savvy YouTube users) loves it. | ~1 day |

**Sequencing note:** 2.1 (storyboard) is the single most "wow" feature in this phase and the most
requested by users of every client. Do it first, even though 2.3/2.5 are cheaper.

---

## Phase 3 — Downloader 2.0 (3–4 weeks)

> The downloader is the strongest unique asset in the app: a 12-way parallel Range fetcher, a
> real FFmpeg remuxer, a 3-tier MP3 encoder, and a live job queue. It is also currently
> **pointed at one video at a time**. This phase turns it into a library.

| # | Feature | Detail | Effort |
|---|---|---|---|
| 3.1 | **Batch download** ★ | Playlist/Channel page → multi-select → enqueue N jobs. The queue, per-job notification, cancel, pause/resume, and retry all already exist (`DownloadService`); you only need a selection UI and to feed `ACT_START` repeatedly. | ~4 days |
| 3.2 | **Per-video download profiles** | A "default" rule set: max quality, audio-only for podcasts, Wi-Fi-only, max concurrent jobs. Already partly present (`defQ`, `wifiOnly`, `fastdl`). Promote to a real profile editor. | ~2 days |
| 3.3 | **Persistent jobs across restarts** ★ | The job queue is **in-memory only** (`DownloadService.queue`/`active`). Serialize the queue to disk; on service start, restore and resume with HTTP Range from the byte offset already written. | ~3 days |
| 3.4 | **Clip / trim export** ★ | "Save this moment" — native FFmpeg trim. Your `libwtcodec.so` already links FFmpeg; add a `trim(in, out, outPath, startMs, endMs)` JNI. (Trim can be stream-copy — no re-encode, so it's fast.) | ~4 days + NDK rebuild |
| 3.5 | **Chapter export** | One tap → export a chapter as a standalone file (reuses 3.4) | ~1 day |
| 3.6 | **Audio normalization** | FFmpeg `loudnorm` filter → consistent perceived loudness across downloads. Requires adding the filter to your static FFmpeg build. | ~2 days + NDK rebuild |
| 3.7 | **Opus/WebM audio support** | YouTube serves `audio/webm; codecs=opus` — often the *higher quality* track. Your `pickArr()` only accepts `audio/* + mp4` (`Resolver.java:949`), and MediaMuxer can't mux Opus into MP4. Options: (a) mux into MKV/WebM, (b) decode Opus → PCM → MP3 (you already have that pipeline), (c) keep m4a. | ~3 days |
| 3.8 | **Thumbnail + metadata sidecar** | Write a `.jpg` poster and a `.json` sidecar (title/author/duration/source-id) next to each download, and register the thumbnail in MediaStore so the system gallery shows it. | ~2 days |
| 3.9 | **Auto-generated chapters from captions** | When the description has no timestamps, derive chapter boundaries from caption cue clustering (pause gaps + topic shifts). Your caption parser (`VttSrt`) already produces the cues. | ~3 days |

**Phase 3 requires the NDK.** 3.4/3.6/3.7 need `tools/ffmpeg/` build scripts that are **missing
from the archive** (ANALYSIS §8.2). Recover or rewrite them first — see the Phase 7 note on
making the native layer reproducible.

---

## Phase 4 — Local Library (2–3 weeks)

> `Library.java` stores Watch Later, playlists, and watch history as JSON strings in one
> `SharedPreferences` file called `"mtw"`. That works at 10 videos and falls over at 1,000.
> This phase makes the library a real database and — crucially — makes it **searchable and
> offline-complete**.

| # | Feature | Detail | Effort |
|---|---|---|---|
| 4.1 | **Real database (SQLite/Room)** | Tables: `videos`, `playlists`, `playlist_items`, `watch_history`, `downloads`, `tags`, `captions`. Replace the prefs-JSON. Keeps the existing public API of `Library.java` so call sites don't churn. | ~4 days |
| 4.2 | **Offline metadata cache** ★ | For every video you see, store title/author/thumbnail/duration/description. Right now, opening your library offline shows nothing but IDs — this makes the library **fully browsable with no network**. | ~3 days |
| 4.3 | **Full-text search over your library** | FTS4/FTS5 over titles, descriptions, notes, and **caption transcripts** (see 5.1) | ~3 days |
| 4.4 | **Smart collections** | User-defined rules: "unwatched AND longer than 20 min AND added this week". Evaluate in SQL. | ~3 days |
| 4.5 | **Tags and personal notes** | Per-video tags + free-text notes, surfaced in search | ~2 days |
| 4.6 | **Two-way YouTube sync (opt-in)** | You already have a **logged-in WebView session**. Rather than handling an OAuth token, read the session cookie and call the *web* API for playlist/WL sync. Security note: never surface the cookie to JS; keep it entirely in native code. | ~5 days, high value, **highest ToS risk in the plan — keep opt-in and personal-use-only** |
| 4.7 | **Auto-backup to a user folder** | The v2.0 backup writes a plaintext JSON to `Downloads/WTube/`. Upgrade to a SAF-chosen folder + scheduled backups + retention. | ~2 days |
| 4.8 | **Watch-history graph** | The app already records every watch (`Library.histAdd`). Store the *related-graph* edges too (you already fetch `/next` related for every video!) — this is the raw material for 5.4. | ~1 day |

---

## Phase 5 — On-device Intelligence (3–4 weeks) ★ the differentiator

> Everything here runs **on the device, with no server, no account, and no API keys**. No
> competitor offers this, because no competitor owns the whole pipeline (player + downloader +
> history + transcript). It is also the phase that makes the app *yours* rather than a clone.

| # | Feature | Detail | Effort |
|---|---|---|---|
| 5.1 | **Transcript index** ★ | Fetch and store captions for every video you watch (you already parse VTT and already fetch caption tracks for SRT). Index the full text with timestamps. | ~3 days |
| 5.2 | **"Find the moment"** ★★ | Search *inside* any video: type a phrase → see matching caption lines with timestamps → tap to seek. This is the feature people will screenshot and share. Requires 4.1 + 4.3 + 5.1. | ~4 days |
| 5.3 | **Auto key-moments / summary** | Extractive summarization over the transcript (TF-IDF sentence scoring, pure Java, no ML deps) → a "Key moments" card with jump links. | ~4 days |
| 5.4 | **Local "For You" feed** ★ | You already fetch the `/next` related list for every video you open — that *is* a recommendation graph. Build a local, personalized feed by traversing that graph weighted by your watch affinity (from 4.8). Fully offline-capable, no YouTube recommendation API needed. | ~5 days |
| 5.5 | **Sponsor intelligence** | Pre-compute SponsorBlock segments for your whole Watch Later queue in the background. Show upcoming sponsor markers on the seek bar, and support "skip only if I haven't seen this segment before" (per-video skip memory). | ~3 days |
| 5.6 | **Smart resume** | Resume not just by timestamp but by *context*: if you left during a sponsor block, offer "resume at the content start" instead. | ~2 days |

**5.2 and 5.4 are the two features that justify the whole project.** They are impossible for
NewPipe (no persistent transcript index, no local history graph) and un-shippable for a
web wrapper.

---

## Phase 6 — Transparency, Privacy & Power (1–2 weeks)

> The ad-block audience is the audience that cares about this most — and it is a genuine
> differentiator versus the WebView-based alternatives.

| # | Feature | Detail | Effort |
|---|---|---|---|
| 6.1 | **Network inspector** ★ | Per-domain request log: host, bytes, blocked/allowed, why. `AdBlock.block()` already returns a boolean and you already log to `StreamStore` — extend it to record the reason and serve a live "requests" screen. | ~3 days |
| 6.2 | **Runtime per-site rules** | Tap a domain in the inspector → block/allow it for this session or permanently (writes to the `sWhite`/`sAlways` sets). | ~2 days |
| 6.3 | **Privacy dashboard** | One screen: what leaves the device (list of hosts, with the reason each is contacted), what's stored locally, one-tap wipe. | ~2 days |
| 6.4 | **Isolated "private" WebView profile** | A second WebView with its own cookie/cache jar for private browsing, separate from your signed-in session. | ~3 days |
| 6.5 | **Encrypted backups** | AES-GCM via `javax.crypto` (no new dependency) + a password prompt. | ~2 days |
| 6.6 | **Full data export** | One JSON/CSV export of history, playlists, downloads, tags, notes. | ~1 day |

---

## Phase 7 — Platform & Release (2–4 weeks)

| # | Feature | Detail | Effort |
|---|---|---|---|
| 7.1 | **x86_64 ABI** ★ | You're arm64-only today. An x86_64 slice lets the app run on emulators — which **unblocks automated UI testing**, a precondition for everything else. Also the single biggest contributor to "it doesn't run on my emulator" support tickets. | ~1 day (build.sh change) |
| 7.2 | **CI + release pipeline** | GitHub Actions: build on every push, run the 8+ tests, produce a signed APK/AAB, run `verify-apk.sh`, publish. Today every release is a manual sequence you re-typed 40+ times. | ~3 days |
| 7.3 | **AAB + Play-ready packaging** | `bundle` target, ABI splits, per-app-language, R8 shrinking, resource shrinking. | ~2 days |
| 7.4 | **Modern platform polish** | Material You dynamic color, edge-to-edge on Android 15/16, predictive back, per-app language. | ~3 days |
| 7.5 | **Native rebuild reproducibility** ★ | Recover/write `tools/ffmpeg/build-ffmpeg.sh`, `tools/lame/build-lib.sh`, `unversion.py`; record the exact FFmpeg configure flags; add a `make native` target. Today the `.so` is prebuilt-only. | ~3 days |
| 7.6 | **Open-source release (optional)** | Requires fixing the LGPL/LAME static-linkage issue (ANALYSIS §9) and auditing the uBlock/EasyList filter-data licensing. Big credibility win, real legal work. | ~1 week + legal review |

---

## What I would *not* do

| Idea | Why not |
|---|---|
| **Rewrite the UI in Jetpack Compose** | 12 kLOC of programmatic UI is a real asset — it works, it's fast, and it has zero layout XML. A Compose rewrite is 4–8 weeks and would delay every feature above. *If* you migrate, do it one screen at a time via `ComposeView` interop, never all at once. |
| **Implement YouTube Premium / account token flows** | High ToS risk, high ban risk, and your current token-free client chain is the reason the app works on mobile IPs. Don't touch it. |
| **Ship to Play Store** | The app is an ad-blocker + downloader for a service whose ToS prohibits both. Play Store review will reject it, and the uBlock project has been litigated on exactly this ground. Keep it personal-use / sideloaded, and don't associate it with a commercial entity. |
| **Add a chat/community feature** | Massive scope, zero differentiation, and a large moderation surface. |
| **Add an LLM "ask this video"** | Requires a server or shipping a multi-GB model. 5.2 ("find the moment") gets 80% of the value at 2% of the cost, and stays fully on-device. |

---

## Recommended sequencing — the first 6 weeks

This is the concrete plan I'd start on tomorrow.

| Week | Work | Deliverable |
|---|---|---|
| 1 | **Phase 0.1–0.2** | 4 P0 fixes + 8 unit tests, all green. Ship as v2.01. |
| 2 | **Phase 0.3–0.6** | Logging instead of swallowing; `buildPlayback()` split; `MtBridge` extraction; duplicate `.so` deleted (−2.1 MB). |
| 3 | **Phase 0.7–0.8 + 1.1 groundwork** | CSS injection idempotent; signing + `verify-apk.sh` + CI; InnerTube `/search` endpoint working in a unit test. |
| 4–5 | **Phase 1.1–1.3** | Native search screen, channel page, playlist page. Ship as v2.1. |
| 6 | **Phase 2.1** | Storyboard scrub preview — the single highest-impact "wow" feature. Ship as v2.2. |

**Why this order:** Phase 0 is ~1 week and unblocks everything. Phase 1 is the biggest product
leap. Phase 2.1 is the feature users will notice first. After week 6 you have a client that is
no longer "a WebView with a player" — and every later phase becomes additive rather than
corrective.

---

## Effort summary

| Phase | Items | Est. effort | Ships as |
|---|---|---|---|
| 0 — Stabilize | 8 | ~1 week | v2.01 |
| 1 — Native discovery | 6 | 2–3 weeks | v2.1 – v2.3 |
| 2 — Player 2.0 | 8 | 2–3 weeks | v2.2 – v2.5 |
| 3 — Downloader 2.0 | 9 | 3–4 weeks | v2.6 – v2.9 |
| 4 — Local library | 8 | 2–3 weeks | v3.0 |
| 5 — On-device intelligence | 6 | 3–4 weeks | v3.1 – v3.3 |
| 6 — Transparency & privacy | 6 | 1–2 weeks | v3.4 |
| 7 — Platform & release | 6 | 2–4 weeks | v4.0 / store-ready |

---

## Appendix — capability audit (what the current code already supports)

Useful for scoping: these capabilities **already exist** and are directly reusable by the phases
above.

| Capability | Where | Reused by |
|---|---|---|
| InnerTube POST client + 4-step fallback chain | `Resolver.java:589–690` | 1.x, 2.7 |
| 4 InnerTube endpoints in use | `/visitor_id`, `/player`, `/reel/reel_item_watch`, `/next` | 1.x |
| Continuation-token pagination | `Resolver.java:359` | 1.1, 1.3, comments |
| Tolerant deep JSON search | `Resolver.deepFind()`, `Resolver.java:307` | 1.x, 2.1 |
| HLS support (library + collection + fallback) | `build.sh:55`, `Resolver.java:835`, `PlayerActivity.java:670` | 2.7 |
| MergingMediaSource A/V playback | `PlayerService.java:375` | 2.5, 2.6 |
| MediaSession + audio focus + noisy receiver | `PlayerService.java:144–225` | 2.x, 3.x |
| 12-way Range-parallel downloader + resume | `DownloadService.java:1104–1215` | 3.1, 3.3 |
| 3-tier MP3 encoder (FFmpeg / MediaCodec / LAME) | `DownloadService.java:463–800` | 3.7 |
| Native FFmpeg mux + MP3 in one static `.so` | `libwtcodec.so` (7 JNI exports) | 3.4, 3.6, 3.7 |
| Job queue + per-job notification + pause/resume/retry | `DownloadService.java` | 3.1, 3.2 |
| Live progress snapshots | `DownloadService.Snap` | 3.1, 3.3 |
| VTT/XML caption parsing | `VttSrt.java` | 3.9, 5.1 |
| Caption track collection | `Resolver.capTracks()` | 3.9, 5.1 |
| SponsorBlock + K-anonymity hashing | `Resolver.sbSegs()` | 5.5 |
| Related-list graph (already fetched for every video) | `Resolver.related()` | 4.8, 5.4 |
| Watch history (200 entries) | `Library.histAdd()` | 4.8, 5.4, 5.6 |
| Ad-block engine (53k hosts + 3rd-party + whitelist) | `AdBlock.java` | 6.1, 6.2 |
| googlevideo URL capture + diagnostics log | `StreamStore.java` | 6.1 |
| uBlock-derived cosmetic CSS | `assets/cosmetic_generic.css` | 6.1 |
| Crash log + share | `CrashHandler.java` | 7.2 |
| Backup / restore | v2.0 feature | 4.7, 6.5 |
| Theme engine (dark, AMOLED, per-part hue) | `MainActivity.themeJs()` | 7.4 |
| Programmatic icon rendering (no bitmaps) | `NavTab.java`, `LibIcon` | 2.x, 7.4 |
