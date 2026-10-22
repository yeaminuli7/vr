/* Adversarial cases for the Wtube nav-hiding detector. */
const fs = require('fs');
const { JSDOM } = require('jsdom');
const INJECT = fs.readFileSync(require('path').join(__dirname, '..', '..', 'assets', 'inject.js'), 'utf8');

let pass = 0, fail = 0;
function ok(c, m) { if (c) { pass++; console.log('  PASS  ' + m); } else { fail++; console.log('  FAIL  ' + m); } }
function run(html, flags) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  if (flags) { dom.window.__MTFLAGS = flags; }
  dom.window.eval(INJECT);
  return dom.window;
}
function hid(w, sel) {
  const el = w.document.querySelector(sel);
  if (!el) { return false; }
  return /display\s*:\s*none/i.test(el.getAttribute('style') || '');
}
const TOPBAR = `<ytm-mobile-topbar-renderer id="header-bar"><div class="mobile-topbar-header">
  <ytm-home-logo><a href="/"><img></a></ytm-home-logo>
  <ytm-search-box id="search"><input type="search"></ytm-search-box>
  <div id="__mtbrand">Wtube</div><div id="__mtbell">b</div>
  <ytm-account-avatar><a href="/feed/you"><img></a></ytm-account-avatar>
</div></ytm-mobile-topbar-renderer>`;

console.log('\n=== A: top strip with NO class names (role=tablist only) ===');
{
  const w = run(`<!DOCTYPE html><html><body><ytm-app>${TOPBAR}
    <div id="stripx" role="tablist">
      <a href="/">Home</a><a href="/shorts">Shorts</a>
      <a href="/feed/subscriptions">Subs</a><a href="/feed/you">You</a>
    </div></ytm-app></body></html>`);
  ok(hid(w, '#stripx'), 'class-less role=tablist strip hidden by pure signature');
  ok(!hid(w, 'ytm-mobile-topbar-renderer'), 'top bar kept');
  ok(!hid(w, 'ytm-search-box'), 'search kept');
  w.close();
}

console.log('\n=== B: channel page tabs must NOT be hidden ===');
{
  const w = run(`<!DOCTYPE html><html><body><ytm-app>${TOPBAR}
    <ytm-browse><div class="ytm-channel-tabs" role="tablist">
      <a href="/@creator">Home</a><a href="/@creator/videos">Videos</a>
      <a href="/creator/shorts">Shorts</a><a href="/@creator/live">Live</a>
    </div></ytm-browse></ytm-app></body></html>`);
  ok(!hid(w, '.ytm-channel-tabs'), 'channel tab bar (Home/Videos/Shorts/Live) is KEPT');
  w.close();
}

console.log('\n=== C: desktop layout top nav ===');
{
  const w = run(`<!DOCTYPE html><html><body>
    <ytd-app><div id="masthead"><ytd-topbar>
      <a href="/">Logo</a>
      <ytd-searchbox><input type="text"></ytd-searchbox>
    </ytd-topbar></div>
    <ytd-guide-renderer><a href="/">Home</a><a href="/shorts">Shorts</a>
      <a href="/feed/subscriptions">Subscriptions</a><a href="/feed/you">You</a></ytd-guide-renderer>
    </ytd-app></body></html>`);
  ok(hid(w, 'ytd-guide-renderer'), 'desktop guide (Home/Shorts/Subs/You) hidden');
  ok(!hid(w, 'ytd-searchbox'), 'desktop search kept');
  w.close();
}

