package com.mytube.web;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * ডাউনলোড-মেটাডেটা: ফাইলের নাম যা বহন করতে পারে না — ভিডিও-আইডি, চ্যানেল,
 * কোয়ালিটি।
 *
 * অ্যাপ-প্রাইভেট ফাইলে থাকে (ইউজারের Downloads-ফোল্ডারে কোনো অতিরিক্ত
 * .json ফাইল নয়), কী = ফাইলের display name। ফাইল মুছে গেলে এন্ট্রিটাও
 * পরিষ্কার করা হয় (DlMeta.drop)।
 */
public final class DlMeta {

    private DlMeta() {
    }

    private static final Object LOCK = new Object();
    private static JSONObject map;
    private static boolean loaded;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "dlmeta.json");
    }

    private static JSONObject map(Context c) {
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                JSONObject m = null;
                try {
                    File f = file(c);
                    if (f.isFile()) {
                        byte[] b = new byte[(int) f.length()];
                        FileInputStream in = new FileInputStream(f);
                        int n = 0;
                        while (n < b.length) {
                            int r = in.read(b, n, b.length - n);
                            if (r < 0) {
                                break;
                            }
                            n += r;
                        }
                        in.close();
                        m = new JSONObject(new String(b, "UTF-8"));
                    }
                } catch (Throwable t) {
                    m = null;
                }
                map = m != null ? m : new JSONObject();
            }
            return map;
        }
    }

    private static void flush(Context c) {
        try {
            File f = file(c);
            File tmp = new File(f.getParentFile(), "dlmeta.tmp");
            byte[] b;
            synchronized (LOCK) {
                b = (map != null ? map : new JSONObject()).toString().getBytes("UTF-8");
            }
            FileOutputStream os = new FileOutputStream(tmp);
            os.write(b);
            os.close();
            if (f.isFile()) {
                f.delete();
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            // মেটাডেটা হারালে অ্যাপ চলবে, শুধু চ্যানেল-নাম দেখাবে না
        }
    }

    /** নামানো ফাইলের তথ্য সংরক্ষণ। কোনোটিই null হতে পারে। */
    public static void put(Context c, String name, String vid, String title,
            String ch, String label) {
        if (c == null || name == null || name.length() == 0) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", vid != null ? vid : "");
            o.put("t", title != null ? title : "");
            o.put("c", ch != null ? ch : "");
            o.put("q", label != null ? label : "");
            o.put("at", System.currentTimeMillis());
            synchronized (LOCK) {
                map(c).put(name, o);
            }
            flush(c);
        } catch (Throwable t) {
        }
    }

    /** ফাইল-নাম দিয়ে মেটাডেটা; না থাকলে null। */
    public static JSONObject get(Context c, String name) {
        if (c == null || name == null || name.length() == 0) {
            return null;
        }
        try {
            synchronized (LOCK) {
                return map(c).optJSONObject(name);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** ফাইল মুছে ফেলার সময় এন্ট্রি সরানো। */
    public static void drop(Context c, String name) {
        if (c == null || name == null || name.length() == 0) {
            return;
        }
        try {
            synchronized (LOCK) {
                map(c).remove(name);
            }
            flush(c);
        } catch (Throwable t) {
        }
    }
}
