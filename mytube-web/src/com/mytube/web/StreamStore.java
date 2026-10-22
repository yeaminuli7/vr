package com.mytube.web;

import android.net.Uri;
import java.util.ArrayList;

/** ওয়েব-প্লেয়ার নিজে যে googlevideo স্ট্রিম-URL ব্যবহার করে, সেগুলো চুপিচুপি সংগ্রহ করে রাখে। */
public class StreamStore {

    public static class S {
        public final String url;
        public final String mime;
        public final String itag;
        public final boolean pr;

        S(String u, String m, String i) {
            this(u, m, i, false);
        }

        S(String u, String m, String i, boolean playerResponse) {
            url = u;
            mime = m;
            itag = i;
            pr = playerResponse;
        }
    }

    private static final ArrayList<S> list = new ArrayList<S>();

    /** WebView-এর UA (MediaPlayer-হেডারে ব্যবহৃত)। */
    public static volatile String sUA = "";
    private static final StringBuffer log = new StringBuffer();

    public static void note(String s) {
        try {
            synchronized (log) {
                log.append(new java.text.SimpleDateFormat("HH:mm:ss")
                        .format(new java.util.Date())).append(' ').append(s).append('\n');
                if (log.length() > 4000) {
                    log.delete(0, log.length() - 4000);
                }
            }
        } catch (Throwable t) {
        }
    }

    public static String dump() {
        synchronized (log) {
            return log.toString();
        }
    }

    public static int count() {
        synchronized (list) {
            return list.size();
        }
    }

    /** player-response থেকে পাওয়া ফরম্যাট (আসল ফুল-ফাইল URL)। */
    public static void addFmt(String url, String itag, String mime) {
        try {
            if (url == null || itag == null || url.indexOf("googlevideo") < 0) {
                return;
            }
            if (mime == null) {
                mime = "video/?";
            }
            synchronized (list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).itag.equals(itag)) {
                        list.remove(i);
                    }
                }
                list.add(new S(url, mime, itag, true));
                if (list.size() > 24) {
                    list.remove(0);
                }
            }
            note("fmt itag=" + itag + " pr");
        } catch (Throwable t) {
        }
    }

    /** কম্বাইন্ড (অডিও+ভিডিও) স্ট্রিম — itag 18। */
    public static S combined() {
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                S s = list.get(i);
                if (s.itag.equals("18") && s.pr) {
                    return s;
                }
            }
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).itag.equals("18")) {
                    return list.get(i);
                }
            }
        }
        return null;
    }

    /** ভিডিও-অনলি স্ট্রিম (adaptive) — বড় itag = ভালো কোয়ালিটি। */
    public static S videoOnly() {
        S best = null;
        int bi = -1;
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                S s = list.get(i);
                if (!s.mime.startsWith("video/") || s.itag.equals("18")) {
                    continue;
                }
                try {
                    int n = Integer.parseInt(s.itag);
                    if (n > bi) {
                        bi = n;
                        best = s;
                    }
                } catch (Throwable t) {
                }
            }
        }
        return best;
    }

    public static void capture(Uri u) {
        try {
            String host = u.getHost();
            if (host == null || !host.endsWith("googlevideo.com")) {
                return;
            }
            String p = u.getPath();
            if (p == null || !p.contains("videoplayback")) {
                return;
            }
            String mime = q(u, "mime");
            String itag = q(u, "itag");
            if (itag == null) {
                return;
            }
            if (mime == null) {
                mime = "video/?";
            }
            String us = stripSeg(u.toString());
            synchronized (list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).itag.equals(itag)) {
                        list.remove(i);
                    }
                }
                list.add(new S(us, mime, itag));
                if (list.size() > 16) {
                    list.remove(0);
                }
            }
            note("cap itag=" + itag + " " + mime);
        } catch (Throwable t) {
        }
    }

    /** অডিও-হ্যান্ডঅফের স্ট্রিম: m4a > যেকোনো অডিও > itag-18 (MP4 A/V) > শেষ যেকোনো। */
    public static S bestAudio() {
        S prM4a = null;
        S prAny = null;
        S capM4a = null;
        S capAny = null;
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                S s = list.get(i);
                if (s.mime.startsWith("audio/")) {
                    if (s.pr) {
                        if (prAny == null) {
                            prAny = s;
                        }
                        if (s.mime.contains("mp4")) {
                            prM4a = s;
                        }
                    } else {
                        if (capAny == null) {
                            capAny = s;
                        }
                        if (s.mime.contains("mp4") && capM4a == null) {
                            capM4a = s;
                        }
                    }
                }
            }
        }
        S pick = prM4a != null ? prM4a : prAny != null ? prAny
                : capM4a != null ? capM4a : capAny;
        if (pick != null) {
            note("pick audio itag=" + pick.itag + (pick.pr ? " pr" : ""));
            return pick;
        }
        S av = combined();
        if (av != null) {
            note("pick av18");
            return av;
        }
        note("pick none");
        return null;
    }

    public static S find(String itag) {
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).itag.equals(itag)) {
                    return list.get(i);
                }
            }
        }
        return null;
    }

    /** সেগমেন্ট-চিহ্ন (range/rn/rbuf) সরায় — নাহলে MediaPlayer মাত্র কয়েক সেকেন্ড পায়। */
    private static String stripSeg(String url) {
        String[] pars = {"range", "rn", "rbuf"};
        for (int p = 0; p < pars.length; p++) {
            while (true) {
                String par = pars[p];
                int i = url.indexOf("?" + par + "=");
                if (i < 0) {
                    i = url.indexOf("&" + par + "=");
                }
                if (i < 0) {
                    break;
                }
                int j = url.indexOf('&', i + 1);
                url = j < 0 ? url.substring(0, i) : url.substring(0, i) + url.substring(j);
            }
        }
        return url;
    }

    private static String q(Uri u, String k) {
        try {
            String v = u.getQueryParameter(k);
            return v != null ? v.toLowerCase() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
