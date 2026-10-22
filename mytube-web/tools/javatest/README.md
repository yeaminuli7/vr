# Java self-tests (no device, no Android)

These compile a **pure** part of the app source standalone with `javac` and run
it on the JVM, so the logic can be checked without an emulator or phone. They
exist because some behaviour cannot be exercised from the build sandbox.

## ExtractorsTest

Covers `src/com/mytube/web/Extractors.java` — the native equivalent of what
yt-dlp does for TikTok / Facebook / Instagram (read the JSON embedded in the
page and pull out the direct stream URLs).

Payloads are realistic fixtures, not real captures:

- `unescape()` — `\u0026`, `\/`, `\u002F`, escaped quotes, no-escape passthrough
- `host()` — normal, with port, junk input
- `canHandle()` — TikTok / vm.tiktok / Facebook / fb.watch / Instagram yes,
  YouTube and generic sites no
- TikTok — `playAddr` + `downloadAddr`, and `bitrateInfo[]` with several
  qualities (capital `Bitrate` / `UrlList` keys), HD vs SD labelling
- TikTok — empty and null input
- Facebook — `playable_url` / `playable_url_quality_hd`, HD listed first,
  plus legacy `hd_src` / `sd_src`
- Instagram — `video_versions[]`
- de-duplication of identical URLs, and `.m4a` flagged as audio

## Run

```sh
cd tools/javatest
cp ../../src/com/mytube/web/Extractors.java .
sed -i 's/^package com.mytube.web;//' Extractors.java
printf 'public class StreamStore { public static void note(String s) {} }\n' > StreamStore.java
javac -encoding UTF-8 -d . Extractors.java StreamStore.java ExtractorsTest.java
java -cp . ExtractorsTest
```
