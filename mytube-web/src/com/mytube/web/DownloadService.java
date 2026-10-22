package com.mytube.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ডাউনলোড-ইঞ্জিন v2 (SnapTube-কৌশল):
 * - জব-কিউ: একসাথে অনেক ভিডিও; ৩টা সমান্তরাল, বাকি লাইনে
 * - সর্বোচ্চ গতি: প্রতি ফাইল ৪-চাংক Range-পারালাল + ভিডিও-অডিও একসাথে (≈৮ কানেকশন)
 * - মিক্সিং: TrackReader-রিমাক্স (EOS-নিরাপদ, প্রগ্রেস-সহ)
 * - MP3: m4a → MediaCodec-ডিকোড → LAME; SRT: VTT→SRT
 * - প্রতি-জব নোটিফিকেশন + ক্যানসেল; Android 10+: MediaStore Downloads
 */
public class DownloadService extends Service {

    public static final String ACT_START = "com.mytube.web.dl.START";
    public static final String ACT_CANCEL = "com.mytube.web.dl.CANCEL";
    public static final String ACT_PAUSE = "com.mytube.web.dl.PAUSE";
    public static final String ACT_RESUME = "com.mytube.web.dl.RESUME";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_V = "v";
    public static final String EXTRA_A = "a";
    public static final String EXTRA_LABEL = "label"; // যেমন "720p" / "192kbps"
    /** ইউটিউব-ভিডিও-আইডি (থাম্বনেইল/মেটাডেটার জন্য; না থাকলে ফাঁকা) */
    public static final String EXTRA_VID = "vid";
    /** আপলোডার-চ্যানেলের নাম (মেটাডেটার জন্য; না থাকলে ফাঁকা) */
    public static final String EXTRA_CH = "chn";
    public static final String EXTRA_DIRECT = "direct"; // সরাসরি এক-ফাইল URL
    /** সরাসরি ডাউনলোডের ফাইল-এক্সটেনশন (mp4/m4a/webm/mp3...) — না দিলে mp4। */
    public static final String EXTRA_DEXT = "dext";
    public static final String EXTRA_MP3 = "mp3"; // int: বিটরেট (128/192/320)
    public static final String EXTRA_SRT = "srt"; // ক্যাপশন baseUrl
    public static final String EXTRA_JID = "jid"; // ক্যানসেলের জন্য জব-স্লট

    /** পুরনো-সামঞ্জস্য: কোনো জব চলছে কি না। */
    public static volatile boolean sRunning = false;
    public static volatile DownloadService sSelf = null;

    private static final int MAX_PARALLEL = 3;
    private static final int PARTS = 12;
    private static final long MULTIPART_MIN = 3L * 1024 * 1024;
    private static final int NID_SUMMARY = 30;
    private static final int NID_BASE = 31;
    private static final String CH = "downloads";
    private static final String UA =
            "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip";

    private static final class Job {
        String title;
        String v;
        String a;
        String label;
        String vid; // ইউটিউব-ভিডিও-আইডি
        String ch;  // আপলোডার-চ্যানেল
        String direct;
    String dext;
        String srt;
        int mp3;
        int slot;
        volatile boolean cancel = false;
        volatile boolean paused = false;
        final AtomicLong got = new AtomicLong();
        volatile long total = 0;
        volatile int phase = 0; // 0=download 1=mux 2=convert 3=queue
        volatile long t0 = 0;
        volatile long muxWritten = 0;
        volatile long muxTotal = 1;
        String doneMsg; // সফল-বার্তা (m4a-ফলব্যাক ইত্যাদির জন্য)
        volatile long lastGot = 0;
        volatile long lastT = 0;
        volatile double winSpeed = 0; // তাৎক্ষণিক MB/s
    }

