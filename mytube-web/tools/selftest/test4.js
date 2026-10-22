/* Pasting a YouTube link into YouTube's own search bar.

   YouTube's search box is a normal GET form, so pasting a link and pressing
   Enter would run a *search* for that URL and show a results page. inject.js
   intercepts the keydown in the capture phase and, when the entire query is a
   video link, opens the video instead. Ordinary word searches must be left
   completely alone. */
const fs = require('fs');
const { JSDOM } = require('jsdom');

const INJECT = fs.readFileSync(
  require('path').join(__dirname, '.', 'inject.js'), 'utf8');

let pass = 0, fail = 0;
function ok(cond, msg) {
  if (cond) { pass++; console.log('  PASS  ' + msg); }
  else { fail++; console.log('  FAIL  ' + msg); }
}

/* ---- harness: a mobile YouTube page with a search box ---- */
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
  const w = dom.window;
  // record every bridge call instead of talking to Android
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, title, short) => w.__calls.openVideo.push({ id, title, short }),
    toast: (m) => w.__calls.toast.push(m),
    nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { }, pos: () => 0,
    bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);
  return w;
}

const SEARCH_INPUT = '<input type="search" name="search_query" placeholder="Search">';

function typeLink(w, text) {
  const inp = w.document.querySelector('input');
  inp.value = text;
  const ev = new w.KeyboardEvent('keydown', {
    key: 'Enter', keyCode: 13, bubbles: true, cancelable: true
  });
  inp.dispatchEvent(ev);
  return ev;
}

function lastOpened(w) {
  const a = w.__calls.openVideo;
  return a.length ? a[a.length - 1] : null;
}

/* ---- 1. every common link shape opens the video ---- */
console.log('\n[1] link shapes');
const ID = 'dQw4w9WgXcQ';
const shapes = [
  ['full watch URL', 'https://www.youtube.com/watch?v=' + ID],
  ['bare watch URL', 'youtube.com/watch?v=' + ID],
  ['with no scheme', 'm.youtube.com/watch?v=' + ID],
  ['short link', 'https://youtu.be/' + ID],
  ['short with query', 'https://youtu.be/' + ID + '?t=42'],
  ['watch with playlist', 'https://www.youtube.com/watch?v=' + ID + '&list=PL123&index=2'],
  ['shorts link', 'https://www.youtube.com/shorts/' + ID],
  ['embed link', 'https://www.youtube.com/embed/' + ID],
  ['live link', 'https://www.youtube.com/live/' + ID],
  ['with surrounding spaces', '  https://youtu.be/' + ID + '  '],
  ['scheme + www + share params', 'https://www.youtube.com/watch?v=' + ID + '&feature=share&si=abc'],
];
for (const [label, text] of shapes) {
  const w = page(SEARCH_INPUT);
  const ev = typeLink(w, text);
  const got = lastOpened(w);
  ok(got && got.id === ID, label + ' -> opens ' + ID);
  ok(ev.defaultPrevented, label + ' -> search submission blocked');
  w.close();
}

/* ---- 2. Shorts links keep the shorts flag ---- */
console.log('\n[2] shorts flag');
{
  const w = page(SEARCH_INPUT);
  typeLink(w, 'https://www.youtube.com/shorts/' + ID);
  const got = lastOpened(w);
  ok(got && got.short === true, 'shorts link sets short=true');
  ok(w.document.querySelector('input').value === '', 'input cleared after opening');
  w.close();
}
{
  const w = page(SEARCH_INPUT);
  typeLink(w, 'https://youtu.be/' + ID);
  const got = lastOpened(w);
  ok(got && got.short === false, 'normal link sets short=false');
  w.close();
}

/* ---- 3. ordinary searches must be untouched ----
   (a non-YouTube URL is deliberately NOT in this list: it opens the page
   instead of searching, and is covered by test5.js) */
console.log('\n[3] normal searches unaffected');
const searches = [
  'cats playing piano',
  'how to make biryani',
  'youtube.com',                          // bare host, not a video
  'watch?v=' + ID,                        // no host -> just text
  'best of ' + ID,                        // id embedded in words
  'https://www.youtube.com/watch?v=SHORT',// too short to be an id
  'https://www.youtube.com/results?search_query=x',
  '',
  '   ',
];
for (const text of searches) {
  const w = page(SEARCH_INPUT);
  const ev = typeLink(w, text);
  ok(w.__calls.openVideo.length === 0, 'no video opened for: "' + text + '"');
  ok(!ev.defaultPrevented, 'search allowed to run for: "' + text + '"');
  ok(w.document.querySelector('input').value === text, 'input left as typed: "' + text + '"');
  w.close();
}

