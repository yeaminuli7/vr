# vr — Wtube 2.0 source & analysis

Repository for **Wtube 2.0** (`com.mytube.web`, versionCode 77) — a YouTube front-end for
Android with a self-written ExoPlayer/Media3 engine, a self-written parallel downloader
(MP3 / SRT), and a layered ad-blocking pipeline — plus a full source-code audit of it.

| Path | What it is |
|---|---|
| [`ANALYSIS.md`](ANALYSIS.md) | **The audit.** Complete source analysis: architecture, module-by-module review, 20 prioritised findings with line references, security/privacy review, performance, build engineering, native-layer and licensing assessment, test strategy. |
| [`ROADMAP-PHASES.md`](ROADMAP-PHASES.md) | **The plan.** 7-phase advanced-feature roadmap — 38 features, each anchored to existing code. Native discovery, Player 2.0, Downloader 2.0, local library, on-device intelligence, privacy tooling, release engineering. Includes a 6-week sequencing plan. |
| `mytube-web/` | The extracted source of `Wtube_source_2.0.zip.xml` (unmodified). 12,187 lines of Java across 16 classes, 5 asset blobs, the Gradle-free `build.sh`, and the native/C/Python tooling. |

## Headline findings

Four P0 defects, all in the shipped 2.0 build:

1. **Download truncation race** — `DownloadService.parFetch()` has 12 chunk threads each calling
   `RandomAccessFile.setLength()` on the *same* file, so a later chunk can truncate bytes an
   earlier chunk already wrote. Corrupt MP4s on every download ≥ 3 MB. → `DownloadService.java:1171`
2. **`PlayerService` NPE** — `START_STICKY` restart delivers a null Intent, and
   `onStartCommand` dereferences it. Crash loop. → `PlayerService.java:247`
3. **`PlayerService` foreground-service violation** — `startForeground()` is only called on the
   `ACT_PLAY` branch, so notification actions can trigger
   `ForegroundServiceDidNotStartInTimeException`. → `PlayerService.java:258`
4. **Download service never stops** — cancelling a *queued* job never calls `pump()`, leaving the
   foreground service (and its notification) alive forever. → `DownloadService.java:122`

Plus the dominant maintainability issue: **~168 of ~330 `catch (Throwable)` blocks are
completely empty**, which is why the 1,214-line DEVLOG is largely a record of bugs found by
reading source rather than by tests.

See [`ANALYSIS.md`](ANALYSIS.md) for the full report, the ~10 P1/P2 findings, and the
phased fix plan.

## The plan in one line

Wtube has three strong engines (web shell, native player, downloader) and **no middle layer** —
there's no native search, no native channel/playlist browsing, and nothing that finds what you've
already saved. Phase 1 closes that gap; Phase 5 exploits the fact that this app owns the *whole*
pipeline (player + muxer + downloader + history + captions) to ship on-device intelligence no
competitor can match.

The two features that justify the project: **"find the moment"** (search inside any video's
transcript and jump to the timestamp) and **storyboard scrub preview** (YouTube-style thumbnails
that follow the seek bar).

```bash
cd mytube-web
./build.sh Wtube_v2.0.apk 77 2.0
```

Needs Linux + JDK + `curl`/`unzip`/`zip` + `python3`. The script downloads `android.jar`,
`aapt2`, R8/D8 and the Media3 AARs itself. **The signing keystore is not in this repo**
(see ANALYSIS.md §8.1) — the keystore path/password are referenced from an absolute local path.

## Download