console.log('\n=== D: live login -> signed-in top strip appears (mutation path) ===');
(async () => {
  const w = run(`<!DOCTYPE html><html><body><ytm-app>${TOPBAR}
    <ytm-single-column-browse-results-renderer><ytm-rich-grid-renderer></ytm-rich-grid-renderer></ytm-single-column-browse-results-renderer>
    </ytm-app></body></html>`);
  ok(!hid(w, '#late-strip'), 'precondition: no strip yet');
  /* simulate the signed-in top tab strip being injected by YouTube */
  const d = w.document;
  const strip = d.createElement('div');
  strip.id = 'late-strip';
  strip.setAttribute('role', 'tablist');
  strip.innerHTML = '<a href="/">Home</a><a href="/shorts">Shorts</a>'
    + '<a href="/feed/subscriptions">Subscriptions</a><a href="/feed/you">You</a>';
  d.querySelector('ytm-app').appendChild(strip);
  await new Promise(r => setTimeout(r, 400));   /* MutationObserver 30ms debounce */
  ok(hid(w, '#late-strip'), 'late-appearing signed-in top strip is hidden automatically');
  w.close();

  console.log('\n=== E: nav strip that only appears after a delay (no mutation) ===');
  const w2 = run(`<!DOCTYPE html><html><body><ytm-app>${TOPBAR}</ytm-app></body></html>`);
  const d2 = w2.document;
  const s2 = d2.createElement('div');
  s2.id = 'delayed-strip';
  s2.innerHTML = '<a href="/">Home</a><a href="/feed/subscriptions">Subs</a>';
  d2.querySelector('ytm-app').appendChild(s2);
  await new Promise(r => setTimeout(r, 900));   /* 600ms interval tick */
  ok(hid(w2, '#delayed-strip'), 'strip caught by the 600ms sweep');
  w2.close();

  console.log('\n=== F: host guard (Google sign-in page must be untouched) ===');
  {
    const dom = new JSDOM(`<!DOCTYPE html><html><body>
      <form id="login"><a href="/">Home</a><a href="/shorts">Shorts</a></form>
      </body></html>`, { runScripts: 'outside-only', pretendToBeVisual: true,
                        url: 'https://accounts.google.com/signin' });
    const w = dom.window;
    w.eval(INJECT);
    ok(!w.document.getElementById('__mtnavcss'), 'no nav CSS injected on google.com');
    ok(!hid(w, '#login'), 'google sign-in page untouched');
    w.close();
  }

  console.log('\n=== G: JS-navigated nav strip (no href at all) ===');
  {
    const w = run(`<!DOCTYPE html><html><body><ytm-app>
      <ytm-mobile-topbar-renderer id="header-bar">
        <header class="mobile-topbar-header">
          <ytm-search-box id="search"><input type="search"></ytm-search-box>
          <div id="__mtbrand">Wtube</div><div id="__mtbell">b</div>
          <div class="ytm-new-top-tabs" role="tablist">
            <div class="tab-item">Home</div>
            <div class="tab-item">Shorts</div>
            <div class="tab-item">Subscriptions</div>
          </div>
        </header>
      </ytm-mobile-topbar-renderer>
      </ytm-app></body></html>`);
    ok(hid(w, '.ytm-new-top-tabs'), 'href-less JS nav strip is hidden by label signature');
    ok(!hid(w, 'ytm-search-box'), 'search kept in the href-less case');
    ok(!hid(w, '#__mtbrand'), 'brand kept in the href-less case');
    w.close();
  }

  console.log('\n=== H: label-based rules must NOT touch content/real-link bars ===');
  {
    const w = run(`<!DOCTYPE html><html><body><ytm-app>
      <ytm-browse>
        <div class="ytm-channel-tabs" role="tablist">
          <a href="/@creator">Home</a><a href="/@creator/videos">Videos</a>
          <a href="/creator/shorts">Shorts</a><a href="/@creator/live">Live</a>
        </div>
      </ytm-browse>
      <ytm-single-column-browse-results-renderer>
        <ytm-rich-grid-renderer class="rich-grid-single-column">
          <ytm-rich-item-renderer><a href="/watch?v=a">Home</a></ytm-rich-item-renderer>
          <ytm-rich-item-renderer><a href="/watch?v=b">Shorts</a></ytm-rich-item-renderer>
        </ytm-rich-grid-renderer>
      </ytm-single-column-browse-results-renderer>
      </ytm-app></body></html>`);
    ok(!hid(w, '.ytm-channel-tabs'), 'channel tab bar (real links) is KEPT');
    ok(!hid(w, 'ytm-rich-grid-renderer'), 'video grid whose titles are Home/Shorts is KEPT');
    ok(!hid(w, 'ytm-rich-item-renderer'), 'video cards are KEPT');
    w.close();
  }

  console.log('\n=== I: item-level nav inside the header row ===');
  {
    const w = run(`<!DOCTYPE html><html><body><ytm-app>
      <ytm-mobile-topbar-renderer id="header-bar">
        <header class="mobile-topbar-header">
          <ytm-search-box id="search"><input type="search"></ytm-search-box>
          <div id="__mtbrand">Wtube</div>
          <div class="nav-tab-item">Home</div>
          <div class="nav-tab-item">You</div>
        </header>
      </ytm-mobile-topbar-renderer>
      </ytm-app></body></html>`);
    ok(hid(w, '.nav-tab-item'), 'item-level nav entries in the header are hidden');
    ok(!hid(w, 'ytm-search-box'), 'search still kept');
    ok(!hid(w, '#__mtbrand'), 'brand still kept');
    w.close();
  }

  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})();
