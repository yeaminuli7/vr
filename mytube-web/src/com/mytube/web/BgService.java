package com.mytube.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;

public class BgService extends Service {

    public static final String ACT_START = "com.mytube.web.START";
    public static final String ACT_RESUME = "com.mytube.web.RESUME";
    public static final String ACT_PAUSE = "com.mytube.web.PAUSE";
    public static final String ACT_STOP = "com.mytube.web.STOP";
    public static final String ACT_TOGGLE = "com.mytube.web.TOGGLE";
    public static final String ACT_AUDIO = "com.mytube.web.AUDIO";
    public static final String ACT_AUDIO_STOP = "com.mytube.web.AUDIO_STOP";
    private static final String CH = "playback";

    public static volatile boolean sRunning = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private boolean mpReady = false;
    private String mpTitle = "";
    private String mpUrl = "";
    private boolean retried = false;
    private android.os.PowerManager.WakeLock wl;
    private final Runnable stopLater = new Runnable() {
        public void run() {
            stopAll();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        sRunning = true;
        String act = i != null && i.getAction() != null ? i.getAction() : ACT_START;
        // Android 8+: প্রতিটি startForegroundService()-কলের পরে startForeground() ডাকা
        // বাধ্যতামূলক — সার্ভিস আগে থেকে চালু/ফোরগ্রাউন্ড থাকলেও। Pause/Toggle/Stop-শাখায়
        // এমনটা হতো না → ৫ সেকেন্ড পর ForegroundServiceDidNotStartInTimeException (v1.14-ক্র্যাশ)।
        // তাই শাখা-নির্বিশেষে সবার আগে ডাকা হচ্ছে; থামানোর-শাখায় এর পরেই stopForeground
        // চলে যায় বলে নোটিফিকেশন স্থায়ী হয় না।
        try {
            fg(1, build("Playing in background"));
        } catch (Throwable tf) {
        }
        try {
            if (ACT_STOP.equals(act)) {
                h.removeCallbacks(stopLater);
                stopAll();
                return START_NOT_STICKY;
            }
            if (ACT_AUDIO_STOP.equals(act)) {
                h.removeCallbacks(stopLater);
                stopPlayer();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACT_AUDIO.equals(act)) {
                h.removeCallbacks(stopLater);
                fg(1, build("Starting audio\u2026"));
                final String url = i.getStringExtra("url");
                final long pos = i.getLongExtra("pos", 0L);
                String t = i.getStringExtra("title");
                if (t != null && t.length() > 0) {
                    mpTitle = t;
                }
                if (url != null) {
                    h.post(new Runnable() {
                        public void run() {
                            startPlayer(url, pos);
                        }
                    });
                }
                return START_STICKY;
            }
            h.removeCallbacks(stopLater);
            if (ACT_PAUSE.equals(act)) {
                nm().notify(1, build("Paused"));
                h.postDelayed(stopLater, 120000);
                return START_STICKY;
            }
            if (ACT_TOGGLE.equals(act)) {
                if (mp != null) {
                    if (mpReady) {
                        if (mp.isPlaying()) {
                            mp.pause();
                            nm().notify(1, build("Paused \u2014 tap to play"));
                        } else {
                            mp.start();
                            nm().notify(1, build("\u25B6 " + label()));
                        }
                    }
                } else {
                    jsToggle();
                }
            } else {
                fg(1, build("Playing in background"));
            }
        } catch (Throwable t) {
            try {
                stopSelf();
            } catch (Throwable t2) {
            }
        }
        return START_STICKY;
    }

    private String label() {
        return mpTitle != null && mpTitle.length() > 0 ? mpTitle : "Audio";
    }

    private void startPlayer(String url, long pos) {
        stopPlayer();
        mpUrl = url;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "webtube:bg");
            wl.setReferenceCounted(false);
            wl.acquire(3600000L);
        } catch (Throwable t) {
        }
        try {
            mp = new MediaPlayer();
            try {
                mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            } catch (Throwable t) {
            }
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            } catch (Throwable t) {
            }
            try {
                java.util.Map<String, String> h = new java.util.HashMap<String, String>();
                if (StreamStore.sUA != null && StreamStore.sUA.length() > 0) {
                    h.put("User-Agent", StreamStore.sUA);
                }
                h.put("Referer", "https://m.youtube.com/");
                mp.setDataSource(getApplicationContext(), android.net.Uri.parse(url), h);
            } catch (Throwable tds) {
                mp.setDataSource(url);
            }
            StreamStore.note("mp start itag-url len=" + url.length());
            final long fpos = pos;
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    mpReady = true;
                    StreamStore.note("mp prepared, playing");
                    try {
                        if (fpos > 2000) {
                            p.seekTo((int) Math.min(fpos, 2147483000L));
                        }
                        p.start();
                        nm().notify(1, build("\u25B6 " + label()));
                    } catch (Throwable t) {
                        stopAll();
                    }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) {
                    StreamStore.note("mp done");
                    stopAll();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int what, int extra) {
                    StreamStore.note("mp err " + what + "/" + extra);
                    try {
                        p.reset();
                    } catch (Throwable t) {
                    }
                    if (!retried) {
                        retried = true;
                        StreamStore.S alt = StreamStore.bestAudio();
                        if (alt != null && !alt.url.equals(mpUrl)) {
                            StreamStore.note("mp retry alt itag=" + alt.itag);
                            startPlayer(alt.url, 0L);
                            return true;
                        }
                    }
                    StreamStore.note("mp failed; web-continue");
                    stopPlayer();
                    jsToggle();
                    nm().notify(1, build("Playing in background"));
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            stopAll();
        }
    }

    private void stopPlayer() {
        try {
            if (mp != null) {
                mp.stop();
            }
        } catch (Throwable t) {
        }
        try {
            if (mp != null) {
                mp.release();
            }
        } catch (Throwable t) {
        }
        mp = null;
        mpReady = false;
        mpUrl = "";
        try {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Throwable t) {
        }
    }

    private void stopAll() {
        stopPlayer();
        try {
            stopForeground(true);
        } catch (Throwable t) {
        }
        try {
            stopSelf();
        } catch (Throwable t) {
        }
    }

    private void jsToggle() {
        final MainActivity a = MainActivity.ins;
        if (a == null) {
            return;
        }
        a.bgToggle();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        h.removeCallbacks(stopLater);
        stopPlayer();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent r) {
        stopAll();
        super.onTaskRemoved(r);
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    /** নিরাপদ startForeground — API 29+-এ স্পষ্ট mediaPlayback-টাইপ (Android 14/16-কঠোর)। */
    private void fg(int id, Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, n, android.content.pm.ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(id, n);
            }
        } catch (Throwable t) {
            StreamStore.note("fg err " + t);
        }
    }

    private Notification build(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Playback",
                    NotificationManager.IMPORTANCE_LOW);
            nm().createNotificationChannel(c);
        }
        PendingIntent tg = PendingIntent.getService(this, 2,
                new Intent(this, BgService.class).setAction(ACT_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent sp = PendingIntent.getService(this, 3,
                new Intent(this, BgService.class).setAction(ACT_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // নোটিফিকেশনে ট্যাপ = অ্যাপ খোলে (টগল নয়)
        PendingIntent op = PendingIntent.getActivity(this, 4,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("Wtube")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(op)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_play, "Play/Pause", tg).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", sp).build());
        return b.build();
    }
}
