package com.mytube.web;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * স্ট্রিম-রিজলভার v3 — NewPipe-র প্রমাণিত রেসিপি (টোকেন-মুক্ত)।
 *
 * মূল কৌশল (NewPipe v0.29.1 থেকে শেখা):
 *  ১) visitor_id এন্ডপয়েন্ট থেকে টাটকা visitorData নেওয়া
 *  ২) স্ট্রিম-URL-এর মূল উৎস = VISIONOS ক্লায়েন্ট (youtubei.googleapis.com-এ /player),
 *     পূর্ণ ডিভাইস-কনটেক্সট + ক্লায়েন্ট-স্ক্রিন WATCH + cpn নন্স
 *  ৩) ANDROID ক্লায়েন্ট reel_item_watch এন্ডপয়েন্ট দিয়ে প্লেয়াবিলিটি-চেক + ফলব্যাক
 *  ৪) শেষ-ভরসা: IOS (পূর্ণ কনটেক্সট) → পুরনো ANDROID → WEB; সব ধাপে HLS-ম্যানিফেস্ট সংগ্রহ
 *
 * প্রতিটি ধাপের ফল Res.log-এ জমে — ব্যর্থ হলে ডায়ালগে কারণ দেখা যায়।
 */
public class Resolver {

    /** রিলেটেড-ভিডিও সারি (/next, WEB ক্লায়েন্ট — স্যান্ডবক্স-প্রমাণিত)। */
    public static class Rel {
        public final String id;
        public final String title;
        public final String by;
        public final String dur;
        public final String thumb;

        public Rel(String id, String title, String by, String dur, String thumb) {
            this.id = id;
            this.title = title;
            this.by = by;
            this.dur = dur;
            this.thumb = thumb;
        }
    }

    private static final String WUA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** /next থেকে রিলেটেড তালিকা (lockupViewModel-পার্স, টোকেনমুক্ত)। */
    public static List<Rel> related(String vid, StringBuilder log) {
        List<Rel> out = new ArrayList<>();
        try {
            JSONObject ctx = webNextBody("2.20250926.00.00");
            ctx.put("videoId", vid);
            JSONObject j = post(NEXT_URL + rnd(12), ctx, WUA);
            JSONArray res = j.getJSONObject("contents")
                    .getJSONObject("twoColumnWatchNextResults")
                    .getJSONObject("secondaryResults")
                    .getJSONObject("secondaryResults")
                    .getJSONArray("results");
            for (int i = 0; i < res.length(); i++) {
                JSONObject o = res.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                JSONObject lk = o.optJSONObject("lockupViewModel");
                if (lk == null) {
                    continue;
                }
                String id = lk.optString("contentId", "");
                if (id.length() != 11) {
                    continue;
                }
                JSONObject lm = lk.optJSONObject("metadata") == null ? null
                        : lk.optJSONObject("metadata").optJSONObject("lockupMetadataViewModel");
                if (lm == null) {
                    continue;
                }
                String title = optContent(lm.optJSONObject("title"));
                // চ্যানেল: metadataRows[0].metadataParts[0].text.content
                String by = "";
                JSONObject cm = lm.optJSONObject("metadata");
                if (cm != null) {
                    JSONObject cvm = cm.optJSONObject("contentMetadataViewModel");
                    if (cvm != null && cvm.optJSONArray("metadataRows") != null
                            && cvm.optJSONArray("metadataRows").length() > 0) {
                        JSONObject r0 = cvm.optJSONArray("metadataRows").optJSONObject(0);
                        if (r0 != null && r0.optJSONArray("metadataParts") != null
                                && r0.optJSONArray("metadataParts").length() > 0) {
                            JSONObject p0 = r0.optJSONArray("metadataParts").optJSONObject(0);
                            if (p0 != null && p0.optJSONObject("text") != null) {
                                by = p0.optJSONObject("text").optString("content", "");
                            }
                        }
                    }
                }
                // সময়-ব্যাজ: overlays → thumbnailBadgeViewModel.text (":" আছে এমন)
                String dur = "";
                JSONObject ci = lk.optJSONObject("contentImage");
                JSONObject tv = ci == null ? null : ci.optJSONObject("thumbnailViewModel");
                if (tv != null && tv.optJSONArray("overlays") != null) {
                    for (int k = 0; k < tv.optJSONArray("overlays").length() && dur.isEmpty(); k++) {
                        JSONObject ob = tv.optJSONArray("overlays").optJSONObject(k);
                        JSONObject bo = ob == null ? null
                                : ob.optJSONObject("thumbnailBottomOverlayViewModel");
                        if (bo == null || bo.optJSONArray("badges") == null) {
                            continue;
                        }
                        for (int m = 0; m < bo.optJSONArray("badges").length(); m++) {
                            JSONObject bg = bo.optJSONArray("badges").optJSONObject(m);
                            JSONObject tb = bg == null ? null
                                    : bg.optJSONObject("thumbnailBadgeViewModel");
                            String t = tb == null ? "" : tb.optString("text", "");
                            if (t.contains(":")) {
                                dur = t;
                                break;
                            }
                        }
                    }
                }
                // থাম্বনেইল: সবচেয়ে বড় সোর্স
                String thumb = "";
                if (tv != null && tv.optJSONObject("image") != null
                        && tv.optJSONObject("image").optJSONArray("sources") != null) {
                    JSONArray src = tv.optJSONObject("image").optJSONArray("sources");
                    for (int k = 0; k < src.length(); k++) {
                        JSONObject so = src.optJSONObject(k);
                        if (so != null && so.optString("url", "").length() > 0) {
                            thumb = so.optString("url");
                        }
                    }
                }
                if (!title.isEmpty()) {
                    out.add(new Rel(id, title, by, dur, thumb));
                }
            }
            log.append("related n=").append(out.size());
        } catch (Throwable t) {
            log.append("related err ").append(t);
        }
        return out;
    }

