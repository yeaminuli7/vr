package com.mytube.web;

import android.net.Uri;
import java.util.ArrayList;

/**
 * ওয়েব-পেজ নিজে যে মিডিয়া-ফাইল লোড করে (mp4 / m4a / webm / mp3) সেগুলো ধরে রাখে।
 *
 * <p>উদ্দেশ্য: ইউটিউবের বাইরের সাইট (TikTok ইত্যাদি) থেকে ডাউনলোড। আমরা কোনো
 * সাইট-ভিত্তিক স্ক্র্যাপার লিখি না — বরং ব্রাউজার হিসেবে পেজটি খুলে দিই, আর
 * পেজটা নিজে যে ভিডিও-ফাইলটা টানে সেটাই ধরে ফেলি। যে সাইট ফাইলটা লুকায়
 * (blob:, টুকরো-টুকরো স্ট্রিম) সেখানে কিছুই পাওয়া যায় না — তাতে ক্ষতি নেই,
 * ব্যবহারকারী শুধু পেজটা দেখবেন।
 *
 * <p>সাইট-নির্দিষ্ট কোড না থাকায় ইউটিউবের মতোই কিছু ভাঙার আশঙ্কা নেই।
 */
public class MediaSniffer {

    /** একটি পাওয়া মিডিয়া-ফাইল। */
    public static final class M {
        public final String url;
        public final String host;
        public final String ext;    // mp4 / m4a / webm / mp3 / ogg
        public final boolean audio;
        public final long at;

        /** ফাইলের আকার (বাইট); -1 = এখনো অজানা, -2 = পাওয়া যায়নি। */
        public volatile long size = -1;

        M(String u, String h, String e, boolean a) {
            url = u;
            host = h;
            ext = e;
            audio = a;
            at = System.currentTimeMillis();
        }

        /** "12.4 MB" / null (আকার অজানা হলে কিছুই দেখানো হয় না)। */
        public String sizeStr() {
            long n = size;
            if (n <= 0) {
                return null;
            }
            if (n >= 1073741824L) {
                return String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0);
            }
            if (n >= 1048576L) {
                return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
            }
            if (n >= 1024L) {
                return (n / 1024L) + " KB";
            }
            return n + " B";
        }

