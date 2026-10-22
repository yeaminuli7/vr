(function () {
  if (window.__MT) { return; }
  window.__MT = 1;

  /* লোগো-লুকানো-CSS সবার আগে (হেডার জন্মানোর আগেই) — ঝলক বন্ধ */
  function ensureHideCss() {
    if (document.getElementById('__mtbrandcss')) { return; }
    try {
      var st = document.createElement('style');
      st.id = '__mtbrandcss';
      st.textContent = 'ytm-home-logo,ytm-home-logo-renderer,ytm-logo,'
        + 'ytd-logo,.mobile-topbar-logo,c3-icon.mobile-topbar-logo,'
        + '.mobile-topbar-header-endpoint[key="logo"]{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) { }
  }
  ensureHideCss();

  /* ---- ইউটিউবের নিজের নেভ-বার লুকানোর CSS (ডকুমেন্ট-স্টার্টেই → ঝলক নেই) ----
     একই ফাইলের নিচে mtNavOn()/ensureNavCss()/mtHideYtNav() আছে — ফাংশন
     ডিক্লারেশন হোইস্ট হয়, তাই এখানে কল করা যায়। */
  ensureNavCss();

  /* ---- Trusted Types মোকাবিলা (ইউটিউবে innerHTML/DOMParser-ই নিষিদ্ধ) ---- */
  var __ttp = null;
  function ttHtml(el, html) {
    try {
      if (__ttp === null) {
        __ttp = (window.trustedTypes && trustedTypes.createPolicy)
          ? trustedTypes.createPolicy('wtx', { createHTML: function (x) { return x; } })
          : false;
      }
    } catch (e) { __ttp = false; }
    if (__ttp) {
      try { el.innerHTML = __ttp.createHTML(html); return true; } catch (e) { }
    }
    return false;
  }
  function svgNode(markup) {
    try {
      var d = document.createElement('div');
      if (!ttHtml(d, markup)) { return null; }
      return d.firstElementChild;
    } catch (e) { return null; }
  }
  /* ব্র্যান্ড-লোগো: সম্পূর্ণ স্ট্রিং-পার্স-মুক্ত নির্মাণ (সর্বোচ্চ নির্ভরযোগ্য) */
  function makeBrandSvg() {
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 512 512');
    svg.setAttribute('width', '26');
    svg.setAttribute('height', '26');
    svg.style.display = 'block';
    function add(tag, attrs) {
      var el = document.createElementNS(NS, tag);
      for (var k in attrs) { el.setAttribute(k, attrs[k]); }
      svg.appendChild(el);
      return el;
    }
    add('rect', { width: '512', height: '512', rx: '116', fill: '#0F1110' });
    add('path', { d: 'M86 170 L142 362 L198 214 L254 362 L310 170', fill: 'none',
      stroke: '#F2F2F2', 'stroke-width': '46', 'stroke-linecap': 'round',
      'stroke-linejoin': 'round' });
    add('rect', { x: '294', y: '294', width: '132', height: '132', rx: '38', fill: '#34D399' });
    add('path', { d: 'M342 330 L342 390 L392 360 Z', fill: '#0F1110' });
    return svg;
  }

  /* ---- Background playback helpers ---- */
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    Object.defineProperty(document, 'webkitHidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
  } catch (e) { }
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function (ev) {
    document.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
  });

  /* ---- Cosmetic ad hiding ---- */
  try {
    var st = document.createElement('style');
    st.textContent =
        'ytm-promoted-video-renderer,ytm-promoted-sparkles-web-renderer,'
      + 'ytm-promoted-sparkles-shorts-renderer,ytm-ad-slot-renderer,'
      + 'ytm-companion-slot-renderer,ytm-banner-promo-renderer,'
      + 'ytm-mobile-app-promo-renderer,.mobile-app-promo,'
      + 'ytd-promoted-video-renderer,ytd-promoted-sparkles-web-renderer,'
      + 'ytd-promoted-sparkles-text-search-renderer,ytd-ad-slot-renderer,'
      + 'ytd-companion-slot-renderer,ytd-guide-signin-promo-renderer,'
      + 'ytd-banner-promo-renderer,#masthead-ad,#player-ads,#merch-shelf,'
      + '.ytp-ad-image-overlay,.ad-badge,[class*="ad-badge"]'
      + '{display:none!important}';
    document.documentElement.appendChild(st);
  } catch (e) { }

  /* ---- SPA navigation reporter + video click intercept (multi-hook) ---- */
  var lastVid = null;
  function vidFrom(href) {
    // শুধু সাধারণ ভিডিও (watch / youtu.be) — Shorts ওয়েবেই চলে (আগের মতো)
    var m = href.match(/[?&]v=([A-Za-z0-9_-]{11})/)
         || href.match(/youtu\.be\/([A-Za-z0-9_-]{11})/);
    return m ? m[1] : null;
  }
  function isShort(href) {
    return /\/shorts\//.test(href || '');
  }
  function tryOpen(id, short) {
    if (!id) { lastVid = null; return; }
    if (id === lastVid) { return; }
    lastVid = id;
    window.__mtpause = Date.now() + 5000;
    try {
      MTApp.openVideo(id, (document.title || '').replace(/\s*[-|]\s*YouTube\s*$/, ''), !!short);
    } catch (e) { }
  }
  var homeHref = null; // শেষ নন-ওয়াচ পেজ — ফাঁস হলে এখানে ফেরাই
  var lastOtherLink = null, lastOtherAt = 0; // একই বহিঃ-লিংক বারবার না খুলতে
  var lastClickOpen = 0;
  function report() {
    try { MTApp.nav(location.href); } catch (e) { }
    var href = location.href || '';
    // ফলব্যাক: সার্চ-বারের কী-হুক কোনো কারণে ধরতে পারেনি, কিন্তু কোয়েরিটি
    // একটি ভিডিও-লিংক — তখন ফলাফল-পেজেই ধরে নিই
    if (href.indexOf('/results') >= 0) {
      var mq = href.match(/[?&]search_query=([^&]*)/);
      var q = null;
      if (mq) {
        try { q = decodeURIComponent(mq[1].replace(/\+/g, ' ')); } catch (e) { q = mq[1]; }
      }
      var qv = mtVideoLink(q);
      if (qv) {
        tryOpen(qv.id, qv.short);
        setTimeout(function () { try { history.back(); } catch (e) { } }, 350);
        return;
      }
      var qo = mtOtherLink(q);
      if (qo) {
        // বারবার একই লিংক না খুলি (report() প্রতি ৯০০ms চলে)
        if (qo !== lastOtherLink || (Date.now() - lastOtherAt) > 5000) {
          lastOtherLink = qo;
          lastOtherAt = Date.now();
          try { MTApp.openLink(qo); } catch (e) { }
        }
        return;
      }
    }
    var vid = vidFrom(href);
    if (!vid && href.indexOf('youtube.com') >= 0 && href.indexOf('/watch') < 0) {
      homeHref = href;
    }
    if (vid && homeHref && (Date.now() - lastClickOpen) > 1500) {
      // নেভিগেশন ফাঁস করেছে (ক্লিক-ইন্টারসেপ্ট ধরেনি) —
      // আমাদের প্লেয়ার খুলে ইউজারকে আগের পেজে ফিরিয়ে দিই
      tryOpen(vid, isShort(href));
      setTimeout(function () { try { history.back(); } catch (e) { } }, 350);
      return;
    }
    tryOpen(vid, isShort(href));
  }
  document.addEventListener('yt-navigate-finish', report);
  document.addEventListener('popstate', report);
  // হুক ১: পেজ-লোড হলেই যাচাই (ফুল-পেজ নেভিগেশনে ইভেন্ট আসতে পারে না)
  report();
  // হুক ২: পোলিং-ওয়াচার — যেকোনো নেভিগেশন-মেকানিজমই URL বদল লুকাতে পারে না
  setInterval(report, 900);

  /* ---- ভিডিও-লিংক ট্যাপ ক্যাপচার-ফেজে আটকানো: ওয়েব /watch-এ যাবেই না ---- */
  document.addEventListener('click', function (e) {
    try {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (!a) { return; }
      var vid = vidFrom(a.href || '');
      if (!vid) { return; }
      e.preventDefault();
      e.stopImmediatePropagation();
      lastClickOpen = Date.now();
      tryOpen(vid, isShort(a.href || ''));
    } catch (err) { }
  }, true);

  /* ---- সার্চ-বারে ইউটিউব-লিংক পেস্ট করলে ভিডিও খোলা ----
     ইউটিউবের সার্চ-বারে লিংক দিয়ে এন্টার দিলে সে /results?search_query=...
     তে যায় — ভিডিও খোলে না। তাই ইনপুটেই আটকাই: পুরো কোয়েরিটি যদি একটি
     ভিডিও-লিংক হয়, সার্চ না করে সরাসরি ভিডিও খুলে দিই।
     সাধারণ শব্দ দিয়ে সার্চ করলে কিছুই বদলাবে না। */

  /** পুরো স্ট্রিংটি একটি ভিডিও-লিংক হলে {id, short}, নাহলে null। */
  function mtVideoLink(raw) {
    var s = String(raw == null ? '' : raw).trim();
    if (!s || s.length > 300) { return null; }
    var re = /^(?:https?:\/\/)?(?:(?:www|m)\.)?(?:youtube\.com\/(?:watch\?(?:[^#]*&)?v=|shorts\/|embed\/|live\/|v\/)|youtu\.be\/)([A-Za-z0-9_-]{11})(?:[?&#][\s\S]*)?$/;
    var m = s.match(re);
    if (!m) { return null; }
    return { id: m[1], short: /\/shorts\//.test(s) };
  }

  /**
   * পুরো স্ট্রিংটি একটি ইউটিউব-বহির্ভূত http(s) লিংক হলে সেটি, নাহলে null।
   * এগুলো সার্চ না করে সরাসরি ব্রাউজারে খুলে দেওয়া হয় — পেজটা নিজে যে
   * ভিডিও-ফাইল টানে, অ্যাপ সেটাই ধরে ডাউনলোডের সুযোগ দেয়।
   */
  function mtOtherLink(raw) {
    var s = String(raw == null ? '' : raw).trim();
    if (!s || s.length > 2000) { return null; }
    if (mtVideoLink(s)) { return null; }          // ইউটিউব ভিডিও — অন্য পথে
    var m = s.match(/^https?:\/\/([^\/?#\s]+)([^\s]*)$/i);
    if (!m) { return null; }
    var host = m[1].toLowerCase().replace(/:\d+$/, '');
    if (!host || host.indexOf('.') < 0) { return null; }
    if (/(^|\.)youtube\.com$/.test(host) || host === 'youtu.be'
        || /(^|\.)youtube-nocookie\.com$/.test(host)
        || /(^|\.)google\.com$/.test(host)
        || /(^|\.)googleusercontent\.com$/.test(host)
        || /(^|\.)gstatic\.com$/.test(host)
        || /(^|\.)ytimg\.com$/.test(host)
        || /(^|\.)ggpht\.com$/.test(host)) {
      return null;                                 // ইউটিউব/গুগল — সার্চই থাকুক
    }
    return s;
  }

  /** এটি কি ইউটিউবের সার্চ-ইনপুট? (DOM বদলালেও চলবে — বৈশিষ্ট্য দিয়ে চেনা) */
  function mtIsSearchInput(el) {
    if (!el) { return false; }
    var t = (el.tagName || '').toLowerCase();
    if (t !== 'input') { return false; }
    var ty = (el.type || 'text').toLowerCase();
    if (ty === 'submit' || ty === 'button' || ty === 'hidden' || ty === 'checkbox') {
      return false;
    }
    var n = (el.getAttribute && el.getAttribute('name')) || '';
    if (n === 'search_query') { return true; }
    if (n.indexOf('search') >= 0) { return true; }
    if ((el.id || '').toLowerCase().indexOf('search') >= 0) { return true; }
    if (el.closest) {
      if (el.closest('ytd-searchbox') || el.closest('ytm-search-box')
          || el.closest('#search') || el.closest('form#search-form')) {
        return true;
      }
    }
    return false;
  }

  function mtOpenFromSearch(id, short) {
    try { MTApp.toast('Opening video'); } catch (e) { }
    window.__mtpause = Date.now() + 5000;
    try { MTApp.openVideo(id, '', !!short); } catch (e) { }
  }

  /** সত্য = আমরা নিয়েছি (সার্চ বন্ধ); মিথ্যা = স্বাভাবিক সার্চ চলুক। */
  function mtTakeSearch(el) {
    var v = mtVideoLink(el && el.value);
    if (v) {
      try { el.value = ''; } catch (e) { }
      try { el.blur(); } catch (e) { }
      mtOpenFromSearch(v.id, v.short);
      return true;
    }
    // ইউটিউবের বাইরের লিংক (TikTok / Instagram / Facebook / যেকোনো সাইট)
    var o = mtOtherLink(el && el.value);
    if (o) {
      try { el.value = ''; } catch (e) { }
      try { el.blur(); } catch (e) { }
      window.__mtpause = Date.now() + 5000;
      try { MTApp.openLink(o); } catch (e) { }
      return true;
    }
    return false;
  }

  document.addEventListener('keydown', function (e) {
    try {
      if (!e) { return; }
      if (e.keyCode !== 13 && e.key !== 'Enter') { return; }
      var el = e.target;
      if (!mtIsSearchInput(el)) { return; }
      if (!mtTakeSearch(el)) { return; }
      e.preventDefault();
      e.stopImmediatePropagation();
    } catch (err) { }
  }, true);

  document.addEventListener('submit', function (e) {
    try {
      var f = e.target;
      if (!f || !f.querySelectorAll) { return; }
      var ins = f.querySelectorAll('input');
      for (var i = 0; i < ins.length; i++) {
        if (!mtIsSearchInput(ins[i])) { continue; }
        if (!mtTakeSearch(ins[i])) { continue; }
        e.preventDefault();
        e.stopImmediatePropagation();
        return;
      }
    } catch (err) { }
  }, true);

  /* Shorts-অডিও-কিক চালু/বন্ধ (Settings → Web → Shorts audio fix) */
  function mtKickOn() {
    try {
      return !(window.__MTFLAGS && window.__MTFLAGS.skick === false);
    } catch (e) {
      return true;
    }
  }

  /* লগইন-অ্যাকাউন্টের অ্যাভাটার+নাম ধরা (Profile-এ দেখায়) */
  var __accLast = '';
  function mtScanAccount() {
    try {
      var im = null;
      var hdr = document.querySelector('.mobile-topbar-header')
          || document.querySelector('header');
      if (hdr) {
        var c = hdr.querySelectorAll('img');
        for (var i = 0; i < c.length; i++) {
          var src = c[i].getAttribute('src') || '';
          if (/googleusercontent|ggpht/.test(src)) { im = c[i]; break; }
        }
      }
      if (!im) {
        var all = document.querySelectorAll(
            'button img[src*="ggpht"], img[src*="yt3.googleusercontent"]');
        if (all.length) { im = all[0]; }
      }
      if (!im) { return; }
      var src = im.getAttribute('src') || '';
      var nm = (im.getAttribute('alt') || '').trim();
      if (!nm && im.closest) {
        var b = im.closest('button');
        if (b) {
          nm = (b.getAttribute('aria-label') || '')
              .replace(/^Account:?\s*/i, '').trim();
        }
      }
      var sig = src + '|' + nm;
      if (sig === __accLast) { return; }
      __accLast = sig;
      if (window.MTApp && MTApp.account) { MTApp.account(nm, src); }
    } catch (e) { }
  }

  /* সাইন-আউট সনাক্ত: অ্যাভাটার মানে অ্যাকাউন্ট আছে। কোনো গুগল-অ্যাভাটার
     ইমেজই নেই মানে সাইন-ইন নেই (বা সাইন-আউট হয়ে গেছে) — অ্যাপকে জানাই
     যাতে Account-সেকশনে "Sign in with Google" আবার দেখায়। */
  function mtScanSignedOut() {
    try {
      var host = (location.hostname || '').toLowerCase();
      if (host.indexOf('accounts.google.com') >= 0) { return; }
      if (!/youtube\.com$|youtu\.be$/.test(host)) { return; }
      var any = document.querySelectorAll(
          'img[src*="ggpht"], img[src*="yt3.googleusercontent"],'
        + 'img[src*="googleusercontent"]');
      if (any.length) { return; }
      if (__accLast === '') { return; }
      __accLast = '';
      if (window.MTApp && MTApp.account) { MTApp.account('', ''); }
    } catch (e) { }
  }

  /* গুগল ওয়েবভিউতে OAuth ব্লক করলে এই পেজটা আসে — ব্যবহারকারীকে বোঝাতে হবে
     অ্যাপের দোষ নয়। */
  function mtSigninBlocked() {
    try {
      if ((location.hostname || '').indexOf('accounts.google.com') < 0
          && !/\/rejected/.test(location.pathname || '')) {
        return;
      }
      var t = (document.body ? document.body.textContent : '') || '';
      if (!/may not be secure/i.test(t)) { return; }
      if (window.MTApp && MTApp.toast) { MTApp.toast('Google blocked sign-in in this app view'); }
    } catch (e) { }
  }

  /* ---- Play/pause events -> app ---- */
  function bindVideos() {
    try {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (window.__mtpause && Date.now() < window.__mtpause) {
          try { v.pause(); } catch (e) { }
        }
        if (!v.__mt) {
          v.__mt = 1;
          /* Shorts: স্ক্যানের আগেই চলছে-এমন ভিডিও (লিসেনার-জানালা মিস) → এখনই কিক */
          if (mtKickOn() && /\/shorts\//.test(location.pathname || '') && !v.paused && !v.__mtkick) {
            v.__mtkick = 1;
            try { v.pause(); v.play(); } catch (e) { }
          }
          v.addEventListener('play', function () {
            if (window.__mtpause && Date.now() < window.__mtpause) {
              try { this.pause(); } catch (e) { }
              return;
            }
            /* Shorts: WebView-এর প্রথম-প্লেতে অডিও-ট্র্যাক লাগে না —
               পজ-প্লে কিক নিজেই করে দিই (ইউজারের হাতে-যা-করা-কাজ, স্বয়ংক্রিয়) */
            if (mtKickOn() && /\/shorts\//.test(location.pathname || '') && !this.__mtkick) {
              this.__mtkick = 1;
              try { var sv = this; sv.pause(); sv.play(); } catch (e) { }
            }
            try { MTApp.play(); } catch (e) { }
          });
          v.addEventListener('pause', function () { try { MTApp.pause(); } catch (e) { } });
          v.addEventListener('ended', function () { try { MTApp.pause(); } catch (e) { } });
        }
      }
    } catch (e) { }
    setTimeout(bindVideos, 900);
  }
  bindVideos();

  /* ---- Position reporter (audio-mode handoff) ---- */
  setInterval(function () {
    try {
      var v = document.querySelector('video');
      if (v && !v.paused && !v.ended) {
        MTApp.pos(v.currentTime, isFinite(v.duration) ? v.duration : 0,
                  (document.title || '').substring(0, 120), location.href);
      }
    } catch (e) { }
  }, 1000);

  /* ---- Ad skipper + overlay closer ---- */
  setInterval(function () {
    try {
      var s = document.querySelector('.ytp-ad-skip-button-modern,.ytp-ad-skip-button,'
        + 'button[class*="skip-button"]');
      if (s) { s.click(); }
      var c = document.querySelector('.ytp-ad-overlay-close-button,'
        + '.ytp-ad-overlay-close-container button');
      if (c) { c.click(); }
    } catch (e) { }
  }, 400);

  /* ---- "Open in YouTube" promo removal (all languages, structural) ---- */
  var APPRX = /open in youtube|open in app|open the app|open app|open youtube|in the youtube app|use the youtube app|watch on youtube|get the app|download youtube|install the app|switch to the app|(?:\u0987\u0989\u099f\u09bf\u0989\u09ac|youtube)[^\n]{0,30}(?:\u0985\u09cd\u09af\u09be\u09aa|\u0996\u09c1\u09b2|\u0993\u09aa\u09c7\u09a8)|\u0985\u09cd\u09af\u09be\u09aa(?:\u09c7|\u09c7\u09b0)[^\n]{0,20}(?:\u0996\u09c1\u09b2|\u09a6\u09c7\u0996|\u09a8)|\u0993\u09aa\u09c7\u09a8/i;
  try {
    var st3 = document.createElement('style');
    st3.textContent = 'ytm-smartbanner-renderer,ytm-promo-banner-renderer,'
      + 'ytm-app-banner-renderer,ytm-banner-promo-renderer{display:none!important}';
    document.documentElement.appendChild(st3);
  } catch (e) { }
  function iCount(el) {
    try { return el.querySelectorAll('a,button,input,textarea,select').length; }
    catch (e) { return 999; }
  }
  /* surgical: এমন পূর্বপুরুষই লুকায় যার ভেতরে প্রোমো ছাড়া আর কোনো interactive নেই —
     সার্চ/বেল-বোতামসহ কন্টেইনার কখনো লুকাবে না */
  function hideBanner(n) {
    try {
      var el = n, best = n;
      var base = iCount(n);
      for (var h = 0; h < 6 && el && el !== document.body; h++) {
        el = el.parentElement;
        if (!el) { break; }
        if (iCount(el) === base) { best = el; } else { break; }
      }
      best.style.display = 'none';
    } catch (e) {
      try { n.style.display = 'none'; } catch (e2) { }
    }
  }
  /* ---- player top-right "Open App" CTA (positional killer) ---- */
  function killPlayerCta() {
    try {
      var pl = document.querySelector('.player-container')
             || document.querySelector('#player');
      if (!pl) { return; }
      var pr = pl.getBoundingClientRect();
      var els = pl.querySelectorAll('button, a, ytm-*, div[class], span[class]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.__mtcta) { continue; }
        var er = el.getBoundingClientRect();
        if (er.width <= 0 || er.height <= 0 || er.width > 240 || er.height > 72) { continue; }
        if (er.right < pr.right - pr.width * 0.4 || er.top > pr.top + 110) { continue; }
        var t = (el.textContent || '').trim();
        var al = el.getAttribute('aria-label') || '';
        var s = (t + ' ' + al).toLowerCase();
        if (s.length > 0 && s.length < 80
            && (APPRX.test(t) || APPRX.test(al))) {
          el.__mtcta = 1;
          el.style.display = 'none';
        }
      }
    } catch (e) { }
  }

  /* ---- Notifications bell (topbar-এ, সার্চের পাশে) ---- */
  function addBell() {
    try {
      var bar = document.querySelector('ytm-mobile-topbar-renderer');
      if (!bar || document.getElementById('__mtbell')) { return; }
      var hdr = bar.querySelector('header.mobile-topbar-header') || bar;
      var a = document.createElement('a');
      a.id = '__mtbell';
      a.href = '/feed/notifications';
      a.setAttribute('aria-label', 'Notifications');
      a.style.cssText = 'display:-webkit-flex;display:flex;-webkit-align-items:center;'
        + 'align-items:center;margin:0 2px;padding:8px;-webkit-flex:0 0 auto;flex:0 0 auto;';
      var bs = svgNode('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="21" height="21" fill="#fff">'
        + '<path d="M12 22a2.3 2.3 0 0 0 2.3-2.3H9.7A2.3 2.3 0 0 0 12 22zm7-5.3v-1l-1.7-1.7v-4'
        + 'A5.5 5.5 0 0 0 13 4.6V3.5a1 1 0 1 0-2 0v1.1a5.5 5.5 0 0 0-4.3 5.4v4L5 15.7v1z"/></svg>');
      if (bs) { a.appendChild(bs); }
      /* ফোন-টাচ: YT-জেসচার-স্তর click জন্মাতে দেয় না — touchend/pointerup-এই নেভিগেট */
      var bellGo = function (ev) {
        try {
          if (Date.now() - (a.__mtgo || 0) < 600) { return; }
          a.__mtgo = Date.now();
          if (ev.cancelable) { ev.preventDefault(); }
          ev.stopPropagation();
          if (ev.stopImmediatePropagation) { ev.stopImmediatePropagation(); }
          location.assign('/feed/notifications');
        } catch (e2) { }
      };
      a.addEventListener('touchend', bellGo, false);
      a.addEventListener('pointerup', bellGo, false);
      a.addEventListener('click', bellGo, false);
      hdr.appendChild(a); /* হেডারের শেষ সন্তান = ডান-প্রান্ত */
    } catch (e) { }
  }

  /* ---- Popup (PiP) + Background buttons in action row ---- */
  var SVGP = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="currentColor">'
    + '<path d="M19 11h-8v6h8v-6zm2-8H3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V5'
    + 'a2 2 0 0 0-2-2zm0 16H3V6.98h18V19z"/></svg>';
  var SVGB = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="currentColor">'
    + '<path d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z"/></svg>';
  function mtActBtn(id, svg, label) {
    var b = document.createElement('button');
    b.id = id;
    b.setAttribute('aria-label', label);
    b.style.cssText = 'display:-webkit-flex;display:flex;-webkit-flex-direction:column;'
      + 'flex-direction:column;-webkit-align-items:center;align-items:center;'
      + 'background:none;border:0;color:#fff;padding:4px 12px;font-size:11px;line-height:1.4';
    var sv = svgNode(svg);
    if (sv) { b.appendChild(sv); }
    var sp = document.createElement('span');
    sp.textContent = label;
    b.appendChild(sp);
    return b;
  }
  function addActions() {
    try {
      if (document.getElementById('__mtpip')) { return; }
      var host = document.querySelector('ytm-video-actions-renderer')
        || document.querySelector('ytm-watch-metadata-renderer')
        || document.querySelector('ytm-watch');
      if (!host) { return; }
      var row = document.createElement('div');
      row.style.cssText = 'display:-webkit-flex;display:flex;padding:2px 4px 8px;';
      row.appendChild(mtActBtn('__mtpip', SVGP, 'Popup'));
      row.appendChild(mtActBtn('__mtbg', SVGB, 'Background'));
      host.appendChild(row);
      document.getElementById('__mtpip').addEventListener('click', function () {
        try { MTApp.pip(); } catch (e) { }
      });
      document.getElementById('__mtbg').addEventListener('click', function () {
        try { MTApp.bgaudio(); } catch (e) { }
      });
    } catch (e) { }
  }

  function killAppPromo() {
    try {
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
      var n;
      while ((n = w.nextNode())) {
        var t = n.nodeValue;
        if (!t) { continue; }
        t = t.trim();
        if (t.length === 0 || t.length > 60) { continue; }
        if (!APPRX.test(t)) { continue; }
        var el = n.parentElement;
        if (!el || el.__mtp) { continue; }
        el.__mtp = 1;
        hideBanner(el);
      }
    } catch (e) { }
  }

  /* ---- লোগো রিব্র্যান্ড (Wtube: আইকন-B + নাম) ---- */

  function rebrand() {
    try {
      if (location.pathname.indexOf('/watch') === 0) { return; }
      var bar = document.querySelector('ytm-mobile-topbar-renderer')
        || document.querySelector('ytm-header');
      if (!bar) { return; }
      /* আসল হেডার-সারি (প্রমাণিত গঠন: ytm-mobile-topbar-renderer > header.mobile-topbar-header) */
      var hdr = bar.querySelector('header.mobile-topbar-header')
        || bar.querySelector('header') || bar;
      /* ১) নিজেদের ব্র্যান্ড হেডার-সারির একদম বাঁয়ে */
      if (!document.getElementById('__mtbrand')) {
        var a = document.createElement('a');
        a.id = '__mtbrand';
        a.href = '/';
        a.setAttribute('aria-label', 'Wtube');
        a.title = 'Wtube';
        a.appendChild(makeBrandSvg());
        var sp = document.createElement('span');
        sp.textContent = 'Wtube';
        sp.style.cssText = 'margin-left:7px;font-weight:600;font-size:17px;letter-spacing:.2px';
        a.appendChild(sp);
        a.style.cssText = 'display:-webkit-flex;display:flex;-webkit-align-items:center;'
          + 'align-items:center;text-decoration:none;color:inherit;margin:0 10px 0 4px;'
          + '-webkit-flex:0 0 auto;flex:0 0 auto;align-self:center;';
        hdr.insertBefore(a, hdr.firstChild);
      }
      ensureHideCss();
      /* ৩) রানটাইম-ফলব্যাক: হেডারের ভেতরে YouTube-লেবেল/লোগো-শ্রেণির যে-কোনো জিনিস */
      var els = hdr.querySelectorAll('[aria-label],[title],[class*="logo"],[key]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (!el || el.id === '__mtbrand' || el.id === '__mtbell' || el.id === '__mtset') { continue; }
        var aria = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '');
        var cls = ' ' + ((el.getAttribute('class') || '') + ' ' + (el.className || ''));
        var tag = (el.tagName || '').toLowerCase();
        var isLogo = /youtube\s+home|go\s+to\s+home|^youtube$/i.test(aria.trim())
          || /(^|\s)(mobile-topbar-logo|full-logo|home-logo|topbar-logo)(\s|$)/i.test(cls)
          || tag === 'ytm-home-logo' || tag === 'ytm-logo' || tag === 'ytd-logo';
        if (isLogo && el.style.display !== 'none') {
          el.style.display = 'none';
          el.setAttribute('hidden', '');
        }
      }
    } catch (e) { }
  }

  /* ---- তাৎক্ষণিক প্রোমো-কিলার: DOM-এ ঢোকামাত্রই লুকায় (৬০০ms-দেরি বাতিল) ---- */
  var __mtFlush = false;
  function flushK() {
    if (__mtFlush) { return; }
    __mtFlush = true;
    setTimeout(function () {
      __mtFlush = false;
      try { killAppPromo(); killPlayerCta(); rebrand(); mtHideYtNav(); } catch (e) { }
    }, 30);
  }
  try {
    var MO = window.MutationObserver || window.WebKitMutationObserver;
    if (MO) {
      new MO(function () { flushK(); })
        .observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) { }

  setInterval(function () { killAppPromo(); killPlayerCta(); addBell(); rebrand(); addActions(); mtScanAccount(); mtScanSignedOut(); mtSigninBlocked(); mtHideYtNav(); }, 600);

  /* ================================================================
     ইউটিউবের নিজের নেভিগেশন বার লুকানো — আমাদের ন্যাভ-বারই একমাত্র
     ----------------------------------------------------------------
     সমস্যা: সাইন-ইন করলে ইউটিউব ফিড-ট্যাবগুলো (Home / Shorts /
     Subscriptions / Library) উপরে সরিয়ে দেয় — ২০২৬-এর নতুন "swipeable
     top tabs" লেআউট (টিম ইউটিউব ৭ মে ২০২৬ অ্যানাউন্স)। ফলে আমাদের
     নিচের ন্যাভ-বারের সাথে উপরে ডুপ্লিকেট Home/Shorts/… বার দেখা যায়।

     সমাধান — দুই স্তর:
       ১) CSS: জানা কনটেইনার (ytm-pivot-bar-renderer, টপ-ট্যাব-স্ট্রিপ …)
          ডকুমেন্ট-স্টার্টেই লুকানো → কোনো ঝলক নেই।
       ২) রানটাইম শনাক্তকরণ: লিংক-সিগনেচার (href = /, /shorts, /feed/…)
          দিয়ে যেকোনো "নেভ-স্ট্রিপ" খুঁজে বের করে লুকানো। ইউটিউব ক্লাস-নাম
          বদলালেও কাজ করে — এটাই আসল সমাধান।
     ================================================================ */
  function mtNavOn() {
    try {
      return !(window.__MTFLAGS && window.__MTFLAGS.navhide === false);
    } catch (e) { return true; }
  }

  /* শুধু ইউটিউবের পেজে কাজ করবে (Google-লগইন-পেজে ঢুকবে না) */
  function mtNavHostOk() {
    try {
      var h = (location.hostname || '').toLowerCase();
      if (!h) { return true; }
      return h.indexOf('youtube.com') >= 0 || h.indexOf('youtu.be') >= 0
          || h.indexOf('youtube-nocookie.com') >= 0;
    } catch (e) { return true; }
  }

  /* ইউটিউবের ট্যাব-ডেস্টিনেশন (হোম / শর্টস / সাবস্ক্রাইব / ইউ-লাইব্রেরি) */
  var NAV_PATHS = {
    '/': 1, '/shorts': 1, '/feed/subscriptions': 1, '/feed/you': 1,
    '/feed/trending': 1, '/feed/history': 1, '/feed/explore': 1,
    '/feed/library': 1, '/feed/storefront': 1
  };

  /* শুধু নেভ-ওয়ার্ড লেবেল (ফিল্টার-চিপে "Shorts" বৈধ ফিল্টার, তাই বাদ) */
  var NAV_WORDS = /^(home|subscriptions?|library|you|explore|trending|history)$/;

  /* এলিমেন্টের href (নিজের, না পারলে ভেতরের a) */
  function mtNavHref(el) {
    try {
      if (!el) { return ''; }
      var a = ((el.tagName || '').toLowerCase() === 'a') ? el
            : (el.querySelector ? el.querySelector('a[href]') : null);
      if (!a) { return ''; }
      return a.getAttribute('href') || '';
    } catch (e) { return ''; }
  }

  /* নেভ-পাথ (কোয়েরি-সহ, যেমন "/?itct=0") — সিগনেচারে ব্যবহার */
  function mtNavPath(el) {
    try {
      var h = mtNavHref(el);
      if (!h || h.charAt(0) !== '/') { return null; }
      var p = h.split('?')[0].split('#')[0];
      if (p.length > 1 && p.charAt(p.length - 1) === '/') {
        p = p.slice(0, p.length - 1);
      }
      return NAV_PATHS[p] ? p : null;
    } catch (e) { return null; }
  }

  /* ঠিক নেভ-পাথ (কোয়েরি/হ্যাশ ছাড়া) — চিপ শনাক্তে ব্যবহার */
  function mtNavExact(el) {
    try {
      var h = mtNavHref(el);
      if (!h || h.charAt(0) !== '/' || h.indexOf('?') >= 0 || h.indexOf('#') >= 0) {
        return null;
      }
      var p = h;
      if (p.length > 1 && p.charAt(p.length - 1) === '/') {
        p = p.slice(0, p.length - 1);
      }
      return NAV_PATHS[p] ? p : null;
    } catch (e) { return null; }
  }

  /* এই লিংকটা কখনো নেভ-বারের অংশ নয় (লোগো / অ্যাকাউন্ট / সার্চ) */
  function mtNavLinkSafe(el) {
    try {
      if (el.closest && el.closest(
          'ytm-account-avatar,ytm-home-logo,ytm-search-box,#search,'
        + '#__mtbrand,#__mtbell,.mobile-topbar-header-endpoint')) { return true; }
      return false;
    } catch (e) { return false; }
  }

  /* যে কনটেইনারটি কখনো লুকানো হবে না (আমাদের হেডার/সার্চ থাকে) */
  function mtNavKeep(el) {
    try {
      var t = (el.tagName || '').toLowerCase();
      if (t === 'ytm-mobile-topbar-renderer' || t === 'ytm-header-bar'
          || t === 'ytm-app' || t === 'ytm-watch' || t === 'ytm-browse'
          || t === 'ytm-single-column-browse-results-renderer'
          || t === 'ytm-account-avatar' || t === 'ytm-home-logo'
          || t === 'body' || t === 'html') { return true; }
      var c2 = (' ' + (el.getAttribute('class') || '') + ' ').toLowerCase();
      if (c2.indexOf(' mobile-topbar-header ') >= 0) { return true; }
      /* সার্চ-বক্স / আমাদের ব্র্যান্ড / বেল থাকলে লুকাবো না */
      if (el.querySelector('input,ytm-search-box,#search,#__mtbrand,#__mtbell')) {
        return true;
      }
      /* ফিড-চিপ-বার আলাদাভাবে (গ্রানুলার) সামলানো হয় */
      if (el.closest && el.closest(
          'ytm-feed-filter-chip-bar-renderer,#filter-chip-bar')) { return true; }
      return false;
    } catch (e) { return true; }
  }

  /* সরাসরি-সন্তান হিসেবে নেভ-লিংক ধরে এমন বারের সংখ্যা (ক্লাস-নাম ছাড়াই
     শনাক্ত করার জন্য: যেমন <div><a href="/">…</a><a href="/shorts">…</a></div>) */
  function mtNavDirectNavCount(el) {
    try {
      var n = 0;
      var kids = el.children || [];
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        if (mtNavPath(k)) { n++; continue; }
        /* অনেক সন্তান থাকলে (কার্ড-গ্রিড) ধরা পড়ে না — খরচও কম */
        if (k.children && k.children.length <= 2) {
          var a = k.querySelector ? k.querySelector('a[href]') : null;
          if (a && mtNavPath(a)) { n++; }
        }
      }
      return n;
    } catch (e) { return 0; }
  }

  /* ছোট বার (পুরো পেজ-কনটেইনার হলে লুকাবো না) */
  function mtNavSmall(el) {
    try {
      var r = el.getBoundingClientRect();
      return !(r.height > 280);
    } catch (e) { return true; }
  }

  /* স্ট্রিপ-মন্দি: ট্যাব/চিপ/পিভট/নেভ-মতো দেখতে এবং ছোট */
  function mtNavStrip(el) {
    try {
      var cls = (' ' + (el.getAttribute('class') || '') + ' ').toLowerCase();
      var tag = (el.tagName || '').toLowerCase();
      var role = el.getAttribute('role') || '';
      var looks = /tab|chip|pivot|nav|menu|guide|bar/.test(cls + ' ' + tag)
            || role === 'tablist' || role === 'tab' || role === 'navigation';
      if (!looks) { return false; }
      return mtNavSmall(el);
    } catch (e) { return false; }
  }

  /* নেভ-আইটেম-মতো (একটা ট্যাব/চিপ/আইকন) */
  function mtNavItemLike(el) {
    try {
      var cls = (' ' + (el.getAttribute('class') || '') + ' ').toLowerCase();
      var tag = (el.tagName || '').toLowerCase();
      return /item|tab|chip|pivot|option|entry/.test(cls + ' ' + tag);
    } catch (e) { return false; }
  }

  /* কনটেন্ট-কনটেইনার (ভিডিও-গ্রিড/শেল্ফ) — নেভ-বার হতে পারে না */
  function mtNavContentish(el) {
    try {
      var cls = (' ' + (el.getAttribute('class') || '') + ' ').toLowerCase();
      var tag = (el.tagName || '').toLowerCase();
      return /video|lockup|rich|compact|grid|shelf|list|section|metadata|content/
        .test(cls + ' ' + tag);
    } catch (e) { return false; }
  }

  /* লেবেল (টেক্সট/tab-title/title) */
  function mtNavLabelOf(el) {
    try {
      var t = (el.getAttribute('tab-title') || el.getAttribute('title')
            || el.textContent || '').trim().toLowerCase();
      return t.replace(/\s+/g, ' ');
    } catch (e) { return ''; }
  }

  /* লেবেল কি নেভ-ওয়ার্ড? ("youtube" → মিস না, কারণ \b) */
  function mtNavLabelIsNav(t) {
    try {
      return /^(home|shorts|subscriptions?|library|you|explore|trending|history)\b/
        .test(t || '');
    } catch (e) { return false; }
  }

  /* এলিমেন্ট (নিজে বা নেস্টেড) কি নেভ-ওয়ার্ড-লেবেলযুক্ত? */
  function mtNavLabelIsNavEl(el) {
    try {
      if (mtNavLabelIsNav(mtNavLabelOf(el))) { return true; }
      var kids = el.querySelectorAll
          ? el.querySelectorAll('[tab-title],[title]') : [];
      for (var i = 0; i < kids.length; i++) {
        if (mtNavLabelIsNav(mtNavLabelOf(kids[i]))) { return true; }
      }
      return false;
    } catch (e) { return false; }
  }

  /* বাস্ট্রে-লিংক (আসল href) আছে কি না — চ্যানেল-ট্যাব বাদ রাখতে */
  function mtNavHasRealLink(el) {
    try {
      var as = el.querySelectorAll('a[href]');
      for (var i = 0; i < as.length; i++) {
        var h = as[i].getAttribute('href') || '';
        if (h && h.charAt(0) !== '#') { return true; }
      }
      return false;
    } catch (e) { return true; }
  }

  /* ছোট কনটেইনারে নেভ-ওয়ার্ড-লেবেলযুক্ত আইটেম সংখ্যা */
  function mtNavLabelHits(el) {
    try {
      var n = 0;
      var kids = el.children || [];
      for (var i = 0; i < kids.length; i++) {
        if (mtNavLabelIsNavEl(kids[i])) { n++; }
      }
      return n;
    } catch (e) { return 0; }
  }

  function mtNavHide(el) {
    try {
      if (!el || el.__mtnav) { return; }
      el.__mtnav = 1;
      el.style.setProperty('display', 'none', 'important');
      el.setAttribute('hidden', '');
    } catch (e) { }
  }

  function mtNavShow(el) {
    try {
      if (!el || !el.__mtnav) { return; }
      el.__mtnav = 0;
      el.style.removeProperty('display');
      el.removeAttribute('hidden');
    } catch (e) { }
  }

  /* ---- CSS স্তর: জানা কনটেইনার (অ্যান্টি-ফ্লিকার) ---- */
  function ensureNavCss() {
    if (!mtNavOn()) { return; }
    if (!mtNavHostOk()) { return; }
    if (document.getElementById('__mtnavcss')) { return; }
    try {
      var st = document.createElement('style');
      st.id = '__mtnavcss';
      var hide =
          /* নিচের ট্যাব-বার (Home/Shorts/Subs/You) — আমাদের ডুপ্লিকেট */
          'ytm-pivot-bar-renderer,'
        + 'ytd-mini-guide-renderer,ytd-guide-renderer,'
        + '#appbar-guide-menu,'
          /* উপরের ট্যাব-স্ট্রিপ (২০২৬-এর নতুন লেআউট + যেকোনো নতুন নাম) */
        + 'ytm-mobile-topbar-renderer [role="tablist"],'
        + 'ytm-mobile-topbar-renderer .mobile-topbar-tabs,'
        + 'ytm-mobile-topbar-renderer .topbar-tabs,'
        + 'ytm-mobile-topbar-renderer ytm-topbar-tabs-renderer,'
        + 'ytm-mobile-topbar-renderer ytm-mobile-topbar-tabs,'
        + 'ytm-topbar-tabs-renderer,ytm-mobile-topbar-tabs,'
        + 'ytm-feed-filter-chip-bar-renderer [role="tablist"],'
        + 'ytm-header-bar [role="tablist"],'
        + 'ytm-header-bar .mobile-topbar-tabs,'
          /* অ্যাপ-শেলের সরাসরি-সন্তান ট্যাব-বার (নতুন যেকোনো লেআউট) */
        + 'ytm-app > [role="tablist"],ytm-app > ytm-top-tabs-renderer';
      var pad =
          /* পিভট-বার লোডা থাকলে বডি/অ্যাপের বাড়তি নিচের প্যাডিং বাদ
             (আমাদের ন্যাভ-বার WebView-এর বাইরে, তাই এই প্যাডিং ফাঁকা জায়গা) */
          'body[has-pivot-bar="true"],body[has-pivot-bar],ytm-app';
      st.textContent = hide + '{display:none!important}' + pad
        + '{padding-bottom:0!important}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) { }
  }

  function mtNavCssOff() {
    try {
      var el = document.getElementById('__mtnavcss');
      if (el && el.parentNode) { el.parentNode.removeChild(el); }
    } catch (e) { }
  }

  /* ---- রানটাইম স্তর: লিংক-সিগনেচার দিয়ে শনাক্ত করে লুকানো ---- */
  function mtHideYtNav() {
    try {
      if (!mtNavOn()) {
        mtNavCssOff();
        var all = document.querySelectorAll('[__mtnav]');
        for (var z = 0; z < all.length; z++) { mtNavShow(all[z]); }
        return;
      }
      if (!mtNavHostOk()) { return; }
      ensureNavCss();

      /* ১) পুরো নেভ-বার (নিচের পিভট-বার, গাইড) */
      var known = document.querySelectorAll(
          'ytm-pivot-bar-renderer,ytd-mini-guide-renderer,'
        + 'ytd-guide-renderer,#appbar-guide-menu');
      for (var i = 0; i < known.length; i++) { mtNavHide(known[i]); }

      /* ২) ফিড-চিপ-বারের ভেতরের নেভ-চিপ (Home / Subscriptions …) লুকাও —
            বাকি ফিল্টার-চিপ (All / Music / Gaming) থাকে */
      var bars = document.querySelectorAll(
          'ytm-feed-filter-chip-bar-renderer,#filter-chip-bar');
      for (var b = 0; b < bars.length; b++) {
        var chips = bars[b].querySelectorAll(
            'ytm-chip-cloud-chip-renderer,[role="tab"]');
        for (var c = 0; c < chips.length; c++) {
          var ch = chips[c];
          if (ch.__mtnav) { continue; }
          /* চিপের ভেতরের লিংকটা আলাদা করে ধরবো না (ডুপ্লিকেট) */
          var inner = ch.querySelector('a[href]');
          if (inner && inner.__mtnav) { continue; }
          var lbl = (ch.getAttribute('tab-title')
                  || (ch.textContent || '')).trim().toLowerCase();
          var navish = !!mtNavExact(ch) || NAV_WORDS.test(lbl);
          if (navish) {
            if (inner) { mtNavHide(inner); }
            mtNavHide(ch);
          }
        }
      }

      /* ৩) সিগনেচার-স্ক্যান: প্রতিটি নেভ-লিংক থেকে উপরে উঠে তার "স্ট্রিপ" খুঁজে
            লুকানো (ক্লাস-নাম বদলালেও চলে) */
      var links = document.querySelectorAll('a[href]');
      for (var k = 0; k < links.length; k++) {
        var lk = links[k];
        if (!mtNavPath(lk)) { continue; }
        if (mtNavLinkSafe(lk)) { continue; }
        var cur = lk.parentElement;
        var cand = null;
        var guard = 0;
        while (cur && guard++ < 12) {
          if (mtNavKeep(cur)) { break; }
          /* স্ট্রিপ-চিহ্ন (ক্লাস/role) অথবা সরাসরি-নেভ-লিংক-বার */
          if (mtNavStrip(cur)
              || (mtNavDirectNavCount(cur) >= 2 && mtNavSmall(cur))) { cand = cur; }
          cur = cur.parentElement;
        }
        if (cand) { mtNavHide(cand); continue; }
        /* স্ট্রিপ পাওয়া গেল না → নিজে আইটেমটাই লুকাও */
        var it = lk.parentElement;
        if (it && mtNavItemLike(it) && !mtNavKeep(it)) { mtNavHide(it); }
        else if (!mtNavKeep(lk)) { mtNavHide(lk); }
      }

      /* ৪) লেবেল-ভিত্তিক: লিংক না থাকলেও (JS-নেভিগেশন) নেভ-বার শনাক্ত —
            যেখানে "Home"/"Shorts"/"Subscriptions" লেবেল আছে কিন্তু আসল
            href নেই (চ্যানেল-ট্যাবের মতো আসল-লিংকযুক্ত বার বাদ থাকে) */
      var shells = document.querySelectorAll(
          'ytm-mobile-topbar-renderer,ytm-header-bar,ytm-pivot-bar-renderer,'
        + 'ytm-browse,ytm-app,header,ytm-single-column-browse-results-renderer');
      for (var s = 0; s < shells.length; s++) {
        var kids = shells[s].children || [];
        for (var j = 0; j < kids.length; j++) {
          var box = kids[j];
          if (box.__mtnav) { continue; }
          if (mtNavKeep(box)) { continue; }
          if (mtNavContentish(box)) { continue; }
          if (!mtNavSmall(box)) { continue; }
          if (mtNavHasRealLink(box)) { continue; }
          if (mtNavLabelHits(box) >= 2) { mtNavHide(box); }
        }
      }

      /* ৫) হেডারের ভেতরের আলাদা আইটেম-লেভেল নেভ (স্ট্রিপ-ছাড়া) */
      var keeps = document.querySelectorAll(
          'ytm-mobile-topbar-renderer,ytm-header-bar,header,ytm-app');
      for (var q = 0; q < keeps.length; q++) {
        var kk = keeps[q].children || [];
        for (var m = 0; m < kk.length; m++) {
          var one = kk[m];
          if (one.__mtnav) { continue; }
          if (mtNavHasRealLink(one)) { continue; }
          if (mtNavContentish(one)) { continue; }
          if (!mtNavItemLike(one)) { continue; }
          if (!mtNavLabelIsNavEl(one)) { continue; }
          mtNavHide(one);
        }
      }
    } catch (e) { }
  }

  /* ---- ইউটিউবের নিজের ট্যাব-বার লুকানো (আমাদের ন্যাভ-বার ব্যবহৃত) ---- */
  mtHideYtNav();
})();
