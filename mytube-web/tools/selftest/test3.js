/* End-to-end simulation of the Wtube login journey.
   1. signed-out m.youtube.com
   2. redirect to accounts.google.com (sign-in)
   3. back to m.youtube.com, now signed in -> YouTube's top nav strip exists
   4. YouTube re-renders the strip (Polymer replaces the node)
   Every step must end with ONLY Wtube's own bottom nav visible. */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');
const INJECT = fs.readFileSync(path.join(__dirname, '..', '..', 'assets', 'inject.js'), 'utf8');

let pass = 0, fail = 0;
function ok(c, m) { if (c) { pass++; console.log('  PASS  ' + m); } else { fail++; console.log('  FAIL  ' + m); } }
function hid(w, sel) {
  const el = w.document.querySelector(sel);
  if (!el) { return false; }
  return /display\s*:\s*none/i.test(el.getAttribute('style') || '');
}
function injectInto(w) { w.eval(INJECT); }

const TOPBAR = `<ytm-mobile-topbar-renderer id="header-bar" class="sticky-player">
  <header class="mobile-topbar-header">
    <ytm-home-logo><a href="/"><img></a></ytm-home-logo>
    <ytm-search-box id="search"><input type="search"></ytm-search-box>
    <div id="__mtbrand">Wtube</div><div id="__mtbell">b</div>
    <ytm-account-avatar><a href="/feed/you"><img></a></ytm-account-avatar>
  </header></ytm-mobile-topbar-renderer>`;

const PIVOT = `<ytm-pivot-bar-renderer role="tablist" class="frosted-glass">
  <ytm-pivot-bar-item-renderer><a href="/">Home</a></ytm-pivot-bar-item-renderer>
  <ytm-pivot-bar-item-renderer><a href="/shorts">Shorts</a></ytm-pivot-bar-item-renderer>
  <ytm-pivot-bar-item-renderer><a href="/feed/subscriptions">Subs</a></ytm-pivot-bar-item-renderer>
  <ytm-pivot-bar-item-renderer><a href="/feed/you">You</a></ytm-pivot-bar-item-renderer>
</ytm-pivot-bar-renderer>`;

/* YouTube's 2026 signed-in top feed tabs */
const TOPNAV = `<div class="ytm-feed-tabs-strip" role="tablist">
  <div class="feed-tab"><a href="/">Home</a></div>
  <div class="feed-tab"><a href="/shorts">Shorts</a></div>
  <div class="feed-tab"><a href="/feed/subscriptions">Subscriptions</a></div>
  <div class="feed-tab"><a href="/feed/you">You</a></div>
</div>`;

const CHIPBAR = `<ytm-feed-filter-chip-bar-renderer id="filter-chip-bar" class="chip-bar frosted-glass">
  <ytm-chip-cloud-chip-renderer role="tab" tab-title="Home"><a href="/">Home</a></ytm-chip-cloud-chip-renderer>
  <ytm-chip-cloud-chip-renderer role="tab" tab-title="Subscriptions"><a href="/feed/subscriptions">Subscriptions</a></ytm-chip-cloud-chip-renderer>
  <ytm-chip-cloud-chip-renderer role="tab" tab-title="All"><a href="/?itct=0">All</a></ytm-chip-cloud-chip-renderer>
  <ytm-chip-cloud-chip-renderer role="tab" tab-title="Music"><a href="/feed/music">Music</a></ytm-chip-cloud-chip-renderer>
</ytm-feed-filter-chip-bar-renderer>`;