    private static final String NEXT_URL =
            "https://youtubei.googleapis.com/youtubei/v1/next?prettyPrint=false&t=";

    /** /next-এর বডি-কাঠামো (সঠিক স্থানে request/useSsl/user — ৪০০-বাগের ফিক্স)। */
    private static JSONObject webNextBody(String ver) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", ver);
        client.put("hl", "en");
        client.put("gl", "US");
        client.put("utcOffsetMinutes", 0);
        JSONObject c = new JSONObject();
        c.put("client", client);
        c.put("request", new JSONObject()
                .put("internalExperimentFlags", new JSONArray()));
        c.put("useSsl", true);
        c.put("user", new JSONObject().put("lockedSafetyMode", false));
        JSONObject ctx = new JSONObject();
        ctx.put("context", c);
        return ctx;
    }

    /** কমেন্ট-সারি (/next কন্টিনিউএশন — স্যান্ডবক্স-প্রমাণিত)। */
    public static class Cmt {
        public final String by;
        public final String when;
        public final String text;
        public final String likes;

        public Cmt(String by, String when, String text, String likes) {
            this.by = by;
            this.when = when;
            this.text = text;
            this.likes = likes;
        }
    }

    /** চলমান ভিডিওর মেটাডেটা (ওয়াচ-পেজের তথ্য-সারি)। */
    public static class Meta {
        public final String title;
        public final String ch;
        public final String subs;
        public final String date;
        public final String views;
        public final String likes;
        public final String desc;
        public final String avatar;
        public final String chUrl;

        public Meta(String title, String ch, String subs, String date,
                String views, String likes, String desc,
                String avatar, String chUrl) {
            this.title = title;
            this.ch = ch;
            this.subs = subs;
            this.date = date;
            this.views = views;
            this.likes = likes;
            this.desc = desc;
            this.avatar = avatar;
            this.chUrl = chUrl;
        }
    }

    /** /next (পুরনো WEB-সংস্করণ) থেকে চ্যানেল/সাব/তারিখ/ভিউ/লাইক। */
    public static Meta meta(String vid, StringBuilder log) {
        String title = "", ch = "", subs = "", date = "", views = "", likes = "", desc = "";
        String avatar = "", chUrl = "";
        try {
            JSONObject b = webNextBody("2.20240726.00.00");
            b.put("videoId", vid);
            JSONObject j = post(NEXT_URL + rnd(12), b, WUA);
            JSONArray cs = j.getJSONObject("contents")
                    .getJSONObject("twoColumnWatchNextResults")
                    .getJSONObject("results")
                    .getJSONObject("results")
                    .getJSONArray("contents");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject o = cs.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                JSONObject pi = o.optJSONObject("videoPrimaryInfoRenderer");
                if (pi != null) {
                    if (title.length() == 0) {
                        title = optContent(pi.optJSONObject("title"));
                    }
                    JSONObject vc = pi.optJSONObject("viewCount") == null ? null
                            : pi.optJSONObject("viewCount")
                                    .optJSONObject("videoViewCountRenderer");
                    if (vc != null) {
                        views = optContent(vc.optJSONObject("viewCount"));
                    }
                    date = optContent(pi.optJSONObject("dateText"));
                    if (date.length() == 0) {
                        date = optContent(pi.optJSONObject("relativeDateText"));
                    }
                }
                JSONObject si = o.optJSONObject("videoSecondaryInfoRenderer");
                if (si != null && si.optJSONObject("owner") != null) {
                    JSONObject ow = si.optJSONObject("owner")
                            .optJSONObject("videoOwnerRenderer");
                    if (ow != null) {
                        ch = optContent(ow.optJSONObject("title"));
                        subs = optContent(ow.optJSONObject("subscriberCountText"));
                        // প্রোফাইল-ছবি: thumbnail.thumbnails-এর সবচেয়ে বড়টা
                        JSONObject th = ow.optJSONObject("thumbnail");
                        if (th != null && th.optJSONArray("thumbnails") != null) {
                            JSONArray ts = th.optJSONArray("thumbnails");
                            for (int m2 = 0; m2 < ts.length(); m2++) {
                                JSONObject so = ts.optJSONObject(m2);
                                if (so != null && so.optString("url", "").length() > 0) {
                                    avatar = so.optString("url");
                                }
                            }
                        }
                        // চ্যানেল-লিংক: canonicalBaseUrl (@হ্যান্ডেল) বা browseId
                        JSONObject tt = ow.optJSONObject("title");
                        if (tt != null && tt.optJSONArray("runs") != null
                                && tt.optJSONArray("runs").length() > 0) {
                            JSONObject r0 = tt.optJSONArray("runs").optJSONObject(0);
                            JSONObject ne = r0 == null ? null
                                    : r0.optJSONObject("navigationEndpoint");
                            JSONObject be = ne == null ? null
                                    : ne.optJSONObject("browseEndpoint");
                            if (be != null) {
                                String base = be.optString("canonicalBaseUrl", "");
                                String bid = be.optString("browseId", "");
                                if (base.startsWith("/")) {
                                    chUrl = "https://m.youtube.com" + base;
                                } else if (base.startsWith("@")) {
                                    chUrl = "https://m.youtube.com/" + base;
                                } else if (bid.startsWith("UC")) {
                                    chUrl = "https://m.youtube.com/channel/" + bid;
                                }
                            }
                        }
                    }
                    // ডেসক্রিপশন: attributedDescription.content (সম্পূর্ণ লেখা)
                    JSONObject ad = si.optJSONObject("attributedDescription");
                    if (ad != null) {
                        desc = ad.optString("content", "");
                    }
                }
            }
            // লাইক: likeButtonViewModel → toggleButtonViewModel → defaultButtonViewModel
            JSONObject lb = deepFind(j, "likeButtonViewModel", 0);
            if (lb != null) {
                JSONObject t1 = lb.optJSONObject("toggleButtonViewModel");
                JSONObject t2 = t1 == null ? null : t1.optJSONObject("toggleButtonViewModel");
                JSONObject db = t2 == null ? null : t2.optJSONObject("defaultButtonViewModel");
                JSONObject bv = db == null ? null : db.optJSONObject("buttonViewModel");
                if (bv != null) {
                    likes = bv.optString("title", "");
                }
            }
            log.append("meta ok");
        } catch (Throwable t) {
            log.append("meta err ").append(t);
        }
        return new Meta(title, ch, subs, date, views, likes, desc, avatar, chUrl);
    }

    /** গভীর-অনুসন্ধানে প্রথম মিল। */
    private static JSONObject deepFind(Object o, String key, int depth) {
        if (depth > 8) {
            return null;
        }
        if (o instanceof JSONObject) {
            JSONObject jo = (JSONObject) o;
            JSONObject hit = jo.optJSONObject(key);
            if (hit != null) {
                return hit;
            }
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                JSONObject r = deepFind(jo.opt(it.next()), key, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = deepFind(a.opt(i), key, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** ভিডিওর কমেন্ট: কন্টিনিউএশন-টোকেন নিয়ে দ্বিতীয় /next — প্রথম পাতা (২০টা)। */
    public static List<Cmt> comments(String vid, StringBuilder log) {
        List<Cmt> out = new ArrayList<>();
        try {
            JSONObject b1 = webNextBody("2.20250926.00.00");
            b1.put("videoId", vid);
            JSONObject j = post(NEXT_URL + rnd(12), b1, WUA);
            JSONArray cs = j.getJSONObject("contents")
                    .getJSONObject("twoColumnWatchNextResults")
                    .getJSONObject("results")
                    .getJSONObject("results")
                    .getJSONArray("contents");
            String token = null;
            for (int i = 0; i < cs.length(); i++) {
                JSONObject o = cs.optJSONObject(i);
                JSONObject isr = o == null ? null : o.optJSONObject("itemSectionRenderer");
                if (isr == null
                        || !"comment-item-section".equals(isr.optString("sectionIdentifier"))) {
                    continue;
                }
                JSONArray cc = isr.optJSONArray("contents");
                JSONObject cir = cc != null && cc.length() > 0
                        ? cc.optJSONObject(0).optJSONObject("continuationItemRenderer") : null;
                JSONObject ce = cir == null ? null : cir.optJSONObject("continuationEndpoint");
                JSONObject cmd = ce == null ? null : ce.optJSONObject("continuationCommand");
                if (cmd != null) {
                    token = cmd.optString("token", null);
                }
            }
            if (token == null) {
                log.append("cmt no-token");
                return out;
            }
            JSONObject b2 = webNextBody("2.20250926.00.00");
            b2.put("continuation", token);
            JSONObject j2 = post(NEXT_URL + rnd(12), b2, WUA);
            collectCmts(j2, out);
            log.append("cmt n=").append(out.size());
        } catch (Throwable t) {
            log.append("cmt err ").append(t);
        }
        return out;
    }

    /** commentEntityPayload-গুলো গভীর-অনুসন্ধানে সংগ্রহ (সর্বোচ্চ ২০)। */
    private static void collectCmts(Object o, List<Cmt> out) {
        if (out.size() >= 20) {
            return;
        }
        if (o instanceof JSONObject) {
            JSONObject jo = (JSONObject) o;
            JSONObject p = jo.optJSONObject("commentEntityPayload");
            if (p != null) {
                JSONObject pr = p.optJSONObject("properties");
                JSONObject au = p.optJSONObject("author");
                if (pr != null) {
                    String text = pr.optJSONObject("content") == null ? ""
                            : pr.optJSONObject("content").optString("content", "");
                    String when = pr.optString("publishedTime", "");
                    String by = au == null ? "" : au.optString("displayName", "");
                    String likes = "";
                    JSONObject tb = p.optJSONObject("toolbar");
                    if (tb != null) {
                        likes = tb.optString("likeCountNotliked", "");
                    }
                    if (text.length() > 0) {
                        out.add(new Cmt(by, when, text, likes));
                    }
                }
            }
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext() && out.size() < 20) {
                collectCmts(jo.opt(it.next()), out);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length() && out.size() < 20; i++) {
                collectCmts(a.opt(i), out);
            }
        }
    }

    /** {"content": "..."} বা {"runs":[{"text": "..."}]} — উভয়ই। */
    private static String optContent(JSONObject o) {
        if (o == null) {
            return "";
        }
        String c = o.optString("content", "");
        if (c.length() > 0) {
            return c;
        }
        if (o.optJSONArray("runs") != null && o.optJSONArray("runs").length() > 0) {
            return o.optJSONArray("runs").optJSONObject(0).optString("text", "");
        }
        return "";
    }

    public static class Res {
        public String combined;
        public String video;
        public String audio;
        public String title = "";
        public String err = "";
        public String hls = "";
        public final StringBuilder log = new StringBuilder();
        public final java.util.ArrayList<Var> vars = new java.util.ArrayList<Var>();
        public final java.util.ArrayList<Cap> caps = new java.util.ArrayList<Cap>();
        public final java.util.ArrayList<Chap> chaps = new java.util.ArrayList<Chap>();
        public long dislikes = -1;
        /** নির্বাচিত URL-এ n= আছে কিন্তু ratebypass=yes নেই (থ্রটল-ঝুঁকি)। */
        public boolean nparam = false;
    }

    /** ডেসক্রিপশন-টাইমস্ট্যাম্প থেকে পাওয়া চ্যাপ্টার। */
    public static class Chap {
        public final long ms;
        public final String title;

        public Chap(long m, String t) {
            ms = m;
            title = t;
        }
    }

    /** ক্যাপশন-ট্র্যাক (SRT-ডাউনলোডের উৎস)। */
    public static class Cap {
        public String lang;
        public String label;
        public String url;

        public Cap(String l, String lb, String u) {
            lang = l;
            label = lb;
            url = u;
        }
    }

    /** একটি নির্দিষ্ট কোয়ালিটির ভিডিও-শুধু URL। */
    public static class Var {
        public int h;
        public String url;

        public Var(int hh, String uu) {
            h = hh;
            url = uu;
        }
    }

    private static final Random RNG = new Random();
    private static final String ALPHA =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static String rnd(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHA.charAt(RNG.nextInt(ALPHA.length())));
        }
        return sb.toString();
    }

    // ================= মূল পথ: VISIONOS =================

    private static final String VUA =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0"
                    + " like Mac OS X; BD)";

    private static JSONObject visionOsContext(String visitorData) throws Exception {
        JSONObject c = new JSONObject();
        c.put("clientName", "VISIONOS");
        c.put("clientVersion", "1.02");
        c.put("clientScreen", "WATCH");
        c.put("platform", "MOBILE");
        c.put("deviceMake", "Apple");
        c.put("deviceModel", "RealityDevice14,1");
        c.put("osName", "visionOS");
        c.put("osVersion", "25.6.0.23O471");
        if (visitorData != null) {
            c.put("visitorData", visitorData);
        }
        c.put("hl", "en-GB");
        c.put("gl", "GB");
        c.put("utcOffsetMinutes", 0);
        return wrapContext(c);
    }

    // ================= ফলব্যাক-পথ: ANDROID reel =================

    private static final String AUA =
            "com.google.android.youtube/21.03.36 (Linux; U; Android 15; BD) gzip";

    private static JSONObject androidReelContext(String visitorData) throws Exception {
        JSONObject c = new JSONObject();
        c.put("clientName", "ANDROID");
        c.put("clientVersion", "21.03.36");
        c.put("clientScreen", "WATCH");
        c.put("platform", "MOBILE");
        c.put("osName", "Android");
        c.put("osVersion", "16");
        c.put("androidSdkVersion", 36);
        if (visitorData != null) {
            c.put("visitorData", visitorData);
        }
        c.put("hl", "en-GB");
        c.put("gl", "GB");
        c.put("utcOffsetMinutes", 0);
        return wrapContext(c);
    }

    private static JSONObject wrapContext(JSONObject client) throws Exception {
        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        ctx.put("request", new JSONObject().put("internalExperimentFlags",
                new org.json.JSONArray()));
        ctx.put("useSsl", true);
        ctx.put("user", new JSONObject().put("lockedSafetyMode", false));
        return new JSONObject().put("context", ctx);
    }

    /** visitor_id এন্ডপয়েন্ট থেকে টাটকা visitorData। */
    // ---- visitorData ক্যাশ ----
    // প্রতিবার visitor_id আলাদা করে আনলে প্রতিটি ভিডিওতে একটি অতিরিক্ত
    // HTTPS রাউন্ড-ট্রিপ (TCP+TLS সহ) নষ্ট হয়। মান কয়েক ঘণ্টা স্থির থাকে,
    // তাই UA+base অনুযায়ী মনে রাখি।
    private static final java.util.HashMap<String, String> VIS =
            new java.util.HashMap<String, String>();
    private static final java.util.HashMap<String, Long> VIS_AT =
            new java.util.HashMap<String, Long>();
    private static final long VIS_TTL_MS = 20L * 60L * 1000L;

    /** শেষ সফল রেজলভের সংক্ষিপ্ত বিবরণ (Diagnostics স্ক্রিনে যায়)। */
    public static volatile String lastDiag = "";

    /**
     * YouTube স্ট্রিম-URL-এ `n` প্যারামিটার থাকলে এবং `ratebypass=yes` না থাকলে
     * সার্ভার ডাউনলোড থ্রটল করে (~৫০ kB/s)। আমরা `n` রূপান্তর করি না, তাই এই
     * ফ্ল্যাগটি সত্য হলে "ইন্টারনেট ফাস্ট কিন্তু ভিডিও স্লো" হওয়ার কারণ এটিই।
     */
    public static boolean throttledUrl(String u) {
        try {
            if (u == null || u.length() == 0) {
                return false;
            }
            int i = u.indexOf('?');
            if (i < 0) {
                return false;
            }
            String q = u.substring(i + 1);
            int h = q.indexOf('#');
            if (h >= 0) {
                q = q.substring(0, h);
            }
            boolean hasN = false;
            boolean bypass = false;
            for (String p : q.split("&")) {
                if (p.startsWith("n=") && p.length() > 2) {
                    hasN = true;
                } else if (p.equals("ratebypass=yes")) {
                    bypass = true;
                }
            }
            return hasN && !bypass;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String visitorId(JSONObject ctxBody, String ua, String base, Res r,
                                    String tag) {
        String ck = ua + "|" + base;
        try {
            String got = VIS.get(ck);
            Long at = VIS_AT.get(ck);
            if (got != null && got.length() > 0 && at != null
                    && (System.currentTimeMillis() - at.longValue()) < VIS_TTL_MS) {
                return got;
            }
        } catch (Throwable t) {
        }
        try {
            JSONObject j = post(base + "visitor_id?prettyPrint=false", ctxBody, ua);
            JSONObject rc = j.optJSONObject("responseContext");
            String v = rc != null ? rc.optString("visitorData", "") : "";
            if (v.length() > 0) {
                try {
                    VIS.put(ck, v);
                    VIS_AT.put(ck, Long.valueOf(System.currentTimeMillis()));
                } catch (Throwable t) {
                }
                return v;
            }
            r.log.append(tag).append(": no visitorData\n");
        } catch (Throwable t) {
            r.log.append(tag).append(": visitor err ")
                    .append(t.getClass().getSimpleName()).append('\n');
        }
        return null;
    }

    private static JSONObject post(String u, JSONObject body, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("X-Goog-Api-Format-Version", "2");
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.getOutputStream().write(body.toString().getBytes("UTF-8"));
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("http " + code);
        }
        return new JSONObject(read(c.getInputStream()));
    }

    // ================= রেজলভ-প্রবাহ =================

    /**
     * পাবলিক এন্ট্রি: প্রথমে স্বাভাবিক রেজলভ, তারপর — যদি নির্বাচিত স্ট্রিম
     * থ্রটলড হয় — ANDROID ক্লায়েন্ট থেকে অ-থ্রটল URL পাওয়ার একবার চেষ্টা।
     * এই বাড়তি চেষ্টা কেবল থ্রটলড অবস্থাতেই হয়, আর ব্যর্থ হলে আগের ফলাফলই
     * ফেরত যায় — তাই এটি কখনো পরিস্থিতি খারাপ করতে পারে না।
     */
    public static Res resolve(String videoId) {
        Res r = resolveInner(videoId);
        try {
            if (r == null || !r.nparam
                    || (r.video == null && r.combined == null)) {
                return r;
            }
            StreamStore.note("throttle detected — trying ANDROID source");
            Res alt = new Res();
            String reason = tryClassic(alt, videoId, "ANDROID", "20.05.38", "30",
                    "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip",
                    "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w", false, "");
            if (reason == null && !alt.nparam
                    && (alt.video != null || alt.combined != null)) {
                alt.log.append("unthrottled ANDROID source selected\n");
                lastDiag = "ok (ANDROID fallback) " + lastDiag;
                StreamStore.note("stream " + lastDiag);
                return alt;
            }
            StreamStore.note("ANDROID fallback kept throttled/absent — keeping original");
        } catch (Throwable t) {
        }
        return r;
    }

    private static Res resolveInner(String videoId) {
        Res r = new Res();
        String lastReason = "";

        // ---- ১) VISIONOS: মূল স্ট্রিম-উৎস ----
        try {
            JSONObject base = visionOsContext(null);
            String vis = visitorId(base, VUA, "https://www.youtube.com/youtubei/v1/",
                    r, "visionos");
            JSONObject body = visionOsContext(vis);
            body.put("videoId", videoId);
            body.put("cpn", rnd(16));
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            String u = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t="
                    + rnd(12) + "&id=" + videoId;
            JSONObject j = post(u, body, VUA);
            String st = playability(j, r, "visionos");
            if (st == null) {
                takeTitle(j, r);
                takeHls(j, r);
                pick(j.optJSONObject("streamingData"), r);
                capTracks(j, r);
                if (r.combined != null || (r.video != null && r.audio != null)) {
                    r.log.append("visionos: ok\n");
                    return finish(r);
                }
                r.log.append("visionos: no urls\n");
            } else {
                lastReason = st;
            }
        } catch (Throwable t) {
            r.log.append("visionos: err ").append(t.getClass().getSimpleName()).append('\n');
        }

        // ---- ২) ANDROID reel_item_watch: প্লেয়াবিলিটি + ফলব্যাক ----
        try {
            JSONObject base = androidReelContext(null);
            String vis = visitorId(base, AUA,
                    "https://youtubei.googleapis.com/youtubei/v1/", r, "reel");
            JSONObject body = androidReelContext(vis);
            JSONObject pr = new JSONObject();
            pr.put("videoId", videoId);
            pr.put("cpn", rnd(16));
            pr.put("contentCheckOk", true);
            pr.put("racyCheckOk", true);
            body.put("playerRequest", pr);
            body.put("disablePlayerResponse", false);
            String u = "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch"
                    + "?prettyPrint=false&t=" + rnd(12) + "&id=" + videoId
                    + "&$fields=playerResponse";
            JSONObject j = post(u, body, AUA);
            JSONObject prs = j.optJSONObject("playerResponse");
            if (prs == null) {
                r.log.append("reel: no playerResponse\n");
            } else {
                String st = playability(prs, r, "reel");
                if (st == null) {
                    takeTitle(prs, r);
                    takeHls(prs, r);
                    pick(prs.optJSONObject("streamingData"), r);
                    capTracks(prs, r);
                    if (r.combined != null || (r.video != null && r.audio != null)) {
                        r.log.append("reel: ok\n");
                        return finish(r);
                    }
                    r.log.append("reel: no urls\n");
                } else {
                    lastReason = st;
                }
            }
        } catch (Throwable t) {
            r.log.append("reel: err ").append(t.getClass().getSimpleName()).append('\n');
        }

        // ---- ৩) IOS (পূর্ণ ডিভাইস-কনটেক্সট) ----
        lastReason = tryClassic(r, videoId, "IOS", "20.05.3", null,
                "com.google.ios.youtube/20.05.3 (iPhone16,2; U; CPU iOS 18_3_2"
                        + " like Mac OS X)",
                "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc", true, lastReason);

        // ---- ৪) ANDROID (পুরনো পথ) ----
        lastReason = tryClassic(r, videoId, "ANDROID", "20.05.38", "30",
                "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip",
                "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w", false, lastReason);

        if (r.hls.length() > 0) {
            r.log.append("hls manifest available\n");
            return r;
        }
        r.err = lastReason.length() > 0 ? lastReason : "no stream";
        return r;
    }

    private static Res finish(Res r) {
        try {
            boolean th = throttledUrl(r.video) || throttledUrl(r.combined)
                    || throttledUrl(r.audio);
            r.nparam = th;
            lastDiag = (th ? "THROTTLED (n unset)" : "ok")
                    + " dual=" + (r.video != null && r.audio != null)
                    + " single=" + (r.combined != null) + " hls=" + (r.hls.length() > 0);
            StreamStore.note("stream " + lastDiag);
        } catch (Throwable t) {
        }
        java.util.Collections.sort(r.vars, new java.util.Comparator<Var>() {
            public int compare(Var a, Var b) {
                return b.h - a.h;
            }
        });
        return r;
    }

    /** পুরনো স্টাইলের ক্লায়েন্ট-ফলব্যাক (www.youtube.com/youtubei/v1/player?key=...)। */
    private static String tryClassic(Res r, String videoId, String name, String ver,
                                     String sdk, String ua, String key, boolean iosCtx,
                                     String lastReason) {
        try {
            JSONObject body = new JSONObject();
            JSONObject ctx = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", name);
            client.put("clientVersion", ver);
            client.put("hl", "en");
            client.put("gl", "US");
            if (sdk != null) {
                client.put("androidSdkVersion", Integer.parseInt(sdk));
            }
            if (iosCtx) {
                client.put("deviceMake", "Apple");
                client.put("deviceModel", "iPhone16,2");
                client.put("osName", "iPhone");
                client.put("osVersion", "18.3.2.22D82");
            }
            ctx.put("client", client);
            body.put("context", ctx);
            body.put("videoId", videoId);
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            String u = "https://www.youtube.com/youtubei/v1/player?key=" + key
                    + "&prettyPrint=false";
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", ua);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.getOutputStream().write(body.toString().getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code != 200) {
                r.log.append(name.toLowerCase()).append(": http ").append(code).append('\n');
                return lastReason;
            }
            JSONObject j = new JSONObject(read(c.getInputStream()));
            String st = playability(j, r, name.toLowerCase());
            if (st == null) {
                takeTitle(j, r);
                takeHls(j, r);
                pick(j.optJSONObject("streamingData"), r);
                capTracks(j, r);
                if (r.combined != null || (r.video != null && r.audio != null)) {
                    r.log.append(name.toLowerCase()).append(": ok\n");
                    finish(r);
                    return null;
                }
                r.log.append(name.toLowerCase()).append(": no urls\n");
            } else {
                return st;
            }
        } catch (Throwable t) {
            r.log.append(name.toLowerCase()).append(": err ")
                    .append(t.getClass().getSimpleName()).append('\n');
        }
        return lastReason;
    }

    // ================= সাধারণ সহায়ক =================

    /** playabilityStatus যাচাই; ঠিক থাকলে null, নাহলে কারণ। */
    private static String playability(JSONObject j, Res r, String tag) {
        JSONObject ps = j.optJSONObject("playabilityStatus");
        if (ps == null) {
            return null;
        }
        String st = ps.optString("status", "");
        if (st.length() == 0 || "OK".equalsIgnoreCase(st)) {
            return null;
        }
        String reason = ps.optString("reason", "");
        String full = st + (reason.length() > 0 ? " - " + reason : "");
        r.log.append(tag).append(": ").append(full).append('\n');
        return full;
    }

    private static void takeTitle(JSONObject j, Res r) {
        if (r.title.length() == 0) {
            JSONObject vd = j.optJSONObject("videoDetails");
            if (vd != null) {
                r.title = vd.optString("title", "");
            }
        }
        takeChapters(j, r);
    }

    /** ডেসক্রিপশনের টাইমস্ট্যাম্প-সারি ("0:00 Intro") থেকে চ্যাপ্টার। ৩টার কম হলে নয়। */
    private static void takeChapters(JSONObject j, Res r) {
        if (r.chaps.size() > 0) {
            return;
        }
        try {
            JSONObject vd = j.optJSONObject("videoDetails");
            if (vd == null) {
                return;
            }
            String d = vd.optString("shortDescription", "");
            if (d == null || d.length() < 12) {
                return;
            }
            String[] lines = d.replace("\r", "").split("\n");
            java.util.ArrayList<Chap> got = new java.util.ArrayList<Chap>();
            for (String ln : lines) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*[-–—:~]?\\s*(.{2,70})$")
                        .matcher(ln.trim());
                if (!m.matches()) {
                    continue;
                }
                long ms;
                if (m.group(3) != null) {
                    ms = (Long.parseLong(m.group(1)) * 3600
                            + Long.parseLong(m.group(2)) * 60
                            + Long.parseLong(m.group(3))) * 1000L;
                } else {
                    ms = (Long.parseLong(m.group(1)) * 60
                            + Long.parseLong(m.group(2))) * 1000L;
                }
                String tt = m.group(4).trim();
                if (tt.length() > 60) {
                    tt = tt.substring(0, 60);
                }
                got.add(new Chap(ms, tt));
            }
            if (got.size() >= 3) {
                java.util.Collections.sort(got, new java.util.Comparator<Chap>() {
                    @Override
                    public int compare(Chap a, Chap b) {
                        return Long.compare(a.ms, b.ms);
                    }
                });
                r.chaps.addAll(got);
            }
        } catch (Throwable t) {
        }
    }

    private static void takeHls(JSONObject j, Res r) {
        if (r.hls.length() > 0) {
            return;
        }
        JSONObject sd = j.optJSONObject("streamingData");
        if (sd != null) {
            String h = sd.optString("hlsManifestUrl", "");
            if (h != null && h.length() > 0) {
                r.hls = h;
            }
        }
    }

    private static boolean haveHeight(Res r, int h) {
        for (int i = 0; i < r.vars.size(); i++) {
            if (r.vars.get(i).h == h) {
                return true;
            }
        }
        return false;
    }

    /** ক্যাপশন-ট্র্যাক সংগ্রহ (baseUrl + "&fmt=vtt" = আসল WEBVTT; স্যান্ডবক্স-প্রমাণিত)। */
    private static void capTracks(JSONObject j, Res r) {
        try {
            JSONObject cs = j.optJSONObject("captions");
            if (cs == null) {
                return;
            }
            JSONObject tr = cs.optJSONObject("playerCaptionsTracklistRenderer");
            if (tr == null) {
                return;
            }
            org.json.JSONArray arr = tr.optJSONArray("captionTracks");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length() && r.caps.size() < 10; i++) {
                try {
                    JSONObject t = arr.getJSONObject(i);
                    String bu = t.optString("baseUrl", "");
                    if (bu == null || bu.length() == 0) {
                        continue;
                    }
                    String lang = t.optString("languageCode", "en");
                    String label = lang;
                    JSONObject nm = t.optJSONObject("name");
                    if (nm != null) {
                        String st = nm.optString("simpleText", "");
                        if (st.length() > 0) {
                            label = st;
                        } else {
                            org.json.JSONArray runs = nm.optJSONArray("runs");
                            if (runs != null && runs.length() > 0) {
                                label = runs.getJSONObject(0).optString("text", lang);
                            }
                        }
                    }
                    if ("asr".equals(t.optString("kind", ""))
                            && !label.toLowerCase().contains("auto")) {
                        label = label + " (auto)";
                    }
                    r.caps.add(new Cap(lang, label, bu));
                } catch (Throwable t2) {
                }
            }
        } catch (Throwable t) {
        }
    }

    /** স্ট্রিমিং-ডেটা থেকে সেরা ফরম্যাট বাছাই (ভিডিও: mp4/avc, অডিও: m4a)। */
    private static void pick(JSONObject sd, Res r) {
        if (sd == null) {
            return;
        }
        pickArr(sd.optJSONArray("formats"), r, true);
        pickArr(sd.optJSONArray("adaptiveFormats"), r, false);
    }

    private static void pickArr(org.json.JSONArray arr, Res r, boolean progressive) {
        if (arr == null) {
            return;
        }
        String bestV = null;
        int bestH = 0;
        String bestA = null;
        long bestAb = 0;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject f = arr.getJSONObject(i);
                String url = f.optString("url", "");
                if (url == null || url.length() == 0) {
                    continue;
                }
                String mime = f.optString("mimeType", "");
                int itag = f.optInt("itag", 0);
                if (progressive) {
                    if (mime.startsWith("video/") && mime.contains("mp4")
                            && url.contains("googlevideo")) {
                        if (itag == 18 || r.combined == null) {
                            r.combined = url;
                        }
                    }
                } else {
                    if (mime.startsWith("video/") && mime.contains("mp4")
                            && mime.contains("avc")) {
                        int h = f.optInt("height", 0);
                        if (h > 0 && !haveHeight(r, h)) {
                            r.vars.add(new Var(h, url));
                        }
                        if (h > bestH && h <= 1080) {
                            bestH = h;
                            bestV = url;
                        }
                    } else if (mime.startsWith("audio/") && mime.contains("mp4")) {
                        long b = f.optLong("bitrate", 0);
                        if (b > bestAb) {
                            bestAb = b;
                            bestA = url;
                        }
                    }
                }
            } catch (Throwable t) {
            }
        }
        if (bestV != null && r.video == null) {
            r.video = bestV;
        }
        if (bestA != null && r.audio == null) {
            r.audio = bestA;
        }
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toString("UTF-8");
    }
    // ===== Wtube 2.0: SponsorBlock + Return-YouTube-Dislike =====

    /** SponsorBlock সেগমেন্ট: {startMs, endMs, category} — K-Anonymity হ্যাশ-প্রিফিক্স। */
    public static java.util.ArrayList<Object[]> sbSegs(String vid) {
        java.util.ArrayList<Object[]> out = new java.util.ArrayList<Object[]>();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(vid.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            String u = "https://sponsor.ajay.app/api/skipSegments/" + sb
                    + "?categories=%5B%22sponsor%22%2C%22selfpromo%22%2C%22intro%22%2C%22outro%22%2C%22interaction%22%5D";
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Wtube/2.0 (SponsorBlock skipper)");
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return out;
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getInputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            in.close();
            c.disconnect();
            org.json.JSONArray a = new org.json.JSONArray(bo.toString("UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null || !vid.equals(o.optString("videoID", ""))) {
                    continue;
                }
                org.json.JSONArray seg = o.optJSONArray("segment");
                if (seg == null || seg.length() < 2) {
                    continue;
                }
                long st = (long) Math.round(seg.getDouble(0) * 1000.0);
                long en = (long) Math.round(seg.getDouble(1) * 1000.0);
                if (en - st > 1000) {
                    out.add(new Object[]{st, en, o.optString("category", "sponsor")});
                }
            }
        } catch (Throwable t) {
        }
        return out;
    }

    /** Return-YouTube-Dislike API থেকে ডিসলাইক-সংখ্যা (-1 = অজানা)। */
    public static long fetchDislikes(String vid) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://returnyoutubedislikeapi.com/votes?videoId=" + vid)
                            .openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return -1;
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getInputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            in.close();
            c.disconnect();
            org.json.JSONObject o = new org.json.JSONObject(bo.toString("UTF-8"));
            return o.optLong("dislikes", -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}
