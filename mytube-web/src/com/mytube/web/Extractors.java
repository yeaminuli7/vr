package com.mytube.web;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * ইউটিউবের বাইরের প্ল্যাটফর্ম থেকে সরাসরি ভিডিও-লিংক বের করা — yt-dlp যেভাবে
 * করে, ঠিক সেই পদ্ধতিতেই (পেজ/এন্ডপয়েন্টের ভেতরের JSON পড়ে), কিন্তু নেটিভ
 * জাভায়।
 *
 * <p>কেন yt-dlp নিজে নয়: yt-dlp একটি পাইথন প্রোগ্রাম। অ্যাপে চালাতে গেলে
 * arm64-র জন্য CPython রানটাইম (প্রায় ২০ MB) এবং Gradle/AAR বিল্ড লাগে —
 * এই অফলাইন javac/d8 পাইপলাইনে দুটোরই সুযোগ নেই। তাই এক্সট্র্যাকশনের
 * *পদ্ধতিটি* নেটিভভাবে করা হয়েছে; ফলাফল একই ধরনের।
 *
 * <p>সব ফাংশনই বিশুদ্ধ (pure) — নেটওয়ার্ক ছাড়া টেস্ট করা যায়।
 */
public final class Extractors {

    private Extractors() {
    }

    /** একটি পাওয়া স্ট্রিম। */
    public static final class Hit {
        public final String url;
        public final String ext;
        public final boolean audio;
        public final String label; // "HD", "720p", "Audio" ...

        public Hit(String u, String e, boolean a, String l) {
            url = u;
            ext = e;
            audio = a;
            label = l;
        }
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** এই হোস্টগুলো আমরা নিজে সামলাতে পারি? */
    public static boolean canHandle(String url) {
        String h = host(url);
        if (h.length() == 0) {
            return false;
        }
        if (h.endsWith("youtube.com") || h.equals("youtu.be")
                || h.endsWith("ytimg.com") || h.endsWith("ggpht.com")) {
            return false; // ইউটিউবের নিজের পথ আলাদা
        }
        return true;
    }

