package com.mytube.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

/**
 * নামানো ফাইল থেকে থাম্বনেইল ও দৈর্ঘ্য বের করা।
 *
 * নেটওয়ার্ক লাগে না — MediaMetadataRetriever সরাসরি ফাইল থেকে এক ফ্রেম
 * তুলে আনে, তাই অফলाइনেও কাজ করে এবং পুরনো ডাউনলোডও থাম্বনেইল পায়।
 */
public final class DlThumb {

    private DlThumb() {
    }

    /** এক ফাইলের ফলাফল। */
    public static final class Info {
        /** থাম্বনেইল; না পাওয়া গেলে null। */
        public Bitmap bmp;
        /** মিলিসেকেন্ডে দৈর্ঘ্য; না জানা গেলে -1। */
        public long durMs = -1L;
        /** সত্য = ভিডিও-ফ্রেম নেই (অডিও-ফাইল), placeholder দেখাতে হবে। */
        public boolean audio;
    }

    private static final int CACHE_N = 80;
    private static final LruCache<String, Info> CACHE = new LruCache<String, Info>(CACHE_N);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static java.util.concurrent.ExecutorService pool;

    private static synchronized java.util.concurrent.ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        }
        return pool;
    }

    /** ক্যাশে থাকলে সাথে সাথে ফেরত দেয়, নাহলে null। */
    public static Info peek(String key) {
        if (key == null) {
            return null;
        }
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    /**
     * ব্যাকগ্রাউন্ডে থাম্বনেইল + দৈর্ঘ্য এনে main-thread-এ cb চালায়।
     * cb null হলে শুধু ক্যাশ ভরার কাজটা হয় (পরের বারের জন্য)।
     */
    public static void load(final Context c, final String key, final Uri uri,
            final boolean audio, final Callback cb) {
        if (c == null || key == null || uri == null) {
            return;
        }
        Info hit = peek(key);
        if (hit != null) {
            if (cb != null) {
                cb.onInfo(hit);
            }
            return;
        }
        try {
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    final Info info = read(c, uri, audio);
                    synchronized (CACHE) {
                        CACHE.put(key, info);
                    }
                    if (cb != null) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onInfo(info);
                            }
                        });
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private static Info read(Context c, Uri uri, boolean audio) {
        Info out = new Info();
        out.audio = audio;
        MediaMetadataRetriever r = null;
        try {
            r = new MediaMetadataRetriever();
            r.setDataSource(c, uri);

            try {
                String d = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    out.durMs = Long.parseLong(d.trim());
                }
            } catch (Throwable t) {
            }

            if (audio) {
                try {
                    byte[] art = r.getEmbeddedPicture();
                    if (art != null && art.length > 0) {
                        Bitmap b = android.graphics.BitmapFactory.decodeByteArray(
                                art, 0, art.length);
                        if (b != null) {
                            out.bmp = scale(b, 320);
                            out.audio = false; // এমবেডেড আর্ট আছে
                        }
                    }
                } catch (Throwable t) {
                }
                return out;
            }

            // -1 = যেকোনো ফ্রেম; ২ সেকেন্ডে যাওয়া হয় যাতে কালো শুরু-ফ্রেম এড়ানো যায়
            Bitmap f = r.getFrameAtTime(2000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) {
                f = r.getFrameAtTime(-1L);
            }
            if (f != null) {
                out.bmp = scale(f, 320);
            }
        } catch (Throwable t) {
            // থাম্বনেইল না পেলেও দৈর্ঘ্য থাকতে পারে — চুপচাপ ফেরত
        } finally {
            if (r != null) {
                try {
                    r.release();
                } catch (Throwable t) {
                }
            }
        }
        return out;
    }

    /** প্রস্থ ধরে 16:9 ক্রপ → স্কেল (থাম্বনেইল ভিউ-এ CENTER_CROP করা হবে)। */
    private static Bitmap scale(Bitmap src, int targetW) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) {
                return src;
            }
            if (w <= targetW) {
                return src;
            }
            int th = h * targetW / w;
            return Bitmap.createScaledBitmap(src, targetW, th, true);
        } catch (Throwable t) {
            return src;
        }
    }

    public interface Callback {
        void onInfo(Info info);
    }
}