        /** "Video · tiktok.com" / "Audio · cdn.example.com" */
        public String label() {
            return (audio ? "Audio" : "Video") + " · " + host;
        }
    }

    private static final int MAX = 10;
    private static final long ARM_MS = 30L * 60L * 1000L;

    private static final ArrayList<M> list = new ArrayList<M>();
    private static volatile boolean armed = false;
    private static volatile long armedAt = 0L;
    private static volatile long pageTag = 0L;

    /** লিংক খোলার সময়: এই পেজের মিডিয়া খুঁজতে শুরু করি। */
    public static void arm() {
        synchronized (list) {
            list.clear();
        }
        armed = true;
        armedAt = System.currentTimeMillis();
        pageTag++;
    }

    public static void disarm() {
        armed = false;
    }

    public static boolean isArmed() {
        return armed && (System.currentTimeMillis() - armedAt) < ARM_MS;
    }

    public static long pageTag() {
        return pageTag;
    }

    public static ArrayList<M> list() {
        ArrayList<M> out = new ArrayList<M>();
        synchronized (list) {
            out.addAll(list);
        }
        return out;
    }

    public static void clear() {
        synchronized (list) {
            list.clear();
        }
    }

    /**
     * একটি রিকোয়েস্ট দেখা গেল। মিডিয়া-ফাইল মনে হলে তালিকায় যোগ হয় এবং
     * সত্যি ফেরত দেয় (নতুন কিছু যোগ হলে) — নাহলে false।
     */
    public static boolean capture(Uri u) {
        return u != null && capture(u.toString());
    }

    /**
     * স্ট্রিং-ভিত্তিক মূল যুক্তি (অ্যান্ড্রয়েড-মুক্ত, তাই আলাদা টেস্ট করা যায়)।
     * মিডিয়া-ফাইল মনে হলে তালিকায় যোগ করে true ফেরত দেয়।
     */
    public static boolean capture(String full) {
        if (!isArmed() || full == null) {
            return false;
        }
        try {
            // নিজে ভেঙে নিই — java.net.URI কঠোর, আসল CDN-URL-এ প্রায়ই ব্যর্থ হয়
            int sc = full.indexOf("://");
            if (sc <= 0) {
                return false;
            }
            String sch = full.substring(0, sc).toLowerCase();
            if (!sch.equals("http") && !sch.equals("https")) {
                return false;
            }
            int hash = full.indexOf('#');
            String base = hash >= 0 ? full.substring(0, hash) : full;
            int hs = sc + 3;
            int slash = base.indexOf('/', hs);
            String hostPort = slash >= 0 ? base.substring(hs, slash) : base.substring(hs);
            String pathQ = slash >= 0 ? base.substring(slash) : "";
            int qm = pathQ.indexOf('?');
            String path = qm >= 0 ? pathQ.substring(0, qm) : pathQ;
            String q = qm >= 0 ? pathQ.substring(qm + 1).toLowerCase() : "";
            int at = hostPort.indexOf('@');
            if (at >= 0) {
                hostPort = hostPort.substring(at + 1);
            }
            int colon = hostPort.lastIndexOf(':');
            String host = (colon > 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase();
            if (host.length() == 0 || host.indexOf('.') < 0) {
                return false;
            }
            // ইউটিউবের স্ট্রিম StreamStore দেখে — এখানে দরকার নেই
            if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")
                    || host.endsWith("ytimg.com") || host.endsWith("ggpht.com")
                    || host.endsWith("gstatic.com")) {
                return false;
            }
            String lower = path.toLowerCase();

            // টুকরো/সেগমেন্ট/মিডিয়া-বহির্ভূত জিনিস বাদ
            if (lower.endsWith(".ts") || lower.endsWith(".m4s")
                    || lower.endsWith(".m3u8") || lower.endsWith(".mpd")
                    || lower.endsWith(".vtt") || lower.endsWith(".srt")
                    || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".webp")
                    || lower.endsWith(".gif") || lower.endsWith(".svg")
                    || lower.endsWith(".js") || lower.endsWith(".css")
                    || lower.endsWith(".json") || lower.endsWith(".html")) {
                return false;
            }

            String ext = null;
            boolean audio = false;
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v")
                    || lower.endsWith(".mov") || lower.endsWith(".mkv")) {
                ext = lower.endsWith(".mkv") ? "mkv"
                        : lower.endsWith(".mov") ? "mov"
                        : lower.endsWith(".m4v") ? "m4v" : "mp4";
            } else if (lower.endsWith(".webm")) {
                ext = "webm";
            } else if (lower.endsWith(".m4a") || lower.endsWith(".aac")) {
                ext = "m4a";
                audio = true;
            } else if (lower.endsWith(".mp3")) {
                ext = "mp3";
                audio = true;
            } else if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
                ext = "ogg";
                audio = true;
            } else if (q.contains("mime_type=video")) {
                ext = "mp4";
            } else if (q.contains("mime_type=audio")) {
                ext = "m4a";
                audio = true;
            }
            if (ext == null) {
                return false;
            }

            synchronized (list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).url.equals(full)) {
                        return false;
                    }
                }
                M m = new M(full, host, ext, audio);
                list.add(0, m);
                while (list.size() > MAX) {
                    list.remove(list.size() - 1);
                }
                resolveSize(m);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * এক্সট্র্যাক্টর (TikTok/Facebook/Instagram) থেকে পাওয়া ঠিকানা যোগ করা —
     * "armed" বা হোস্ট-ফিল্টারের বাইরে, কারণ এগুলো ইচ্ছাকৃতভাবে বাছা।
     */
    public static void offer(String url, String ext, boolean audio, String host) {
        if (url == null || url.length() == 0) {
            return;
        }
        try {
            String h = host;
            if (h == null || h.length() == 0) {
                try {
                    int sc = url.indexOf("://");
                    int sl = url.indexOf('/', sc + 3);
                    h = sc >= 0 ? (sl > 0 ? url.substring(sc + 3, sl) : url.substring(sc + 3))
                            : "media";
                } catch (Throwable t) {
                    h = "media";
                }
            }
            String e = ext == null || ext.length() == 0 ? "mp4" : ext;
            synchronized (list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).url.equals(url)) {
                        return;
                    }
                }
                M m = new M(url, h, e, audio);
                list.add(0, m);
                while (list.size() > MAX) {
                    list.remove(list.size() - 1);
                }
                resolveSize(m);
            }
        } catch (Throwable t) {
        }
    }

    private static final java.util.concurrent.ExecutorService SZ =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    /**
     * ফাইলের আকার বের করা (অপশন-শিটে "12.4 MB" দেখানোর জন্য)। HEAD না
     * চললে Range: bytes=0-0 দিয়ে Content-Range পড়া হয়। ব্যর্থ হলে চুপচাপ
     * -2 — কিছুই দেখানো হয় না।
     */
    private static void resolveSize(final M m) {
        try {
            SZ.execute(new Runnable() {
                @Override
                public void run() {
                    long n = probeSize(m.url);
                    m.size = n > 0 ? n : -2;
                }
            });
        } catch (Throwable t) {
            m.size = -2;
        }
    }

    private static long probeSize(String u) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip");
            long len = c.getContentLength();
            if (len > 0) {
                return len;
            }
        } catch (Throwable t) {
        } finally {
            try {
                if (c != null) {
                    c.disconnect();
                }
            } catch (Throwable t) {
            }
        }
        // HEAD বন্ধ — প্রথম বাইটটুকু চেয়ে মোট আকার জানা যায়
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range", "bytes=0-0");
            String cr = c.getHeaderField("Content-Range"); // bytes 0-0/12345
            c.disconnect();
            if (cr != null) {
                int sl = cr.lastIndexOf('/');
                if (sl > 0) {
                    String tot = cr.substring(sl + 1).trim();
                    if (tot.length() > 0 && !tot.equals("*")) {
                        return Long.parseLong(tot);
                    }
                }
            }
        } catch (Throwable t) {
        }
        return -1;
    }

    /** এক্সটেনশন অনুযায়ী MIME (MediaStore-এ সঠিক ফোল্ডারে বসার জন্য)। */
    public static String mimeOf(String ext) {
        if (ext == null) {
            return "video/mp4";
        }
        if (ext.equals("mp3")) {
            return "audio/mpeg";
        }
        if (ext.equals("m4a")) {
            return "audio/mp4";
        }
        if (ext.equals("ogg")) {
            return "audio/ogg";
        }
        if (ext.equals("webm")) {
            return "video/webm";
        }
        if (ext.equals("mkv")) {
            return "video/x-matroska";
        }
        if (ext.equals("mov")) {
            return "video/quicktime";
        }
        if (ext.equals("m4v")) {
            return "video/x-m4v";
        }
        return "video/mp4";
    }
}
