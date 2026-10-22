# Wtube 2.0 (vc 77) — Complete Source-Code Analysis

**Subject:** `Wtube_source_2.0.zip.xml` → `mytube-web/` (package `com.mytube.web`)
**Stack:** Java (no Gradle) + Media3/ExoPlayer 1.11.1 + hand-built Android UI + prebuilt `libwtcodec.so` (FFmpeg + LAME, arm64)
**Size:** 12,187 lines Java / 16 classes · 3.4 MB `assets/` · 2.1 MB `libs/` · 729 lines native/C+Python tooling
**Method:** full read of all 16 Java sources, 5 asset blobs, the manifest, `build.sh`, the C/JS/Python tooling, plus ELF inspection of the shipped `.so`.

> **Note on the upload:** the file named `Wtube_source_2.0.zip.xml` is not XML — it is a ZIP archive (PK header). It extracts to a single top-level folder `mytube-web/`.

---

## 0. বাংলায় সারসংক্ষেপ (Executive summary in Bangla)

এটি একটি **সম্পূর্ণ নিজস্ব** YouTube-ক্লায়েন্ট অ্যান্ড্রয়েড অ্যাপ — WebView-ভিত্তিক ব্রাউজিং, নিজস্ব ExoPlayer-ভিত্তিক প্লেয়ার, আর নিজস্ব ডাউনলোড-ইঞ্জিন (মাল্টি-চাংক, MP3, SRT)। কোড-মান **চমৎকার উচ্চ** — ৪১টি ভার্সন, রিজলভার-ফলব্যাক-চেইন, নেটিভ FFmpeg/LAME, uBlock-ফিল্টার — কিন্তু **স্ট্রাকচারাল টেকনিক্যাল-ডেট** ভারী।

**সবচেয়ে গুরুত্বপূর্ণ ৪টি সমস্যা (P0):**

1. **ডাউনলোডে ফাইল-ট্রাঙ্কেশন রেস** — ১২টি চাংক থ্রেড প্রতিটি নিজের `RandomAccessFile.setLength()` কল করে, ফলে ডাউনলোড ৩MB+ ফাইলে ইন্টারলিভ-করে **ফাইলের অংশ মুছে যেতে পারে** (ভিডিও নষ্ট/শূন্য-বাইট)।
2. **`PlayerService` — null-Intent NPE** — `START_STICKY` সার্ভিস প্রসেস-মারা হলে null Intent-এ রিস্টার্ট হয় → `i.getStringExtra()` → NPE → ক্র্যাশ-লুপ।
3. **`PlayerService` — ফোরগ্রাউন্ড-বাধ্যতা ভাঙা** — `BgService`/`DownloadService`-এ v1.15-এ ঠিক করা যে বাগ এখানে বাকি → `ForegroundServiceDidNotStartInTimeException`।
4. **`DownloadService` — কিউ-ডিলিটে সার্ভিস আটকে থাকে** — FGS-notification স্থায়ী, কোনো `stopSelf()` হয় না।

**সবচেয়ে বড় কোড-কোয়ালিটি সমস্যা:** ~৩৩০টি `catch (Throwable)` ব্লক, যার মধ্যে **~১৬৮টি সম্পূর্ণ ফাঁকা** (ত্রুটি চুপচাপ swallowed)। এটাই DEVLOG-এ বারবার "কোড পড়ে বাগ খুঁজে পাওয়া" — মূল কারণ।

বিস্তারিত, লাইন-সূচিত, নিচে।

---

## 1. What the software actually is

A **YouTube front-end for Android** with three independent engines that the author wrote from scratch:

| Engine | Where | What it does |
|---|---|---|
| **Web shell** | `MainActivity` + `assets/inject.js` + `assets/core.js` | WebView hosting `m.youtube.com`, DOM rebrand, cosmetic ad-hiding, click-capture, account scraping |
| **Native player** | `PlayerActivity` + `PlayerService` (Media3/ExoPlayer 1.11.1) | Real playback: merged A/V, quality switching, audio-only, PiP, captions, chapters, SponsorBlock, equalizer, gesture UX, watch-page with related/comments |
| **Downloader** | `DownloadService` + `libwtcodec.so` | 3-job queue, 12-way Range-parallel fetch, FFmpeg remux, LAME/hardware MP3, VTT→SRT, MediaStore output |

Everything is **programmatic UI** — there are no XML layouts at all. `res/` contains only `strings.xml` (one string), `colors.xml` (one colour), an adaptive icon and 10 PNG densities. The whole UI is `LinearLayout`/`FrameLayout` built in Java and drawn with `Canvas` (icons in `NavTab`, `LibIcon`, `IconButton`, `PowerIcon`, `PlayIcon`).

**Non-obvious design decision worth calling out:** the author never used Gradle/AGP. `build.sh` drives `aapt2` → `javac` → `d8` → `zip` → `uber-signer` directly. This makes the build ~7 seconds and dependency-free, but it is also the source of several release-engineering problems (§8).

---

## 2. Architecture

### 2.1 Component map

```
com.mytube.web
├── MainActivity .............. WebView shell, nav bar, library/profile/settings UI,
│                               ad-block intercept, offline overlay, JS bridge (MTApp)
├── PlayerActivity ............ Own player UI (4,091 lines — 34% of the codebase)
│   └── attaches to ↓
├── PlayerService ............. FGS + ExoPlayer owner, MediaSession, notification,
│                               next/prev queue, sleep timer, audio-only mode
├── DownloadService ........... FGS download engine, job queue, notifications, snapshots
├── BgService ................. Legacy web-player background audio (MediaPlayer)
├── Resolver .................. InnerTube client: VISIONOS → ANDROID-reel → IOS → ANDROID → WEB
├── AdBlock ................... uBlock-derived host/path filter engine
├── StreamStore ............... In-memory googlevideo URL capture + diagnostic log
├── Library ................... Watch Later / Playlists / watch history (SharedPreferences "mtw")
├── DownloadsSheet ............ In-app downloads screen (5 collapsible sections)
├── VttSrt .................... WEBVTT / timedtext-XML → SRT converter
├── NativeLoader .............. libwtcodec.so loader (libdir → assets fallback)
├── Ffmpeg / Lame ............. JNI bridges to the static native lib
├── NavTab / CrashHandler ..... Custom-drawn nav icon; crash log writer
```