    private final ArrayList<Job> queue = new ArrayList<>(); // main-thread only
    private final ArrayList<Job> active = new ArrayList<>(); // main-thread only
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private int slotSeq = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        NativeLoader.ensure(this);
        sSelf = this;
        // বেশি সমান্তরাল কানেকশনের জন্য keep-alive-পুল বাড়াই
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "32");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String act = i != null && i.getAction() != null ? i.getAction() : ACT_START;
        // FGS-বাধ্যতা: সবার আগে (v1.15-শিক্ষা)
        startFgSummary();
        if (ACT_CANCEL.equals(act)) {
            final int jid = i != null ? i.getIntExtra(EXTRA_JID, -1) : -1;
            main.post(new Runnable() {
                @Override
                public void run() {
                    cancelJob(jid);
                }
            });
            return START_NOT_STICKY;
        }
        if (ACT_PAUSE.equals(act) || ACT_RESUME.equals(act)) {
            final int jid = i != null ? i.getIntExtra(EXTRA_JID, -1) : -1;
            final boolean pa = ACT_PAUSE.equals(act);
            main.post(new Runnable() {
                @Override
                public void run() {
                    setPaused(jid, pa);
                }
            });
            return START_NOT_STICKY;
        }
        if (i == null) {
            return START_NOT_STICKY;
        }
        final Job j = new Job();
        j.title = i.getStringExtra(EXTRA_TITLE);
        j.v = i.getStringExtra(EXTRA_V);
        j.a = i.getStringExtra(EXTRA_A);
        j.label = i.getStringExtra(EXTRA_LABEL);
        j.vid = i.getStringExtra(EXTRA_VID);
        j.ch = i.getStringExtra(EXTRA_CH);
        j.direct = i.getStringExtra(EXTRA_DIRECT);
        j.dext = i.getStringExtra(EXTRA_DEXT);
        j.srt = i.getStringExtra(EXTRA_SRT);
        j.mp3 = i.getIntExtra(EXTRA_MP3, 0);
        main.post(new Runnable() {
            @Override
            public void run() {
                enqueue(j);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        sSelf = null;
        super.onDestroy();
    }

    // ---------- লাইভ-স্ন্যাপশট (ইন-অ্যাপ ডাউনলোড-স্ক্রিনের জন্য) ----------

    public static final class Snap {
        public String title;
        public String label;
        public int phase; // 0=download 1=mux 2=convert 3=queued
        public long got;
        public long total;
        public long t0;
        public long muxWritten;
        public long muxTotal;
        public int slot;
        public boolean done;
        public boolean failed;
        public String msg;
        public double speed;
        public boolean paused;
        public String v;
        public String a;
        public String direct;
        public String srt;
        public int mp3;
        public String vid; // ইউটিউব-আইডি (চলমান জবের থাম্বনেইলের জন্য)
        public String ch;
    }

    private static final java.util.ArrayList<Snap> sDone = new java.util.ArrayList<>();

    private static Snap snap(Job j, boolean queued) {
        Snap s = new Snap();
        s.speed = j.winSpeed;
        s.title = j.title;
        s.label = j.label;
        s.phase = queued ? 3 : j.phase;
        s.got = j.got.get();
        s.total = j.total;
        s.t0 = j.t0;
        s.muxWritten = j.muxWritten;
        s.muxTotal = j.muxTotal;
        s.slot = j.slot;
        s.paused = j.paused;
        s.v = j.v;
        s.a = j.a;
        s.vid = j.vid;
        s.ch = j.ch;
        s.direct = j.direct;
        s.srt = j.srt;
        s.mp3 = j.mp3;
        return s;
    }

    /** UI-থ্রেড থেকে ডাকো: সব জব (চলছে+লাইনে+সাম্প্রতিক-শেষ) এর অবস্থা। */
    public static java.util.ArrayList<Snap> snapshot() {
        java.util.ArrayList<Snap> out = new java.util.ArrayList<>();
        DownloadService s = sSelf;
        if (s != null) {
            synchronized (s.queue) {
                for (int i = 0; i < s.queue.size(); i++) {
                    out.add(snap(s.queue.get(i), true));
                }
            }
            for (int i = 0; i < s.active.size(); i++) {
                out.add(snap(s.active.get(i), false));
            }
        }
        synchronized (sDone) {
            out.addAll(sDone);
        }
        return out;
    }

    // ---------- কিউ-ব্যবস্থাপনা (main-thread) ----------

    private void enqueue(Job j) {
        j.slot = slotSeq++;
        j.phase = 3;
        synchronized (queue) {
            queue.add(j);
        }
        notifyState(j, "Queued");
        pump();
    }

    private void pump() {
        while (active.size() < MAX_PARALLEL) {
            Job j;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    break;
                }
                j = queue.remove(0);
            }
            active.add(j);
            startJob(j);
        }
        updateSummary();
        if (active.isEmpty() && queueEmpty()) {
            sRunning = false;
            stopForeground(true);
            stopSelf();
        } else {
            sRunning = true;
        }
    }

    private boolean queueEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    private void startJob(final Job j) {
        j.phase = 0;
        j.t0 = System.currentTimeMillis();
        j.lastT = j.t0;
        j.lastGot = 0;
        j.winSpeed = 0;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runJob(j);
                    if (j.cancel) {
                        notifyDone(j, "Cancelled: " + j.title, true);
                    } else {
                        notifyDone(j, j.doneMsg != null ? j.doneMsg
                                : "Saved: " + safeName(j.title, j.label), false);
                    }
                } catch (final Throwable t) {
                    String msg = t.getMessage();
                    if (msg == null || msg.length() == 0) {
                        msg = t.getClass().getSimpleName();
                    }
                    if (msg.length() > 80) {
                        msg = msg.substring(0, 80);
                    }
                    StreamStore.note("dl fail [" + j.label + "]: " + msg);
                    notifyDone(j, "Failed: " + j.title + " - " + msg, true);
                } finally {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            active.remove(j);
                            pump();
                        }
                    });
                }
            }
        });
    }

    private void cancelJob(int slot) {
        synchronized (queue) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).slot == slot) {
                    Job j = queue.remove(i);
                    j.cancel = true;
                    nm().cancel(NID_BASE + j.slot);
                    updateSummary();
                    return;
                }
            }
        }
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).slot == slot) {
                active.get(i).cancel = true;
                return;
            }
        }
    }

    private void setPaused(int slot, boolean pa) {
        Job found = null;
        synchronized (queue) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).slot == slot) {
                    found = queue.get(i);
                    break;
                }
            }
        }
        if (found == null) {
            for (int i = 0; i < active.size(); i++) {
                if (active.get(i).slot == slot) {
                    found = active.get(i);
                    break;
                }
            }
        }
        if (found != null && found.paused != pa) {
            found.paused = pa;
            notifyState(found, pa ? "Paused" : "Resumed");
        }
    }

    private void runJob(Job j) throws Exception {
        try {
            if (getSharedPreferences("mtw", 0).getBoolean("wifiOnly", false)
                    && isMeteredNet()) {
                throw new IllegalStateException(
                        "Waiting for Wi-Fi (Wi-Fi only is on)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
        }
        File dir = new File(getCacheDir(), "dl" + j.slot);
        dir.mkdirs();
        try {
            if (j.cancel) {
                return;
            }
            if (j.srt != null && j.srt.length() > 0) {
                downloadSub(j, dir);
            } else if (j.mp3 > 0 && j.a != null && j.a.length() > 0) {
                downloadMp3(j, dir);
            } else if (j.direct != null && j.direct.length() > 0) {
                String ext = j.dext != null && j.dext.length() > 0 ? j.dext : "mp4";
                String mime = MediaSniffer.mimeOf(ext);
                File out = new File(dir, "s.part");
                parFetch(j.direct, out, j, 0);
                if (j.cancel) {
                    return;
                }
                savePublic(j, out, safeName(j.title, "media") + "." + ext, mime);
            } else if (j.v != null && j.v.length() > 0 && j.a != null && j.a.length() > 0) {
                downloadMerged(j, dir);
            } else if (j.v != null && j.v.length() > 0) {
                File out = new File(dir, "s.part");
                parFetch(j.v, out, j, 0);
                if (j.cancel) {
                    return;
                }
                savePublic(j, out, safeName(j.title, "audio") + ".m4a", "audio/mp4");
            }
        } finally {
            deleteDir(dir);
        }
    }

    // ---------- SRT ----------

    private void downloadSub(Job j, File dir) throws Exception {
        // প্রথমে VTT-বলে চাই; সার্ভার XML দিলে toSrtAny-ই সামলায়; খালি হলে fmt ছাড়া আবার
        String body = fetchText(j.srt + (j.srt.contains("fmt=") ? "" : "&fmt=vtt"));
        StreamStore.note("srt body " + body.length() + " bytes");
        String srt = VttSrt.toSrtAny(body);
        if (srt.trim().length() == 0 && !j.srt.contains("fmt=")) {
            StreamStore.note("srt retry without fmt");
            body = fetchText(j.srt);
            srt = VttSrt.toSrtAny(body);
        }
        StreamStore.note("srt cues parsed ok");
        if (srt.trim().length() == 0) {
            throw new Exception("empty subtitles ("
                    + (body.length() > 0 ? body.substring(0, Math.min(40, body.length()))
                    : "no data") + ")");
        }
        File f = new File(dir, "s.srt");
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(srt.getBytes("UTF-8"));
        fo.close();
        savePublic(j, f, safeName(j.title, j.label != null ? j.label : "") + ".srt",
                "application/x-subrip");
    }

    private String fetchText(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent",
                "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0"
                        + " like Mac OS X; BD)");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new Exception("http " + code);
        }
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 4 * 1024 * 1024) {
                break;
            }
        }
        in.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    // ---------- MP3 ----------

    private void downloadMp3(Job j, File dir) throws Exception {
        File fa = new File(dir, "m.part");
        long asz = urlSize(j.a);
        j.total = Math.max(0, asz);
        parFetch(j.a, fa, j, asz);
        if (j.cancel) {
            return;
        }
        StreamStore.note("mp3 fetched " + fa.length() + " bytes");
        String base = safeName(j.title, j.mp3 + "kbps");
        Throwable lastErr = null;
        // ০) FFmpeg — নেটিভ C গতিতে ডিকোড+রিস্যাম্পল+lame, এক ধাপে
        if (Ffmpeg.available()) {
            try {
                j.phase = 2;
                notifyProgress(j);
                File out = new File(dir, "out.mp3");
                long t0 = System.currentTimeMillis();
                int r = Ffmpeg.mp3(fa.getAbsolutePath(), out.getAbsolutePath(), j.mp3);
                if (r == 0 && out.length() > 1024) {
                    savePublic(j, out, base + ".mp3", "audio/mpeg");
                    j.doneMsg = "Saved: " + base + ".mp3";
                    StreamStore.note("ffmpeg mp3 ok " + out.length() + " bytes in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return;
                }
                lastErr = new Exception("ffmpeg: " + Ffmpeg.err());
                StreamStore.note("ffmpeg mp3 fail r=" + r + " " + Ffmpeg.err());
            } catch (Throwable t) {
                lastErr = t;
                StreamStore.note("ffmpeg mp3 err " + t);
            }
        }
        // ১) ফোনের নিজস্ব MP3-এনকোডার (সিস্টেম/হার্ডওয়্যার)
        if (mediaMp3Supported()) {
            try {
                j.phase = 2;
                notifyProgress(j);
                File out = new File(dir, "out.mp3");
                convertAudio(fa, out, j.mp3, j, true);
                savePublic(j, out, base + ".mp3", "audio/mpeg");
                j.doneMsg = "Saved: " + base + ".mp3";
                StreamStore.note("mp3 via device encoder");
                return;
            } catch (Throwable t) {
                lastErr = t;
                StreamStore.note("hw mp3 fail: " + t);
            }
        }
        // ২) LAME (অ্যাপের ভেতরের এনকোডার)
        if (Lame.available()) {
            try {
                j.phase = 2;
                notifyProgress(j);
                File out = new File(dir, "out2.mp3");
                convertAudio(fa, out, j.mp3, j, false);
                savePublic(j, out, base + ".mp3", "audio/mpeg");
                j.doneMsg = "Saved: " + base + ".mp3";
                StreamStore.note("mp3 via lame");
                return;
            } catch (Throwable t) {
                lastErr = t;
                StreamStore.note("lame mp3 fail: " + t);
            }
        }
        // ৩) শেষ-ভরসা: MP3 না-পারলে অডিও m4a হিসেবে বাঁচাও — ব্যবহারকারী অডিও পাবেই
        try {
            savePublic(j, fa, safeName(j.title, "audio") + ".m4a", "audio/mp4");
            String why2 = lastErr != null && lastErr.getMessage() != null
                    && lastErr.getMessage().length() > 0 ? lastErr.getMessage()
                    : "no encoder";
            String ffL = Ffmpeg.loadError();
            String laL = Lame.loadError();
            if (ffL != null) {
                if (ffL.length() > 160) {
                    ffL = ffL.substring(0, 160);
                }
            }
            if (laL != null) {
                if (laL.length() > 160) {
                    laL = laL.substring(0, 160);
                }
            }
            StreamStore.note("enc miss: ff=" + ffL + " lame=" + laL
                    + " why=" + why2);
            String detail = (ffL != null || laL != null)
                    ? " - lib: " + (ffL != null ? "ff " + ffL + "; " : "")
                    + (laL != null ? "lame " + laL : "") : "";
            if (detail.length() > 220) {
                detail = detail.substring(0, 220);
            }
            j.doneMsg = "MP3 failed (" + why2 + ")" + detail + " - saved as M4A";
            StreamStore.note("mp3 fallback m4a; lame load: " + Lame.loadError());
            return;
        } catch (Throwable t2) {
            lastErr = t2;
        }
        String why = lastErr != null ? (lastErr.getMessage() != null
                && lastErr.getMessage().length() > 0 ? lastErr.getMessage()
                : lastErr.getClass().getSimpleName()) : "no encoder";
        String ll = Lame.loadError();
        throw new Exception("MP3 failed: " + why + (ll != null ? "; lib: " + ll : ""));
    }

    /** এই ফোনে সিস্টেম MP3-এনকোডার আছে কি না (একবার দেখে রাখে)। */
    private static Boolean sMediaMp3 = null;

    private boolean mediaMp3Supported() {
        if (sMediaMp3 != null) {
            return sMediaMp3;
        }
        try {
            MediaCodec c = MediaCodec.createEncoderByType("audio/mpeg");
            try {
                MediaFormat f = MediaFormat.createAudioFormat("audio/mpeg", 44100, 2);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                sMediaMp3 = Boolean.TRUE;
            } finally {
                try {
                    c.stop();
                } catch (Throwable t) {
                }
                try {
                    c.release();
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
            sMediaMp3 = Boolean.FALSE;
            StreamStore.note("media mp3 encoder: none ("
                    + t.getClass().getSimpleName() + ")");
        }
        return sMediaMp3;
    }

    /** PCM→MP3 লেখার গন্তব্য: সিস্টেম-এনকোডার বা LAME। */
    private interface AudioSink {
        void init(int sr, int ch, int kbps, File out) throws Exception;

        void write(short[] pcm, int perChannel) throws Exception;

        void finish() throws Exception;

        void abort();
    }

    private static final class LameSink implements AudioSink {
        private long h;
        private FileOutputStream fo;
        private final byte[] buf = new byte[65536];

        @Override
        public void init(int sr, int ch, int kbps, File out) throws Exception {
            h = Lame.open(sr, ch, kbps);
            if (h == 0) {
                throw new Exception("lame init failed");
            }
            fo = new FileOutputStream(out);
        }

        @Override
        public void write(short[] pcm, int perChannel) throws Exception {
            int w = Lame.encode(h, pcm, perChannel, buf);
            if (w < 0) {
                throw new Exception("lame encode " + w);
            }
            if (w > 0) {
                fo.write(buf, 0, w);
            }
        }

        @Override
        public void finish() throws Exception {
            int w = Lame.flush(h, buf);
            if (w > 0) {
                fo.write(buf, 0, w);
            }
            fo.close();
            Lame.close(h);
            h = 0;
        }

        @Override
        public void abort() {
            try {
                if (fo != null) {
                    fo.close();
                }
            } catch (Throwable t) {
            }
            if (h != 0) {
                Lame.close(h);
                h = 0;
            }
        }
    }

    /** ফোনের নিজস্ব "audio/mpeg" এনকোডার দিয়ে MP3। */
    private static final class MediaMp3Sink implements AudioSink {
        private MediaCodec enc;
        private FileOutputStream fo;
        private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        private long ptsUs = 0;
        private int sr = 44100;
        private boolean eosDone = false;

        @Override
        public void init(int sr2, int ch, int kbps, File out) throws Exception {
            sr = sr2;
            enc = MediaCodec.createEncoderByType("audio/mpeg");
            MediaFormat f = MediaFormat.createAudioFormat("audio/mpeg", sr2, ch);
            f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            fo = new FileOutputStream(out);
        }

        @Override
        public void write(short[] pcm, int perChannel) throws Exception {
            byte[] b = new byte[pcm.length * 2];
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(pcm);
            int off = 0;
            int guard = 0;
            while (off < b.length && !eosDone) {
                if (++guard > 20000) {
                    throw new Exception("encoder stalled");
                }
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    java.nio.ByteBuffer ib = enc.getInputBuffer(ii);
                    if (ib == null) {
                        enc.queueInputBuffer(ii, 0, 0, ptsUs, 0);
                        continue;
                    }
                    ib.clear();
                    int n = Math.min(ib.capacity(), b.length - off);
                    ib.put(b, off, n);
                    enc.queueInputBuffer(ii, 0, n, ptsUs, 0);
                    off += n;
                }
                drain(false);
            }
            ptsUs += perChannel * 1000000L / sr;
        }

        private void drain(boolean wait) throws Exception {
            int guard = 0;
            while (!eosDone) {
                if (!wait && ++guard > 64) {
                    return;
                }
                if (wait && ++guard > 400) {
                    return;
                }
                int oo = enc.dequeueOutputBuffer(bi, wait ? 1000 : 0);
                if (oo == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!wait) {
                        return;
                    }
                    continue;
                }
                if (oo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }
                if (oo < 0) {
                    continue;
                }
                java.nio.ByteBuffer ob = enc.getOutputBuffer(oo);
                if (ob != null && bi.size > 0) {
                    byte[] o = new byte[bi.size];
                    ob.position(bi.offset);
                    ob.limit(bi.offset + bi.size);
                    ob.get(o);
                    fo.write(o);
                }
                eosDone = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                enc.releaseOutputBuffer(oo, false);
            }
        }

        @Override
        public void finish() throws Exception {
            int guard = 0;
            while (!eosDone) {
                if (++guard > 50) {
                    break;
                }
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    enc.queueInputBuffer(ii, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drain(true);
                }
            }
            fo.close();
            try {
                enc.stop();
            } catch (Throwable t) {
            }
            try {
                enc.release();
            } catch (Throwable t) {
            }
        }

        @Override
        public void abort() {
            try {
                if (fo != null) {
                    fo.close();
                }
            } catch (Throwable t) {
            }
            try {
                if (enc != null) {
                    enc.stop();
                }
            } catch (Throwable t) {
            }
            try {
                if (enc != null) {
                    enc.release();
                }
            } catch (Throwable t) {
            }
        }
    }

    /** m4a ডিকোড → PCM → sink (MP3); useMedia=true হলে সিস্টেম-এনকোডার। */
    private void convertAudio(File in, File out, int kbps, Job j, boolean useMedia)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        int at = pickTrack(ex, "audio/");
        if (at < 0) {
            ex.release();
            throw new Exception("no audio track");
        }
        MediaFormat fmt = ex.getTrackFormat(at);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        ex.selectTrack(at);
        StreamStore.note("mp3 decode sr=" + sr + " ch=" + ch + " br=" + kbps
                + (useMedia ? " hw" : " lame"));
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(fmt, null, null, 0);
        dec.start();
        AudioSink sink = useMedia ? new MediaMp3Sink() : new LameSink();
        boolean sinkOpen = false;
        try {
            sink.init(sr, ch, kbps, out);
            sinkOpen = true;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int chunkShorts = 8192 * Math.max(1, ch);
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            boolean eos = false;
            long lastOut = System.currentTimeMillis();
            while (!eos && !j.cancel) {
                int ii = dec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer ib = dec.getInputBuffer(ii);
                    int sz = ib != null ? ex.readSampleData(ib, 0) : -1;
                    if (sz < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int oo = dec.dequeueOutputBuffer(bi, 10000);
                while (oo >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oo);
                    if (ob != null && bi.size > 0) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        byte[] b = new byte[bi.size];
                        ob.get(b);
                        acc.write(b);
                        while (acc.size() >= chunkShorts * 2) {
                            byte[] all = acc.toByteArray();
                            int ns = chunkShorts / Math.max(1, ch);
                            short[] pcm = new short[chunkShorts];
                            java.nio.ByteBuffer.wrap(all).order(
                                    java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                            sink.write(pcm, ns);
                            acc.reset();
                            if (all.length > chunkShorts * 2) {
                                acc.write(all, chunkShorts * 2, all.length - chunkShorts * 2);
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastOut > 500) {
                            lastOut = now;
                            notifyProgress(j);
                        }
                    }
                    lastOut = System.currentTimeMillis();
                    dec.releaseOutputBuffer(oo, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true;
                    }
                    oo = dec.dequeueOutputBuffer(bi, 0);
                }
                // ডিকোডার আটকে গেলে চিরকাল "converting" নয় — কারণসহ ফেল
                if (!eos && System.currentTimeMillis() - lastOut > 30000) {
                    throw new Exception("decoder stalled (no output 30s)");
                }
            }
            if (j.cancel) {
                throw new Exception("cancelled");
            }
            byte[] rest = acc.toByteArray();
            if (rest.length >= 2 * Math.max(1, ch)) {
                short[] pcm = new short[rest.length / 2];
                java.nio.ByteBuffer.wrap(rest).order(
                        java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                sink.write(pcm, pcm.length / Math.max(1, ch));
            }
            sink.finish();
            sinkOpen = false;
        } finally {
            if (sinkOpen) {
                sink.abort();
            }
            try {
                dec.stop();
            } catch (Throwable t) {
            }
            try {
                dec.release();
            } catch (Throwable t) {
            }
            try {
                ex.release();
            } catch (Throwable t) {
            }
        }
    }

    // ---------- ভিডিও+অডিও জোড়া ----------

    private void downloadMerged(final Job j, File dir) throws Exception {
        final File fv = new File(dir, "v.part");
        final File fa = new File(dir, "a.part");
        // সাইজ আগে (মোট-প্রগ্রেস + চাংক-সিদ্ধান্ত)
        final long vs = urlSize(j.v);
        final long as2 = urlSize(j.a);
        j.total = Math.max(0, vs) + Math.max(0, as2);
        // ভিডিও ও অডিও একসাথে (২-গুণ গতি)
        final Throwable[] err = new Throwable[1];
        Thread tv = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    parFetch(j.v, fv, j, vs);
                } catch (Throwable t) {
                    synchronized (err) {
                        if (err[0] == null) {
                            err[0] = t;
                        }
                    }
                }
            }
        });
        Thread ta = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    parFetch(j.a, fa, j, as2);
                } catch (Throwable t) {
                    synchronized (err) {
                        if (err[0] == null) {
                            err[0] = t;
                        }
                    }
                }
            }
        });
        tv.start();
        ta.start();
        tv.join();
        ta.join();
        if (j.cancel) {
            return;
        }
        if (err[0] != null) {
            throw new Exception("fetch: " + err[0].getMessage(), err[0]);
        }
        j.phase = 1;
        j.muxTotal = Math.max(1, fv.length() + fa.length());
        j.muxWritten = j.muxTotal / 2; // দ্রুত-পথের প্রগ্রেস
        notifyProgress(j);
        File out = new File(dir, "out.mp4");
        boolean muxed = false;
        if (Ffmpeg.available()) {
            try {
                long t0 = System.currentTimeMillis();
                int r = Ffmpeg.mux(fv.getAbsolutePath(), fa.getAbsolutePath(),
                        out.getAbsolutePath());
                if (r == 0 && out.length() > 1024) {
                    muxed = true;
                    StreamStore.note("ffmpeg mux ok " + out.length() + " bytes in "
                            + (System.currentTimeMillis() - t0) + "ms");
                } else {
                    StreamStore.note("ffmpeg mux fail r=" + r + " " + Ffmpeg.err());
                }
            } catch (Throwable t) {
                StreamStore.note("ffmpeg mux err " + t);
            }
        }
        if (!muxed) {
            j.muxWritten = 0;
            notifyProgress(j);
            mux(fv, fa, out, j);
            StreamStore.note("java mux ok");
        }
        savePublic(j, out, safeName(j.title, j.label) + ".mp4", "video/mp4");
    }

    // ---------- মিক্সার (EOS-নিরাপদ, এক-পাঠ) ----------

    private static final class Reader {
        final MediaExtractor ex;
        final int out;
        final ByteBuffer buf = ByteBuffer.allocateDirect(2 * 1024 * 1024);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean pending = false;
        boolean eos = false;

        Reader(MediaExtractor ex, int out) {
            this.ex = ex;
            this.out = out;
        }

        void fill() {
            if (pending || eos) {
                return;
            }
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) {
                eos = true;
                return;
            }
            info.set(0, sz, ex.getSampleTime(), ex.getSampleFlags());
            pending = true;
        }
    }

    private void mux(File v, File a, File out, Job j) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        ev.setDataSource(v.getAbsolutePath());
        int vt = pickTrack(ev, "video/");
        if (vt < 0) {
            ev.release();
            throw new Exception("no video track");
        }
        ev.selectTrack(vt);
        MediaExtractor ea = new MediaExtractor();
        ea.setDataSource(a.getAbsolutePath());
        int at = pickTrack(ea, "audio/");
        if (at < 0) {
            ev.release();
            ea.release();
            throw new Exception("no audio track");
        }
        ea.selectTrack(at);
        MediaMuxer m = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vto = m.addTrack(ev.getTrackFormat(vt));
        int ato = m.addTrack(ea.getTrackFormat(at));
        m.start();
        Reader vr = new Reader(ev, vto);
        Reader ar = new Reader(ea, ato);
        long samples = 0;
        long lastTick = 0;
        try {
            while (true) {
                vr.fill();
                ar.fill();
                if (vr.eos && ar.eos) {
                    break;
                }
                Reader pick;
                if (vr.eos) {
                    pick = ar;
                } else if (ar.eos) {
                    pick = vr;
                } else {
                    pick = vr.info.presentationTimeUs <= ar.info.presentationTimeUs ? vr : ar;
                }
                m.writeSampleData(pick.out, pick.buf, pick.info);
                j.muxWritten += pick.info.size;
                samples++;
                if (samples > 5000000) {
                    throw new Exception("mux stuck");
                }
                if ((samples & 2047) == 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastTick > 400) {
                        lastTick = now;
                        notifyProgress(j);
                    }
                }
                pick.pending = false;
                pick.ex.advance();
            }
            m.stop();
        } finally {
            try {
                m.release();
            } catch (Throwable t) {
            }
            try {
                ev.release();
            } catch (Throwable t) {
            }
            try {
                ea.release();
            } catch (Throwable t) {
            }
        }
    }

    private int pickTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- পারালাল-ফেচ (মাল্টি-চাংক Range) ----------

    private void parFetch(String u, File out, Job j, long knownSize) throws Exception {
        long size = knownSize > 0 ? knownSize : urlSize(u);
        if (size <= 0 || size < MULTIPART_MIN) {
            fetchOne(u, out, j);
            return;
        }
        try {
            if (!getSharedPreferences("mtw", 0).getBoolean("fastdl", true)) {
                fetchOne(u, out, j); // ফাস্ট-মোড বন্ধ = এক-সংযোগে ধীর-নামা
                return;
            }
        } catch (Throwable t) {
        }
        int np;
        if (size >= 16L * 1024 * 1024) {
            np = PARTS; // 12
        } else if (size >= 8L * 1024 * 1024) {
            np = 8;
        } else if (size >= 3L * 1024 * 1024) {
            np = 4;
        } else {
            np = 1;
        }
        if (j.total <= 0) {
            j.total = size;
        }
        long each = size / np;
        final Throwable[] err = new Throwable[1];
        Thread[] th = new Thread[np];
        for (int i = 0; i < np; i++) {
            final long start = i * (long) each;
            final long end = (i == np - 1) ? size - 1 : ((i + 1) * (long) each) - 1;
            th[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchRange(u, out, start, end, j);
                    } catch (Throwable t) {
                        synchronized (err) {
                            if (err[0] == null) {
                                err[0] = t;
                            }
                        }
                    }
                }
            });
            th[i].start();
        }
        for (int i = 0; i < np; i++) {
            th[i].join();
        }
        if (j.cancel) {
            return;
        }
        if (err[0] != null) {
            throw new Exception("part: " + err[0].getMessage(), err[0]);
        }
        if (out.length() < size) {
            throw new Exception("incomplete " + out.length() + "/" + size);
        }
    }

    private void fetchRange(String u, File out, long start, long end, Job j) throws Exception {
        long pos = start;
        int tries = 0;
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            raf.setLength(end + 1);
            while (pos <= end && !j.cancel) {
                while (j.paused && !j.cancel) {
                    try {
                        Thread.sleep(300);
                    } catch (Throwable t2) {
                    }
                }
                tries++; // প্রতিটি নতুন কানেকশনই একটি চেষ্টা
                if (tries > 8) {
                    throw new Exception("too many retries at " + pos);
                }
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    int code = c.getResponseCode();
                    if (code != 206 && code != 200) {
                        throw new Exception("http " + code);
                    }
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[65536];
                    raf.seek(pos);
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (j.cancel) {
                            break;
                        }
                        if (j.paused) {
                            break; // সংযোগ ছাড়ি — রেজিউমে Range-ই ফিরবে
                        }
                        raf.write(buf, 0, n);
                        pos += n;
                        j.got.addAndGet(n);
                        maybeProgress(j);
                    }
                    in.close();
                    c.disconnect();
                    if (pos > end) {
                        return;
                    }
                    // সার্ভার আগেই বন্ধ করে দিলে Range পুনরায় (রিজিউম)
                } catch (Throwable t) {
                    if (j.cancel) {
                        return;
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        } finally {
            try {
                raf.close();
            } catch (Throwable t) {
            }
        }
    }

    private void fetchOne(String u, File out, Job j) throws Exception {
        while (j.paused && !j.cancel) {
            try {
                Thread.sleep(300);
            } catch (Throwable t2) {
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() / 100 != 2) {
            throw new Exception("http " + c.getResponseCode());
        }
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (j.cancel) {
                break;
            }
            fo.write(buf, 0, n);
            j.got.addAndGet(n);
            maybeProgress(j);
        }
        fo.close();
        in.close();
    }

    private long lastProg = 0;

    private void maybeProgress(Job j) {
        long now = System.currentTimeMillis();
        // তাৎক্ষণিক গতি (গত ০.৪+ সেকেন্ডের ঘনফল)
        long g = j.got.get();
        if (j.lastT > 0 && now > j.lastT) {
            double inst = (g - j.lastGot) / 1048576.0
                    / ((now - j.lastT) / 1000.0);
            if (inst >= 0) {
                j.winSpeed = j.winSpeed > 0
                        ? (0.6 * j.winSpeed + 0.4 * inst) : inst;
            }
        }
        j.lastGot = g;
        j.lastT = now;
        if (now - lastProg > 400) {
            lastProg = now;
            notifyProgress(j);
        }
    }

    // ---------- সেভ + নোটিফিকেশন ----------

    private Uri savePublic(Job j, File src, String name, String mime) throws Exception {
        Uri out;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/WTube");
            cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new Exception("storage denied");
            }
            OutputStream os = getContentResolver().openOutputStream(uri);
            copy(src, os);
            cv.clear();
            cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            out = uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "WTube");
            dir.mkdirs();
            File dst = new File(dir, name);
            copy(src, new FileOutputStream(dst));
            out = Uri.fromFile(dst);
        }
        // ফাইল-নাম যা বহন করতে পারে না — ভিডিও-আইডি, চ্যানেল, কোয়ালিটি
        try {
            if (j != null) {
                DlMeta.put(this, name, j.vid, j.title, j.ch, j.label);
            }
        } catch (Throwable t) {
        }
        return out;
    }

    private void copy(File src, OutputStream os) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        in.close();
        os.close();
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private String jobText(Job j) {
        String lb = j.label != null && j.label.length() > 0 ? " [" + j.label + "]" : "";
        String base = (j.title != null ? j.title : "Download") + lb;
        if (j.phase == 1) {
            int pct = j.muxTotal > 0 ? (int) Math.min(100,
                    95 + 5 * j.muxWritten / j.muxTotal) : 95;
            return base + " - muxing " + pct + "%";
        }
        if (j.phase == 2) {
            return base + " - converting";
        }
        long got = j.got.get();
        double sec = Math.max(0.001, (System.currentTimeMillis() - j.t0) / 1000.0);
        double mbs = j.winSpeed > 0 ? j.winSpeed : got / 1048576.0 / sec;
        if (j.total > 0) {
            int pct = (int) Math.min(95, 95 * got / j.total);
            String eta = Ui.etaStr(got, j.total, mbs);
            return base + " - " + pct + "% - "
                    + String.format(java.util.Locale.US, "%.1f MB/s", mbs)
                    + (eta != null ? " - " + eta : "");
        }
        return base + " - " + String.format(java.util.Locale.US,
                "%.1f MB - %.1f MB/s", got / 1048576.0, mbs);
    }

    private void notifyProgress(Job j) {
        try {
            Notification.Builder b = nb(NID_BASE + j.slot);
            b.setContentTitle("Wtube Download")
                    .setContentText(jobText(j))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            if (j.total > 0 && j.phase == 0) {
                int pct = (int) Math.min(95, 95 * j.got.get() / j.total);
                b.setProgress(100, pct, false);
            } else {
                b.setProgress(100, 0, true);
            }
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                    pi(ACT_CANCEL, 10 + j.slot, j.slot)).build());
            nm().notify(NID_BASE + j.slot, b.build());
        } catch (Throwable t) {
        }
    }

    private void notifyState(Job j, String text) {
        try {
            Notification.Builder b = nb(NID_BASE + j.slot);
            b.setContentTitle("Wtube Download")
                    .setContentText((j.title != null ? j.title : "") + " - " + text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true);
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                    pi(ACT_CANCEL, 10 + j.slot, j.slot)).build());
            nm().notify(NID_BASE + j.slot, b.build());
        } catch (Throwable t) {
        }
    }

    private void notifyDone(Job j, String text, boolean failed) {
        final Snap s = snap(j, false);
        s.done = true;
        s.failed = failed;
        s.msg = text;
        main.post(new Runnable() {
            @Override
            public void run() {
                synchronized (sDone) {
                    sDone.add(0, s);
                    while (sDone.size() > 20) {
                        sDone.remove(sDone.size() - 1);
                    }
                }
            }
        });
        try {
            nm().cancel(NID_BASE + j.slot);
            Notification.Builder b = nb(NID_BASE + j.slot);
            b.setContentTitle("Wtube Download")
                    .setContentText(text)
                    .setAutoCancel(true);
            nm().notify(NID_BASE + j.slot, b.build());
        } catch (Throwable t) {
        }
        try {
            if (!getSharedPreferences("mtw", 0).getBoolean("incognito", false)) {
                histAdd(getApplicationContext(), j, text, failed);
            }
        } catch (Throwable t3) {
        }
    }

    /** ডাউনলোড-ইতিহাস লগ (সাম্প্রতিক ১০০টি, প্রিফে স্থায়ী)। */
    private boolean isMeteredNet() {
        try {
            Object cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm instanceof android.net.ConnectivityManager) {
                return ((android.net.ConnectivityManager) cm).isActiveNetworkMetered();
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private static void histAdd(android.content.Context c, Job j, String text, boolean failed) {
        try {
            String status;
            if (failed) {
                status = text != null && text.startsWith("Failed") ? "Failed" : "Cancelled";
            } else {
                status = "Saved";
            }
            android.content.SharedPreferences p = c.getSharedPreferences("mtw", 0);
            org.json.JSONArray a;
            try {
                a = new org.json.JSONArray(p.getString("dhist", "[]"));
            } catch (Throwable t) {
                a = new org.json.JSONArray();
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("t", j.title == null ? "" : j.title);
            o.put("l", j.label == null ? "" : j.label);
            o.put("s", status);
            o.put("d", System.currentTimeMillis());
            a.put(o);
            while (a.length() > 100) {
                a.remove(0);
            }
            p.edit().putString("dhist", a.toString()).apply();
        } catch (Throwable t) {
        }
    }

    /** হিস্ট্রি-তালিকা (নতুন→পুরনো): Object[]{title, label, status, time} */
    public static java.util.ArrayList<Object[]> histList(android.content.Context c) {
        java.util.ArrayList<Object[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(
                    c.getSharedPreferences("mtw", 0).getString("dhist", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                out.add(new Object[]{o.optString("t", ""), o.optString("l", ""),
                        o.optString("s", ""), Long.valueOf(o.optLong("d", 0))});
            }
        } catch (Throwable t) {
        }
        return out;
    }

    private Notification.Builder nb(int id) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        b.setSmallIcon(R.drawable.ic_stat);
        return b;
    }

    private void updateSummary() {
        try {
            int q;
            synchronized (queue) {
                q = queue.size();
            }
            String text = active.size() + " downloading"
                    + (q > 0 ? " - " + q + " queued" : "");
            Notification.Builder b = nb(NID_SUMMARY);
            b.setContentTitle("Wtube Downloads")
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            nm().notify(NID_SUMMARY, b.build());
        } catch (Throwable t) {
        }
    }

    private void startFgSummary() {
        try {
            Notification.Builder b = nb(NID_SUMMARY);
            b.setContentTitle("Wtube Downloads")
                    .setContentText("Starting...")
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID_SUMMARY, b.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NID_SUMMARY, b.build());
            }
        } catch (Throwable t) {
        }
    }

    private PendingIntent pi(String action, int code, int slot) {
        Intent i = new Intent(this, DownloadService.class).setAction(action)
                .putExtra(EXTRA_JID, slot);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private long urlSize(String u) {
        // HEAD বাদ (googlevideo-তে ধীর/অসমর্থিত) — শুধু Range-0-0, দ্রুত
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", UA);
            String cr = c.getHeaderField("Content-Range");
            c.disconnect();
            if (cr != null && cr.contains("/")) {
                return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            }
        } catch (Throwable t) {
        }
        return -1;
    }

    private void deleteDir(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    f.delete();
                }
            }
            dir.delete();
        } catch (Throwable t) {
        }
    }

    private String safeName(String title, String label) {
        String t = title == null ? "" : title;
        t = t.replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        if (t.length() == 0) {
            t = "video";
        }
        if (t.length() > 60) {
            t = t.substring(0, 60);
        }
        if (label != null && label.length() > 0 && !"video".equals(label)) {
            t = t + " " + label;
        }
        return t;
    }
}
