# APK deliverables

Every build output lives here and **is committed and pushed to GitHub**.

## Rules

1. **Build into this directory.** `build.sh` copies its output here automatically
   (see the "Publish" step at the end).
2. **Never build into `build/`.** Directories named `build/` are excluded from
   workspace snapshots — files written there are silently discarded and never
   reach anyone. This has already cost one lost APK.
3. **Commit and push after every build.** The remote is the only durable store;
   the sandbox's local git state can be reset between sessions.
4. **Never commit signing keys.** `.gitignore` excludes `*.p12`, `*.jks` and
   `*.keystore` — **except `debug.keystore`**, which is deliberately tracked so
   successive sandbox builds share one key and install over each other. The
   real release keystore lives outside the repo.
5. **Publish a GitHub release for every build.** The Releases page is what
   users actually look at, so the APK is not "shipped" until a release exists
   with a download link.

## After building

```sh
cd mytube-web
./build.sh Wtube_v2.03.apk 80 2.03      # also lands in apk/
git add -f apk/Wtube_v2.03.apk
git commit -m "Add built Wtube v2.03 APK"
git push origin <branch>
```

Then publish the release:

```sh
gh release create v2.03 --target <branch> \
  --title "Wtube v2.03 — <summary>" --notes-file /tmp/notes.md
```

Direct download link (works on a phone):

```
https://github.com/<owner>/<repo>/raw/<branch>/mytube-web/apk/Wtube_v2.03.apk
```

## Releases

Published builds live on the
[Releases page](https://github.com/yeaminuli7/vr/releases), newest first:

| Release | Version | Notes |
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
| [v2.03](https://github.com/yeaminuli7/vr/releases/tag/v2.03) | 2.03 (vc 80) | Sign-in actually responds (adds the missing `WebChromeClient` popup support); ad blocking strengthened |
| [v2.02](https://github.com/yeaminuli7/vr/releases/tag/v2.02) | 2.02 (vc 79) | Sign in with your Google account |
| [v2.01](https://github.com/yeaminuli7/vr/releases/tag/v2.01) | 2.01 (vc 78) | Hides YouTube's duplicate top nav bar |

Each release notes page has the direct `.apk` download link at the top.

> **Release assets vs. repo files.** Release *asset* uploads go to
> `uploads.github.com`, which is not reachable from the sandbox. Releases
> therefore link to the APK committed in the repo rather than hosting a
> separate copy. Both resolve to the same file.

## Signing caveat

Builds from v2.02 onward share the committed `debug.keystore`
(alias `mytube`), so they **install over each other** without uninstalling.

v2.01 and earlier were signed with generated keys that no longer exist, so
moving from those to v2.02+ requires **uninstalling first**.

Neither is the real release keystore — rebuild locally with `build.sh` for a
properly signed production build.