(async () => {
  console.log('\n=== STEP 1: signed-out m.youtube.com ===');
  let w = new JSDOM(`<!DOCTYPE html><html><body has-pivot-bar="true"><ytm-app id="app" class="sticky-player">
    ${TOPBAR}${PIVOT}
    <ytm-single-column-browse-results-renderer class="modern-tabs">
      <ytm-rich-grid-renderer class="rich-grid-single-column"></ytm-rich-grid-renderer>
    </ytm-single-column-browse-results-renderer>
    </ytm-app></body></html>`,
    { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://m.youtube.com/' }).window;
  injectInto(w);
  ok(hid(w, 'ytm-pivot-bar-renderer'), 'YouTube bottom pivot bar already hidden');
  ok(!!w.document.getElementById('__mtnavcss'), 'anti-flicker stylesheet applied at document start');
  ok(!hid(w, 'ytm-mobile-topbar-renderer'), 'top bar kept (search/brand/bell live there)');
  w.close();

  console.log('\n=== STEP 2: redirect to accounts.google.com ===');
  w = new JSDOM(`<!DOCTYPE html><html><body>
    <form id="signin"><a href="/">Home</a><a href="/shorts">Shorts</a></form></body></html>`,
    { runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://accounts.google.com/signin' }).window;
  injectInto(w);
  ok(!w.document.getElementById('__mtnavcss'), 'nav CSS NOT injected on the Google page');
  ok(!hid(w, '#signin'), 'Google sign-in page untouched');
  w.close();

  console.log('\n=== STEP 3: back to m.youtube.com, SIGNED IN (top nav present) ===');
  w = new JSDOM(`<!DOCTYPE html><html><body has-pivot-bar="true"><ytm-app id="app" class="sticky-player">
    ${TOPBAR}
    ${TOPNAV}
    <ytm-single-column-browse-results-renderer class="modern-tabs">
      <ytm-rich-grid-renderer class="rich-grid-single-column"></ytm-rich-grid-renderer>
      ${CHIPBAR}
    </ytm-single-column-browse-results-renderer>
    ${PIVOT}
    </ytm-app></body></html>`,
    { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://m.youtube.com/' }).window;
  injectInto(w);
  ok(hid(w, '.ytm-feed-tabs-strip'), 'YouTube TOP nav strip (Home/Shorts/Subs/You) is HIDDEN');
  ok(hid(w, 'ytm-pivot-bar-renderer'), 'YouTube bottom pivot bar is HIDDEN');
  ok(hid(w, 'ytm-chip-cloud-chip-renderer[tab-title="Home"]'), 'chip-bar "Home" chip hidden');
  ok(hid(w, 'ytm-chip-cloud-chip-renderer[tab-title="Subscriptions"]'), 'chip-bar "Subscriptions" chip hidden');
  ok(!hid(w, 'ytm-chip-cloud-chip-renderer[tab-title="All"]'), 'chip-bar "All" filter kept');
  ok(!hid(w, 'ytm-chip-cloud-chip-renderer[tab-title="Music"]'), 'chip-bar "Music" filter kept');
  ok(!hid(w, 'ytm-search-box'), 'search box kept');
  ok(!hid(w, '#__mtbrand'), 'our brand kept');
  ok(!hid(w, '#__mtbell'), 'our bell kept');
  ok(!hid(w, 'ytm-mobile-topbar-renderer'), 'top bar kept');
  ok(!hid(w, 'ytm-single-column-browse-results-renderer'), 'feed content kept');
  const cssText = w.document.getElementById('__mtnavcss').textContent;
  ok(/ytm-pivot-bar-renderer/.test(cssText) && /display:none!important/.test(cssText),
     'stylesheet hides the pivot bar with !important (no flash)');
  ok(/padding-bottom:0!important/.test(cssText), 'pivot-bar bottom padding cleared (no empty 58px strip)');

  console.log('\n=== STEP 4: YouTube re-renders the top nav (Polymer replaces the node) ===');
  const strip = w.document.querySelector('.ytm-feed-tabs-strip');
  const fresh = w.document.createElement('div');
  fresh.className = 'ytm-feed-tabs-strip';
  fresh.setAttribute('role', 'tablist');
  fresh.innerHTML = '<div class="feed-tab"><a href="/">Home</a></div>'
    + '<div class="feed-tab"><a href="/shorts">Shorts</a></div>'
    + '<div class="feed-tab"><a href="/feed/subscriptions">Subscriptions</a></div>';
  strip.parentNode.replaceChild(fresh, strip);
  await new Promise(r => setTimeout(r, 400));
  ok(hid(w, '.ytm-feed-tabs-strip'), 're-rendered top nav is re-hidden by the MutationObserver');
  w.close();

  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})();