/* ---- 4. it must not hijack non-search inputs ---- */
console.log('\n[4] other inputs untouched');
{
  // NOT inside the search box — an ordinary text field elsewhere on the page
  const html = `<!DOCTYPE html><html><body>
    <ytm-mobile-topbar-renderer><ytm-search-box id="search">
      <input type="search" name="search_query"></ytm-search-box></ytm-mobile-topbar-renderer>
    <ytd-comment-box><input type="text" id="comment" placeholder="Add a comment">
    </ytd-comment-box></body></html>`;
  const dom = new JSDOM(html, { url: 'https://m.youtube.com/', runScripts: 'outside-only' });
  const w = dom.window;
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, sh) => w.__calls.openVideo.push({ id, title: t, short: sh }),
    toast: () => { }, nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { }, pos: () => 0,
    bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);
  const inp = w.document.getElementById('comment');
  inp.value = 'https://youtu.be/' + ID;
  const ev = new w.KeyboardEvent('keydown', {
    key: 'Enter', keyCode: 13, bubbles: true, cancelable: true
  });
  inp.dispatchEvent(ev);
  ok(w.__calls.openVideo.length === 0, 'comment box Enter does not open a video');
  ok(!ev.defaultPrevented, 'comment box Enter not blocked');
  w.close();
}
{
  // YouTube's newer DOM uses a contenteditable div / different wrapper;
  // detection must fall back to the #search container.
  const w = page('<input type="search" placeholder="Search">');
  const ev = typeLink(w, 'https://youtu.be/' + ID);
  ok(lastOpened(w) && lastOpened(w).id === ID, 'detected via #search container');
  ok(ev.defaultPrevented, 'blocked when detected via container');
  w.close();
}
{
  const w = page('<input type="text" name="search_query">');
  const ev = typeLink(w, 'https://youtu.be/' + ID);
  ok(lastOpened(w) && lastOpened(w).id === ID, 'detected via name=search_query');
  ok(ev.defaultPrevented, 'blocked when detected via name');
  w.close();
}

/* ---- 5. form submit is caught too (some paths skip keydown) ---- */
console.log('\n[5] form submit fallback');
{
  const html = `<!DOCTYPE html><html><head></head><body>
    <ytm-search-box id="search">
      <form id="search-form" action="/results">
        <input type="search" name="search_query" value="https://youtu.be/${ID}">
      </form>
    </ytm-search-box></body></html>`;
  const dom = new JSDOM(html, { url: 'https://m.youtube.com/', runScripts: 'outside-only' });
  const w = dom.window;
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, s) => w.__calls.openVideo.push({ id, title: t, short: s }),
    toast: () => { }, nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { },
    pos: () => 0, bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);
  const form = w.document.getElementById('search-form');
  const ev = new w.Event('submit', { bubbles: true, cancelable: true });
  form.dispatchEvent(ev);
  ok(ev.defaultPrevented, 'submitting a link via the form is blocked');
  ok(w.__calls.openVideo.length === 1 && w.__calls.openVideo[0].id === ID,
     'submitting a link via the form opens the video');
  w.close();
}
{
  const html = `<!DOCTYPE html><html><head></head><body>
    <ytm-search-box id="search">
      <form id="search-form" action="/results">
        <input type="search" name="search_query" value="funny cats">
      </form>
    </ytm-search-box></body></html>`;
  const dom = new JSDOM(html, { url: 'https://m.youtube.com/', runScripts: 'outside-only' });
  const w = dom.window;
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, s) => w.__calls.openVideo.push({ id, title: t, short: s }),
    toast: () => { }, nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { },
    pos: () => 0, bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);
  const form = w.document.getElementById('search-form');
  const ev = new w.Event('submit', { bubbles: true, cancelable: true });
  form.dispatchEvent(ev);
  ok(!ev.defaultPrevented, 'ordinary text submits normally');
  ok(w.__calls.openVideo.length === 0, 'ordinary text opens nothing');
  w.close();
}

/* ---- 6. safety net: a /results page whose query is a link ---- */
console.log('\n[6] results-page fallback');
{
  const q = encodeURIComponent('https://youtu.be/' + ID);
  const dom = new JSDOM('<!DOCTYPE html><html><body><ytm-app></ytm-app></body></html>', {
    url: 'https://m.youtube.com/results?search_query=' + q,
    runScripts: 'outside-only'
  });
  const w = dom.window;
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, s) => w.__calls.openVideo.push({ id, title: t, short: s }),
    toast: () => { }, nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { },
    pos: () => 0, bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);           // report() runs immediately on load
  const got = lastOpened(w);
  ok(got && got.id === ID, 'link in search_query opens the video on the results page');
  ok(w.__calls.nav.length > 0, 'nav reporter still ran');
  w.close();
}
{
  const dom = new JSDOM('<!DOCTYPE html><html><body><ytm-app></ytm-app></body></html>', {
    url: 'https://m.youtube.com/results?search_query=funny+cats',
    runScripts: 'outside-only'
  });
  const w = dom.window;
  w.__calls = { openVideo: [], toast: [], nav: [] };
  w.MTApp = {
    openVideo: (id, t, s) => w.__calls.openVideo.push({ id, title: t, short: s }),
    toast: () => { }, nav: (u) => w.__calls.nav.push(u),
    pause: () => { }, pip: () => { },
    pos: () => 0, bgaudio: () => { }, account: () => { }, play: () => { }
  };
  w.eval(INJECT);
  ok(w.__calls.openVideo.length === 0, 'normal results page untouched');
  ok(w.__calls.nav.length > 0, 'nav reporter ran');
  w.close();
}

/* ---- 7. normal click interception still works (no regression) ---- */
console.log('\n[7] existing click interception intact');
{
  const w = page('<input type="search" name="search_query">');
  const a = w.document.createElement('a');
  a.href = 'https://m.youtube.com/watch?v=' + ID;
  w.document.body.appendChild(a);
  const ev = new w.MouseEvent('click', { bubbles: true, cancelable: true });
  a.dispatchEvent(ev);
  const got = lastOpened(w);
  ok(got && got.id === ID, 'tapping a video link still opens the player');
  ok(ev.defaultPrevented, 'tapping a video link is still intercepted');
  w.close();
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
