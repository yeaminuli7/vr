package com.mytube.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

/**
 * Foreground service that owns the ExoPlayer engine.
 * Keeps playback alive in background and with screen off.
 * Same-process access: static sSelf (set between onCreate and onDestroy).
 */
public class PlayerService extends Service {

    public static final String ACT_PLAY = "com.mytube.web.player.PLAY";
    public static final String ACT_PAUSE = "com.mytube.web.player.PAUSE";
    public static final String ACT_RESUME = "com.mytube.web.player.RESUME";
    public static final String ACT_TOGGLE = "com.mytube.web.player.TOGGLE";
    public static final String ACT_STOP = "com.mytube.web.player.STOP";
    public static final String ACT_NEXT = "com.mytube.web.player.NEXT";
    public static final String ACT_PREV = "com.mytube.web.player.PREV";
    /** Next-কিউ: বর্তমান ভিডিওর রিলেটেড তালিকা (অ্যাক্টিভিটি পূরণ করে)। */
    public static final java.util.ArrayList<String> sNextIds = new java.util.ArrayList<>();
    public static final java.util.ArrayList<String> sNextTitles = new java.util.ArrayList<>();
    private static final java.util.ArrayList<String> sHistIds = new java.util.ArrayList<>();
    private static final java.util.ArrayList<String> sHistTitles = new java.util.ArrayList<>();

    public static void setNextQueue(java.util.List<Resolver.Rel> list) {
        synchronized (sNextIds) {
            sNextIds.clear();
            sNextTitles.clear();
            if (list != null) {
                for (Resolver.Rel r : list) {
                    sNextIds.add(r.id);
                    sNextTitles.add(r.title);
                }
            }
        }
    }

    /** প্লে-হিস্ট্রির স্ট্যাক — Prev এটা থেকে আসে। */
    public static void noteHistory(String id, String t) {
        if (id == null || id.length() != 11) {
            return;
        }
        synchronized (sHistIds) {
            if (!sHistIds.isEmpty() && sHistIds.get(sHistIds.size() - 1).equals(id)) {
                return;
            }
            sHistIds.add(id);
            sHistTitles.add(t == null ? "" : t);
            if (sHistIds.size() > 30) {
                sHistIds.remove(0);
                sHistTitles.remove(0);
            }
        }
    }
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_V = "v";
    public static final String EXTRA_VID = "vid";
    public static final String EXTRA_A = "a";
    public static final String EXTRA_YT = "yt";
    public static final String EXTRA_VARS = "vars"; // "height|url" অ্যারে
    public static final String EXTRA_CAPS = "caps"; // "lang|label|url" স্ট্রিং-লিস্ট

    /** Live reference while the service is alive (same process only). */
    public static volatile PlayerService sSelf;

    /**
     * ইউজার-নির্ধারিত গেইন (০..১)। ভলিউম-জেসচার সিস্টম-স্ট্রিমের মাঝের
     * ধাপগুলো মসৃণ করতে এটি ব্যবহার করে; ডাকিং (0.2x) এর ওপর ভিত্তি করে।
     */
    private static volatile float sUserGain = 1f;
    private static volatile boolean sDucked = false;

    /** গেইন বসানো; চলমান সার্ভিস থাকলে সাথে সাথে প্রয়োগ হয়। */
    public static void setUserGain(float g) {
        if (Float.isNaN(g)) {
            return;
        }
        sUserGain = Math.max(0f, Math.min(1f, g));
        applyGain();
    }

    public static float userGain() {
        return sUserGain;
    }

    /** সার্ভিসের ExoPlayer-এ গেইন প্রয়োগ (ডাকিং হলে ২০%)। */
    static void applyGain() {
        PlayerService s = sSelf;
        if (s == null || s.player == null) {
            return;
        }
        try {
            s.player.setVolume(sDucked ? sUserGain * 0.2f : sUserGain);
        } catch (Throwable t) {
        }
    }

    private static final int NID = 21;
    private static final String CH = "player";

    private ExoPlayer player;
    private DefaultMediaSourceFactory msf;
    private MediaSession session;
    private AudioManager am;
    private Object focusReq; // AudioFocusRequest on API 26+
    private boolean focusHeld = false;
    private boolean pausedByFocus = false;
    private String title = "Media";
    private String url = "";
    private String lastError = null;
    // ইউটিউব-মোড স্টেট — স্ক্রিন মরলেও সার্ভিসে বাঁচে (নোটিফ-ফেরা/Open-পথে দরকার)
    private final android.os.Handler h =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean ytMode = false;
    private boolean audioOnly = false;
    private boolean sleepSet = false;
    private String audioUrl = null;
    private final java.util.ArrayList<Resolver.Var> varList =
            new java.util.ArrayList<Resolver.Var>();
    private boolean noisyRegistered = false;

