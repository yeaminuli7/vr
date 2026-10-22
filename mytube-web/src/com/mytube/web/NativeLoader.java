package com.mytube.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * নেটিভ-লাইব্রেরি লোডার (libwtcodec.so):
 * ১) স্বাভাবিক পথ — System.loadLibrary (lib/arm64-v8a, ইনস্টলার-বের-করা)
 * ২) ফলব্যাক — assets-কপি → filesDir/nativelib → System.load(পথ)
 * ফলব্যাক ইনস্টলার-নির্ভরতা পুরোপুরি বাদ দেয় (কিছু ডিভাইস/Android-সংস্করণ
 * lib-এন্ট্রি বের না-ও করতে পারে — v1.19–v1.25-এ ফোনে ঠিক তা-ই ঘটেছে)।
 */
public final class NativeLoader {

    /** .so বদলালে বদলাতে হবে (পুরনো কপি বাদ দিতে)। */
    private static final String VER = "v63";

    private static boolean done = false;
    private static boolean ok = false;
    private static String how = "";
    private static String err = "";

    private NativeLoader() {
    }

    public static synchronized void ensure(android.content.Context c) {
        if (done) {
            return;
        }
        done = true;
        // ১) স্বাভাবিক পথ
        try {
            System.loadLibrary("wtcodec");
            ok = true;
            how = "libdir";
            StreamStore.note("native ok via libdir");
            return;
        } catch (Throwable t) {
            err = "libdir: " + t;
        }
        // ২) assets → filesDir
        try {
            File dir = new File(c.getFilesDir(), "nativelib");
            dir.mkdirs();
            File out = new File(dir, "libwtcodec." + VER + ".so");
            if (!out.exists() || out.length() == 0) {
                File tmp = new File(dir, "libwtcodec.tmp");
                InputStream in = c.getAssets().open("libwtcodec.so");
                OutputStream fo = new FileOutputStream(tmp);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    fo.write(b, 0, n);
                }
                fo.close();
                in.close();
                if (out.exists()) {
                    out.delete();
                }
                if (!tmp.renameTo(out)) {
                    throw new Exception("rename failed");
                }
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
            System.load(out.getAbsolutePath());
            ok = true;
            how = "assets";
            StreamStore.note("native ok via assets (" + out.length() + " bytes)");
            return;
        } catch (Throwable t2) {
            err = err + " | assets: " + t2;
        }
        StreamStore.note("native load fail: " + err);
    }

    public static boolean loaded() {
        return ok;
    }

    /** কোন পথে লোড হলো ("libdir"/"assets"), না-হলে ""। */
    public static String how() {
        return ok ? how : "";
    }

    /** ব্যর্থতার পূর্ণ বার্তা (ডায়াগনস্টিক)। */
    public static String error() {
        return ok ? "" : err;
    }
}