### 2.2 Playback flow (video tap → audio out)

```
User taps video in WebView
  └─ inject.js  click-capture (capture phase)  → preventDefault
     └─ MTApp.openVideo(id, title)            [JS bridge]
        └─ MainActivity.routeVideo()  → startActivity(PlayerActivity)
           └─ PlayerActivity.startYoutube(vid)
              ├─ Library.histAdd()            (unless incognito)
              ├─ loadRelated(vid) ────────────► Resolver.related()  [thread]
              └─ Resolver.resolve(vid) ───────► [thread]
                 │  VISIONOS /player  (primary — yields real googlevideo URLs)
                 │  ANDROID reel_item_watch
                 │  IOS → ANDROID → WEB (fallback chain)
                 │  + captionTracks + chapter timestamps
                 ├─ h.post → startService(PlayerService.ACT_PLAY, v+a+vars+caps)
                 │    └─ PlayerService.loadMerge()  → MergingMediaSource
                 ├─ fetchSbAndDis(vid) ────────► SponsorBlock + RYD  [thread]
                 └─ attachRetry loop (60 × 100 ms) → attach(surface)
```

### 2.3 Download flow

```
PlayerActivity.showSaveMenu()  → quality / MP3 bitrate / subtitle language
  └─ startService(DownloadService.ACT_START, v|a|direct|srt|mp3)
     └─ enqueue() → pump() → 3 parallel jobs
        └─ runJob()
           ├─ srt      → fetchText(&fmt=vtt) → VttSrt.toSrtAny() → .srt
           ├─ mp3      → parFetch(m4a) → Ffmpeg.mp3() | MediaCodec audio/mpeg | LAME | m4a fallback
           ├─ direct   → parFetch → .mp4
           └─ v + a    → parFetch ∥ parFetch → Ffmpeg.mux() | MediaMuxer → .mp4
                         └─ savePublic() → MediaStore Downloads/WTube
```

### 2.4 Ad-blocking pipeline (three independent layers)

| Layer | Mechanism | Effect |
|---|---|---|
| **Network** | `AdBlock.block()` in `shouldInterceptRequest` — 53,375 always-block hosts + 1,767 third-party rules + 830 whitelist + 5 path patterns | DNS/request-level removal of ad hosts |
| **Response rewriting** | `assets/core.js` — global `JSON.parse` trap, `fetch` wrapper, `ytInitialPlayerResponse` property trap | Strips `adPlacements`/`adSlots`/`playerAds` *before* YouTube's own JS parses the payload — the only layer that actually removes video ads |
| **Cosmetic** | `assets/cosmetic_generic.css` (170 KB) + `cosmetic_youtube.css` + `inject.js` | Hides ad slots, badges, promo banners |

This is genuinely the right architecture — the `core.js` response-rewriting approach is the same idea uBlock Origin uses, and the DEVLOG shows the author arrived at it independently after host-blocking alone failed.

---

## 3. Module reference