    /**
     * দেওয়া লিংকটি কি সত্যিই একটি ভিডিও/অডিও ফাইল? (যেমন পেস্ট করা
     * `.../video.mp4`) — তাহলে কোনো এক্সট্র্যাকশনই লাগে না, সোজা ডাউনলোড।
     */
    public static String directExt(String url) {
        try {
            String path = url == null ? "" : url.toLowerCase().trim();
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int hash = path.indexOf('#');
            if (hash >= 0) {
                path = path.substring(0, hash);
            }
            String[] exts = {"mp4", "m4v", "mov", "mkv", "webm", "m4a", "mp3", "ogg", "opus", "aac"};
            for (int i = 0; i < exts.length; i++) {
                if (path.endsWith("." + exts[i])) {
                    return exts[i];
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /**
     * সাধারণ সাইটের জন্য: পেজের হেডারে থাকা `og:video` / `twitter:player:stream`
     * এবং HTML5 `&lt;video&gt;` / `&lt;source&gt;` ঠিকানা। এটাই বেশিরভাগ সাইটে
     * কাজ করে — সাইটভিত্তিক কোড ছাড়াই।
     */
    static ArrayList<Hit> parseGeneric(String html) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (html == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        String[] props = {
            "og:video:secure_url", "og:video:url", "og:video",
            "twitter:player:stream", "og:audio", "og:audio:secure_url"
        };
        for (int i = 0; i < props.length; i++) {
            String u = metaContent(html, props[i]);
            if (u != null) {
                boolean audio = props[i].startsWith("og:audio");
                addUrl(out, seen, u, audio ? "Audio" : "Video");
            }
        }
        // JSON-LD: "contentUrl":"..."
        int i = 0;
        while (i < html.length()) {
            int k = indexOfCI(html, "\"contenturl\"", i);
            if (k < 0) {
                break;
            }
            String u = strValue(html, k + 12);
            if (u != null) {
                addUrl(out, seen, u, null);
            }
            i = k + 12;
        }
        // <video src=...> / <source src=...>
        i = 0;
        while (i < html.length()) {
            int k = indexOfCI(html, "<video", i);
            int s2 = indexOfCI(html, "<source", i);
            int at = k < 0 ? s2 : (s2 < 0 ? k : Math.min(k, s2));
            if (at < 0) {
                break;
            }
            int tagEnd = html.indexOf('>', at);
            if (tagEnd < 0) {
                break;
            }
            String tag = html.substring(at, tagEnd);
            String u = attrValue(tag, "src");
            if (u != null && u.startsWith("http")) {
                addUrl(out, seen, u, null);
            }
            i = tagEnd + 1;
        }
        return out;
    }

    /** `<meta property="og:video" content="URL">` — ক্রম যাই হোক। */
    static String metaContent(String html, String prop) {
        try {
            String low = html.toLowerCase();
            String want = prop.toLowerCase();
            int i = 0;
            while (true) {
                int k = low.indexOf("<meta", i);
                if (k < 0) {
                    return null;
                }
                int end = low.indexOf('>', k);
                if (end < 0) {
                    return null;
                }
                String tag = html.substring(k, end);
                String p = attrValue(tag, "property");
                if (p == null) {
                    p = attrValue(tag, "name");
                }
                if (p != null && p.trim().equalsIgnoreCase(want)) {
                    String c = attrValue(tag, "content");
                    if (c != null && c.trim().length() > 0) {
                        return c.trim();
                    }
                }
                i = end + 1;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** ট্যাগের ভেতর থেকে অ্যাট্রিবিউট (একক বা দ্বিগুণ উদ্ধৃতি)। */
    static String attrValue(String tag, String name) {
        try {
            String low = tag.toLowerCase();
            String key = name.toLowerCase();
            int i = 0;
            while (true) {
                int k = low.indexOf(key, i);
                if (k < 0) {
                    return null;
                }
                int before = k - 1;
                boolean boundary = before < 0 || !Character.isLetterOrDigit(low.charAt(before));
                int j = k + key.length();
                while (j < low.length() && Character.isWhitespace(low.charAt(j))) {
                    j++;
                }
                if (boundary && j < low.length() && low.charAt(j) == '=') {
                    j++;
                    while (j < low.length() && Character.isWhitespace(low.charAt(j))) {
                        j++;
                    }
                    if (j < tag.length() && (tag.charAt(j) == '"' || tag.charAt(j) == '\'')) {
                        char qq = tag.charAt(j);
                        int close = tag.indexOf(qq, j + 1);
                        if (close > j) {
                            return unescape(tag.substring(j + 1, close)).replace("&amp;", "&");
                        }
                        return null;
                    }
                    int e = j;
                    while (e < low.length() && !Character.isWhitespace(low.charAt(e))
                            && low.charAt(e) != '>') {
                        e++;
                    }
                    String raw = tag.substring(j, e);
                    return unescape(raw).replace("&amp;", "&");
                }
                i = k + key.length();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** হোস্ট-অনুযায়ী এক্সট্র্যাক্টর। ব্যর্থ হলে খালি তালিকা। */
    public static ArrayList<Hit> extract(String url) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        try {
            // পেস্ট করা লিংকটাই যদি ভিডিও-ফাইল হয় — কোনো স্ক্যান/এক্সট্র্যাকশন নয়
            String dex = directExt(url);
            if (dex != null) {
                boolean audio = dex.equals("m4a") || dex.equals("mp3")
                        || dex.equals("ogg") || dex.equals("opus") || dex.equals("aac");
                out.add(new Hit(url.trim(), dex, audio, audio ? "Audio" : "Video"));
                return out;
            }
            String h = host(url);
            String html;
            if (h.endsWith("tiktok.com")) {
                html = get(resolveRedirects(url));
                if (html != null) {
                    out = parseTikTok(html);
                }
                if (out.isEmpty() && html != null) {
                    out = parseTikTokRehydration(html);
                }
                if (out.isEmpty() && html != null) {
                    out = parseGeneric(html);
                }
            } else if (h.equals("fb.watch") || h.endsWith("facebook.com")
                    || h.endsWith("fb.com")) {
                html = get(url);
                if (html != null) {
                    out = parseFacebook(html);
                }
                if (out.isEmpty() && html != null) {
                    out = parseGeneric(html);
                }
            } else if (h.endsWith("instagram.com") || h.endsWith("instagr.am")) {
                html = get(url);
                if (html != null) {
                    out = parseInstagram(html);
                }
                if (out.isEmpty()) {
                    html = get(instagramApiUrl(url));
                    if (html != null) {
                        out = parseInstagram(html);
                    }
                }
                if (out.isEmpty() && html != null) {
                    out = parseGeneric(html); // মেটা-ট্যাগ ফলব্যাক
                }
            } else {
                // যেকোনো অন্য সাইট — og:video / <video> থেকে
                html = get(url);
                if (html != null) {
                    out = parseGeneric(html);
                }
            }
        } catch (Throwable t) {
            StreamStore.note("extract err " + t.getClass().getSimpleName());
        }
        return out;
    }

    // ==================== বিশুদ্ধ পার্সার (টেস্টযোগ্য) ====================

    /**
     * TikTok: নতুন পেজে `__UNIVERSAL_DATA_FOR_REHYDRATION__`-এ পূর্ণ JSON
     * থাকে; পুরনো/সরল পেজে সোজা `playAddr`/`downloadAddr`। দুটোই দেখি।
     */
    static ArrayList<Hit> parseTikTok(String html) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (html == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        // মানের গুণমান অনুসারে: downloadAddr আগে, তারপর playAddr
        addUrl(out, seen, firstString(html, "\"downloadAddr\":", "\"playAddr\":"));
        addUrl(out, seen, firstString(html, "\"playAddr\":", "\"downloadAddr\":"));
        addUrl(out, seen, firstString(html, "\"playAddrHd\":", null));
        // bitrateInfo[].PlayAddr.UrlList[] — একাধিক কোয়ালিটি দেয়।
        // আসল পেজে কী-গুলো মিশ্র হাতের ("Bitrate" / "UrlList"), তাই
        // কেস-নিরপেক্ষভাবে খুঁজি।
        try {
            int i = 0;
            while (true) {
                int urls = indexOfCI(html, "\"UrlList\"", i);
                if (urls < 0) {
                    break;
                }
                long bps = 0;
                int band = lastIndexOfCI(html, "\"Bitrate\"", urls);
                if (band >= 0 && urls - band < 800) {
                    int val = html.indexOf(':', band) + 1;
                    int end = val;
                    while (end < html.length()
                            && (Character.isDigit(html.charAt(end))
                                || html.charAt(end) == ' ')) {
                        end++;
                    }
                    try {
                        bps = Long.parseLong(html.substring(val, end).trim());
                    } catch (Throwable t) {
                    }
                }
                String u = firstArrayString(html, urls);
                if (u != null) {
                    String lab = bps >= 1500000 ? "HD"
                            : bps >= 800000 ? "SD" : "Video";
                    addUrl(out, seen, u, lab);
                }
                i = urls + 9;
            }
        } catch (Throwable t) {
        }
        return out;
    }

    /** ব্যাকআপ: পেজের ভেতরে যেকোনো tiktok/video-*.mp4 ঠিকানা খুঁজি। */
    static ArrayList<Hit> parseTikTokRehydration(String html) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (html == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int i = 0;
        while (i < html.length()) {
            int k = html.indexOf("https", i);
            if (k < 0) {
                break;
            }
            int end = k;
            while (end < html.length() && end - k < 1200) {
                char c = html.charAt(end);
                if (c == '"' || c == '\'' || c == '\\' && end + 1 < html.length()
                        && (html.charAt(end + 1) == 'u' || html.charAt(end + 1) == '"')) {
                    break;
                }
                if (c == ' ' || c == '<' || c == '>') {
                    break;
                }
                end++;
            }
            String raw = html.substring(k, end);
            String u = unescape(raw);
            if (u.contains(".mp4") && (u.contains("tiktok") || u.contains("tiktokcdn")
                    || u.contains("/video/"))) {
                addUrl(out, seen, u, null);
            }
            i = k + 5;
        }
        return out;
    }

    /** Facebook: hd_src / sd_src, অথবা ফোনের পেজে playable_url*. */
    static ArrayList<Hit> parseFacebook(String html) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (html == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        addUrl(out, seen, firstString(html, "\"browser_native_hd_url\":", null), "HD");
        addUrl(out, seen, firstString(html, "\"playable_url_quality_hd\":", null), "HD");
        addUrl(out, seen, firstString(html, "\"hd_src\":", null), "HD");
        addUrl(out, seen, firstString(html, "\"browser_native_sd_url\":", null), "SD");
        addUrl(out, seen, firstString(html, "\"playable_url\":", null), "SD");
        addUrl(out, seen, firstString(html, "\"sd_src\":", null), "SD");
        return out;
    }

    /** Instagram: video_versions[] (এন্ডপয়েন্ট বা পেজের ভেতরের JSON)। */
    static ArrayList<Hit> parseInstagram(String body) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (body == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        addUrl(out, seen, firstString(body, "\"video_url\":", null), "Video");
        try {
            int k = body.indexOf("\"video_versions\"");
            if (k >= 0) {
                int i = k;
                int found = 0;
                while (found < 4) {
                    int u = body.indexOf("\"url\":", i);
                    if (u < 0 || u - k > 4000) {
                        break;
                    }
                    String s = strValue(body, u + 6);
                    addUrl(out, seen, s, found == 0 ? "HD" : "SD");
                    i = u + 6;
                    found++;
                }
            }
        } catch (Throwable t) {
        }
        return out;
    }

    private static int indexOfCI(String body, String key, int from) {
        try {
            String l = body.toLowerCase();
            return l.indexOf(key.toLowerCase(), from);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int lastIndexOfCI(String body, String key, int before) {
        try {
            String l = body.toLowerCase();
            int at = Math.min(before + key.length(), l.length());
            return l.lastIndexOf(key.toLowerCase(), at);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** `"key":"value"` থেকে value (JSON-উদ্ধৃতি/এস্কেপ সামলে)। */
    static String firstString(String body, String key, String altKey) {
        String v = strAfter(body, key);
        if (v == null && altKey != null && altKey.length() > 0) {
            v = strAfter(body, altKey);
        }
        return v;
    }

    private static String strAfter(String body, String key) {
        int k = body.indexOf(key);
        if (k < 0) {
            return null;
        }
        return strValue(body, k + key.length());
    }

    /** key-এর পরে প্রথম JSON স্ট্রিং-মানটি পড়ে (খালি হলে null)। */
    private static String strValue(String body, int from) {
        int i = from;
        while (i < body.length() && (body.charAt(i) == ' ' || body.charAt(i) == ':'
                || body.charAt(i) == '\t')) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                sb.append(c).append(body.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        String v = unescape(sb.toString());
        return v.length() == 0 ? null : v;
    }

    private static String firstArrayString(String body, int fromKey) {
        int i = body.indexOf('[', fromKey);
        if (i < 0) {
            return null;
        }
        return strValue(body, i + 1);
    }

    /** JSON/JS এস্কেপ খোলা: \\/ \u002F \u0026 \/ ইত্যাদি। */
    static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(i + 1);
            if (n == '/' || n == '\\' || n == '"' || n == '\'') {
                sb.append(n);
                i++;
            } else if (n == 'u' && i + 5 < s.length()) {
                try {
                    int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                    sb.append((char) cp);
                    i += 5;
                } catch (Throwable t) {
                    sb.append(c);
                }
            } else if (n == 'n') {
                sb.append('\n');
                i++;
            } else if (n == 't') {
                sb.append('\t');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void addUrl(ArrayList<Hit> out, LinkedHashSet<String> seen,
                               String url) {
        addUrl(out, seen, url, null);
    }

    private static void addUrl(ArrayList<Hit> out, LinkedHashSet<String> seen,
                               String url, String label) {
        if (url == null) {
            return;
        }
        String u = unescape(url).trim().replace("&amp;", "&");
        if (!u.startsWith("http")) {
            return;
        }
        if (!seen.add(u)) {
            return;
        }
        boolean audio = u.contains(".m4a") || u.contains(".mp3");
        String ext = audio ? "m4a" : u.contains(".webm") ? "webm" : "mp4";
        out.add(new Hit(u, ext, audio,
                label != null ? label : (audio ? "Audio" : "Video")));
    }

    // ==================== নেটওয়ার্ক ====================

    static String get(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                StreamStore.note("extract http " + code + " " + host(u));
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > 4 * 1024 * 1024) {
                    break; // পেজ অনেক বড় হলে থামি
                }
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (c != null) {
                    c.disconnect();
                }
            } catch (Throwable t) {
            }
        }
    }

    /** vm.tiktok.com / fb.watch — ছোট লিংক আসল ঠিকানায় নিয়ে যাই। */
    static String resolveRedirects(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.getResponseCode();
            String real = c.getURL() != null ? c.getURL().toString() : u;
            return real;
        } catch (Throwable t) {
            return u;
        } finally {
            try {
                if (c != null) {
                    c.disconnect();
                }
            } catch (Throwable t) {
            }
        }
    }

    static String instagramApiUrl(String u) {
        String base = u;
        int q = base.indexOf('?');
        if (q >= 0) {
            base = base.substring(0, q);
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + "?__a=1&__d=dis";
    }

    static String host(String url) {
        try {
            String s = url == null ? "" : url.trim();
            int sc = s.indexOf("://");
            if (sc < 0) {
                return "";
            }
            String rest = s.substring(sc + 3);
            int slash = rest.indexOf('/');
            String hp = slash >= 0 ? rest.substring(0, slash) : rest;
            int at = hp.indexOf('@');
            if (at >= 0) {
                hp = hp.substring(at + 1);
            }
            int colon = hp.lastIndexOf(':');
            String h = colon > 0 ? hp.substring(0, colon) : hp;
            return h.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }
}
