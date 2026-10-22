package com.mytube.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ইউটিউব-থাম্বনেইল (i.ytimg.com) — কেবল **চলমান** ডাউনলোড-জবের কার্ডে ব্যবহার,
 * যেখানে নেটওয়ার্ক আগেই চালু আছে।
 *
 * নামা-শেষ ভিডিওর থাম্বনেইল ফাইল থেকেই আসে (DlThumb), তাই অফলাইনেও চলে।
 */
public final class YtThumb {

    private YtThumb() {
    }

    private static final int CACHE_N = 40;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_N);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static java.util.concurrent.ExecutorService pool;

    private static synchronized java.util.concurrent.ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        }
        return pool;
    }

    public static void load(final Context c, final String vid, final ImageView iv) {
        if (c == null || vid == null || vid.length() != 11 || iv == null) {
            return;
        }
        Bitmap hit;
        synchronized (CACHE) {
            hit = CACHE.get(vid);
        }
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        try {
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = fetch(vid);
                    if (b == null) {
                        return;
                    }
                    synchronized (CACHE) {
                        CACHE.put(vid, b);
                    }
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                iv.setImageBitmap(b);
                            } catch (Throwable t) {
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
        }
    }

    private static Bitmap fetch(String vid) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(
                    "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg").openConnection();
            cn.setConnectTimeout(6000);
            cn.setReadTimeout(6000);
            cn.setInstanceFollowRedirects(true);
            cn.connect();
            if (cn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = cn.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (cn != null) {
                try {
                    cn.disconnect();
                } catch (Throwable t) {
                }
            }
        }
    }
}
