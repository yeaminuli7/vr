# Wtube inject.js self-test

Headless DOM tests for `assets/inject.js` — the YouTube-navigation hiding logic
(`mtHideYtNav()` / `ensureNavCss()`).

These tests build a synthetic `m.youtube.com` DOM (signed-in, with the 2026
"swipeable top tabs" feed layout) and run the **real** `assets/inject.js`
inside it, then assert which elements end up hidden.

`test5.js` — pasting a **non-YouTube** link into YouTube's search bar
- TikTok / Instagram / Facebook / X / Vimeo / any site → the page is opened
  through the bridge, the search is blocked, the input is cleared
- YouTube and Google hosts are never treated as external (`youtube.com`,
  `youtu.be`, `accounts.google.com`, `google.com`, `ytimg.com`, `yt3.ggpht.com`,
  `gstatic.com`), so the video logic in test4.js keeps owning them
- ordinary text still searches: bare host without a scheme, a URL inside
  words, a URL with a space, `javascript:` / `data:` / `file:` / `ftp:`
- the `/results?search_query=<link>` fallback opens the page, while a normal
  results page does not, and the same link is not opened twice
- a comment box is never hijacked
- an older app without `openLink` does not throw

## Run

```sh
cd tools/selftest
npm install        # needs network (jsdom only, dev dependency)
npm test
```

## What is covered

`test.js`
- bottom pivot bar (`ytm-pivot-bar-renderer`) hidden
- a brand-new, unknown top tab strip hidden **by link signature** (no class names)
- feed chip bar: `Home` / `Subscriptions` chips hidden, `All` / `Music` / `Gaming` filter chips kept
- search box, our brand (`#__mtbrand`), our bell (`#__mtbell`), top bar and feed content kept
- watch page: related-chips bar (`ytm-chip-cloud-renderer.chip-bar`) kept
- setting `navhide=false` restores YouTube's nav and removes the stylesheet

`test2.js`
- class-less `role="tablist"` strip hidden by pure signature
- channel page tabs (Home/Videos/Shorts/Live) are **not** hidden
- desktop layout guide (`ytd-guide-renderer`) hidden, desktop search kept
- a top strip that appears *after* login is hidden automatically (MutationObserver path)
- a top strip that appears without a mutation is caught by the 600 ms sweep
- `accounts.google.com` sign-in page is never touched (host guard)

`test3.js`
- **the full login journey**: signed-out home -> accounts.google.com sign-in -> back to a
  signed-in `m.youtube.com` that carries YouTube's 2026 top feed tabs
- asserts YouTube's top strip and bottom pivot bar are hidden at the signed-in step, that
  the filter chips (All / Music) survive, and that a Polymer re-render of the strip is
  re-hidden by the MutationObserver
- asserts the anti-flicker stylesheet exists immediately at document start and that the
  pivot-bar bottom padding is cleared
`test4.js` — pasting a YouTube link into YouTube's own search bar
- every link shape opens the video and blocks the search: `watch?v=`, bare
  `youtube.com/watch?v=`, `m.youtube.com`, `youtu.be/` (with `?t=`), `shorts/`,
  `embed/`, `live/`, links carrying `&list=` / `&feature=share`, extra spaces
- `shorts/` links carry the shorts flag; the input is cleared after opening
- **ordinary searches are untouched** (no video opened, not blocked, text kept):
  plain words, a bare `youtube.com`, `watch?v=` with no host, an id embedded in
  words, an 11-char-short id, a non-YouTube host, a `/results` URL, empty input
- non-search inputs (a comment box) are never hijacked
- detected by `#search` container, by `name=search_query`, and by `type=search`
- the `submit` event is caught too, for paths that skip keydown
- safety net: a loaded `/results?search_query=<link>` page opens the video,
  while a normal results page is left alone
- no regression: tapping a video link still opens the player