Prebuilt APKs are on the [Releases page](https://github.com/yeaminuli7/vr/releases) —
latest is [v2.19](https://github.com/yeaminuli7/vr/releases/tag/v2.19). Each release
notes page has the direct `.apk` link at the top; open it on your phone and install.

| Release | Version | Highlights |
| --- | --- | --- |
| [v2.19](https://github.com/yeaminuli7/vr/releases/tag/v2.19) | 2.19 (vc 96) | A pasted video link is resolved, not scanned: direct media files download instantly, and a generic og:video extractor covers any site |
| [v2.18](https://github.com/yeaminuli7/vr/releases/tag/v2.18) | 2.18 (vc 95) | Zoom no longer hijacks the volume swipe (up/down = volume, left/right = pan, two fingers = pan). Native yt-dlp-style extraction for TikTok/Facebook/Instagram |
| [v2.17](https://github.com/yeaminuli7/vr/releases/tag/v2.17) | 2.17 (vc 94) | Non-YouTube links now open a download-options sheet (format, size, DOWNLOAD button) instead of just loading the page |
| [v2.16](https://github.com/yeaminuli7/vr/releases/tag/v2.16) | 2.16 (vc 93) | Paste a TikTok/Instagram/Facebook (or any) link into the search bar: the page opens and, if it exposes a media file, a download sheet appears |
| [v2.15](https://github.com/yeaminuli7/vr/releases/tag/v2.15) | 2.15 (vc 92) | Downloads show time remaining alongside the existing percentage and speed, in both the app and the notification |
| [v2.14](https://github.com/yeaminuli7/vr/releases/tag/v2.14) | 2.14 (vc 91) | Slow-loading fix: detects YouTube's `n`-parameter throttling and switches to an unthrottled source; caches visitorData to cut a round-trip per video |
| [v2.13](https://github.com/yeaminuli7/vr/releases/tag/v2.13) | 2.13 (vc 90) | Press and hold on the player to play at 2x; release returns to the previous speed |
| [v2.12](https://github.com/yeaminuli7/vr/releases/tag/v2.12) | 2.12 (vc 89) | Paste a YouTube link into YouTube's search bar and the video opens instead of searching. Ordinary searches unaffected |
| [v2.11](https://github.com/yeaminuli7/vr/releases/tag/v2.11) | 2.11 (vc 88) | Smooth volume gesture: the drag now accumulates instead of discarding fractions, and fills between the system's integer steps with player gain |
| [v2.10](https://github.com/yeaminuli7/vr/releases/tag/v2.10) | 2.10 (vc 87) | Pinch to zoom the video (100-500%), drag to pan while zoomed, double-tap to reset. Existing gestures unchanged |
| [v2.09](https://github.com/yeaminuli7/vr/releases/tag/v2.09) | 2.09 (vc 86) | History rebuilt YouTube-style: day sections with thumbnail cards; "Open in web" removed from the offline dialog |
| [v2.08](https://github.com/yeaminuli7/vr/releases/tag/v2.08) | 2.08 (vc 85) | Downloads screen rebuilt YouTube-style: thumbnail cards with duration badge, channel and size, three-dot menu, storage line, live progress |
| [v2.07](https://github.com/yeaminuli7/vr/releases/tag/v2.07) | 2.07 (vc 84) | All login options removed: no sign-in row, no Account section, no sign-out. Sign-in pages get no injection; account-page browser routing now off by default |
| [v2.06](https://github.com/yeaminuli7/vr/releases/tag/v2.06) | 2.06 (vc 83) | Telegram-style in-app browser (Custom Tabs): Subs/History/Your channel open signed in; offline build script |
| [v2.05](https://github.com/yeaminuli7/vr/releases/tag/v2.05) | 2.05 (vc 82) | Sign-in flow hardened: clean injection on YouTube sign-in pages, Chrome brands in JS, intent:// fallback |
| [v2.04](https://github.com/yeaminuli7/vr/releases/tag/v2.04) | 2.04 (vc 81) | Account buttons respond; WebView identity hardened against Google's sign-in block |
| [v2.03](https://github.com/yeaminuli7/vr/releases/tag/v2.03) | 2.03 (vc 80) | Sign-in responds; ad blocking strengthened |
| [v2.02](https://github.com/yeaminuli7/vr/releases/tag/v2.02) | 2.02 (vc 79) | Google account sign-in |
| [v2.01](https://github.com/yeaminuli7/vr/releases/tag/v2.01) | 2.01 (vc 78) | Hides YouTube's duplicate top nav |

Requires Android 7.0+ (API 24). Package stays `com.mytube.web` so versions
install over each other — except v2.01 and older, whose signing keys are gone.
