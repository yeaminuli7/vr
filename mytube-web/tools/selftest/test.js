/* Wtube nav-hiding verification harness.
   Runs the real assets/inject.js against a synthetic signed-in m.youtube.com
   DOM and asserts that YouTube's own nav is hidden while our own bar,
   the search box, the bell and the watch-page related chips survive. */
const fs = require('fs');
const { JSDOM } = require('jsdom');

const INJECT = fs.readFileSync(require('path').join(__dirname, '..', '..', 'assets', 'inject.js'), 'utf8');

let pass = 0, fail = 0;
function ok(cond, msg) {
  if (cond) { pass++; console.log('  PASS  ' + msg); }
  else { fail++; console.log('  FAIL  ' + msg); }
}

/* ---- synthetic signed-in mobile YouTube DOM (2026 top-tabs experiment) ---- */
const HTML = `<!DOCTYPE html><html><head></head>
<body has-pivot-bar="true" has-safe-area-in-max="true">
  <ytm-app id="app" class="sticky-player">
    <ytm-mobile-topbar-renderer id="header-bar" class="sticky-player">
      <div class="mobile-topbar-header">
        <ytm-home-logo><a href="/"><img src="logo.svg"></a></ytm-home-logo>
        <ytm-search-box id="search"><input type="search" placeholder="Search"></ytm-search-box>
        <div id="__mtbrand">Wtube</div>
        <div id="__mtbell">bell</div>
        <ytm-account-avatar><a href="/feed/you"><img src="av.png"></a></ytm-account-avatar>
      </div>
      <!-- the 2026 experiment: swipeable top feed tabs, signed-in only -->
      <ytm-feed-filter-chip-bar-renderer id="filter-chip-bar" class="chip-bar frosted-glass">
        <ytm-chip-cloud-chip-renderer role="tab" tab-title="Home"><a href="/">Home</a></ytm-chip-cloud-chip-renderer>
        <ytm-chip-cloud-chip-renderer role="tab" tab-title="Subscriptions"><a href="/feed/subscriptions">Subscriptions</a></ytm-chip-cloud-chip-renderer>
        <ytm-chip-cloud-chip-renderer role="tab" tab-title="All"><a href="/?itct=0">All</a></ytm-chip-cloud-chip-renderer>
        <ytm-chip-cloud-chip-renderer role="tab" tab-title="Music"><a href="/feed/music">Music</a></ytm-chip-cloud-chip-renderer>
        <ytm-chip-cloud-chip-renderer role="tab" tab-title="Gaming"><a href="/feed/gaming">Gaming</a></ytm-chip-cloud-chip-renderer>
      </ytm-feed-filter-chip-bar-renderer>
      <!-- an unknown, brand-new top tab strip (class names YouTube may introduce) -->
      <div class="ytm-unknown-xyz-top-tabs" role="tablist">
        <div class="tab"><a href="/">Home</a></div>
        <div class="tab"><a href="/shorts">Shorts</a></div>
        <div class="tab"><a href="/feed/subscriptions">Subscriptions</a></div>
        <div class="tab"><a href="/feed/you">You</a></div>
      </div>
    </ytm-mobile-topbar-renderer>
    <ytm-single-column-browse-results-renderer class="modern-tabs">
      <ytm-rich-grid-renderer class="rich-grid-single-column">
        <ytm-rich-item-renderer><a href="/watch?v=abc">A video</a></ytm-rich-item-renderer>
        <ytm-rich-item-renderer><a href="/">Some channel</a></ytm-rich-item-renderer>
      </ytm-rich-grid-renderer>
    </ytm-single-column-browse-results-renderer>
    <ytm-pivot-bar-renderer role="tablist" class="frosted-glass">
      <ytm-pivot-bar-item-renderer><a href="/">Home</a></ytm-pivot-bar-item-renderer>
      <ytm-pivot-bar-item-renderer><a href="/shorts">Shorts</a></ytm-pivot-bar-item-renderer>
      <ytm-pivot-bar-item-renderer><a href="/feed/subscriptions">Subs</a></ytm-pivot-bar-item-renderer>
      <ytm-pivot-bar-item-renderer><a href="/feed/you">You</a></ytm-pivot-bar-item-renderer>
    </ytm-pivot-bar-renderer>
  </ytm-app>
</body></html>`;

