/* Pasting a NON-YouTube link into YouTube's search bar.

   Chosen behaviour: the link is not searched for. It is handed to the app,
   which opens the page in the browser and watches what media that page
   loads. If nothing is found the user simply sees the page (no message).

   YouTube/Google hosts must never take this path — they are handled by the
   video logic in test4.js, or just searched. */
const fs = require('fs');
const { JSDOM } = require('jsdom');

const INJECT = fs.readFileSync(
  require('path').join(__dirname, '..', '..', 'assets', 'inject.js'), 'utf8');

let pass = 0, fail = 0;
function ok(cond, msg) {
  if (cond) { pass++; console.log('  PASS  ' + msg); }
  else { fail++; console.log('  FAIL  ' + msg); }
}

function stub(w) {
  w.__calls = { openVideo: [], openLink: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, s) => w.__calls.openVideo.push({ id, title: t, short: s }),
    openLink: (u) => w.__calls.openLink.push(u),
    toast: (m) => w.__calls.toast.push(m),
    nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { }, pos: () => 0,
    bgaudio: () => { }, account: () => { }, play: () => { }
  };
}

function page(container) {
  const html = `<!DOCTYPE html><html><head></head><body>
    <ytm-mobile-topbar-renderer id="header-bar">
      <div class="mobile-topbar-header">
        <ytm-search-box id="search">${container}</ytm-search-box>
      </div>
    </ytm-mobile-topbar-renderer>
    <ytm-app></ytm-app></body></html>`;
  const dom = new JSDOM(html, {
    url: 'https://m.youtube.com/',
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });
  stub(dom.window);
  dom.window.eval(INJECT);
  return dom.window;
}

const SEARCH_INPUT = '<input type="search" name="search_query" placeholder="Search">';

function typeText(w, text) {
  const inp = w.document.querySelector('input');
  inp.value = text;
  const ev = new w.KeyboardEvent('keydown', {
    key: 'Enter', keyCode: 13, bubbles: true, cancelable: true
  });
  inp.dispatchEvent(ev);
  return ev;
}

/* ---- 1. external links open the page, and do not search ---- */
console.log('\n[1] external links open the page');
const externals = [
  ['tiktok video', 'https://www.tiktok.com/@user/video/7311111111111111111'],
  ['tiktok short', 'https://vm.tiktok.com/ZMabcdef/'],
  ['instagram reel', 'https://www.instagram.com/reel/Cx1y2z3AbCd/'],
  ['facebook video', 'https://www.facebook.com/watch/?v=1234567890'],
  ['facebook reel', 'https://fb.watch/abcdEFG/'],
  ['twitter/x', 'https://x.com/user/status/1234567890'],
  ['vimeo', 'https://vimeo.com/123456789'],
  ['generic site', 'https://example.com/some/page?x=1'],
  ['with spaces around', '   https://www.tiktok.com/@u/video/731  '],
];
for (const [label, text] of externals) {
  const w = page(SEARCH_INPUT);
  const ev = typeText(w, text);
  const trimmed = text.trim();
  ok(w.__calls.openLink.length === 1 && w.__calls.openLink[0] === trimmed,
     label + ' -> opens the page');
  ok(w.__calls.openVideo.length === 0, label + ' -> does not open a video');
  ok(ev.defaultPrevented, label + ' -> search blocked');
  ok(w.document.querySelector('input').value === '', label + ' -> input cleared');
  w.close();
}

/* ---- 2. YouTube / Google hosts must never be treated as external ---- */
console.log('\n[2] YouTube and Google hosts are excluded');
const notExternal = [
  'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  'https://youtu.be/dQw4w9WgXcQ',
  'https://m.youtube.com/feed/trending',
  'https://www.youtube.com/results?search_query=cats',
  'https://accounts.google.com/signin',
  'https://www.google.com/search?q=cats',
  'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg',
  'https://yt3.ggpht.com/abc',
  'https://www.gstatic.com/thing.js',
];
for (const text of notExternal) {
  const w = page(SEARCH_INPUT);
  typeText(w, text);
  ok(w.__calls.openLink.length === 0, 'not opened externally: ' + text);
  w.close();
}