| File | LOC | Role | Assessment |
|---|---|---|---|
| `PlayerActivity.java` | 4,091 | Player UI + watch page | **God class.** `buildPlayback()` alone is **595 lines** (`:1084–1678`). Holds player, gesture detector, captions, chapters, equalizer, comments, related list, download sheet, quality menu, audio mode — all in one Activity. |
| `MainActivity.java` | 2,605 | Web shell + library/settings/profile | Also a god class: `showSettings()` 389 lines, `showLibraryScreen()` 225, `showProfile()` 190, `setupWeb()` 185. 78 `catch(Throwable)`. |
| `DownloadService.java` | 1,577 | Download engine | Solid structure (Job/Reader/AudioSink abstractions, clean fallbacks). Contains the **P0 truncation race** (§5.1). |
| `Resolver.java` | 1,058 | InnerTube client | Well-organised 4-step fallback chain, good diagnostics. Hardcoded API keys + `post()` never calls `disconnect()`. |
| `PlayerService.java` | 755 | ExoPlayer FGS | Clean MediaSession/audio-focus/noisy-receiver handling. Two **P0 lifecycle bugs** (§5.2, §5.3). |
| `DownloadsSheet.java` | 638 | Downloads screen | Fine. |
| `BgService.java` | 328 | Legacy web-audio FGS | Pre-PlayerService path; still reachable. Its v1.15 `startForeground` fix is the template PlayerService is missing. |
| `StreamStore.java` | 241 | URL capture + log | Fine. `stripSeg()` is a nice catch (segmented MSE URLs are useless to MediaPlayer). |
| `Library.java` | 194 | WL / playlists / history | Fine, simple prefs-JSON. |
| `VttSrt.java` | 186 | Subtitle conversion | Genuinely well done — handles VTT *and* srv1/srv3 XML, tested. |
| `AdBlock.java` | 170 | Filter engine | Clean design, but **whitelist precedence bug** (§5.4). |
| `NavTab.java` | 122 | Custom-drawn nav icons | Nice — zero bitmap assets for icons. |
| `NativeLoader.java` | 92 | `.so` loader | The assets fallback path is **dead weight on modern Android** (§5.8). |
| `CrashHandler.java` | 68 | Crash log | Fine — writes to `filesDir/crash_log.txt`, trims to 60 KB, chains to the default handler. |
| `Lame.java` / `Ffmpeg.java` | 33 / 29 | JNI bridges | Doc comments are **stale** (still say `liblamejni.so`, a separate library — it's one `libwtcodec.so` now). |

---

## 4. What is genuinely strong

This is important — the project is not a toy, and any refactor must preserve these properties.

1. **The resolver fallback chain.** VISIONOS → ANDROID-reel → IOS → ANDROID → WEB, each with full device context, plus HLS-manifest collection at every step and a per-step diagnostic log. The DEVLOG documents the *reasoning* (NewPipe's token-free recipe, IP-based blocking of the ANDROID client) — this is real reverse-engineering work, and the design is right: it degrades instead of failing.
2. **Layered ad blocking.** Host blocking alone cannot kill video ads (they come from `googlevideo`); the author correctly identified that response rewriting is required and built a `JSON.parse` trap + `fetch` wrapper + property trap. This is correct and matches uBlock's design.
3. **Multi-layer MP3 encoding.** FFmpeg-native → device `MediaCodec("audio/mpeg")` → LAME-JNI → save as M4A. The author reached the right conclusion independently: *never lose the user's audio*.
4. **Generation-guard state machine.** `resolveGen` / `relGen` counters prevent async resolve results and auto-next from racing user taps. This is the correct pattern and it's applied consistently.
5. **Diagnostic-first debugging culture.** `StreamStore.note()` / `dump()`, a Diagnostics screen with a share button, crash log sharing, per-step resolve logs. The DEVLOG shows bugs found by *reading* code, not by guessing — a good habit, though it should have been backed by tests.
6. **The native layer is genuinely well-engineered.** Verified from the shipped ELF: exactly **7 JNI exports** (`Ffmpeg`×3, `Lame`×4), `NEEDED = libm.so, libdl.so, libc.so` only (fully bionic-compatible), statically linked FFmpeg 7.1.1 + LAME 3.100 in a single 2.1 MB `.so`. The DEVLOG's account of the `sinh`/`libm` and `iconv`/bionic debugging is accurate.
7. **Graceful degradation everywhere.** Every subsystem has a fallback chain and a user-facing honest error message ("MP3 failed (no encoder) - saved as M4A", "No internet connection", "Stream unavailable").

---

## 5. Findings

Severity: **P0** = data loss / crash / security · **P1** = correctness or reliability · **P2** = performance / maintainability · **P3** = hygiene.

### 5.1 P0 — Multipart downloader truncates the file it is writing

`DownloadService.java:1169–1171`

```java
RandomAccessFile raf = new RandomAccessFile(out, "rw");
try {
    raf.setLength(end + 1);   // ← each of the N chunk threads does this
```

`parFetch()` (`:1091`) spawns up to **12 threads** that all open the *same* output file. Each thread calls `setLength(end + 1)` with **its own** `end`. `setLength()` **truncates** the file when the new length is shorter than the current one.

- Chunk 11 sets length → `size`. Chunk 0 (running slightly later) sets length → `size/12`. The file is truncated to `size/12`, **destroying chunk 11's bytes**.
- Threads then continue writing at their own offsets, leaving a sparse file with zeroed holes.
- The completeness check `if (out.length() < size) throw` **passes** — `length()` is the file size, not the number of *valid* bytes. So a corrupt file is reported as success and handed to the muxer.

This is silent data corruption on exactly the downloads the feature exists for (files ≥ 3 MB use 4 chunks, ≥ 8 MB uses 8, ≥ 16 MB uses 12). It also explains the user's long-standing "download is slow / muxing hangs" complaints in the DEVLOG — the muxer was often fed a file with zeroed regions.

**Fix:** set the length **once, before the threads start**, and never truncate again:

```java
// in parFetch(), before spawning threads:
RandomAccessFile pre = new RandomAccessFile(out, "rw");
pre.setLength(size);          // allocate the full file exactly once
pre.close();
// in fetchRange(): remove raf.setLength(end + 1);  ← delete this line
```

Additionally, verify the result by checking that the bytes at each chunk boundary are non-zero, or track per-chunk completion and fail the job if any chunk is incomplete.

---

### 5.2 P0 — `PlayerService` NPE on null Intent (sticky-restart crash loop)

`PlayerService.java:230–247`

```java
public int onStartCommand(Intent i, int flags, int id) {
    String act = i != null && i.getAction() != null ? i.getAction() : ACT_PLAY;
    ...
    if (ACT_PLAY.equals(act)) {
        String u = i.getStringExtra(EXTRA_URL);   // ← i may be null here
```

`onStartCommand` returns `START_STICKY` (`:244`, `:288`, `:301`). When Android recreates a sticky service after the process is killed, it calls `onStartCommand` with a **null Intent**. The code then defaults `act` to `ACT_PLAY` and immediately dereferences `i` → `NullPointerException`.

`DownloadService` already has the correct guard (`if (i == null) return START_NOT_STICKY;` at `:144`); `PlayerService` does not.

**Fix:** add `if (i == null) { return START_NOT_STICKY; }` at the top of `onStartCommand`.

---

### 5.3 P0 — `PlayerService` violates the foreground-service contract

`PlayerService.java:258` calls `startFg()` **only** inside the `ACT_PLAY` branch. The notification actions (`ACT_TOGGLE`, `ACT_PAUSE`, `ACT_RESUME`, `ACT_NEXT`, `ACT_PREV`) go through `PendingIntent.getService(...)` (`:749–752`), i.e. `startService()`.

On Android 8+, a service started with `startService()`/`startForegroundService()` **must** call `startForeground()` within 5 seconds or the system kills the app with `ForegroundServiceDidNotStartInTimeException`.

This is exactly the bug the author already found and fixed in `BgService` (DEVLOG v1.15) — the fix was never applied to `PlayerService`, which is the service that actually runs the real player.

**Fix:** mirror `BgService.onStartCommand` — call `startFg()` unconditionally at the top of `onStartCommand`, before branching.

---

### 5.4 P0/P1 — Ad-block whitelist overrides the hardcoded ad-host list

`AdBlock.java:79–86`

```java
if (match(sWhite, host)) {
    return false;          // whitelist wins
}
for (int i = 0; i < HOSTS.length; i++) {
    String h = HOSTS[i];
    if (host.equals(h) || host.endsWith("." + h)) {
        return true;       // hardcoded ad host
    }
}
```

`assets/adfilter_white.txt` (830 lines, uBlock-derived) **contains `ad.doubleclick.net`**, which is also in the hardcoded `HOSTS` array (`AdBlock.java:18`). Because the whitelist is evaluated first, the hardcoded rule for `ad.doubleclick.net` is **dead code**, and that host is never blocked.

Verified: of the 18 hardcoded ad hosts, exactly 1 (`ad.doubleclick.net`) is whitelisted.

**Fix:** evaluate the static `HOSTS` list **before** the whitelist, or remove the whitelist entry. The static list is an explicit author decision; it should win.

---

### 5.5 P1 — `DownloadService` never stops after cancelling a *queued* job

`DownloadService.java:122–131`

```java
if (ACT_CANCEL.equals(act)) {
    ...
    main.post(() -> cancelJob(jid));
    return START_NOT_STICKY;      // ← no stopForeground/stopSelf
}
```

`cancelJob()` on a **queued** job removes it from `queue` and returns. It never calls `pump()`. `pump()` is the only place that does:

```java
if (active.isEmpty() && queueEmpty()) { stopForeground(true); stopSelf(); }
```

So cancelling the last queued job leaves the service running as a foreground service forever, with a stale "0 downloading" notification pinned to the shade and a wakelock-class FGS slot held. (Cancelling an *active* job is fine — the job's `finally` block removes it and calls `pump()`.)

**Fix:** call `pump()` at the end of `cancelJob()`.

---

### 5.6 P1 — `savePublic()` writes to public storage without permission on API 24–28

`DownloadService.java:1300–1313`

```java
if (Build.VERSION.SDK_INT >= 29) { ...MediaStore... }
File dir = new File(Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS), "WTube");
```

`minSdkVersion` is 24 and the manifest **does not declare `WRITE_EXTERNAL_STORAGE`**. On Android 7/8/9 (API 24–28) this path throws `SecurityException` — every download on those devices fails at the final save step, after the bytes have already been fetched.

**Fix:** either raise `minSdk` to 29, or declare `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"`, or use `MediaStore.Downloads` on API 29+ and `MediaStore.Files`/SAF on older.

---

### 5.7 P1 — Cosmetic CSS is re-injected on every navigation and accumulates

`MainActivity.java:341–350` (in `onPageStarted` **and** `onPageFinished`):

```java
v.evaluateJavascript(coreJs + themeJs() + injectJs, null);
```

`injectJs` embeds the **170 KB** `cosmetic_generic.css` inside a JavaScript string literal (escaped), and the script creates a **new** `<style>` element and appends it to the document on every evaluation. Nothing removes the previous one (`themeJs()` removes only its own `#__mtt` node).

Result: every navigation adds another ~170 KB `<style>` element to the DOM. A session with 20 navigations accumulates ~3.4 MB of duplicated CSS in the renderer process, and each navigation pays the cost of shipping and parsing a ~200 KB JS payload.

**Fix:** give the cosmetic `<style>` a stable id (`__mtcss`) and remove it before re-inserting, or inject the CSS via `addStyleSheet`/a single style tag created once at document start. Also skip the re-injection entirely if the document already has the current marker.

---

### 5.8 P1 — `assets/libwtcodec.so` is 2.1 MB of dead weight on every supported device

The archive ships the native library **twice**:
- `libs/arm64-v8a/libwtcodec.so` → 2,146,232 bytes
- `assets/libwtcodec.so` → 2,146,232 bytes (identical, copied by `build.sh:90–94`)

The assets copy exists only for `NativeLoader`'s fallback, which does `System.load()` on a file inside `filesDir`. On **Android 10+ (API 29+)** the W^X policy forbids executing code from the app's data directory — `dlopen` of an app-data `.so` is denied by SELinux. The app's `minSdk` is 24 but its `targetSdk` is 36, and the W^X enforcement applies to the device OS regardless of the app's minSdk.

So on every device the app realistically runs on, the fallback **cannot succeed** — and the DEVLOG confirms it: v1.27 identified the real root cause (musl vs. bionic `libm`) and fixed it in the `lib/` copy, after which the assets path was never needed again.

**Fix:** delete the assets copy and the fallback branch in `NativeLoader` (or keep it behind a `Build.VERSION.SDK_INT < 29` guard). That removes ~2.1 MB — roughly **40% of the APK payload** — and simplifies the loader.

---

### 5.9 P1 — `MainActivity.sInstance` is a static Activity reference that is never cleared

`MainActivity.java:2272` sets `sInstance = this` in `onResume()`; `onDestroy()` (`:2367–2379`) clears `ins` but **not** `sInstance`. `sInstance` is then dereferenced at `:2177` (`openInWeb`) and `:1828`.

This keeps the destroyed Activity (and its entire WebView, which holds ~5 MB of renderer state) reachable from a static field for the life of the process.

**Fix:** set `sInstance = null` in `onDestroy()` (and `ins` is already handled).

---

### 5.10 P1 — `Resolver.post()` never releases the connection

`Resolver.java:517–530`

```java
private static JSONObject post(String u, JSONObject body, String ua) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
    ...
    return new JSONObject(read(c.getInputStream()));
}
```

No `c.disconnect()`, and the error path (`code != 200`) throws without closing the stream. Every resolve makes up to **4** of these calls plus 2 `visitor_id` calls, on every video. With HTTP keep-alive the sockets eventually recycle, but the error paths (HTTP 4xx/5xx) leak a connection each time, and `tryClassic()` has the same pattern (`:625–640`).

**Fix:** wrap in try/finally with `c.disconnect()` and drain/close the error stream.

---

### 5.11 P1 — Hardcoded YouTube web API keys

`Resolver.java:566`, `:571` embed the well-known public web-client keys:

```java
"AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",   // IOS
"AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",   // ANDROID
```

These are publicly documented, so this is not a secret leak — but they **rotate**, and the IOS/ANDROID fallback legs are the ones that keep working when VISIONOS breaks. Treat them as volatile config and make the failure mode explicit (the log already does).

---

### 5.12 P2 — ~168 of ~330 `catch (Throwable)` blocks are completely empty

Measured across the 16 sources: `MainActivity` 78, `PlayerActivity` 41, `DownloadService` 41, `BgService` 16, `Resolver` 13, `PlayerService` 12, `DownloadsSheet` 11, and the rest. **~168 catch blocks have an empty body.**

This is the codebase's defining weakness. It is why the DEVLOG is a 1,200-line archaeology of bugs found by reading source: swallowed exceptions mean the only signal is a *user-visible symptom*. A single malformed `JSONObject` in a YouTube response, a transient `IOException`, or a `NullPointerException` in the UI builder all vanish silently.

Concretely: `PlayerActivity` line 910's `onDestroy` calls `eq.release()` inside a `try` — if that throws, the `super.onDestroy()` still runs but the equalizer leaks. `MainActivity`'s `routeVideo` swallows everything, so a failed video launch looks like "tapping does nothing".

**Fix (incremental, not a rewrite):**
1. Make the empty catch blocks *log* instead of swallow — `StreamStore.note("...")` already exists and is cheap.
2. Narrow `Throwable` → `Exception` everywhere except where `OutOfMemoryError`/`StackOverflowError` genuinely need catching.
3. Treat "empty catch in a non-UI method" as a review blocker going forward.

---

### 5.13 P2 — `PlayerActivity.buildPlayback()` is a 595-line method

`PlayerActivity.java:1084–1678`. It constructs the entire player: video box, scrims, centre controls, seek bar, top bar, action row, meta row, related list, comments, description, audio-only view, caption overlay — in one method, inline.

This is unmaintainable and it is why the DEVLOG records "name collision with `curSpeed`/`btnMore`/`showMoreMenu`" and "duplicate `more` icon branch" — inside a 595-line method, duplicate identifiers are invisible to the compiler's readers.

**Fix:** split into `buildVideoBox()`, `buildTopBar()`, `buildControls()`, `buildBottomBar()`, `buildMetaSection()`, `buildRelatedSection()`. Each returns a `View`; `buildPlayback()` composes them. Purely mechanical, no behaviour change.

---

### 5.14 P2 — `Resolver` depends on two different, hard-coded WEB client versions

```java
related()/comments()  →  webNextBody("2.20250926.00.00")
meta()                →  webNextBody("2.20240726.00.00")   // likes live only here
```

The like-count field only exists in the older renderer, so the author pinned two versions. This is inherently brittle: when the older version is retired, `meta()` silently returns empty likes (no exception, no log line).

**Fix:** make the meta parse tolerant of both shapes (the `deepFind()` helper already exists for exactly this) and drop the version pin.

---

### 5.15 P2 — `main.js` `JSON.parse` override is permanent and global

`assets/core.js:60–77` replaces `window.JSON.parse` for the lifetime of the document. Every JSON parse in the page now runs an extra `mtFmt()` + `stripObj()` pass, including for large non-player payloads. `stripObj()` is defensive (try/catch inside), so it degrades safely — but it is a measurable tax on page load and it mutates the page's global namespace, which can break YouTube's own code in subtle ways.

**Fix:** keep it, but early-out cheaply — the current check already only proceeds when `j.streamingData` or ad keys are present, which is correct. The risk is acceptable; add a comment documenting the invariant.

---

### 5.16 P2 — `inject.js` polls every 900 ms forever

`assets/inject.js:137`

```js
setInterval(report, 900);
```

Plus `setInterval` timers for the rebrand loop, the bell, the Shorts audio kick, and the account scanner. Five+ timers running continuously on every page, each doing DOM queries. On a long-lived background tab this is measurable battery drain, and it keeps the renderer "busy" from the WebView's perspective.

**Fix:** replace the 900 ms poll with a `MutationObserver` on `document` (the DEVLOG already uses one elsewhere), and pause all timers on `document.hidden`.

---

### 5.17 P2 — `Library` stores playlists as one `SharedPreferences` string per playlist

`Library.java:151–159` — `key(name)` → `"pl_" + name`, and the whole playlist is one JSON string. Fine for tens of playlists; a 1,000-video playlist serialises and parses the entire array on every read-modify-write (`addToPl`, `removeWl`, …). Reads/writes are not batched with `apply()` transactionally, so a crash mid-sequence can leave the index and the list out of sync.

**Fix (when it matters):** move to a Room/SQLite table, or at minimum debounce and write the index last.

---

### 5.18 P2 — `DownloadService` spawns 24 raw threads per job

`parFetch()` creates `np` (up to 12) `Thread` objects; `downloadMerged()` runs two of those in parallel → **24 threads per job**, ×3 concurrent jobs = **72 threads** for a full queue. Each holds a socket and a 64 KB buffer.

`Executors.newFixedThreadPool(3)` already exists for job-level concurrency; the inner fan-out should use a shared `ExecutorService` (or `WorkManager`) rather than raw threads, so the total connection count is bounded centrally.

**Fix:** replace the inner `Thread[]` with a bounded `ExecutorService` and a `CountDownLatch`.

---

### 5.19 P3 — Stale documentation in the native bridges and README

- `Lame.java:4` — "লাইব্রেরি লোড হয়েছে কি না (NativeLoader.ensure-এর পরে)" but the class javadoc still says `liblamejni.so`; there is no `liblamejni.so` any more.
- `Ffmpeg.java:4` — same, says "liblamejni.so" in the file header comment.
- `build.sh:1` — "# MyTube Web builder" and the default output is `/home/user/MyTubeWeb_v0.1.apk`; the app is now Wtube 2.0.
- `README.md` — says "১৬টি ফাইল" (16 files) which is correct, but the folder map lists `tools/ffmpeg`, `tools/lame` as containing "the JNI C source" while the actual build scripts (`build-ffmpeg.sh`, `build-lib.sh`, `unversion.py`) referenced throughout the DEVLOG are **absent** from the archive.

---

### 5.20 P3 — `Resolver.related()`'s channel-name extraction is fragile

`Resolver.java:94–112` reaches three levels deep into `lockupMetadataViewModel → contentMetadataViewModel → metadataRows[0].metadataParts[0].text.content` with `optJSONObject` chains. Any YouTube renderer change silently yields an empty channel name — again with no error surfaced.

---

## 6. Security & privacy review

### What is done right

| Control | Status |
|---|---|
| Cleartext traffic | **None** — zero `http://` URLs in Java sources; `shouldOverrideUrlLoading` upgrades `http` → `https` |
| `allowBackup` | `false` — correct for an app holding watch history |
| TLS | Default platform trust manager, no custom `TrustManager`, no certificate pinning (appropriate here) |
| Exported components | Only `MainActivity` is exported, and it has a `BROWSABLE` filter for `youtube.com` hosts — no service or `PlayerActivity` is exported |
| Intent injection | `mt_url` is read from `onSaveInstanceState` (app-internal), **not** from `getIntent()` — so the exported activity cannot be used to load an arbitrary URL. This is a genuinely important call that was made correctly |
| File URL access | `setAllowFileAccess` is not enabled; the default is `false` on API 30+ |
| Crash log | Written to app-private `filesDir`, trimmed to 60 KB, and only shared on explicit user action |
| Native loader | Loads from the APK's `lib/` (read-only, verified) — see §5.8 for the assets path |

### Issues

1. **The `MTApp` JavaScript bridge is exposed to *every* page the WebView loads.** `shouldOverrideUrlLoading` returns `false` for arbitrary `https` hosts, so the WebView can browse any site, and that site can call `MTApp.*`. The bridge is reasonably constrained (it validates that URLs contain `youtube.com` before trusting them, and `openVideo` validates the ID length), but there is no **origin check**.
   **Fix:** in each `@JavascriptInterface` method, verify `web.getUrl()` is a `youtube.com` host before acting. Cheap, and closes the whole class of issue.
2. **`WebSettings` hardening is incomplete.** Missing `setAllowFileAccessFromFileURLs(false)`, `setAllowUniversalAccessFromFileURLs(false)`, `setAllowContentAccess(false)`, `setSafeBrowsingEnabled(true)`, and `setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW)`.
3. **The `Bridge.fmt(url, itag, mime)` method accepts any URL from page JS** and injects it into `StreamStore`, which is later handed to MediaPlayer/MediaMuxer. A hostile page could feed a `file://` or `content://` URL into the downloader path. Low impact (the downloader only uses resolver-sourced URLs), but worth validating the scheme.
4. **Privacy:** the app stores watch history, download history, playlists and Watch Later in plaintext `SharedPreferences` under the name `"mtw"`, with no encryption, in a world-readable-on-rooted-device file. It also scrapes the signed-in account name and avatar URL into prefs (`accName`/`accPic`) and downloads that avatar to `filesDir`. The **incognito** toggle (`v2.0`) covers watch history and download history but **not** playlists, Watch Later, or the account cache. The backup feature writes all of it to a plaintext JSON file in `Downloads/WTube/`.
   **Fix:** extend the incognito toggle to cover `wl`, `plIndex`/`pl_*`, and `accName`/`accPic`; and exclude those keys from the backup manifest (or state clearly that backup includes them).
5. **Third-party network dependencies at runtime:** `sponsor.ajay.app` (SponsorBlock), `returnyoutubedislikeapi.com` (RYD), `youtubei.googleapis.com`, `www.youtube.com`. The SponsorBlock call correctly uses a SHA-256 hash prefix for K-anonymity — good privacy practice, worth keeping. The RYD call is a plain unauthenticated request and leaks the video ID to that service; that is inherent to RYD's design, but the user should be able to opt out.

---

## 7. Performance & memory

| Area | Observation | Impact |
|---|---|---|
| Cosmetic CSS injection | 170 KB re-injected on every navigation, never removed (§5.7) | ~3.4 MB DOM bloat per 20 navigations |
| `evaluateJavascript` payload | `coreJs + themeJs + injectJs` ≈ 200 KB, sent on **both** `onPageStarted` and `onPageFinished` | ~400 KB of JS per navigation; doubles the parse cost |
| Ad-filter list | 53,375-host `HashSet` ≈ 3–5 MB RAM, parsed at first request | Acceptable; could be a sorted binary-searched array to halve it |
| Native lib duplication | 2.1 MB × 2 (§5.8) | ~40% of APK size wasted |
| Player thread model | 24 raw threads/job × 3 jobs (§5.18) | 72 sockets, high memory pressure on low-RAM devices |
| ExoPlayer buffer | `setBufferDurationsMs(15000, 50000, 800, 1500)` — 15 s min / 50 s max buffer | Generous for mobile data; consider a metered-network profile |
| `StreamStore.list` | Capped at 24 (pr) / 16 (captured) entries | Fine |
| `StreamStore.log` | `StringBuffer`, trimmed at 4,000 chars | Fine |
| WebView | `web.destroy()` on `onDestroy` ✓ | Fine — but `sInstance` keeps the Activity alive anyway (§5.9) |

---

## 8. Build & release engineering

### How it builds

```
build.sh  →  aapt2 compile/link  →  javac  →  d8 (R8)  →  zip  →  uber-signer
             (tools auto-downloaded to /tmp/mtw-tools)
```

Dependencies fetched at build time: `platform-36_r01.zip` (android.jar), `build-tools_r36` (aapt2), `r8-9.4.26.jar`, 7 Media3 1.11.1 AARs, `androidx.annotation`, `guava-33.3.1-android`, `failureaccess`.

### Problems

1. **The build is not reproducible from this archive.** `build.sh:129–131` hardcodes:
   ```bash
   java -jar /home/user/patchwork/uber-signer.jar ... \
       --ks /home/user/patchwork/webtube.p12 --ksAlias mytube --ksPass mytube2026 \
       --ksKeyPass mytube2026 --ksType PKCS12
   ```
   Neither the signer nor the keystore is in the archive, and **the keystore password is in plaintext in the script**. The README's claim that `build.sh` is "self-contained" is therefore false for anyone but the original author.
   **Fix:** move the keystore path/password to env vars or a git-ignored `signing.properties`, and check the `.p12` into a private secret store (not the repo).
2. **The native library cannot be rebuilt.** The archive contains `tools/ffmpeg/ffmpegjni.c`, `tools/lame/lame_jni.c`, `tools/lame/config.h` — but **not** the LAME source, the FFmpeg source, nor the `build-ffmpeg.sh` / `build-lib.sh` / `unversion.py` scripts that the DEVLOG describes. The `.so` is prebuilt-only, and it is arm64-only (no x86_64 → cannot run on most emulators, which is why the DEVLOG repeatedly says "runtime verification wasn't possible").
3. **No version pinning / integrity checking.** Downloads are fetched with `curl -fsSL` and no checksum is verified. A compromised mirror or a partial download would silently produce a bad build. `fetch()` does check for a non-empty file, but not correctness.
4. **`build.sh` mutates the source tree.** Lines 90–94 copy `libs/arm64-v8a/libwtcodec.so` into `assets/` as a side effect of a build. Every build dirties the working tree; a stale `assets/libwtcodec.so` can survive a `.so` change and get packaged. (Given §5.8 removes this copy entirely, the problem disappears.)
5. **No `resources.arsc` config / no `proguard` rules / no `R8` shrinking of app code** (D8 only, `--release` but no shrinking) — fine for 12 kLOC, but there's no obfuscation, so the DEX is trivially readable. That is a deliberate choice for an educational project; note it.
6. **No CI, no tests, no lint.** There is not a single test file in the archive, and no static analysis in the build. Given the number of subtle, high-value bugs found manually (§5.1, §5.2, §5.3), a handful of JVM unit tests would have caught most of them.

---

## 9. Native layer assessment

Verified directly from the shipped `assets/libwtcodec.so`:

| Property | Value | Verdict |
|---|---|---|
| Architecture | AArch64 (arm64-v8a) | Matches target |
| JNI exports | Exactly 7: `Ffmpeg_mux/mp3/err`, `Lame_open/encode/flush/close` | Matches the Java declarations exactly; no over-exports |
| `DT_NEEDED` | `libm.so`, `libdl.so`, `libc.so` | **Correct** — the v1.27 `sinh`/`libm` bug is genuinely fixed |
| `iconv` | Not present | Correct — bionic has no `iconv`; `--disable-iconv` was used |
| Size | 2,146,232 B | Reasonable for FFmpeg(mov/mp3/aac/h264) + LAME |
| Alignment | 16 KB page size | Android 15+ requirement ✓ |
| License | LAME 3.100 (LGPL) + FFmpeg (LGPL with `--disable-gpl` implied) | See below |

**Code quality of the JNI layer** (`tools/ffmpeg/ffmpegjni.c`, 462 lines) is good: `avformat_open_input`/`av_interleaved_write_frame` with `rescale_ts` for DTS ordering, `swr` resampling into `av_audio_fifo`, PTS-correct MP3 muxing, and a `last_err[512]` buffer for diagnostics that is surfaced to the user through `Ffmpeg.err()`.

### Licensing — needs attention

- **LAME is LGPL.** Linking it statically into `libwtcodec.so` and shipping that `.so` requires that the LAME *object files* be available for relinking, or that LAME be dynamically linked and replaceable. The archive ships neither the LAME source nor the object files, and the `tools/lame/` directory contains only the JNI shim.
- **FFmpeg's license depends on the build flags.** `--enable-libmp3lame` makes it GPL-2.0 (not LGPL) unless `--enable-gpl` is off *and* the mp3 encoder is built as an *external* library — which it is here, so LGPL-2.1+ applies. The DEVLOG notes "LGPL" in passing, but the exact flag set is not recorded in the archive, so the claim cannot be verified from the source.
- **The archive also contains uBlock Origin filter data** (`adfilter_hosts.txt` etc., produced by `tools/mkfilters.py` from uBlock assets + EasyList). uBlock is GPL-3.0; EasyList is GPL-3.0. Compiled filter *data* is generally treated as data rather than a derivative work, but if this app is ever distributed, the licensing posture should be reviewed — especially since the app is an ad-blocker for a commercial service, which is exactly the scenario the uBlock project has litigated.
- **YouTube's Terms of Service** prohibit downloading and background playback outside the official client. The README acknowledges this and frames the project as educational/personal. It should stay personal-use-only, and the app must not be published to a store.

**Recommendation:** re-link LAME dynamically (`libmp3lame.so` as a separate `.so`) or ship the LAME source + a relink script, and record the exact FFmpeg configure flags in the repo.

---

## 10. Testing strategy (what's missing and what to add)

There are **no tests**. The highest-value additions, in order:

| # | Test | Type | Would have caught |
|---|---|---|---|
| 1 | `VttSrt.toSrtAny` — VTT, srv1 XML, srv3 XML, entities, multi-line, no-trailing-space | JVM unit (pure Java, no Android) | The v1.18 backspace-in-regex bug |
| 2 | `DownloadService.parFetch` — 3 chunks over a fake HTTP server, assert **byte-exact** output and no truncation | JVM + local HTTP server | **§5.1 (P0)** |
| 3 | `PlayerService.onStartCommand(null, …)` must not throw | Robolectric | **§5.2 (P0)** |
| 4 | `AdBlock.block()` — matrix of host/path/third-party cases, incl. `ad.doubleclick.net` | JVM unit | **§5.4** |
| 5 | `Resolver` JSON parsers against **saved fixture responses** (one per renderer shape) | JVM unit + fixtures | §5.14, §5.20, and every future YouTube schema change |
| 6 | `Library` / `PlayerService` queue round-trips (add/remove/dedupe/cap) | JVM unit | History/queue corruption |
| 7 | `PlayerActivity.buildPlayback()` — smoke test that it constructs without exception | Robolectric | The "tapping does nothing" class of bug |
| 8 | `StreamStore.stripSeg` — given a segmented MSE URL, assert `range`/`rn`/`rbuf` are gone | JVM unit | The v0.15 segment bug |

Items 1, 2, 4, 5, 6 and 8 are **pure JVM** (no emulator needed) and cover the majority of the P0/P1 findings. The test harness can run in a couple of seconds.

---

## 11. Recommended roadmap

### Phase 1 — Correctness (≈1 day)
1. Fix the multipart truncation race (§5.1) — set file length once, before spawning threads.
2. Add `if (i == null) return START_NOT_STICKY;` to `PlayerService.onStartCommand` (§5.2).
3. Call `startFg()` unconditionally at the top of `PlayerService.onStartCommand` (§5.3).
4. Call `pump()` at the end of `DownloadService.cancelJob()` (§5.5).
5. Move the `AdBlock` static-host check **above** the whitelist check (§5.4).
6. Add `sInstance = null` in `MainActivity.onDestroy()` (§5.9).

### Phase 2 — Hygiene (≈1 day)
7. Delete `assets/libwtcodec.so` + the `NativeLoader` assets fallback → **−2.1 MB APK** (§5.8).
8. Add `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) or raise `minSdk` to 29 (§5.6).
9. Idempotent cosmetic-CSS injection with a stable marker node (§5.7).
10. Harden `WebSettings` + add an origin check to the `MTApp` bridge (§6).
11. `disconnect()` in `Resolver.post()` / `tryClassic()` (§5.10).

### Phase 3 — Observability (≈2 days)
12. Replace all 168 empty `catch` blocks with `StreamStore.note(...)` logging; narrow `Throwable` → `Exception` (§5.12).
13. Add the 8 unit tests from §10 — start with the pure-JVM ones.
14. Add a `scripts/verify-apk.sh` that asserts the markers the DEVLOG already checks by hand (dex strings, `.so` exports, `NEEDED` list, `vc`/`vn`, zip integrity, signature) — the DEVLOG describes doing this by hand for 40+ releases; script it.

### Phase 4 — Structure (≈3–5 days)
15. Split `PlayerActivity.buildPlayback()` into 6 sub-builders (§5.13).
16. Extract the `MTApp` bridge into its own class `MtBridge` with the origin check.
17. Extract `PlayerActivity`'s feature blocks (captions, chapters, equalizer, comments, related) into helper classes.
18. Move the 2.1 MB `cosmetic_generic.css` to a runtime-evaluated single `<style>` node, and consider trimming it to the selectors actually used.

### Phase 5 — Release engineering (≈1–2 days)
19. `signing.properties` (git-ignored) for the keystore path/password; verify the build from a clean clone.
20. Add checksums to the `fetch()` helper in `build.sh`.
21. Commit the LAME/FFmpeg build scripts + configure flags, or re-link LAME dynamically for LGPL compliance (§9).
22. Add a `gradle`-free `make test` / `./build.sh --test` target.

---

## 12. Appendix

### 12.1 File map

```
mytube-web/
├── README.md                     58 lines   — project guide (Bengali)
├── DEVLOG.md                   1,214 lines  — 41-version development history
├── ROADMAP.md                    88 lines   — feature analysis vs. NewPipe/ReVanced/LibreTube
├── PLAYER_TARGETS.md                        — player feature targets
├── build.sh                      140 lines   — aapt2 → javac → d8 → zip → sign
├── AndroidManifest.xml            61 lines   — 7 permissions, 2 activities, 3 services
├── src/com/mytube/web/          12,187 lines — 16 Java classes
├── assets/
│   ├── inject.js                 22 KB      — rebrand, click-capture, Shorts audio kick
│   ├── core.js                    4 KB      — JSON.parse trap / fetch wrapper / property trap
│   ├── cosmetic_generic.css     171 KB      — uBlock-derived cosmetic rules
│   ├── cosmetic_youtube.css       2 KB
│   ├── adfilter_hosts.txt        1.0 MB     — 53,375 always-block hosts
│   ├── adfilter_3p.txt            25 KB     — 1,767 third-party rules
│   ├── adfilter_white.txt         12 KB     — 830 whitelist entries
│   └── libwtcodec.so             2.1 MB     — duplicate of libs/ (see §5.8)
├── libs/arm64-v8a/libwtcodec.so 2.1 MB     — FFmpeg 7.1.1 + LAME 3.100, static
├── res/values/{strings,colors}.xml, mipmap-*/ (icon, 5 densities), drawable/ic_stat.xml
└── tools/
    ├── mkfilters.py               88 lines  — compiles uBlock/EasyList → the three .txt files
    ├── mkicon.py                  69 lines  — Pillow-based adaptive launcher icon
    ├── ffmpeg/ffmpegjni.c       462 lines  — JNI: mux() + mp3()
    └── lame/{lame_jni.c,config.h} 110 lines — JNI: LAME PCM→MP3
```

### 12.2 Build command

```bash
./build.sh Wtube_v2.0.apk 77 2.0
```

Requires: Linux, JDK (`javac`), `curl`, `unzip`, `zip`, `python3` (for the icon), and the signing keystore (not in this archive — see §8.1).

### 12.3 Runtime permission set

| Permission | Needed for | Granted at runtime? |
|---|---|---|
| `INTERNET` | Everything | N/A |
| `FOREGROUND_SERVICE` | FGS | N/A |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `PlayerService`, `BgService` | N/A |
| `FOREGROUND_SERVICE_DATA_SYNC` | `DownloadService` | N/A |
| `POST_NOTIFICATIONS` | Notifications (API 33+) | ✓ `askNotifOnce()` |
| `WAKE_LOCK` | Background audio | N/A |

### 12.4 The one-line verdict

**Excellent engineering instincts, unfinished engineering discipline.** The architecture is right, the native layer is genuinely skilled, the resolver fallback chain and the layered ad-blocking are the correct designs, and the diagnostic culture is strong. But the codebase has no tests, swallows ~168 exceptions, has two god classes (34% of all code in one Activity), ships a duplicate 2.1 MB native library that cannot load on any modern device, and contains at least **four P0 defects** — a data-corrupting download race, two service-lifecycle crashes, and a service that never stops — that are all straightforwardly fixable. Fix Phase 1, add the eight unit tests, and this becomes a genuinely solid piece of software.