/* ---- watch page: related-chips bar must survive ---- */
const WATCH = `<!DOCTYPE html><html><head></head><body>
  <ytm-app id="app">
    <ytm-mobile-topbar-renderer id="header-bar" class="sticky-player">
      <div class="mobile-topbar-header">
        <ytm-search-box id="search"><input type="search"></ytm-search-box>
        <div id="__mtbrand">Wtube</div>
        <div id="__mtbell">bell</div>
      </div>
    </ytm-mobile-topbar-renderer>
    <ytm-watch>
      <ytm-chip-cloud-renderer class="YtmChipCloudRendererHost chip-bar">
        <ytm-chip-cloud-chip-renderer role="tab"><a href="/watch?v=x1">A video titled Home</a></ytm-chip-cloud-chip-renderer>
        <ytm-chip-cloud-chip-renderer role="tab"><a href="/watch?v=x2">Shorts</a></ytm-chip-cloud-chip-renderer>
      </ytm-chip-cloud-renderer>
    </ytm-watch>
    <ytm-pivot-bar-renderer role="tablist"><a href="/">Home</a></ytm-pivot-bar-renderer>
  </ytm-app>
</body></html>`;

function run(html, flags) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  if (flags) { w.__MTFLAGS = flags; }
  w.eval(INJECT);
  return w;
}

function hidden(w, sel) {
  const el = w.document.querySelector(sel);
  if (!el) { return false; }
  const s = el.getAttribute('style') || '';
  if (/display\s*:\s*none/i.test(s)) { return true; }
  return false;
}

console.log('\n=== CASE 1: signed-in home feed (2026 top tabs) ===');
{
  const w = run(HTML, null);

  ok(hidden(w, 'ytm-pivot-bar-renderer'), 'bottom pivot bar (Home/Shorts/Subs/You) is hidden');
  ok(hidden(w, '.ytm-unknown-xyz-top-tabs'), 'unknown new top tab strip is hidden by signature');
  ok(hidden(w, 'ytm-feed-filter-chip-bar-renderer ytm-chip-cloud-chip-renderer[tab-title="Home"]'),
     'chip-bar "Home" chip is hidden');
  ok(hidden(w, 'ytm-feed-filter-chip-bar-renderer ytm-chip-cloud-chip-renderer[tab-title="Subscriptions"]'),
     'chip-bar "Subscriptions" chip is hidden');

  const keep = ['All', 'Music', 'Gaming'];
  keep.forEach(t => {
    const el = w.document.querySelector(
      'ytm-feed-filter-chip-bar-renderer ytm-chip-cloud-chip-renderer[tab-title="' + t + '"]');
    ok(el && !/display\s*:\s*none/i.test(el.getAttribute('style') || ''),
       'chip-bar filter chip "' + t + '" is KEPT visible');
  });

  ok(!hidden(w, 'ytm-search-box'), 'search box is KEPT');
  ok(!hidden(w, '#__mtbrand'), 'our brand is KEPT');
  ok(!hidden(w, '#__mtbell'), 'our bell is KEPT');
  ok(!hidden(w, 'ytm-mobile-topbar-renderer'), 'top bar itself is KEPT');
  ok(!hidden(w, 'ytm-single-column-browse-results-renderer'), 'feed content is KEPT');
  ok(!hidden(w, 'ytm-rich-grid-renderer'), 'video grid is KEPT');
  ok(!hidden(w, 'ytm-rich-item-renderer a[href="/"]'), 'a channel link inside a card is KEPT');

  const css = w.document.getElementById('__mtnavcss');
  ok(!!css, 'anti-flicker stylesheet __mtnavcss is present');
  ok(!!css && /ytm-pivot-bar-renderer/.test(css.textContent) &&
     /display:none!important/.test(css.textContent), 'stylesheet hides pivot bar with !important');
  ok(!!css && /padding-bottom:0!important/.test(css.textContent), 'stylesheet clears pivot-bar padding');

  w.close();
}

console.log('\n=== CASE 2: watch page (related chips must survive) ===');
{
  const w = run(WATCH, null);
  ok(!hidden(w, 'ytm-chip-cloud-renderer.chip-bar'), 'watch related-chips bar is KEPT');
  ok(!hidden(w, 'ytm-chip-cloud-renderer.chip-bar ytm-chip-cloud-chip-renderer'),
     'watch related chips (even titled "Home"/"Shorts") are KEPT');
  ok(hidden(w, 'ytm-pivot-bar-renderer'), 'pivot bar still hidden on watch page');
  ok(!hidden(w, 'ytm-search-box'), 'search box KEPT on watch page');
  w.close();
}

console.log('\n=== CASE 3: setting OFF restores YouTube nav ===');
{
  const w = run(HTML, { navhide: false });
  ok(!hidden(w, 'ytm-pivot-bar-renderer'), 'pivot bar restored when navhide=false');
  ok(!hidden(w, '.ytm-unknown-xyz-top-tabs'), 'top strip restored when navhide=false');
  ok(!w.document.getElementById('__mtnavcss'), 'stylesheet removed when navhide=false');
  w.close();
}

console.log('\n=== CASE 4: navhide=true explicitly ===');
{
  const w = run(HTML, { navhide: true });
  ok(hidden(w, 'ytm-pivot-bar-renderer'), 'pivot bar hidden when navhide=true');
  w.close();
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