    private final Player.Listener plistener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            updateAll();
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            updateAll();
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
            pausedByFocus = false;
            abandonFocus();
            unregisterNoisy();
            lastError = e.getMessage();
            updateAll();
        }
    };

    public String lastError() {
        return lastError;
    }

    private final AudioManager.OnAudioFocusChangeListener focListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int f) {
                    if (player == null) {
                        return;
                    }
                    if (f == AudioManager.AUDIOFOCUS_LOSS) {
                        pausedByFocus = false;
                        pause();
                    } else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        if (player.getPlayWhenReady()) {
                            pausedByFocus = true;
                        }
                        pause();
                    } else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        sDucked = true;
                        applyGain();
                    } else if (f == AudioManager.AUDIOFOCUS_GAIN) {
                        sDucked = false;
                        applyGain();
                        if (pausedByFocus) {
                            pausedByFocus = false;
                            play();
                        }
                    }
                }
            };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sSelf = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "Player",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);
        msf = new DefaultMediaSourceFactory(ds);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(msf)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 50000, 800, 1500)
                        .build())
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.addListener(plistener);
        try {
            session = new MediaSession(this, "webtube_player");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    play();
                }

                @Override
                public void onPause() {
                    pause();
                }

                @Override
                public void onStop() {
                    stopAll();
                }
            });
            session.setActive(true);
        } catch (Throwable t) {
            session = null;
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String act = i != null && i.getAction() != null ? i.getAction() : ACT_PLAY;
        if (ACT_STOP.equals(act)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (ACT_NEXT.equals(act) || ACT_PREV.equals(act)) {
            final boolean nx = ACT_NEXT.equals(act);
            h.post(new Runnable() {
                @Override
                public void run() {
                    playFromQueue(nx);
                }
            });
            return START_STICKY;
        }
        if (ACT_PLAY.equals(act)) {
            String u = i.getStringExtra(EXTRA_URL);
            String v = i.getStringExtra(EXTRA_V);
            String a = i.getStringExtra(EXTRA_A);
            String t = i.getStringExtra(EXTRA_TITLE);
            boolean yt = i.getBooleanExtra(EXTRA_YT, false);
            String[] vars = i.getStringArrayExtra(EXTRA_VARS);
            if (t != null && t.length() > 0) {
                title = t;
            }
            String xvid = i.getStringExtra(EXTRA_VID);
            curVid = xvid != null ? xvid : "";
            startFg();
            if (v != null && v.length() > 0 && a != null && a.length() > 0) {
                url = v;
                ytMode = yt;
                audioUrl = a;
                varList.clear();
                if (vars != null) {
                    for (int k = 0; k < vars.length; k++) {
                        int sp = vars[k].indexOf('|');
                        if (sp > 0) {
                            try {
                                varList.add(new Resolver.Var(
                                        Integer.parseInt(vars[k].substring(0, sp)),
                                        vars[k].substring(sp + 1)));
                            } catch (Throwable t2) {
                            }
                        }
                    }
                }
                setCapsList(i.getStringArrayListExtra(EXTRA_CAPS));
                loadMerge(v, a);
            } else if (u != null && u.length() > 0 && !u.equals(url)) {
                url = u;
                ytMode = i.getBooleanExtra(EXTRA_YT, false);
                audioUrl = null;
                varList.clear();
                load(u);
            } else {
                play();
            }
            return START_STICKY;
        }
        if (ACT_PAUSE.equals(act)) {
            pause();
        } else if (ACT_RESUME.equals(act)) {
            play();
        } else if (ACT_TOGGLE.equals(act)) {
            if (player != null && player.getPlayWhenReady()) {
                pause();
            } else {
                play();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    public Player player() {
        return player;
    }

    public String currentTitle() {
        return title;
    }

    public String currentUrl() {
        return url;
    }

    public boolean isYoutubeMode() {
        return ytMode;
    }

    public boolean isAudioMode() {
        return audioOnly;
    }

    public String currentAudio() {
        return audioUrl;
    }

    public java.util.ArrayList<Resolver.Var> currentVarList() {
        return varList;
    }

    public java.util.ArrayList<Resolver.Cap> currentCaps() {
        return capList;
    }

    private java.util.ArrayList<Resolver.Cap> capList =
            new java.util.ArrayList<Resolver.Cap>();

    /** "lang|label|url" লিস্ট → capList। */
    private void setCapsList(java.util.ArrayList<String> arr) {
        capList.clear();
        if (arr == null) {
            return;
        }
        for (String it : arr) {
            if (it == null) {
                continue;
            }
            String[] p = it.split("\\|", 3);
            if (p.length == 3 && p[2].length() > 0) {
                capList.add(new Resolver.Cap(p[0], p[1], p[2]));
            }
        }
    }

    public boolean hasItem() {
        return player != null && url.length() > 0;
    }

    private void load(String u) {
        if (player == null) {
            return;
        }
        player.setMediaItem(MediaItem.fromUri(u));
        player.prepare();
        play();
    }

    /** ভিডিও+অডিও আলাদা URL একসাথে চালায় (MergingMediaSource)। */
    private void loadMerge(String v, String a) {
        if (player == null || msf == null) {
            return;
        }
        MediaSource vs = msf.createMediaSource(MediaItem.fromUri(v));
        MediaSource as = msf.createMediaSource(MediaItem.fromUri(a));
        player.setMediaSource(new MergingMediaSource(vs, as));
        player.prepare();
        play();
    }

    /** কোয়ালিটি বদল: নতুন ভ্যারিয়েন্টে সুইচ, অবস্থান অটুট। */
    public void switchTo(String v, String a, long posMs) {
        if (player == null) {
            return;
        }
        audioOnly = false;
        loadMerge(v, a);
        if (posMs > 0) {
            player.seekTo(posMs);
        }
        play();
    }

    /** অডিও-শুধু মোড: ভিডিও-ট্র্যাক সম্পূর্ণ বন্ধ (ডেটা/ব্যাটারি সাশ্রয়), অবস্থান অটুট। */
    public void switchToAudio(String a, long posMs) {
        if (player == null || msf == null) {
            return;
        }
        audioOnly = true;
        player.setMediaSource(msf.createMediaSource(MediaItem.fromUri(a)));
        player.prepare();
        if (posMs > 0) {
            player.seekTo(posMs);
        }
        play();
    }

    /** স্লিপ-টাইমার: মিলিসেকেন্ড পরে পজ; 0 বা ঋণাত্মক = বাতিল। */
    public void setSleep(long ms) {
        h.removeCallbacks(sleepRun);
        if (ms > 0) {
            sleepSet = true;
            h.postDelayed(sleepRun, ms);
        } else {
            sleepSet = false;
        }
        updateAll();
    }

    public boolean isSleepSet() {
        return sleepSet;
    }

    private final Runnable sleepRun = new Runnable() {
        @Override
        public void run() {
            sleepSet = false;
            pause();
            updateAll();
        }
    };

    private void play() {
        if (player == null || !hasItem()) {
            return;
        }
        requestFocus();
        registerNoisy();
        player.play();
        updateAll();
    }

    private void pause() {
        if (player == null) {
            return;
        }
        player.pause();
        abandonFocus();
        unregisterNoisy();
        updateAll();
    }

    public void toggle() {
        if (player == null) {
            return;
        }
        if (player.getPlayWhenReady()) {
            pause();
        } else {
            play();
        }
    }

    /** নোটিফিকেশনের Next/Prev: কিউ/হিস্ট্রি থেকে ভিডিও বেছে রিজল্ভ করে চালায়। */
    private void playFromQueue(boolean next) {
        final String id;
        final String ti;
        if (next) {
            synchronized (sNextIds) {
                if (sNextIds.isEmpty()) {
                    return;
                }
                id = sNextIds.remove(0);
                ti = sNextTitles.remove(0);
            }
        } else {
            String lid = null;
            String lt = "";
            synchronized (sHistIds) {
                while (!sHistIds.isEmpty()) {
                    String c = sHistIds.remove(sHistIds.size() - 1);
                    String ct = sHistTitles.remove(sHistTitles.size() - 1);
                    if (!c.equals(curVid)) {
                        lid = c;
                        lt = ct;
                        break;
                    }
                }
            }
            if (lid == null) {
                return;
            }
            id = lid;
            ti = lt;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Resolver.Res r = Resolver.resolve(id);
                if (r.video == null || r.audio == null || r.vars == null || r.vars.isEmpty()) {
                    StreamStore.note("queue resolve fail " + id);
                    return;
                }
                // ডিফল্ট 360p-নীতি (নোটিফিকেশনের Next-ও একই ডিফল্ট মানবে)
                Resolver.Var pick = r.vars.get(0);
                int bd = Integer.MAX_VALUE;
                for (Resolver.Var v : r.vars) {
                    int d = Math.abs(v.h - 360);
                    if (d < bd || (d == bd && v.h < pick.h)) {
                        bd = d;
                        pick = v;
                    }
                }
                final Resolver.Var fp = pick;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        noteHistory(id, ti);
                        curVid = id;
                        title = ti;
                        audioUrl = r.audio;
                        capList.clear();
                        if (r.caps != null) {
                            capList.addAll(r.caps);
                        }
                        ytMode = true;
                        varList.clear();
                        varList.addAll(r.vars);
                        loadMerge(fp.url, r.audio);
                        updateAll();
                        StreamStore.note("queue play " + (fp.h) + "p " + id);
                    }
                });
            }
        }).start();
    }

    /** মেটা এলে আসল টাইটেল বসে (নোটিফিকেশনসহ)। */
    public void setTitle(String t) {
        if (t != null && t.length() > 0 && !t.equals(title)) {
            title = t;
            updateAll();
        }
    }

    public String curVid = "";

    public void stopAll() {
        url = "";
        curVid = "";
        ytMode = false;
        audioOnly = false;
        h.removeCallbacks(sleepRun);
        sleepSet = false;
        audioUrl = null;
        varList.clear();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        abandonFocus();
        unregisterNoisy();
        if (session != null) {
            try {
                session.setActive(false);
            } catch (Throwable t) {
            }
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        sSelf = null;
        abandonFocus();
        unregisterNoisy();
        if (session != null) {
            try {
                session.release();
            } catch (Throwable t) {
            }
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void startFg() {
        Notification n = buildNotif();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
        } catch (Throwable t) {
            stopSelf();
        }
    }

    private void requestFocus() {
        if (focusHeld || am == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build();
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(aa)
                            .setOnAudioFocusChangeListener(focListener, new android.os.Handler(getMainLooper()))
                            .build();
                }
                am.requestAudioFocus((AudioFocusRequest) focusReq);
            } else {
                am.requestAudioFocus(focListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            focusHeld = true;
        } catch (Throwable t) {
        }
    }

    private void abandonFocus() {
        focusHeld = false;
        pausedByFocus = false;
        if (am == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) {
                am.abandonAudioFocusRequest((AudioFocusRequest) focusReq);
            } else {
                am.abandonAudioFocus(focListener);
            }
        } catch (Throwable t) {
        }
    }

    private void registerNoisy() {
        if (!noisyRegistered) {
            try {
                registerReceiver(noisyReceiver,
                        new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                noisyRegistered = true;
            } catch (Throwable t) {
            }
        }
    }

    private void unregisterNoisy() {
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Throwable t) {
            }
            noisyRegistered = false;
        }
    }

    private void updateAll() {
        updateSession();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NID, buildNotif());
            } catch (Throwable t) {
            }
        }
    }

    private void updateSession() {
        if (session == null || player == null) {
            return;
        }
        try {
            boolean playing = player.getPlayWhenReady()
                    && player.getPlaybackState() == Player.STATE_READY;
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
            int st = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            if (player.getPlayerError() != null) {
                st = PlaybackState.STATE_ERROR;
            }
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(acts)
                    .setState(st, player.getCurrentPosition(), player.getPlaybackParameters().speed)
                    .build());
        } catch (Throwable t) {
        }
    }

    private Notification buildNotif() {
        boolean playing = player != null && player.getPlayWhenReady();
        boolean err = player != null && player.getPlayerError() != null;
        String text = err ? "Playback error"
                : title + (audioOnly ? " (audio)" : "");
        Intent open = new Intent(this, PlayerActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int playIco = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playTxt = playing ? "Pause" : "Play";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Wtube Player")
                .setContentText(text)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(po)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "Prev",
                        pi(ACT_PREV, 4)).build())
                .addAction(new Notification.Action.Builder(playIco, playTxt,
                        pi(ACT_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "Next",
                        pi(ACT_NEXT, 5)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                        pi(ACT_STOP, 3)).build());
        if (Build.VERSION.SDK_INT >= 21 && session != null) {
            try {
                b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
            } catch (Throwable t) {
            }
        }
        return b.build();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
