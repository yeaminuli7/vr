(function () {
  if (window.__MTDS) { return; }
  window.__MTDS = 1;

  /* uBlock-স্ক্রিপ্টলেটের সমতুল্য: প্লেয়ার-রেসপন্স থেকে
     অ্যাড-কনফিগ মুছে ফেলা, পেজের স্ক্রিপ্ট তা পাওয়ার আগেই। */

  var RX = /youtubei\/v1\/player|\/player\?|get_video_info|\/player\/|\/watch\?/;

  function mtFmt(j) {
    try {
      var sd = j && j.streamingData;
      if (!sd) { return; }
      var fs = [];
      if (sd.formats) { fs = fs.concat(sd.formats); }
      if (sd.adaptiveFormats) { fs = fs.concat(sd.adaptiveFormats); }
      for (var i = 0; i < fs.length && i < 24; i++) {
        var f = fs[i];
        if (f && f.url) {
          try { MTApp.fmt(f.url, '' + f.itag, f.mimeType || ''); } catch (e) { }
        }
      }
    } catch (e) { }
  }

  function stripObj(o) {
    try {
      if (!o || typeof o !== 'object') { return; }
      mtFmt(o);
      delete o.adPlacements;
      delete o.adSlots;
      delete o.adBreakHeartbeatParams;
      if ('playerAds' in o) { o.playerAds = []; }
      var pr = o.playerResponse;
      if (pr) {
        delete pr.adPlacements;
        delete pr.adSlots;
        if ('playerAds' in pr) { pr.playerAds = []; }
      }
      if (o.response && o.response.playerResponse) {
        stripObj(o.response.playerResponse);
      }
    } catch (e) { }
  }

  /* ১) গ্লোবাল JSON.parse-ট্র্যাপ: পেজ যখনই প্লেয়ার-ডেটা পার্স করবে,
     আমরা তার আগে অ্যাড-কীগুলো সরিয়ে দিই (fetch/XHR দুই পথেই কাজ করে)। */
  try {
    var jp = JSON.parse;
    JSON.parse = function (t, r) {
      var j = jp.call(this, t, r);
      try {
        if (j && typeof j === 'object') {
          if (j.streamingData) { mtFmt(j); }
          if (j.adPlacements || j.adSlots || j.playerAds || j.playerResponse
              || (j.response && j.response.playerResponse)) {
            stripObj(j);
          }
        }
      } catch (e) { }
      return j;
    };
  } catch (e) { }

  /* ২) fetch-র‍্যাপার: প্লেয়ার-API-র রেসপন্স-বডি ঢেলে সাজানো */
  try {
    var of = window.fetch;
    if (of) {
      window.fetch = function () {
        var p = of.apply(this, arguments);
        try {
          var u = arguments[0];
          var url = (typeof u === 'string') ? u : (u && u.url) || '';
          if (RX.test(url)) {
            return p.then(function (resp) {
              try {
                var ct = (resp.headers && resp.headers.get('content-type')) || '';
                if (ct.indexOf('json') < 0) { return resp; }
                return resp.clone().text().then(function (t) {
                  try {
                    var j = JSON.parse(t);
                    stripObj(j);
                    var t2 = JSON.stringify(j);
                    if (t2 === t) { return resp; }
                    var h = new Headers(resp.headers);
                    h.delete('content-length');
                    return new Response(t2, {
                      status: resp.status,
                      statusText: resp.statusText,
                      headers: h
                    });
                  } catch (e) { return resp; }
                });
              } catch (e) { return resp; }
            });
          }
        } catch (e) { }
        return p;
      };
    }
  } catch (e) { }

  /* ৩) ytInitialPlayerResponse-ফাঁদ: পেজ-লোডের ইনলাইন ডেটাও পরিষ্কার */
  try {
    var cur = window.ytInitialPlayerResponse;
    if (cur) { stripObj(cur); }
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return cur; },
      set: function (v) {
        cur = v;
        stripObj(cur);
      }
    });
  } catch (e) { }
})();