/* ---- 3. ordinary text must still search ---- */
console.log('\n[3] ordinary text still searches');
const texts = [
  'cats playing piano',
  'tiktok.com',                                  // bare host, no scheme
  'www.tiktok.com/@u/video/731',                 // no scheme
  'look at https://example.com/x',               // URL inside words
  'https://exa mple.com/x',                      // space breaks the URL
  'javascript:alert(1)',                         // not http(s)
  'data:text/html,<b>x</b>',
  'file:///etc/passwd',
  'ftp://example.com/x',
  '',
  '   ',
];
for (const text of texts) {
  const w = page(SEARCH_INPUT);
  const ev = typeText(w, text);
  ok(w.__calls.openLink.length === 0, 'not opened: "' + text + '"');
  ok(!ev.defaultPrevented, 'search allowed: "' + text + '"');
  ok(w.document.querySelector('input').value === text, 'text kept: "' + text + '"');
  w.close();
}

/* ---- 4. the /results fallback, with de-duplication ---- */
console.log('\n[4] results-page fallback');
{
  const q = encodeURIComponent('https://www.tiktok.com/@u/video/731');
  const dom = new JSDOM('<!DOCTYPE html><html><body><ytm-app></ytm-app></body></html>', {
    url: 'https://m.youtube.com/results?search_query=' + q,
    runScripts: 'outside-only'
  });
  const w = dom.window;
  stub(w);
  w.eval(INJECT);
  ok(w.__calls.openLink.length === 1, 'link in search_query opens the page');
  ok(w.__calls.openLink[0] === 'https://www.tiktok.com/@u/video/731',
     'opens the exact url');
  w.close();
}
{
  // a plain word search must not be opened
  const dom = new JSDOM('<!DOCTYPE html><html><body><ytm-app></ytm-app></body></html>', {
    url: 'https://m.youtube.com/results?search_query=funny+cats',
    runScripts: 'outside-only'
  });
  const w = dom.window;
  stub(w);
  w.eval(INJECT);
  ok(w.__calls.openLink.length === 0, 'normal results page not opened');
  w.close();
}

/* ---- 5. non-search inputs are never hijacked ---- */
console.log('\n[5] other inputs untouched');
{
  const html = `<!DOCTYPE html><html><body>
    <ytm-mobile-topbar-renderer><ytm-search-box id="search">
      <input type="search" name="search_query"></ytm-search-box></ytm-mobile-topbar-renderer>
    <ytd-comment-box><input type="text" id="comment" placeholder="Add a comment">
    </ytd-comment-box></body></html>`;
  const dom = new JSDOM(html, { url: 'https://m.youtube.com/', runScripts: 'outside-only' });
  const w = dom.window;
  stub(w);
  w.eval(INJECT);
  const inp = w.document.getElementById('comment');
  inp.value = 'https://www.tiktok.com/@u/video/731';
  const ev = new w.KeyboardEvent('keydown', {
    key: 'Enter', keyCode: 13, bubbles: true, cancelable: true
  });
  inp.dispatchEvent(ev);
  ok(w.__calls.openLink.length === 0, 'comment box does not open a page');
  ok(!ev.defaultPrevented, 'comment box Enter not blocked');
  w.close();
}

/* ---- 6. missing bridge method must not break search ---- */
console.log('\n[6] older app without openLink');
{
  const w = page(SEARCH_INPUT);
  delete w.MTApp.openLink;
  let threw = false;
  try {
    typeText(w, 'https://www.tiktok.com/@u/video/731');
  } catch (e) {
    threw = true;
  }
  ok(!threw, 'no exception when openLink is absent');
  w.close();
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
