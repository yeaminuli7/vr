package com.mytube.web;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;

/** uBlock Origin-শৈলী ফিল্টার ইঞ্জিন: হোস্ট-সাফিক্স + এক্সেপশন + থার্ড-পার্টি সচেতন। */
public class AdBlock {

    private static final String[] HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "ads.youtube.com", "google-analytics.com", "app-measurement.com",
            "analytics.google.com", "adservice.google.com",
            "fundingchoicesmessages.google.com", "firebaseinstallations.googleapis.com",
            "crashlytics.com", "graph.facebook.com", "connect.facebook.net",
            "app.adjust.com", "video-ad-stats.google.com", "ad.doubleclick.net",
            "v.doubleclick.net",
            /* --- added: modern ad / measurement / verification hosts --- */
            "imasdk.googleapis.com", "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
            "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "pubmatic.com",
            "rubiconproject.com", "criteo.com", "taboola.com", "outbrain.com",
            "moatads.com", "scorecardresearch.com", "quantserve.com",
            "googletagmanager.com", "googletagservices.com",
            "doubleverify.com", "adsafeprotected.com", "3lift.com",
            "openx.net", "casalemedia.com", "bidswitch.net", "adform.net",
            "innovid.com", "sharethrough.com", "spotxchange.com", "tremorhub.com"
    };

    private static final String[] PATHS = {
            "/api/stats/ads", "/pagead/", "/ptracking", "/get_midroll_", "/api/stats/qoe",
            /* --- added --- */
            "/gampad/", "/pagead/interaction/", "/adframes", "/viewad/",
            "/ads/measurement", "/log_interaction", "/ad_break"
    };

    /** অ্যাপের মূল কাজ যেসব সাইট ছাড়া চলে না — কখনো ব্লক হবে না (সাইট = শেষ দুই লেবেল)। */
    private static final String[] CORE_SITES = {
            "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
            "gstatic.com", "googleusercontent.com", "googleapis.com", "google.com",
            "gvt1.com", "youtubei.googleapis.com", "yt3.ggpht.com"
    };

    private static HashSet<String> sAlways = new HashSet<String>();
    private static HashSet<String> sThird = new HashSet<String>();
    private static HashSet<String> sWhite = new HashSet<String>();
    private static boolean sLoaded = false;

    private static synchronized void load(Context ctx) {
        if (sLoaded) {
            return;
        }
        read(ctx, "adfilter_hosts.txt", sAlways);
        read(ctx, "adfilter_3p.txt", sThird);
        read(ctx, "adfilter_white.txt", sWhite);
        for (int i = 0; i < HOSTS.length; i++) {
            sAlways.add(HOSTS[i]);
        }
        sLoaded = true;
    }

    private static void read(Context ctx, String name, HashSet<String> set) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(ctx.getAssets().open(name), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim().toLowerCase();
                if (l.length() > 0) {
                    set.add(l);
                }
            }
            r.close();
        } catch (Throwable t) {
        }
    }

    public static boolean block(Context ctx, Uri u, String pageUrl) {
        try {
            if (u == null) {
                return false;
            }
            String host = u.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            load(ctx);

            /* Known ad hosts win outright. This check must come BEFORE the
               whitelist, otherwise an entry in adfilter_white.txt silently
               disables blocking for a domain that is also an ad host - which
               is exactly what happened to pubads.g.doubleclick.net. */
            for (int i = 0; i < HOSTS.length; i++) {
                String h = HOSTS[i];
                if (host.equals(h) || host.endsWith("." + h)) {
                    return true;
                }
            }
            if (isCore(host)) {
                return false;
            }
            if (match(sWhite, host)) {
                return false;
            }
            if (match(sAlways, host)) {
                return true;
            }
            String p = u.getPath();
            if (p != null) {
                String lp = p.toLowerCase();
                for (int i = 0; i < PATHS.length; i++) {
                    if (lp.startsWith(PATHS[i])) {
                        return true;
                    }
                }
            }
            String page = pageUrl != null ? hostOf(pageUrl) : null;
            if (page != null && !site(host).equals(site(page))) {
                if (match(sThird, host)) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private static boolean isCore(String host) {
        String s = site(host);
        for (int i = 0; i < CORE_SITES.length; i++) {
            if (s.equals(CORE_SITES[i])) {
                return true;
            }
        }
        return false;
    }

    /** host-সাফিক্স ম্যাচ: a.b.c.com → b.c.com → c.com */
    private static boolean match(HashSet<String> set, String host) {
        String h = host;
        while (h != null) {
            if (set.contains(h)) {
                return true;
            }
            int i = h.indexOf('.');
            if (i < 0 || h.indexOf('.', i + 1) < 0) {
                return false;
            }
            h = h.substring(i + 1);
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String u = url;
            int i = u.indexOf("://");
            if (i >= 0) {
                u = u.substring(i + 3);
            }
            i = u.indexOf('/');
            if (i >= 0) {
                u = u.substring(0, i);
            }
            i = u.indexOf(':');
            if (i >= 0) {
                u = u.substring(0, i);
            }
            return u.toLowerCase();
        } catch (Throwable t) {
            return null;
        }
    }

    /** সাইট = শেষ দুই লেবেল (youtube.com-এর মতো) */
    private static String site(String host) {
        int i = host.lastIndexOf('.');
        if (i < 0) {
            return host;
        }
        int j = host.lastIndexOf('.', i - 1);
        return j < 0 ? host : host.substring(j + 1);
    }
}
