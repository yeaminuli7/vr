package com.mytube.web;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;
    private final Thread.UncaughtExceptionHandler def;

    public CrashHandler(Context c) {
        ctx = c.getApplicationContext();
        def = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static File logFile(Context c) {
        return new File(c.getFilesDir(), "crash_log.txt");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File f = logFile(ctx);
            FileWriter w = new FileWriter(f, true);
            PrintWriter p = new PrintWriter(w);
            p.println("==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                    + " thread=" + t.getName()
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " model=" + android.os.Build.MODEL);
            e.printStackTrace(p);
            p.close();
            trim(f);
        } catch (Throwable ig) {
        }
        if (def != null) {
            def.uncaughtException(t, e);
        }
    }

    private static void trim(File f) {
        try {
            long max = 60000;
            if (f.length() > max) {
                byte[] b = readFile(f);
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                o.write(b, (int) (b.length - max), (int) max);
                o.close();
            }
        } catch (Throwable ig) {
        }
    }

    private static byte[] readFile(File f) throws Exception {
        java.io.FileInputStream i = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = i.read(buf)) > 0) {
            b.write(buf, 0, r);
        }
        i.close();
        return b.toByteArray();
    }
}
