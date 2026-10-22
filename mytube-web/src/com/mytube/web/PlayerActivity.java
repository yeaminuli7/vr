package com.mytube.web;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.app.PictureInPictureParams;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioManager;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Player screen: entry (link + demos) and playback (own engine, quality switch). */
public class PlayerActivity extends Activity {

    private static final String[][] DEMOS = {
            {"Big Buck Bunny", "HLS - multiple qualities",
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"},
            {"Tears of Steel", "HLS - multiple qualities",
                    "https://test-streams.mux.dev/tos_ismc/main.m3u8"},
            {"Sintel Trailer", "MP4 - direct link",
                    "https://media.w3.org/2010/05/sintel/trailer.mp4"},
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private float u; // density

    private boolean dark = true;
    private int accent = 0xFFE8212F;
    private int colBg;
    private int colPanel;
    private int colText;
    private int colDim;

    // entry views
    private LinearLayout entryRoot;

    // playback views
    private FrameLayout playRoot;
    private SurfaceView surface;
    private FrameLayout surfaceBox;
    private ProgressBar loading;
    private View topBar;
    private View bottomBar;
    private FrameLayout controls;
    private TextView vTitle;
    private TextView btnQuality;
    private IconButton btnSave;
    private TextView vTime;
    private TextView vDur;
    private LinearLayout centerRow;
    private SeekBar seek;
    private PowerIcon btnPlay;

    public static final String EXTRA_VIDEOID = "vid";
    /** Shorts-মোড ফ্ল্যাগ: ফুল-স্ক্রিন ভার্টিকাল প্লেয়ার। */
    public static final String EXTRA_SHORT = "short";

    private Player player; // service-owned, never released here
    private boolean listenerAdded = false;
    private String pendingUrl = "";
    private String pendingTitle = "";
    private boolean wantAttach = false;
    private int attachTries = 0;
    private int resolveGen = 0;
    private boolean isYoutube = false;
    private String videoId = "";
    private String ytAudio = null;
    private List<Resolver.Var> ytVars = null;
    private java.util.ArrayList<Resolver.Cap> ytCaps = null;
    // ===== Wtube 2.0 =====
    private static final class SbS {
        final long s;
        final long e;
        final String cat;
        SbS(long a, long b, String c) { s = a; e = b; cat = c; }
    }
    private static final class Cue {
        final long s;
        final long e;
        final String t;
        Cue(long a, long b, String c) { s = a; e = b; t = c; }
    }
    private final java.util.ArrayList<SbS> sbList = new java.util.ArrayList<SbS>();
    private final java.util.ArrayList<Cue> capCues = new java.util.ArrayList<Cue>();
    private final java.util.ArrayList<Resolver.Chap> chaps = new java.util.ArrayList<Resolver.Chap>();
    private boolean ccOn = false;
    private android.media.audiofx.Equalizer eq;
    private android.widget.TextView ccOverlay;
    private android.widget.TextView disLine;
    private boolean tickOn = false;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!tickOn) {
                return;
            }
            try {
                if (player != null && playRoot != null) {
                    long pos = player.getCurrentPosition();
                    if (getSharedPreferences("mtw", 0).getBoolean("sbOn", true)) {
                        for (int i = 0; i < sbList.size(); i++) {
                            SbS sg = sbList.get(i);
                            if (pos >= sg.s && pos < sg.e - 400) {
                                player.seekTo(sg.e);
                                showHud("Skipped " + sg.cat);
                                break;
                            }
                            if (pos < sg.s) {
                                break; // সাজানো তালিকা — এর পরেরটা এখনো দূরে
                            }
                        }
                    }
                    if (ccOverlay != null) {
                        if (ccOn && !capCues.isEmpty()) {
                            String txt = null;
                            for (int i = 0; i < capCues.size(); i++) {
                                Cue c = capCues.get(i);
                                if (pos >= c.s && pos <= c.e) {
                                    txt = c.t;
                                    break;
                                }
                                if (c.s > pos) {
                                    break;
                                }
                            }
                            ccOverlay.setText(txt == null ? "" : txt);
                            ccOverlay.setVisibility(
                                    txt == null ? View.GONE : View.VISIBLE);
                        } else if (ccOverlay.getVisibility() != View.GONE) {
                            ccOverlay.setVisibility(View.GONE);
                        }
                    }
                }
            } catch (Throwable t) {
            }
            if (tickOn) {
                h.postDelayed(this, 500);
            }
        }
    };
    private String lastChName = "";
    private TextView statusText;
    private boolean dragging = false;
    private boolean controlsVisible = true;
    private int chosenHeight = 0; // 0 = Auto
    private String errPending = null; // ব্যাকগ্রাউন্ডে এরর জমা থাকে, ফেরার পর দেখাই
    private boolean resumed = false;
    private Resolver.Res failPending;
    // --- প্লেয়ার-প্যাকেজ (v0.24) ---
    private GestureDetector gd;
    private TextView gestureHud;
    private TextView btnSpeed;
    private IconButton btnLoop;
    private float curSpeed = 1f;
    /**
     * চেপে-ধরে-রাখা (press & hold) চলছে কি না। চলাকালীন ভিডিও 2x গতিতে
     * চলে, আর ছেড়ে দিলে {@link #curSpeed}-এ ফেরত যায়।
     */
    private boolean holdBoost = false;
    /** HUD স্থায়ীভাবে দেখানোর জন্য (hold boost চলাকালীন)। */
    private boolean hudPinned = false;
    /**
     * এই স্পর্শে একাধিক আঙুল ছিল কি না। পিঞ্চের সময় আমরা gd-কে ইভেন্ট
     * দিই না, তাই gd-এর নিজের long-press টাইমার বাতিল হয় না — নিজেই
     * মনে রাখি, নাহলে দুই আঙুল স্থির রেখে ৫০০ms থাকলেই 2x হয়ে যেত।
     */
    private boolean multiTouch = false;
    /**
     * জুম করা থাকলে এক-আঙুল টানার অক্ষ (০ = স্থির হয়নি, ১ = উপরে-নিচে →
     * ভলিউম/ব্রাইটনেস, ২ = বাঁয়ে-ডানে → ভিডিও সরানো)। একবার ঠিক হলে
     * পুরো জেসচার জুড়ে আর বদলায় না — নাহলে মাঝপথে ভিডিও সরে যেত।
     */
    private int zoomAxis = 0;
    /** দুই-আঙুল টানার শেষ বিন্দু (জুম+সরানো একসাথে)। */
    private float lastFocusX = 0f, lastFocusY = 0f;
    /** এই পিঞ্চ-জেসচারে দুই-আঙুল টেনে জমানো সরানোর পরিমাণ। */
    private float focusPanX = 0f, focusPanY = 0f;
    private boolean loopOn = false;
    private final java.util.List<Resolver.Rel> relList =
            new java.util.ArrayList<>();
    private boolean fillMode = false;
    private long pendingSeek = 0;
    private boolean seekApplied = false;
    private int posTick = 0;
    private AudioManager am;
    private float downX, downY;
    private boolean verticalGesture = false;
    // --- ভিডিও জুম (পিঞ্চ) + প্যান ---
    private ScaleGestureDetector sgd;
    private float zoom = 1f;          // 1.0 = স্বাভাবিক, সর্বোচ্চ 5.0
    private float panX = 0f, panY = 0f;
    private int baseW = 0, baseH = 0; // fitSurface() যে সাইজ ঠিক করেছে
    private float zoomStartX = 0f, zoomStartY = 0f;
    private static final float ZOOM_MIN = 1f;
    private static final float ZOOM_MAX = 5f;
    // --- মসৃণ ভলিউম/ব্রাইটনেস: ভগ্নাংশ জমা রাখা হয়, প্রতি ইভেন্টে ফেলা যায় না ---
    private float volAcc = -1f;      // 0 .. max (float), -1 = শুরু হয়নি
    private int volStepWritten = -1; // সর্বশেষ সিস্টমে লেখা পূর্ণসংখ্যা ধাপ
    private float brightAcc = -1f;
    // --- অডিও-মোড (v0.25) ---
    private IconButton btnAudio;
    private IconButton btnFull;
    // --- ওয়াচ-পেজ (v0.27): উপরে ভিডিও + নিচে রিলেটেড তালিকা ---
    private LinearLayout watchRoot;
    private FrameLayout videoBox;
    private android.widget.ScrollView belowScroll;
    private LinearLayout relBox;
    private TextView belowTitle;
    private TextView belowMeta;
    private TextView likeCnt;
    private TextView descView;
    private boolean descOpen = false;
    private android.widget.ImageView avatarImg;
    private TextView chName;
    private TextView chSubs;
    private LinearLayout cmtList;
    private boolean cmtOpen = false;
    private String lastChUrl = "";
    private int relGen = 0;
    private LinearLayout actionRow;
    private ViewGroup topSlot;
    private ViewGroup stripSlot;
    private boolean vertVideo = false;
    private boolean shortsLock = false;
    private IconButton btnTimerTop;
    private LinearLayout cmtBox;
    private boolean resolveBusy = false;
    private IconButton btnWl;
    private LinearLayout audioView;
    private TextView audioTitle;
    private PlayIcon audioPlay;
    private boolean audioOnly = false;
    private String lastVideoUrl = null; // অডিও-মোডে প্রবেশের আগের ভিডিও-URL
    private int videoW = 0;
    private int videoH = 0;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshHud();
            if (!resolveBusy && isYoutube && player != null) {
                try {
                    PlayerService ps = PlayerService.sSelf;
                    if (ps != null && ps.hasItem() && ps.player() != null
                            && ps.curVid != null && ps.curVid.length() == 11
                            && !ps.curVid.equals(videoId)) {
                        syncFromService(ps); // নোটিফিকেশনের Next/Prev বদলেছে
                        return;
                    }
                } catch (Throwable t) {
                }
            }
            if (player != null) {
                if (isYoutube && videoId.length() == 11 && (++posTick % 4) == 0
                        && player.isPlaying() && player.getDuration() > 0
                        && player.getCurrentPosition() > 5000
                        && player.getCurrentPosition() < player.getDuration() - 10000) {
                    try {
                        getSharedPreferences("mtw", 0).edit()
                                .putLong("pos_" + videoId, player.getCurrentPosition())
                                .apply();
                    } catch (Throwable t) {
                    }
                }
                h.postDelayed(this, 500);
            }
        }
    };

    /** স্ক্রিন-সারফেসের জীবনচক্র: ধ্বংসে ভিডিও-আউটপুট আলাদা (অডিও চলতে থাকে),
     *  তৈরিতে আবার জুড়ে দিই। */
    private final SurfaceHolder.Callback sholder = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder h) {
            reattach();
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            fitSurface();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            if (player != null) {
                try {
                    player.setVideoSurface(null);
                } catch (Throwable t) {
                }
            }
        }
    };

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            showControls(false);
        }
    };

    private final Player.Listener plistener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY && !seekApplied && pendingSeek > 0
                    && player != null) {
                player.seekTo(pendingSeek);
                seekApplied = true;
                showHud("Resumed");
            }
            if (state == Player.STATE_ENDED && isYoutube && videoId.length() == 11) {
                try {
                    getSharedPreferences("mtw", 0).edit()
                            .remove("pos_" + videoId).apply();
                } catch (Throwable t) {
                }
                // অটোপ্লে-নেক্সট: শেষ হলে নিচের রিলেটেড-তালিকার পরেরটা চালু
                if (!loopOn && getSharedPreferences("mtw", 0)
                        .getBoolean("autoNext", true)) {
                    try {
                        final int g = relGen;
                        relBox.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (relGen == g) {
                                    autoNext();
                                }
                            }
                        }, 600);
                    } catch (Throwable t2) {
                    }
                }
            }
            refreshHud();
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            refreshHud();
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
            videoW = vs.width;
            videoH = vs.height;
            zoomResetQuiet();   // নতুন ভিডিও = জুম ১০০% থেকে শুরু
            fitSurface();
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            updateQualityLabel();
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
            String m = e.getMessage();
            if (m == null) {
                m = "Unknown error";
            }
            final String msg = m;
            h.post(new Runnable() {
                @Override
                public void run() {
                    showError(msg);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        NativeLoader.ensure(this);
        super.onCreate(b);
        // edge-to-edge নিজে হ্যান্ডল করি — সিস্টেম-অফসেট + আমাদের প্যাডিং দুটোই যেন না হয়
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
            }
        } catch (Throwable t) {
        }
        u = getResources().getDisplayMetrics().density;
        android.content.SharedPreferences p = getSharedPreferences("mtw", 0);
        dark = p.getBoolean("dark", true);
        accent = hsl(p.getInt("hueTab", 357));
        colBg = dark ? 0xFF0A0A0A : 0xFFF4F4F4;
        colPanel = dark ? 0xFF1C1C1C : 0xFFEAEAEA;
        colText = dark ? 0xFFF2F2F2 : 0xFF1A1A1A;
        colDim = dark ? 0xFF9A9A9A : 0xFF666666;

        Intent i = getIntent();
        routeIntent(i);
    }

    private void routeIntent(Intent i) {
        String vid = i != null ? i.getStringExtra(EXTRA_VIDEOID) : null;
        String url = i != null ? i.getStringExtra(PlayerService.EXTRA_URL) : null;
        String title = i != null ? i.getStringExtra(PlayerService.EXTRA_TITLE) : null;
        boolean sh = i != null && i.getBooleanExtra(EXTRA_SHORT, false);
        if (vid != null && vid.length() > 0) {
            PlayerService s = PlayerService.sSelf;
            if (s != null && s.player() != null && s.hasItem() && vid.equals(s.curVid)) {
                // মিনি-বার থেকে বড়-প্লেয়ারে — চলন্ত প্লেব্যাকেই অ্যাটাচ (রিজল্ভ নয়)
                isYoutube = true; // curVid-পথ = ইউটিউব-কন্টেন্টই (ytMode-নির্বিশেষে)
                videoId = vid;
                ytAudio = s.currentAudio();
                ytVars = s.currentVarList();
                ytCaps = s.currentCaps();
                chosenHeight = 0;
                pendingSeek = 0;
                seekApplied = true;
                audioOnly = false;
                lastVideoUrl = null;
                shortsLock = sh;
                vertVideo = sh;
                pendingTitle = s.currentTitle();
                pendingUrl = s.currentUrl();
                buildPlayback();
                setContentView(watchRoot);
                applyVideoLayout();
                showControls(true);
                attach(s);
                if (!sh) {
                    loadRelated(vid);
                }
                return;
            }
            startYoutube(vid, title != null ? title : "", sh);
        } else if (url != null && url.length() > 0) {
            startPlay(url, title != null ? title : url);
        } else {
            PlayerService s = PlayerService.sSelf;
            if (s != null && s.player() != null && s.hasItem()) {
                reopen(); // নোটিফিকেশন/আইকন দিয়ে ফেরা — সরাসরি ভিডিও-স্ক্রিন
            } else {
                showEntry();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        routeIntent(i);
    }

    // ---------- mode switching ----------

    private void showEntry() {
        listenerOff();
        player = null;
        pendingUrl = "";
        applyImmersive(true);
        buildEntry();
        setContentView(entryRoot);
    }

    private void startPlay(String url, String title) {
        isYoutube = false;
        resolveBusy = false;
        lastVideoUrl = null;
        shortsLock = false;
        vertVideo = false;
        pendingUrl = url;
        pendingTitle = title;
        wantAttach = true;
        buildPlayback();
        setContentView(watchRoot);
        applyVideoLayout();
        showControls(true);
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACT_PLAY)
                .putExtra(PlayerService.EXTRA_URL, url)
                .putExtra(PlayerService.EXTRA_TITLE, title);
        startService(i);
        h.postDelayed(attachRetry, 100);
    }

    private final Runnable attachRetry = new Runnable() {
        @Override
        public void run() {
            if (playRoot == null || !wantAttach) {
                return;
            }
            PlayerService s = PlayerService.sSelf;
            if (s == null || s.player() == null) {
                if (++attachTries > 60) { // ~৯ সেকেন্ড পর হাল ছাড়া
                    wantAttach = false;
                    showError("Player engine did not start");
                    return;
                }
                h.postDelayed(this, 150);
                return;
            }
            attachTries = 0;
            attach(s);
        }
    };

    /** নোটিফিকেশনের Next/Prev-এ সার্ভিস নতুন ভিডিও চালু করলে UI-সিঙ্ক। */
    /** ফিড-পাতার document.title ("YouTube") নয় — খালি রেখে দিই, মেটা এলে আসল টাইটেল বসবে। */
    private String cleanYtTitle(String t) {
        if (t == null) {
            return "";
        }
        t = t.trim();
        if (t.length() == 0 || "youtube".equalsIgnoreCase(t)
                || "youtube mobile".equalsIgnoreCase(t)) {
            return "";
        }
        return t;
    }

    private void syncFromService(PlayerService s) {
        isYoutube = true;
        videoId = s.curVid;
        pendingTitle = s.currentTitle();
        ytAudio = s.currentAudio();
        ytVars = s.currentVarList();
        ytCaps = s.currentCaps();
        chosenHeight = 0;
        pendingSeek = 0;
        seekApplied = true;
        audioOnly = false;
        shortsLock = false;
        vertVideo = false;
        lastVideoUrl = null;
        if (btnWl != null) {
            btnWl.setIconTint(Library.inWl(this, videoId) ? 0xFF34D399 : 0xFFF2F2F2);
        }
        buildPlayback();
        setContentView(watchRoot);
        applyVideoLayout();
        showControls(true);
        attach(s);
        loadRelated(videoId);
    }

    private void attach(PlayerService s) {
        wantAttach = false;
        attachTries = 0;
        resolveBusy = false;
        player = s.player();
        if (s.isYoutubeMode()) {
            isYoutube = true;
            ytAudio = s.currentAudio();
            ytVars = s.currentVarList();
            ytCaps = s.currentCaps();
        }
        applyAudioUi(s.isAudioMode());
        updateQualityLabel();
        reattach();
        listenerOn();
        refreshHud();
        h.removeCallbacks(poll);
        poll.run();
    }

    /** প্লেয়ারে ভিডিও-সারফেস জুড়ে দিই (সারফেস তৈরি থাকলে)। */
    private void reattach() {
        if (player != null && surface != null) {
            try {
                Surface sf = surface.getHolder().getSurface();
                if (sf != null && sf.isValid()) {
                    player.setVideoSurface(sf);
                }
            } catch (Throwable t) {
            }
        }
    }

    /** চলন্ত প্লে-স্টেটে ফিরে আসা: সার্ভিস থেকেই সব তথ্য নিয়ে ভিডিও-স্ক্রিন বানাই। */
    private void reopen() {
        PlayerService s = PlayerService.sSelf;
        if (s == null || s.player() == null || !s.hasItem()) {
            showEntry();
            return;
        }
        isYoutube = s.isYoutubeMode();
        ytAudio = s.currentAudio();
        ytVars = s.currentVarList();
        ytCaps = s.currentCaps();
        chosenHeight = 0;
        pendingTitle = s.currentTitle();
        pendingUrl = s.currentUrl();
        videoId = "";
        buildPlayback();
        setContentView(watchRoot);
        applyVideoLayout();
        showControls(true);
        attach(s);
    }

    /** ইউটিউব-মোড: ভিডিওআইডি থেকে স্ট্রিম রিজলভ করে নিজের ইঞ্জিনে চালায়। */
    private void startYoutube(String vid, String title) {
        startYoutube(vid, title, false);
    }

    private void startYoutube(String vid, String title, boolean shortVid) {
        if (!getSharedPreferences("mtw", 0).getBoolean("incognito", false)) {
            try {
                Library.histAdd(this, vid,
                        title != null && title.length() > 0 ? title : vid);
            } catch (Throwable t) {
            }
        }
        relList.clear(); // নতুন ভিডিওর তালিকা না আসা পর্যন্ত পুরনোটা থেকে ভুল-নেক্সট নয়
        isYoutube = true;
        shortsLock = shortVid;
        vertVideo = shortVid;
        resolveBusy = true; // রিজল্ভ শেষ না হওয়া পর্যন্ত সার্ভিস-সিঙ্ক বন্ধ
        title = cleanYtTitle(title);
        PlayerService.noteHistory(vid, title);
        videoId = vid;
        ytAudio = null;
        ytVars = null;
        chosenHeight = 0;
        try {
            pendingSeek = getSharedPreferences("mtw", 0).getLong("pos_" + vid, 0);
        } catch (Throwable t) {
            pendingSeek = 0;
        }
        if (pendingSeek < 5000) {
            pendingSeek = 0;
        }
        seekApplied = false;
        audioOnly = false;
        lastVideoUrl = null;
        cmtOpen = false;
        if (btnWl != null) {
            btnWl.setIconTint(Library.inWl(this, vid) ? 0xFF34D399 : 0xFFF2F2F2);
        }
        pendingTitle = title;
        pendingUrl = "";
        final int gen = ++resolveGen;
        final String fTitle = title;
        final String fVid = vid;
        buildPlayback();
        setContentView(watchRoot);
        applyVideoLayout();
        showControls(true);
        setStatus("Resolving stream...");
        if (!shortVid) {
            loadRelated(fVid);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Resolver.Res r = Resolver.resolve(fVid);
                StreamStore.note("resolve " + fVid + " dual=" + (r.video != null && r.audio != null)
                        + " single=" + (r.combined != null) + " hls=" + (r.hls.length() > 0)
                        + (r.err.length() > 0 ? " err=" + r.err : ""));
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != resolveGen || playRoot == null || isFinishing()) {
                            return;
                        }
                        boolean dual = r.video != null && r.audio != null;
                        boolean single = r.combined != null;
                        if (!dual && !single) {
                            if (r.hls.length() > 0) {
                                // সরাসরি URL নেই কিন্তু HLS-ম্যানিফেস্ট আছে — চালানো যায়
                                setStatus("");
                                startPlay(r.hls, fTitle);
                            } else {
                                showResolveFail(r);
                            }
                            return;
                        }
                        ytVars = r.vars;
                        ytAudio = r.audio;
                        ytCaps = r.caps;
                        chaps.clear();
                        chaps.addAll(r.chaps);
                        sbList.clear();
                        capCues.clear();
                        ccOn = false;
                        fetchSbAndDis(fVid);
                        if (dual) {
                            Resolver.Var pick = pickStart(r);
                            if (pick != null) {
                                chosenHeight = pick.h;
                            }
                            setStatus("");
                            wantAttach = true;
                            attachTries = 0;
                            String[] va = new String[r.vars.size()];
                            for (int k = 0; k < r.vars.size(); k++) {
                                va[k] = r.vars.get(k).h + "|" + r.vars.get(k).url;
                            }
                            startService(new Intent(PlayerActivity.this, PlayerService.class)
                                    .setAction(PlayerService.ACT_PLAY)
                                    .putExtra(PlayerService.EXTRA_V,
                                            pick != null ? pick.url : r.video)
                                    .putExtra(PlayerService.EXTRA_A, r.audio)
                                    .putExtra(PlayerService.EXTRA_TITLE, fTitle)
                                    .putExtra(PlayerService.EXTRA_YT, true)
                                    .putExtra(PlayerService.EXTRA_VARS, va)
                                    .putStringArrayListExtra(PlayerService.EXTRA_CAPS,
                                            capsArr(r.caps)));
                            h.postDelayed(attachRetry, 100);
                        } else {
                            setStatus("");
                            startPlay(r.combined, fTitle);
                        }
                    }
                });
            }
        }).start();
    }

    /** দ্রুত শুরুর জন্য শুরুর কোয়ালিটি: 720p পর্যন্ত সেরা, না থাকলে সবচেয়ে ছোট। */
    private Resolver.Var pickStart(Resolver.Res r) {
        if (r == null || r.vars == null || r.vars.isEmpty()) {
            return null; // null-গার্ড: ক্র্যাশ-শিকড় (v1.3-এর NPE)
        }
        // ডিফল্ট = 360p (ইউজার-নির্দেশ) — না থাকলে 360-এর সবচেয়ে কাছেরটা
        return pickNear(r.vars, defQ());
    }

    /** ডিফল্ট প্লেব্যাক-কোয়ালিটি (Settings → Playback; ডিফল্ট 360)। */
    private int defQ() {
        try {
            int q = getSharedPreferences("mtw", 0).getInt("defQ", 360);
            if (getSharedPreferences("mtw", 0).getBoolean("datasaver", false)
                    && isMeteredNet()) {
                return Math.min(q, 360);
            }
            return q;
        } catch (Throwable t) {
            return 360;
        }
    }

    private boolean isMeteredNet() {
        try {
            Object cm = getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm instanceof android.net.ConnectivityManager) {
                return ((android.net.ConnectivityManager) cm).isActiveNetworkMetered();
            }
        } catch (Throwable t) {
        }
        return false;
    }

    /** টার্গেট-নীতি: হুবহু টার্গেট, না থাকলে নিকটতম (টাই-এ ছোটটা)। */
    private static Resolver.Var pickNear(List<Resolver.Var> vars, int target) {
        Resolver.Var best = null;
        int bd = Integer.MAX_VALUE;
        for (Resolver.Var v : vars) {
            int d = Math.abs(v.h - target);
            if (best == null || d < bd || (d == bd && v.h < best.h)) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    /** রিজলভ ব্যর্থ: কারণ দেখাই + ওয়েবে খোলা + ডিটেইলস শেয়ার। */
    private void showResolveFail(final Resolver.Res r) {
        if (!resumed) {
            failPending = r; // onResume-এ দেখানো হবে (BadToken-রক্ষা)
            return;
        }
        String e = r.err != null ? r.err : "";
        boolean net = e.contains("UnknownHost") || e.contains("Unable to resolve")
                || e.contains("connect") || e.contains("Network")
                || e.contains("timeout") || e.contains("Timed out")
                || e.length() == 0;
        String msg = net ? "No internet connection. Check your network and try again."
                : (e.length() > 120 ? e.substring(0, 120) : e);
        new AlertDialog.Builder(this)
                .setTitle(net ? "No internet" : "Stream unavailable")
                .setMessage(msg)
                .setPositiveButton("Retry", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        startYoutube(videoId,
                                pendingTitle != null && pendingTitle.length() > 0
                                        ? pendingTitle : videoId);
                    }
                })
                .setNegativeButton("Close", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    private void shareText(String txt) {
        try {
            Intent si = new Intent(Intent.ACTION_SEND);
            si.setType("text/plain");
            si.putExtra(Intent.EXTRA_TEXT, txt);
            startActivity(Intent.createChooser(si, "Share details"));
        } catch (Throwable t) {
        }
    }

    private void setStatus(String t) {
        if (statusText != null) {
            statusText.setText(t == null ? "" : t);
        }
    }

    private void listenerOn() {
        if (player != null && !listenerAdded) {
            player.addListener(plistener);
            listenerAdded = true;
        }
    }

    private void listenerOff() {
        if (player != null && listenerAdded) {
            try {
                player.removeListener(plistener);
            } catch (Throwable t) {
            }
        }
        listenerAdded = false;
        player = null;
        h.removeCallbacks(poll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playRoot != null && player == null) {
            // returning to this screen while service is still alive
            PlayerService s = PlayerService.sSelf;
            if (s != null && s.player() != null && s.hasItem()) {
                if (pendingUrl.length() == 0) {
                    pendingTitle = s.currentTitle();
                }
                attach(s);
            }
        } else if (player != null) {
            listenerOn();
            reattach();
            poll.run();
        }
        if (playRoot != null) {
            showControls(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (errPending != null) {
            String e = errPending;
            errPending = null;
            showError(e);
        }
        if (failPending != null) {
            Resolver.Res fr = failPending;
            failPending = null;
            showResolveFail(fr);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        // hold boost চলাকালীন স্ক্রিন ছাড়লে 2x-এ আটকে যাওয়া যাবে না
        endHoldBoost();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // service keeps playing; we only stop the UI listener + polling
        if (player != null && listenerAdded) {
            try {
                player.removeListener(plistener);
            } catch (Throwable t) {
            }
            listenerAdded = false;
        }
        h.removeCallbacks(poll);
        h.removeCallbacks(hideControls);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopTick();
        try {
            if (eq != null) {
                eq.release();
                eq = null;
            }
        } catch (Throwable t) {
        }
        listenerOff();
        h.removeCallbacks(attachRetry);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyVideoLayout();
        zoomResetQuiet();
        fitSurface();
    }

    @Override
    public void onBackPressed() {
        // ব্যাক = প্লেব্যাক সম্পূর্ণ বন্ধ (ব্যাকগ্রাউন্ডে আর চলতে থাকে না);
        // ব্যাকগ্রাউন্ড দরকার হলে Audio বাটন বা PiP — সেগুলো স্পষ্ট ইউজার-অ্যাকশন
        PlayerService s = PlayerService.sSelf;
        if (playRoot != null && s != null && s.hasItem()) {
            try {
                startService(new Intent(this, PlayerService.class)
                        .setAction(PlayerService.ACT_STOP));
            } catch (Throwable t) {
            }
        }
        super.onBackPressed();
    }

    // ---------- entry screen ----------

    private void buildEntry() {
        entryRoot = new LinearLayout(this);
        entryRoot.setOrientation(LinearLayout.VERTICAL);
        entryRoot.setBackgroundColor(colBg);
        entryRoot.setFitsSystemWindows(true);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int px = dp(16);
        box.setPadding(px, dp(24), px, px);
        sc.addView(box, new ViewGroup.LayoutParams(-1, -2));
        entryRoot.addView(sc, new LinearLayout.LayoutParams(-1, -1, 1f));

        TextView t = new TextView(this);
        t.setText("Wtube Player");
        t.setTextColor(colText);
        t.setTextSize(22);
        t.setTypeface(null, 1);
        box.addView(t);
        TextView sub = new TextView(this);
        sub.setText("Play any direct video link (MP4 or HLS)");
        sub.setTextColor(colDim);
        sub.setTextSize(13);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(4);
        sub.setLayoutParams(sp);
        box.addView(sub);

        // now playing row
        PlayerService s = PlayerService.sSelf;
        if (s != null && s.hasItem()) {
            LinearLayout np = panel();
            TextView npt = new TextView(this);
            npt.setText("Now playing: " + s.currentTitle());
            npt.setTextColor(colText);
            npt.setTextSize(14);
            npt.setSingleLine(true);
            np.addView(npt, new LinearLayout.LayoutParams(0, -2, 1f));
            Button open = smallBtn("Open");
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    reopen();
                }
            });
            np.addView(open);
            Button stop = smallBtn("Stop");
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startService(new Intent(PlayerActivity.this, PlayerService.class)
                            .setAction(PlayerService.ACT_STOP));
                    showEntry();
                }
            });
            np.addView(stop);
            LinearLayout.LayoutParams nplp = new LinearLayout.LayoutParams(-1, -2);
            nplp.topMargin = dp(16);
            box.addView(np, nplp);
        }

        // v1.40: পেস্ট-বক্স সরানো হয়েছে — ভিডিও অ্যাপ থেকেই খোলে; অফলাইনে Retry-ডায়ালগ।
        TextView hint = new TextView(this);
        hint.setText("Open videos from the Wtube app - they will play here");
        hint.setTextColor(colDim);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(20);
        hint.setLayoutParams(hp);
        box.addView(hint);

        // demo list
        TextView dl = new TextView(this);
        dl.setText("Demo streams");
        dl.setTextColor(colDim);
        dl.setTextSize(12);
        dl.setTypeface(null, 1);
        LinearLayout.LayoutParams dllp = new LinearLayout.LayoutParams(-2, -2);
        dllp.topMargin = dp(24);
        dllp.bottomMargin = dp(8);
        box.addView(dl, dllp);
        for (int k = 0; k < DEMOS.length; k++) {
            final String name = DEMOS[k][0];
            final String note = DEMOS[k][1];
            final String u = DEMOS[k][2];
            LinearLayout row = panel();
            TextView rt = new TextView(this);
            rt.setText(name);
            rt.setTextColor(colText);
            rt.setTextSize(15);
            row.addView(rt);
            TextView rs = new TextView(this);
            rs.setText(note);
            rs.setTextColor(colDim);
            rs.setTextSize(12);
            row.addView(rs);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startPlay(u, name);
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(colPanel);
        int p = dp(12);
        l.setPadding(p, p, p, p);
        return l;
    }

    private Button smallBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable gb =
                new android.graphics.drawable.GradientDrawable();
        gb.setColor(accent);
        gb.setCornerRadius(dp(17));
        b.setBackground(gb);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- playback screen ----------

    private void buildPlayback() {
        playRoot = new FrameLayout(this);
        playRoot.setBackgroundColor(0xFF000000);

        surfaceBox = new FrameLayout(this);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(sholder);
        surfaceBox.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        playRoot.addView(surfaceBox, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER_HORIZONTAL);
        loading = new ProgressBar(this);
        centerBox.addView(loading, new LinearLayout.LayoutParams(dp(52), dp(52)));
        statusText = new TextView(this);
        statusText.setText("");
        statusText.setTextColor(0xFFF2F2F2);
        statusText.setTextSize(13);
        centerBox.addView(statusText, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.CENTER);
        playRoot.addView(centerBox, llp);

        // অডিও-মোড প্যানেল (ভিডিও বন্ধ থাকলে দেখায়)
        audioView = new LinearLayout(this);
        audioView.setOrientation(LinearLayout.VERTICAL);
        audioView.setGravity(Gravity.CENTER);
        audioView.setBackgroundColor(0xFF000000);
        audioView.setVisibility(View.GONE);
        TextView aTag = new TextView(this);
        aTag.setText("Audio mode");
        aTag.setTextColor(0xFF9A9A9A);
        aTag.setTextSize(13);
        audioView.addView(aTag);
        audioTitle = new TextView(this);
        audioTitle.setTextColor(0xFFF2F2F2);
        audioTitle.setTextSize(17);
        audioTitle.setTypeface(null, 1);
        audioTitle.setPadding(dp(24), dp(8), dp(24), dp(8));
        audioTitle.setGravity(Gravity.CENTER);
        audioView.addView(audioTitle);
        audioPlay = new PlayIcon(this);
        LinearLayout.LayoutParams aplp = new LinearLayout.LayoutParams(dp(72), dp(72));
        aplp.topMargin = dp(16);
        audioPlay.setLayoutParams(aplp);
        audioPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) {
                    if (player.getPlayWhenReady()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                    refreshHud();
                }
            }
        });
        audioView.addView(audioPlay);

        // controls container (covers whole screen, passes taps outside bars to video)
        controls = new FrameLayout(this);
        topBar = buildTop();
        bottomBar = buildBottom();

        // সেন্টার কন্ট্রোল: স্কিপ-ব্যাক | প্লে (বৃত্ত-পটভূমি) | স্কিপ-ফরোয়ার্ড
        centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER_VERTICAL);
        IconButton back = iconBtn("replay");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) {
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    showHud("-10s");
                }
            }
        });
        centerRow.addView(back);

        android.graphics.drawable.GradientDrawable circ =
                new android.graphics.drawable.GradientDrawable();
        circ.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circ.setColor(0x66000000);
        FrameLayout playBox = new FrameLayout(this);
        playBox.setBackground(circ);
        int ps = dp(64);
        btnPlay = new PowerIcon(this);
        btnPlay.setState(true);
        playBox.addView(btnPlay, new FrameLayout.LayoutParams(ps, ps));
        playBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) {
                    if (player.getPlayWhenReady()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                    refreshHud();
                }
            }
        });
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-2, -2);
        pblp.leftMargin = dp(18);
        pblp.rightMargin = dp(18);
        centerRow.addView(playBox, pblp);

        IconButton fwd = iconBtn("forward");
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) {
                    player.seekTo(player.getCurrentPosition() + 10000);
                    showHud("+10s");
                }
            }
        });
        centerRow.addView(fwd);

        // v4: উপরে-নিচে গ্রেডিয়েন্ট স্ক্রিম — কন্ট্রোল-লেখা পড়া সহজ
        View scrimT = new View(this);
        android.graphics.drawable.GradientDrawable gdT =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xD9000000, 0x00000000});
        scrimT.setBackground(gdT);
        controls.addView(scrimT, new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP));
        View scrimB = new View(this);
        android.graphics.drawable.GradientDrawable gdB =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0xD9000000});
        scrimB.setBackground(gdB);
        controls.addView(scrimB, new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM));

        controls.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        controls.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        controls.addView(centerRow, new FrameLayout.LayoutParams(-2, -2,
                Gravity.CENTER));
        playRoot.addView(audioView, new FrameLayout.LayoutParams(-1, -1));
        playRoot.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        // gesture layer: single-tap=controls, double-tap=seek/zoom, swipe=brightness/volume
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        startTick();
        if (getSharedPreferences("mtw", 0).getBoolean("amoled", false)) {
            try {
                playRoot.setBackgroundColor(0xFF000000);
            } catch (Throwable t) {
            }
        }
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isInPictureInPictureMode()) {
                    if (player != null) {
                        if (player.getPlayWhenReady()) {
                            player.pause();
                        } else {
                            player.play();
                        }
                    }
                } else {
                    showControls(!controlsVisible);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // দুই আঙুল (পিঞ্চ) চললে দীর্ঘ-চাপ নয় — নাহলে জুম শুরু করতে
                // গিয়ে ভিডিও 2x হয়ে যাবে
                if (e == null || multiTouch || e.getPointerCount() > 1
                        || (sgd != null && sgd.isInProgress())) {
                    return;
                }
                beginHoldBoost();
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (player == null || isInPictureInPictureMode()) {
                    return true;
                }
                float w = surfaceBox.getWidth();
                float x = e.getX();
                if (x < w / 3f) {
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    showHud("-10s");
                } else if (x > w * 2f / 3f) {
                    player.seekTo(player.getCurrentPosition() + 10000);
                    showHud("+10s");
                } else {
                    // জুম করা থাকলে ডাবল-ট্যাপে ১০০%-এ ফেরত, নাহলে ফিট/ফিল টগল
                    if (resetZoom()) {
                        showHud("Zoom reset");
                    } else {
                        fillMode = !fillMode;
                        fitSurface();
                        showHud(fillMode ? "Fill screen" : "Fit screen");
                    }
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (player == null || isInPictureInPictureMode() || e1 == null) {
                    return false;
                }
                /*
                 * জুম করা থাকলে অক্ষ দেখে সিদ্ধান্ত নিই (আগে সব ড্র্যাগই ভিডিও
                 * সরাত, তাই আওয়াজ বাড়াতে উপরে টানলে ভিডিও সরে যেত):
                 *   উপরে-নিচে  → ব্রাইটনেস / ভলিউম (ভিডিও স্থির)
                 *   বাঁয়ে-ডানে → ভিডিও সরানো
                 *   দুই আঙুল   → সব দিকে সরানো (scale listener দেখে)
                 * সিদ্ধান্ত একবারই, ১২dp নড়ার পর — মাঝপথে বদলায় না।
                 */
                if (zoom > 1.001f) {
                    if (e2.getPointerCount() > 1) {
                        return false;
                    }
                    if (zoomAxis == 0
                            && (Math.abs(e2.getY() - e1.getY()) > 12 * u
                                || Math.abs(e2.getX() - e1.getX()) > 12 * u)) {
                        zoomAxis = Math.abs(dy) > Math.abs(dx) ? 1 : 2;
                    }
                    if (zoomAxis == 2) {
                        panX -= dx;
                        panY -= dy;
                        applyZoom();
                        return true;
                    }
                    if (zoomAxis == 0) {
                        return true; // এখনো বোঝা যায়নি — কিছুই করি না
                    }
                    // zoomAxis == 1: নিচের স্বাভাবিক ভলিউম/ব্রাইটনেস পথে যাই
                }
                if (!verticalGesture && Math.abs(dy) > Math.abs(dx)
                        && Math.abs(e2.getY() - e1.getY()) > 12 * u) {
                    verticalGesture = true;
                    downX = e1.getX();
                    downY = e1.getY();
                }
                // জুম থাকলে অক্ষ-লক আগেই ঠিক হয়ে গেছে — verticalGesture ধরে নিই
                if (zoom > 1.001f && zoomAxis == 1 && !verticalGesture) {
                    verticalGesture = true;
                    downX = e1.getX();
                    downY = e1.getY();
                }
                if (!verticalGesture) {
                    return false;
                }
                float delta = (downY - e2.getY()) / (surfaceBox.getHeight() * 0.75f);
                if (downX < surfaceBox.getWidth() / 2f) {
                    // ব্রাইটনেস (শুধু এই উইন্ডোতে — কোনো পারমিশন নয়)
                    if (brightAcc < 0f) {
                        float cur = getWindow().getAttributes().screenBrightness;
                        brightAcc = cur < 0f ? 0.5f : cur;
                    }
                    brightAcc = Math.min(1f, Math.max(0.02f, brightAcc + delta * 0.25f));
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = brightAcc;
                    getWindow().setAttributes(lp);
                    showHud("Brightness " + Math.round(brightAcc * 100) + "%");
                } else {
                    int max = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15;
                    if (max <= 0) {
                        max = 15;
                    }
                    if (volAcc < 0f) {
                        volAcc = am != null
                                ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : max / 2f;
                    }
                    // ধীরে টানলেও প্রতিটি পিক্সেল জমা হয় — আগে ভগ্নাংশ ফেলে দেওয়ায়
                    // ছোট নাড়াচাড়ায় কিছুই হতো না, তারপর হঠাৎ এক ধাপ লাফাতো
                    volAcc = Math.min(max, Math.max(0f, volAcc + delta * max * 0.5f));
                    applyVolume(volAcc, max);
                    showHud("Volume " + Math.round(100f * volAcc / max) + "%");
                }
                downY = e2.getY();
                return true;
            }
        });
        sgd = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector det) {
                        zoomStartX = panX;
                        zoomStartY = panY;
                        lastFocusX = det.getFocusX();
                        lastFocusY = det.getFocusY();
                        focusPanX = 0f;
                        focusPanY = 0f;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector det) {
                        if (player == null || isInPictureInPictureMode()) {
                            return true;
                        }
                        float f = det.getScaleFactor();
                        if (!Float.isFinite(f) || f <= 0f) {
                            return true;
                        }
                        float fx = det.getFocusX();
                        float fy = det.getFocusY();
                        // দুই আঙুলের কেন্দ্র সরে গেলে ভিডিওটাও সরাই — জুম
                        // থাকলেও সব দিকে সরানো সম্ভব থাকে। আলাদা ভেরিয়েবলে
                        // জমাই, কারণ নিচের সূত্রটি panX/panY পুরোটা নতুন করে বসায়।
                        if (zoom > ZOOM_MIN + 0.001f) {
                            focusPanX += fx - lastFocusX;
                            focusPanY += fy - lastFocusY;
                        }
                        lastFocusX = fx;
                        lastFocusY = fy;
                        float prev = zoom;
                        zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom * f));
                        if (zoom <= ZOOM_MIN + 0.001f) {
                            panX = 0f;
                            panY = 0f;
                            focusPanX = 0f;
                            focusPanY = 0f;
                        }
                        // আঙুলের মাঝখান বিন্দুটিকে স্থির রাখা (যাতে জুম "লাফ" না দেয়)
                        float zf = prev > 0.001f ? zoom / prev : 1f;
                        panX = zoomStartX * zf
                                + (fx - surfaceBox.getWidth() / 2f) * (1f - zf) + focusPanX;
                        panY = zoomStartY * zf
                                + (fy - surfaceBox.getHeight() / 2f) * (1f - zf) + focusPanY;
                        applyZoom();
                        showHud("Zoom " + Math.round(zoom * 100) + "%");
                        return true;
                    }
                });
        surfaceBox.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    multiTouch = false;
                    zoomAxis = 0;
                } else if (e.getAction() == MotionEvent.ACTION_POINTER_DOWN) {
                    // দ্বিতীয় আঙুল = পিঞ্চ; দীর্ঘ-চাপ বাতিল, চলমান boost-ও শেষ
                    multiTouch = true;
                    endHoldBoost();
                }
                if (e.getAction() == MotionEvent.ACTION_DOWN
                        || e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    if (e.getAction() != MotionEvent.ACTION_DOWN) {
                        // আঙুল তোলা = ভলিউম কাছের ধাপে নামিয়ে গেইন স্বাভাবিক
                        if (verticalGesture) {
                            settleVolumeGesture();
                        }
                        verticalGesture = false;
                        // চেপে-ধরে-রাখা শেষ হলে আগের গতিতে ফেরত
                        endHoldBoost();
                    }
                }
                // পিঞ্চ চলাকালে সিঙ্গেল-ট্যাপ/স্ক্রল জেসচার বন্ধ
                boolean scaling = sgd != null && (sgd.isInProgress() || e.getPointerCount() > 1);
                if (sgd != null) {
                    sgd.onTouchEvent(e);
                }
                if (!scaling) {
                    gd.onTouchEvent(e);
                }
                return true;
            }
        });

        // ক্ষণস্থায়ী জেসচার-নির্দেশক (টেক্সট, অদৃশ্য হয়)
        gestureHud = new TextView(this);
        gestureHud.setTextColor(0xFFFFFFFF);
        gestureHud.setTextSize(14);
        gestureHud.setTypeface(null, 1);
        gestureHud.setBackgroundColor(0x99000000);
        gestureHud.setPadding(dp(14), dp(6), dp(14), dp(6));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ccOverlay = new TextView(this);
        ccOverlay.setTextColor(0xFFFFFFFF);
        ccOverlay.setTextSize(16);
        ccOverlay.setGravity(Gravity.CENTER);
        ccOverlay.setBackgroundColor(0xB3000000);
        ccOverlay.setPadding(dp(10), dp(4), dp(10), dp(4));
        ccOverlay.setVisibility(View.GONE);
        try {
            surfaceBox.addView(ccOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        } catch (Throwable t) {
        }
        hlp.topMargin = dp(64);
        gestureHud.setVisibility(View.GONE);
        playRoot.addView(gestureHud, hlp);

        // অ্যাকশন-সারি (নিচের স্ট্রিপ; ল্যান্ডস্কেপে টপ-সারি-২): কোয়ালিটি +
        // ওয়াচ-লেটার + প্লেলিস্ট + শেয়ার + টাইমার + অডিও + সেভ + PiP
        actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(dp(8), 0, dp(8), 0);

        LinearLayout.LayoutParams alp;
        btnWl = iconBtn("clock");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnWl.setLayoutParams(alp);
        btnWl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWatchLater();
            }
        });
        actionRow.addView(btnWl);

        IconButton btnPl = iconBtn("playlist");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnPl.setLayoutParams(alp);
        btnPl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPlaylistDialog();
            }
        });
        actionRow.addView(btnPl);

        IconButton btnShare = iconBtn("share");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnShare.setLayoutParams(alp);
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareVideo();
            }
        });
        actionRow.addView(btnShare);

        btnAudio = iconBtn("headset");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnAudio.setLayoutParams(alp);
        btnAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAudioMode();
            }
        });
        actionRow.addView(btnAudio);

        btnSave = iconBtn("download");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnSave.setLayoutParams(alp);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveMenu();
            }
        });
        actionRow.addView(btnSave);

        IconButton btnPip = iconBtn("pip");
        alp = new LinearLayout.LayoutParams(dp(38), dp(38));
        alp.leftMargin = dp(10);
        btnPip.setLayoutParams(alp);
        btnPip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterPip();
            }
        });
        actionRow.addView(btnPip);

        // ওয়াচ-লেআউট: উপরে ভিডিও-বাক্স, নিচে তথ্য + রিলেটেড (ইউটিউব-কাঠামো)
        watchRoot = new LinearLayout(this);
        watchRoot.setOrientation(LinearLayout.VERTICAL);
        watchRoot.setBackgroundColor(0xFF000000);
        // edge-to-edge-হ্যান্ডলিং (Android 15+): স্ট্যাটাস-বার/নচ-এর নিচে ভিডিও,
        // নেভ-বারের উপরে নিচের-তালিকা। ফুলস্ক্রিনে বার লুকানো → ইনসেট ০ → প্যাডিং ০।
        watchRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top;
                int bot;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = in.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    bot = in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                } else {
                    top = in.getSystemWindowInsetTop();
                    bot = in.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bot);
                applyVideoLayout();
                return in;
            }
        });
        videoBox = new FrameLayout(this);
        videoBox.addView(playRoot, new FrameLayout.LayoutParams(-1, -1));
        watchRoot.addView(videoBox, new LinearLayout.LayoutParams(-1, -2));
        belowScroll = new android.widget.ScrollView(this);
        belowScroll.setBackgroundColor(0xFF0F0F0F);
        LinearLayout below = new LinearLayout(this);
        below.setOrientation(LinearLayout.VERTICAL);
        stripSlot = new android.widget.HorizontalScrollView(this);
        stripSlot.setHorizontalScrollBarEnabled(false);
        stripSlot.setVerticalScrollBarEnabled(false);
        stripSlot.setPadding(0, dp(8), 0, dp(8));
        stripSlot.addView(actionRow);
        below.addView(stripSlot);
        View stripDiv = new View(this);
        stripDiv.setBackgroundColor(0xFF262626);
        below.addView(stripDiv,
                new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        belowTitle = new TextView(this);
        belowTitle.setTextColor(0xFFF2F2F2);
        belowTitle.setTextSize(15);
        belowTitle.setMaxLines(3);
        belowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        belowTitle.setPadding(dp(12), dp(10), dp(12), dp(4));
        belowTitle.setText(pendingTitle != null ? pendingTitle : "");
        below.addView(belowTitle);
        // চ্যানেল-সারি: গোল প্রোফাইল-ছবি + নাম/সাবস্ক্রাইবার (ট্যাপে চ্যানেল খোলে)
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(dp(12), dp(4), dp(12), dp(2));
        avatarImg = new android.widget.ImageView(this);
        avatarImg.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarImg.setBackgroundColor(0xFF2A2A2A);
        try {
            avatarImg.setClipToOutline(true);
            avatarImg.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        } catch (Throwable t) {
        }
        metaRow.addView(avatarImg, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout chCol = new LinearLayout(this);
        chCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(0, -2, 1f);
        cclp.leftMargin = dp(8);
        chName = new TextView(this);
        chName.setTextColor(0xFFF2F2F2);
        chName.setTextSize(14);
        chName.setTypeface(null, 1);
        chName.setMaxLines(1);
        chName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chCol.addView(chName);
        chSubs = new TextView(this);
        chSubs.setTextColor(0xFF9A9A9A);
        chSubs.setTextSize(12);
        chSubs.setMaxLines(1);
        chCol.addView(chSubs);
        metaRow.addView(chCol, cclp);

        // SUBSCRIBE-পিল (সাদা গোল-বাটন — ইউটিউব-চেহারা)
        TextView subBtn = new TextView(this);
        subBtn.setText("Subscribe");
        subBtn.setTextColor(0xFF0F0F0F);
        subBtn.setTextSize(12);
        subBtn.setTypeface(null, 1);
        subBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable sgd =
                new android.graphics.drawable.GradientDrawable();
        sgd.setColor(0xFFF2F2F2);
        sgd.setCornerRadius(dp(13));
        subBtn.setBackground(sgd);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-2, -2);
        slp2.leftMargin = dp(8);
        subBtn.setLayoutParams(slp2);
        subBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("Sign in to subscribe on YouTube");
            }
        });
        metaRow.addView(subBtn);

        // লাইক/ডিসলাইক-পিল: [👍 সংখ্যা | 👎]
        LinearLayout likePill = new LinearLayout(this);
        likePill.setOrientation(LinearLayout.HORIZONTAL);
        likePill.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable lpg =
                new android.graphics.drawable.GradientDrawable();
        lpg.setColor(0xFF2A2A2A);
        lpg.setCornerRadius(dp(14));
        likePill.setBackground(lpg);
        likePill.setPadding(dp(4), dp(2), dp(4), dp(2));
        IconButton up = new IconButton(this);
        up.icon = "like";
        up.setPadding(dp(4), dp(4), dp(4), dp(4));
        up.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        likePill.addView(up);
        likeCnt = new TextView(this);
        likeCnt.setTextColor(0xFFF2F2F2);
        likeCnt.setTextSize(12);
        LinearLayout.LayoutParams lclp2 = new LinearLayout.LayoutParams(-2, -2);
        lclp2.leftMargin = dp(4);
        likeCnt.setLayoutParams(lclp2);
        likePill.addView(likeCnt);
        View ldiv = new View(this);
        ldiv.setBackgroundColor(0xFF4A4A4A);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(16));
        dlp.leftMargin = dp(6);
        dlp.rightMargin = dp(6);
        ldiv.setLayoutParams(dlp);
        likePill.addView(ldiv);
        IconButton down = new IconButton(this);
        down.icon = "dislike";
        down.setPadding(dp(4), dp(4), dp(4), dp(4));
        down.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        likePill.addView(down);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("Sign in to like on YouTube");
            }
        });
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("Sign in to dislike on YouTube");
            }
        });
        LinearLayout.LayoutParams lplp = new LinearLayout.LayoutParams(-2, -2);
        lplp.leftMargin = dp(10);
        likePill.setLayoutParams(lplp);
        metaRow.addView(likePill);

        // শেয়ার + থ্রি-ডট
        IconButton shBtn = iconBtn("share");
        LinearLayout.LayoutParams shlp2 = new LinearLayout.LayoutParams(dp(32), dp(32));
        shlp2.leftMargin = dp(10);
        shBtn.setLayoutParams(shlp2);
        shBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareVideo();
            }
        });
        metaRow.addView(shBtn);

        IconButton moreBtn = iconBtn("more");
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(dp(32), dp(32));
        mlp2.leftMargin = dp(10);
        moreBtn.setLayoutParams(mlp2);
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreMenu();
            }
        });
        metaRow.addView(moreBtn);

        metaRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChannel(lastChUrl);
            }
        });
        below.addView(metaRow);
        disLine = new TextView(this);
        disLine.setTextColor(0xFF9A9A9A);
        disLine.setTextSize(12);
        disLine.setPadding(dp(12), 0, dp(12), 0);
        disLine.setVisibility(View.GONE);
        below.addView(disLine);

        belowMeta = new TextView(this);
        belowMeta.setTextColor(0xFF9A9A9A);
        belowMeta.setTextSize(12);
        belowMeta.setPadding(dp(12), 0, dp(12), dp(6));
        below.addView(belowMeta);


        // ডেসক্রিপশন: ভাঁজ-খোলা (৩-লাইনে সংক্ষিপ্ত, ট্যাপে সম্পূর্ণ)
        descView = new TextView(this);
        descView.setTextColor(0xFFC9C9C9);
        descView.setTextSize(13);
        descView.setLineSpacing(0, 1.15f);
        descView.setPadding(dp(12), 0, dp(12), dp(8));
        descView.setMaxLines(3);
        descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        descView.setVisibility(View.GONE);
        descView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                descOpen = !descOpen;
                descView.setMaxLines(descOpen ? Integer.MAX_VALUE : 3);
            }
        });
        below.addView(descView);

        // কমেন্ট রিলেটেড-তালিকার আগে (ইউটিউব-ক্রম: তথ্য → ডেসক্রিপশন → কমেন্ট → রিলেটেড)
        cmtBox = new LinearLayout(this);
        cmtBox.setOrientation(LinearLayout.VERTICAL);
        below.addView(cmtBox, new LinearLayout.LayoutParams(-1, -2));

        TextView upNext = new TextView(this);
        upNext.setText("Up next");
        upNext.setTextColor(0xFF9A9A9A);
        upNext.setTextSize(12);
        upNext.setPadding(dp(12), dp(6), dp(12), dp(2));
        below.addView(upNext);
        relBox = new LinearLayout(this);
        relBox.setOrientation(LinearLayout.VERTICAL);
        below.addView(relBox, new LinearLayout.LayoutParams(-1, -2));
        belowScroll.addView(below, new ViewGroup.LayoutParams(-1, -2));
        watchRoot.addView(belowScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        applyVideoLayout();
    }

    /** নিউপাইপ-স্টাইল গ্রেডিয়েন্ট স্ক্রিম। */
    private android.graphics.drawable.GradientDrawable scrim(boolean top) {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                top ? new int[]{0xCC000000, 0x00000000}
                        : new int[]{0x00000000, 0xCC000000});
    }

    /** গোলাকার পিল-চিপ (কোয়ালিটি/স্পিড লেবেল)। */
    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFF2F2F2);
        t.setTextSize(11);
        t.setAllCaps(false);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(0x66000000);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    private View buildTop() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(scrim(true));

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        // ডান-প্রান্ত-অ্যালাইন: শিরোনাম (weight-1) থাকলে বাঁয়ে টানটান, না থাকলে
        // (পোর্ট্রেট — শিরোনাম নিচে দেখায়) বোতামগুলো একদম ডানে
        l.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        l.setPadding(dp(14), dp(14), dp(6), dp(10));

        vTitle = new TextView(this);
        vTitle.setTextColor(0xFFF2F2F2);
        vTitle.setTextSize(14);
        vTitle.setMaxLines(2);
        vTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vTitle.setText(pendingTitle != null ? pendingTitle : "");
        l.addView(vTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        // স্পিড + লুপ ভিডিওর উপরের বারে (ইউজার-নির্দেশ)
        btnSpeed = chip("1x");
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v2) {
                cycleSpeed();
            }
        });
        l.addView(btnSpeed);

        btnLoop = iconBtn("repeat");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(38), dp(38));
        llp.leftMargin = dp(10);
        btnLoop.setLayoutParams(llp);
        btnLoop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v2) {
                toggleLoop();
            }
        });
        l.addView(btnLoop);

        btnTimerTop = iconBtn("timer");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(38), dp(38));
        tlp.leftMargin = dp(10);
        btnTimerTop.setLayoutParams(tlp);
        btnTimerTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v2) {
                showSleepDialog();
            }
        });
        l.addView(btnTimerTop);

        btnQuality = chip("Auto");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
        qlp.leftMargin = dp(10);
        btnQuality.setLayoutParams(qlp);
        btnQuality.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQualityMenu();
            }
        });
        l.addView(btnQuality);
        v.addView(l, new LinearLayout.LayoutParams(-1, -2));

        // ল্যান্ডস্কেপে দ্বিতীয় সারি: অ্যাকশন-বোতামগুলো এখানে ওঠে
        LinearLayout landRow = new LinearLayout(this);
        landRow.setOrientation(LinearLayout.HORIZONTAL);
        landRow.setGravity(Gravity.CENTER);
        landRow.setPadding(dp(6), 0, dp(6), dp(4));
        landRow.setVisibility(View.GONE);
        topSlot = landRow;
        v.addView(landRow, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private View buildBottom() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(scrim(false));
        v.setPadding(dp(12), dp(4), dp(14), dp(10));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);

        vTime = new TextView(this);
        vTime.setText("0:00");
        vTime.setTextColor(0xFFF2F2F2);
        vTime.setTextSize(12);
        r1.addView(vTime);

        seek = new SeekBar(this);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        int green = 0xFF34D399;
        android.content.res.ColorStateList gs =
                android.content.res.ColorStateList.valueOf(green);
        seek.setProgressTintList(gs);
        seek.setThumbTintList(gs);
        seek.setSecondaryProgressTintList(
                android.content.res.ColorStateList.valueOf(0x8034D399));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s2, int pr, boolean fromUser) {
                if (fromUser) {
                    vTime.setText(fmt(pr));
                    vDur.setText(fmt(s2.getMax()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s2) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s2) {
                dragging = false;
                if (player != null) {
                    player.seekTo(s2.getProgress());
                }
            }
        });
        r1.addView(seek);

        vDur = new TextView(this);
        vDur.setText("0:00");
        vDur.setTextColor(0xFFF2F2F2);
        vDur.setTextSize(12);
        r1.addView(vDur);

        btnFull = iconBtn("full");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(32), dp(32));
        flp.leftMargin = dp(4);
        btnFull.setLayoutParams(flp);
        btnFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFullscreen();
            }
        });
        r1.addView(btnFull);
        v.addView(r1);
        return v;
    }

    private void showControls(boolean on) {
        controlsVisible = on;
        topBar.setVisibility(on ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (centerRow != null && !audioOnly) {
            centerRow.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        h.removeCallbacks(hideControls);
        if (on) {
            h.postDelayed(hideControls, 3500);
            applyImmersive(false);
        } else {
            applyImmersive(true);
        }
    }

    /** ঘোরা অনুযায়ী লেআউট: পোর্ট্রেট=ভিডিও(16:9)+নিচের তালিকা, ল্যান্ডস্কেপ=ফুলস্ক্রিন। */
    private void applyVideoLayout() {
        if (watchRoot == null || videoBox == null) {
            return;
        }
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        // Shorts/উল্লম্ব ভিডিও = ফুল-স্ক্রিন পোর্ট্রেট (Shorts-প্লেয়ারের মতো)
        boolean full = land || !isYoutube || vertVideo;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        videoBox.setLayoutParams(new LinearLayout.LayoutParams(-1, full ? -1
                : Math.round(dm.widthPixels * 9f / 16f)));
        if (belowScroll != null) {
            belowScroll.setVisibility(full ? View.GONE : View.VISIBLE);
        }
        if (vTitle != null) {
            vTitle.setVisibility(land ? View.VISIBLE : View.GONE);
        }
        // অ্যাকশন-সারি: পোর্ট্রেটে ভিডিওর-নিচের স্ট্রিপে, ল্যান্ডস্কেপে টপ-বারে
        if (actionRow != null && stripSlot != null && topSlot != null) {
            if (land) {
                if (actionRow.getParent() == stripSlot) {
                    stripSlot.removeView(actionRow);
                    topSlot.addView(actionRow);
                }
            } else if (actionRow.getParent() == topSlot) {
                topSlot.removeView(actionRow);
                stripSlot.addView(actionRow);
            }
            topSlot.setVisibility(land ? View.VISIBLE : View.GONE);
        }
        applyImmersive(full);
    }

    /** ফুলস্ক্রিন টগল: ওয়াচ-পেজ ↔ ল্যান্ডস্কেপ-ফুলস্ক্রিন। */
    private void toggleFullscreen() {
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(land
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void applyImmersive(boolean hide) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    if (hide) {
                        c.hide(WindowInsets.Type.systemBars());
                        c.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        c.show(WindowInsets.Type.systemBars());
                    }
                }
            } else {
                View d = getWindow().getDecorView();
                if (hide) {
                    d.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                } else {
                    d.setSystemUiVisibility(0);
                }
            }
        } catch (Throwable t) {
        }
    }

    private final Runnable hideHud = new Runnable() {
        @Override
        public void run() {
            // hold boost চলাকালীন HUD পিন করা থাকে — লুকায় না
            if (gestureHud != null && !hudPinned) {
                gestureHud.setVisibility(View.GONE);
            }
        }
    };

    private void showHud(String t) {
        if (gestureHud == null) {
            return;
        }
        gestureHud.setText(t);
        gestureHud.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideHud);
        if (!hudPinned) {
            h.postDelayed(hideHud, 800);
        }
    }

    /**
     * চেপে ধরে রাখলে 2x গতি। শুধু তখনই যখন ভিডিও সত্যিই বাজছে —
     * পজ করা থাকলে বা PiP-তে কিছুই করি না (কিছু বাড়তি হবে না)।
     */
    private void beginHoldBoost() {
        if (holdBoost || player == null || isInPictureInPictureMode()) {
            return;
        }
        if (!player.getPlayWhenReady()) {
            return;
        }
        try {
            player.setPlaybackParameters(
                    new androidx.media3.common.PlaybackParameters(2f));
        } catch (Throwable t) {
            return;
        }
        holdBoost = true;
        hudPinned = true;
        showHud("2x speed — release to resume " + fmtSpeed(curSpeed));
    }

    /** আঙুল তোলা/বাতিল — আগের গতিতে ফেরত, HUD আবার স্বাভাবিক। */
    private void endHoldBoost() {
        if (!holdBoost) {
            return;
        }
        holdBoost = false;
        hudPinned = false;
        try {
            if (player != null) {
                player.setPlaybackParameters(
                        new androidx.media3.common.PlaybackParameters(curSpeed));
            }
        } catch (Throwable t) {
        }
        if (gestureHud != null) {
            gestureHud.setVisibility(View.GONE);
        }
    }

    private void cycleSpeed() {
        float[] sp = new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        int next = 0;
        for (int i = 0; i < sp.length; i++) {
            if (Math.abs(sp[i] - curSpeed) < 0.01f) {
                next = (i + 1) % sp.length;
                break;
            }
        }
        curSpeed = sp[next];
        if (player != null) {
            player.setPlaybackSpeed(curSpeed);
        }
        if (btnSpeed != null) {
            btnSpeed.setText(fmtSpeed(curSpeed));
        }
    }

    private String fmtSpeed(float v) {
        return (v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(v)) + "x";
    }

    private void toggleLoop() {
        loopOn = !loopOn;
        if (player != null) {
            player.setRepeatMode(loopOn
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
        if (btnLoop != null) {
            btnLoop.setIconTint(loopOn ? 0xFF34D399 : 0xFFF2F2F2);
        }
        showHud(loopOn ? "Loop on" : "Loop off");
    }

    /** রিলেটেড তালিকা আনা (আলাদা থ্রেড, জেনারেশন-গার্ডেড)। */
    private void loadRelated(final String vid) {
        if (vid == null || vid.length() == 0) {
            return;
        }
        final int gen = ++relGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder lb = new StringBuilder();
                final List<Resolver.Rel> list = Resolver.related(vid, lb);
                PlayerService.setNextQueue(list);
                final List<Resolver.Cmt> cmts = Resolver.comments(vid, lb);
                final Resolver.Meta mt = Resolver.meta(vid, lb);
                StreamStore.note("related " + vid + " " + lb);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != relGen || relBox == null || isFinishing()) {
                            return;
                        }
                        fillRelated(list);
                        fillComments(cmts);
                        fillMeta(mt);
                    }
                });
            }
        }).start();
    }

    /** নিচের তালিকা ভরা: থাম্বনেইল + সময়-ব্যাজ + শিরোনাম + চ্যানেল। */
    private void fillRelated(List<Resolver.Rel> list) {
        relBox.removeAllViews();
        relList.clear();
        if (list != null) {
            relList.addAll(list);
        }
        PlayerService.setNextQueue(list);
        if (belowTitle != null && pendingTitle != null) {
            belowTitle.setText(pendingTitle);
        }
        if (list.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("No related videos");
            e.setTextColor(0xFF9A9A9A);
            e.setTextSize(13);
            e.setPadding(dp(12), dp(8), dp(12), dp(12));
            relBox.addView(e);
            return;
        }
        for (final Resolver.Rel r : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(6), dp(12), dp(6));

            FrameLayout th = new FrameLayout(this);
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFF1E1E1E);
            th.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (r.dur.length() > 0) {
                TextView badge = new TextView(this);
                badge.setText(r.dur);
                badge.setTextColor(0xFFF2F2F2);
                badge.setTextSize(10);
                badge.setBackgroundColor(0xCC000000);
                badge.setPadding(dp(3), dp(1), dp(3), dp(1));
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.BOTTOM | Gravity.END);
                bl.rightMargin = dp(3);
                bl.bottomMargin = dp(3);
                th.addView(badge, bl);
            }
            loadThumb(img, r.thumb);
            row.addView(th, new LinearLayout.LayoutParams(dp(124), dp(70)));

            LinearLayout tv = new LinearLayout(this);
            tv.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tvp = new LinearLayout.LayoutParams(0, -2, 1f);
            tvp.leftMargin = dp(10);
            TextView t1 = new TextView(this);
            t1.setText(r.title);
            t1.setTextColor(0xFFF2F2F2);
            t1.setTextSize(13);
            t1.setMaxLines(2);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(r.by);
            t2.setTextColor(0xFF9A9A9A);
            t2.setTextSize(11);
            t2.setMaxLines(1);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.addView(t2);
            row.addView(tv, tvp);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRelated(r.id, r.title);
                }
            });
            relBox.addView(row);
        }
    }

    /** মেটা: আসল টাইটেল + চ্যানেল-সারি (অবতার·নাম·সাব) + ভিউ·তারিখ·লাইক + Like/Share। */
    private void fillMeta(Resolver.Meta m) {
        if (m.title != null && m.title.trim().length() > 0
                && (pendingTitle == null || pendingTitle.length() == 0)) {
            pendingTitle = m.title.trim();
            if (belowTitle != null) {
                belowTitle.setText(pendingTitle);
            }
            if (vTitle != null) {
                vTitle.setText(pendingTitle);
            }
            PlayerService ps = PlayerService.sSelf;
            if (ps != null) {
                ps.setTitle(pendingTitle);
            }
        }
        lastChUrl = m.chUrl != null ? m.chUrl : "";
        lastChName = m.ch != null ? m.ch : "";
        if (chName != null) {
            chName.setText(m.ch.length() > 0 ? m.ch : "Unknown channel");
        }
        if (chSubs != null) {
            chSubs.setText(m.subs);
        }
        if (avatarImg != null) {
            if (m.avatar != null && m.avatar.length() > 0) {
                loadThumb(avatarImg, m.avatar);
            } else {
                avatarImg.setImageDrawable(null);
            }
        }
        if (belowMeta != null) {
            StringBuilder sb = new StringBuilder();
            if (m.views.length() > 0) {
                sb.append(m.views);
            }
            if (m.date.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  \u00b7  ");
                }
                sb.append(m.date);
            }
            belowMeta.setText(sb.toString());
        }
        if (descView != null) {
            if (m.desc != null && m.desc.trim().length() > 0) {
                descView.setText(m.desc.trim());
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setVisibility(View.GONE);
            }
        }
        if (likeCnt != null) {
            likeCnt.setText(m.likes);
        }
    }

    /** থ্রি-ডট মেনু (ইউটিউব-মতো অতিরিক্ত অপশন)। */
    private void showMoreMenu() {
        java.util.ArrayList<String> items = new java.util.ArrayList<String>();
        items.add("Save to Watch Later");
        items.add("Save to playlist");
        items.add("Download");
        items.add("Sleep timer");
        items.add("Playback speed");
        items.add("Captions: " + (!ccOn ? "Off" : (capCues.isEmpty() ? "Loading" : "On")));
        if (chaps.size() >= 3) {
            items.add("Chapters (" + chaps.size() + ")");
        }
        items.add("Equalizer");
        items.add("Report");
        final boolean hasChap = chaps.size() >= 3;
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    toggleWatchLater();
                                } else if (w == 1) {
                                    showPlaylistDialog();
                                } else if (w == 2) {
                                    showSaveMenu();
                                } else if (w == 3) {
                                    showSleepDialog();
                                } else if (w == 4) {
                                    showSpeedMenu();
                                } else if (w == 5) {
                                    showCcMenu();
                                } else if (hasChap && w == 6) {
                                    showChapMenu();
                                } else if (w == 6 + (hasChap ? 1 : 0)) {
                                    showEqMenu();
                                } else {
                                    toast("Sign in to report on YouTube");
                                }
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** চ্যানেল-প্রোফাইল/নামে ট্যাপ: ব্রাউজারে চ্যানেল-পেজ খোলে। */
    private void openChannel(String url) {
        if (url == null || url.length() == 0) {
            toast("Channel page unavailable");
            return;
        }
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("openUrl", url));
        } catch (Throwable t) {
            toast("Could not open channel");
        }
    }

    /** কমেন্ট-সেকশন: ভাঁজ-খোলা (ডিফল্ট সংক্ষিপ্ত "Comments · N" শিরো-সারি, ট্যাপে খোলে)। */
    private void fillComments(List<Resolver.Cmt> list) {
        if (cmtBox == null) {
            return;
        }
        cmtBox.removeAllViews();
        if (list.isEmpty()) {
            return; // কমেন্ট নেই/বন্ধ — সেকশনই দেখাই না
        }
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(12), dp(12), dp(12), dp(6));
        TextView ht = new TextView(this);
        ht.setText("Comments");
        ht.setTextColor(0xFFF2F2F2);
        ht.setTextSize(14);
        ht.setTypeface(null, 1);
        hdr.addView(ht);
        TextView hc = new TextView(this);
        hc.setText(list.size() >= 20 ? "  \u00b7  20+" : "  \u00b7  " + list.size());
        hc.setTextColor(0xFF9A9A9A);
        hc.setTextSize(13);
        hdr.addView(hc);
        TextView ha = new TextView(this);
        ha.setText(cmtOpen ? "  \u25B4" : "  \u25BE");
        ha.setTextColor(0xFF9A9A9A);
        ha.setTextSize(12);
        hdr.addView(ha);
        hdr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cmtOpen = !cmtOpen;
                if (cmtList != null) {
                    cmtList.setVisibility(cmtOpen ? View.VISIBLE : View.GONE);
                }
                TextView t = (TextView) ((LinearLayout) v).getChildAt(2);
                t.setText(cmtOpen ? "  \u25B4" : "  \u25BE");
            }
        });
        cmtBox.addView(hdr);

        cmtList = new LinearLayout(this);
        cmtList.setOrientation(LinearLayout.VERTICAL);
        cmtList.setVisibility(cmtOpen ? View.VISIBLE : View.GONE);
        for (Resolver.Cmt c : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(6), dp(12), dp(6));
            TextView meta = new TextView(this);
            String mm = (c.by.length() > 0 ? c.by : "Unknown");
            if (c.when.length() > 0) {
                mm += "  \u00b7  " + c.when;
            }
            if (c.likes.length() > 0) {
                mm += "  \u00b7  " + c.likes + " likes";
            }
            meta.setText(mm);
            meta.setTextColor(0xFF9A9A9A);
            meta.setTextSize(11);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(meta);
            TextView tx = new TextView(this);
            tx.setText(c.text);
            tx.setTextColor(0xFFF2F2F2);
            tx.setTextSize(13);
            row.addView(tx);
            cmtList.addView(row);
        }
        cmtBox.addView(cmtList);
    }

    /** রিলেটেড আইটেমে ট্যাপ: ঐ ভিডিওটি এই প্লেয়ারেই খোলে। */    /** রিলেটেড আইটেমে ট্যাপ: ঐ ভিডিওটি এই প্লেয়ারেই খোলে। */
    /** ভিডিও শেষ হলে রিলেটেড-তালিকার প্রথম (বর্তমানটা বাদে) ভিডিও চালু। */
    private void autoNext() {
        try {
            if (isFinishing() || loopOn) {
                return;
            }
            for (Resolver.Rel r : relList) {
                if (r != null && r.id != null && r.id.length() == 11
                        && !r.id.equals(videoId)) {
                    showHud("Up next");
                    openRelated(r.id, r.title);
                    return;
                }
            }
        } catch (Throwable t) {
        }
    }

    private void openRelated(String id, String title) {
        if (isFinishing() || id == null || id.equals(videoId)) {
            return;
        }
        relGen++;
        relBox.removeAllViews();
        if (belowScroll != null) {
            belowScroll.smoothScrollTo(0, 0);
        }
        startYoutube(id, title);
    }

    /** থাম্বনেইল লোডার: মেমোরি-ক্যাশ + থ্রেড-পুল, ট্যাগ-মিলিয়ে সেট। */
    private static final java.util.HashMap<String, android.graphics.Bitmap> thumbCache =
            new java.util.HashMap<>();
    private static final java.util.concurrent.Executor thumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private void loadThumb(final android.widget.ImageView into, final String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        android.graphics.Bitmap got = thumbCache.get(url);
        if (got != null) {
            into.setImageBitmap(got);
            return;
        }
        into.setTag(url);
        into.setImageDrawable(null);
        thumbPool.execute(new Runnable() {
            @Override
            public void run() {
                android.graphics.Bitmap b = null;
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0");
                    java.io.InputStream in = c.getInputStream();
                    try {
                        b = android.graphics.BitmapFactory.decodeStream(in);
                    } finally {
                        in.close();
                    }
                } catch (Throwable t) {
                    // থাম্বনেইল ঐচ্ছিক — না পেলে ধূসর ঘর থাকবে
                }
                if (b == null) {
                    return;
                }
                synchronized (thumbCache) {
                    if (thumbCache.size() > 60) {
                        thumbCache.clear();
                    }
                    thumbCache.put(url, b);
                }
                final android.graphics.Bitmap fb = b;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(into.getTag())) {
                            into.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
    }

    /** অডিও ↔ ভিডিও মোড টগল — অবস্থান অটুট, ভিডিও-ট্র্যাক চালু/বন্ধ। */
    private void toggleAudioMode() {
        try {
        PlayerService s = PlayerService.sSelf;
        if (s == null || player == null) {
            return;
        }
        if (!isYoutube || ytAudio == null) {
            toast("Audio mode needs a separate audio track");
            return;
        }
        long pos = player.getCurrentPosition();
        if (!audioOnly) {
            String v = currentVideoUrl();
            if (v == null) {
                toast("Video stream unavailable");
                return;
            }
            lastVideoUrl = v; // প্রস্থানে একই কোয়ালিটিতে ফেরার জন্য মনে থাকে
            s.switchToAudio(ytAudio, pos);
            applyAudioUi(true);
            showHud("Audio mode");
        } else {
            String v = lastVideoUrl != null ? lastVideoUrl : currentVideoUrl();
            if (v == null) {
                toast("Video stream unavailable");
                return;
            }
            s.switchTo(v, ytAudio, pos);
            applyAudioUi(false);
            showHud("Video mode");
        }
        } catch (Throwable t) {
            StreamStore.note("audioToggle err " + t);
            toast("Audio mode error");
        }
    }

    /** বর্তমান কোয়ালিটির ভিডিও-URL (নির্বাচন মনে না থাকলে সেরা-শুরু)। */
    private String currentVideoUrl() {
        if (ytVars == null || ytVars.isEmpty()) {
            return null;
        }
        for (Resolver.Var v : ytVars) {
            if (v.h == chosenHeight) {
                return v.url;
            }
        }
        // নির্বাচন না মিললে: 360-নীতি (ডিফল্টের সাথে সামঞ্জস্য)
        Resolver.Var best = pickNear(ytVars, defQ());
        return (best != null ? best : ytVars.get(0)).url;
    }

    /** অডিও-মোডের UI-দশা। */
    private void applyAudioUi(boolean on) {
        audioOnly = on;
        if (surfaceBox != null) {
            surfaceBox.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (audioView != null) {
            audioView.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (btnAudio != null) {
            btnAudio.setIconTint(on ? 0xFF34D399 : 0xFFF2F2F2);
        }
        if (audioTitle != null && pendingTitle != null) {
            audioTitle.setText(pendingTitle);
        }
        if (on) {
            showControls(true);
        }
    }

    /** স্লিপ-টাইমার ডায়াল। */
    private void showSleepDialog() {
        final long[] mins = {5, 10, 15, 30, 60};
        String[] items = new String[mins.length + 1];
        for (int i = 0; i < mins.length; i++) {
            items[i] = mins[i] + " minutes";
        }
        items[mins.length] = "Off";
        new AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PlayerService s = PlayerService.sSelf;
                        if (s == null) {
                            return;
                        }
                        if (w < mins.length) {
                            s.setSleep(mins[w] * 60000L);
                            showHud("Sleep " + mins[w] + "m");
                        } else {
                            s.setSleep(0);
                            showHud("Sleep off");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Watch Later টগল (লোকাল, কোনো অ্যাকাউন্ট নয়)। */
    private void toggleWatchLater() {
        if (!isYoutube || videoId.length() == 0) {
            toast("Not a YouTube video");
            return;
        }
        if (Library.inWl(this, videoId)) {
            Library.removeWl(this, videoId);
            btnWl.setIconTint(0xFFF2F2F2);
            showHud("Removed from Watch Later");
        } else {
            Library.addWl(this, videoId, pendingTitle);
            btnWl.setIconTint(0xFF34D399);
            showHud("Added to Watch Later");
        }
    }

    /** প্লেলিস্টে সেভ: তালিকা + "New playlist"। */
    private void showPlaylistDialog() {
        if (!isYoutube || videoId.length() == 0) {
            toast("Not a YouTube video");
            return;
        }
        final java.util.ArrayList<String> names = Library.playlists(this);
        String[] items = new String[names.size() + 1];
        for (int i = 0; i < names.size(); i++) {
            items[i] = names.get(i);
        }
        items[names.size()] = "New playlist";
        new AlertDialog.Builder(this)
                .setTitle("Save to playlist")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w < names.size()) {
                            String n = names.get(w);
                            boolean ok = Library.addToPl(PlayerActivity.this, n,
                                    videoId, pendingTitle);
                            toast(ok ? "Saved to " + n : "Already in " + n);
                        } else {
                            promptNewPlaylist();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptNewPlaylist() {
        final EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setSingleLine();
        FrameLayout box = new FrameLayout(this);
        int pad = dp(16);
        box.setPadding(pad, dp(6), pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(box)
                .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String n = input.getText().toString().trim();
                        if (n.length() == 0) {
                            return;
                        }
                        Library.createPl(PlayerActivity.this, n);
                        boolean ok = Library.addToPl(PlayerActivity.this, n,
                                videoId, pendingTitle);
                        toast(ok ? "Saved to " + n : "Already in " + n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** শেয়ার: শিরোনাম + youtu.be-লিংক (স্পষ্ট ইউজার-অ্যাকশনেই খোলে)। */
    private void shareVideo() {
        try {
            String text;
            if (isYoutube && videoId.length() > 0) {
                text = (pendingTitle != null && pendingTitle.length() > 0
                        ? pendingTitle + " \u2014 " : "") + "https://youtu.be/" + videoId;
            } else {
                PlayerService s = PlayerService.sSelf;
                String u = s != null ? s.currentUrl() : "";
                if (u.length() == 0) {
                    toast("Nothing to share");
                    return;
                }
                text = u;
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Throwable t) {
            toast("Could not share");
        }
    }

    private void enterPip() {
        if (audioOnly) {
            toast("Audio mode");
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            toast("Popup not supported on this device");
            return;
        }
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (videoW > 0 && videoH > 0) {
                Rational r = new Rational(videoW, videoH);
                if (r.floatValue() >= 0.418f && r.floatValue() <= 2.39f) {
                    b.setAspectRatio(r);
                }
            }
            enterPictureInPictureMode(b.build());
        } catch (Throwable t) {
            toast("Cannot open popup");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && playRoot != null && player != null
                && player.getPlayWhenReady() && !audioOnly) {
            try {
                enterPip();
            } catch (Throwable t) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip) {
        super.onPictureInPictureModeChanged(isInPip);
        if (isInPip) {
            showControls(false);
        } else if (playRoot != null) {
            showControls(true);
        }
    }

    private void fitSurface() {
        if (surfaceBox == null || videoW <= 0 || videoH <= 0) {
            return;
        }
        final int bw = surfaceBox.getWidth();
        final int bh = surfaceBox.getHeight();
        if (bw <= 0 || bh <= 0) {
            surfaceBox.post(new Runnable() {
                @Override
                public void run() {
                    fitSurface();
                }
            });
            return;
        }
        float ar = videoW / (float) videoH;
        int w = bw;
        int hgt = (int) (w / ar);
        if (fillMode) {
            // স্ক্রিন পূরণ (প্রান্ত-ক্রপ)
            if (hgt < bh) {
                hgt = bh;
                w = (int) (hgt * ar);
            }
        } else {
            if (hgt > bh) {
                hgt = bh;
                w = (int) (hgt * ar);
            }
        }
        baseW = w;
        baseH = hgt;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt, Gravity.CENTER);
        surface.setLayoutParams(lp);
        applyZoom();
    }

    // ---------- ভিডিও জুম (পিঞ্চ) + প্যান ----------

    /** জুম + প্যান SurfaceView-এ প্রয়োগ; ভিডিও যাতে হারিয়ে না যায়, প্যান সীমিত। */
    private void applyZoom() {
        if (surface == null || surfaceBox == null) {
            return;
        }
        try {
            surface.setScaleX(zoom);
            surface.setScaleY(zoom);
            surface.setPivotX(surface.getWidth() / 2f);
            surface.setPivotY(surface.getHeight() / 2f);

            int bw = surfaceBox.getWidth();
            int bh = surfaceBox.getHeight();
            float ovX = Math.max(0f, (baseW * zoom - bw) / 2f);
            float ovY = Math.max(0f, (baseH * zoom - bh) / 2f);
            if (panX > ovX) {
                panX = ovX;
            }
            if (panX < -ovX) {
                panX = -ovX;
            }
            if (panY > ovY) {
                panY = ovY;
            }
            if (panY < -ovY) {
                panY = -ovY;
            }
            surface.setTranslationX(panX);
            surface.setTranslationY(panY);
        } catch (Throwable t) {
        }
    }

    /**
     * ভলিউম প্রয়োগ: সিস্টম-স্ট্রিম কেবল পূর্ণসংখ্যা ধাপ নেয় (সাধারণত ১৫টি),
     * তাই ধাপের মাঝের অংশটা ExoPlayer-এর গেইন দিয়ে পূরণ করা হয় — ফলে
     * শব্দ ধাপে ধাপে না লাফিয়ে মসৃণভাবে বাড়ে-কমে।
     */
    private void applyVolume(float acc, int max) {
        if (am == null || max <= 0) {
            return;
        }
        try {
            int step = (int) Math.ceil(acc - 0.0001f); // ওপরের ধাপ = হেডরুম
            if (step < 0) {
                step = 0;
            }
            if (step > max) {
                step = max;
            }
            if (step != volStepWritten) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
                volStepWritten = step;
            }
            float gain = step > 0 ? Math.min(1f, acc / step) : 0f;
            PlayerService.setUserGain(gain);
        } catch (Throwable t) {
        }
    }

    /**
     * আঙুল তোলার সময়: কাছের ধাপে নামিয়ে গেইন ১.০-এ ফেরানো। তখন হার্ডওয়্যার
     * ভলিউম-বোতাম আবার স্বাভাবিকভাবে কাজ করে (কোনো গেইন-ওভারলে থাকে না)।
     */
    private void settleVolumeGesture() {
        try {
            if (volAcc >= 0f) {
                int max = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15;
                if (max <= 0) {
                    max = 15;
                }
                int landed = Math.round(volAcc);
                if (landed < 0) {
                    landed = 0;
                }
                if (landed > max) {
                    landed = max;
                }
                if (am != null) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, landed, 0);
                }
                volStepWritten = landed;
            }
        } catch (Throwable t) {
        } finally {
            PlayerService.setUserGain(1f);
            volAcc = -1f;
            brightAcc = -1f;
            volStepWritten = -1;
        }
    }

    /** জুম ১০০%-এ ফেরানো; সত্য = আগে জুম-ইন করা ছিল। */
    private boolean resetZoom() {
        if (zoom <= 1.001f && Math.abs(panX) < 0.5f && Math.abs(panY) < 0.5f) {
            return false;
        }
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        applyZoom();
        return true;
    }

    /** HUD ছাড়াই জুম রিসেট (নতুন ভিডিও / ঘোরানোর সময়)। */
    private void zoomResetQuiet() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
    }

    private void refreshHud() {
        if (player != null) {
            try {
                int vvw = player.getVideoSize().width;
                int vvh = player.getVideoSize().height;
                if (!shortsLock && vvw > 0 && vvh > 0 && (vvh > vvw) != vertVideo) {
                    vertVideo = vvh > vvw;
                    applyVideoLayout();
                }
            } catch (Throwable t) {
            }
        }
        if (player == null || playRoot == null) {
            return;
        }
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean buffering = player.getPlaybackState() == Player.STATE_BUFFERING;
        playRoot.setKeepScreenOn(player.getPlayWhenReady());
        loading.setVisibility(buffering ? View.VISIBLE : View.GONE);
        if (statusText != null && !buffering && statusText.getText().length() > 0
                && player.getPlaybackState() != Player.STATE_IDLE) {
            setStatus("");
        }
        if (btnPlay != null) {
            btnPlay.setState(player.getPlayWhenReady() && ready);
        }
        if (audioPlay != null) {
            audioPlay.setState(player.getPlayWhenReady() && ready);
        }
        if (!dragging && seek != null) {
            long d = player.getDuration();
            long p = player.getCurrentPosition();
            if (d > 0) {
                seek.setMax((int) d);
                seek.setProgress((int) p);
                seek.setSecondaryProgress((int) player.getBufferedPosition());
                vTime.setText(fmt((int) p));
                vDur.setText(fmt((int) d));
            } else {
                seek.setMax(0);
                seek.setProgress(0);
                vTime.setText("0:00");
                vDur.setText("0:00");
            }
        }
    }

    // ---------- quality ----------

    private static final class QOpt {
        String label;
        int height; // 0 = auto
        Tracks.Group group;
        int trackIndex;
    }

    /** ডাউনলোড-মেনু: কোয়ালিটি তালিকা + Audio-only, বা সরাসরি-লিংক সেভ। */
    /** ডাউনলোড-প্রবেশ: অটো চালু হলে জিজ্ঞেস ছাড়াই ডিফল্টে, নাহলে SnapTube-ধাঁচের শিট। */
    private void showSaveMenu() {
        if (isYoutube && ytVars != null && !ytVars.isEmpty()) {
            android.content.SharedPreferences pr = getSharedPreferences("mtw", 0);
            if (pr.getBoolean("dlAuto", false)) {
                autoDownload();
            } else {
                showDownloadSheet();
            }
            return;
        }
        PlayerService s = PlayerService.sSelf;
        if (s != null && s.hasItem() && s.currentUrl().length() > 0) {
            final String u = s.currentUrl();
            final String t = s.currentTitle();
            new AlertDialog.Builder(this)
                    .setTitle("Download")
                    .setItems(new String[]{"Save video"}, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            startService(new Intent(PlayerActivity.this, DownloadService.class)
                                    .setAction(DownloadService.ACT_START)
                                    .putExtra(DownloadService.EXTRA_TITLE, t)
                                    .putExtra(DownloadService.EXTRA_DIRECT, u));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        toast("Nothing to save");
    }

    /** অটো-ডাউনলোড: ডিফল্ট কোয়ালিটির সবচেয়ে কাছের ভ্যারিয়েন্ট। */
    private void autoDownload() {
        int want = getSharedPreferences("mtw", 0).getInt("dlVQ", 720);
        Resolver.Var pick = nearestVar(ytVars, want);
        if (pick == null) {
            toast("Nothing to save");
            return;
        }
        startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACT_START)
                .putExtra(DownloadService.EXTRA_TITLE, pendingTitle)
                .putExtra(DownloadService.EXTRA_VID, videoId)
                .putExtra(DownloadService.EXTRA_CH, lastChName)
                .putExtra(DownloadService.EXTRA_V, pick.url)
                .putExtra(DownloadService.EXTRA_A, ytAudio)
                .putExtra(DownloadService.EXTRA_LABEL, pick.h + "p"));
        DownloadsSheet.show(this);
    }

    private static Resolver.Var nearestVar(List<Resolver.Var> vars, int want) {
        Resolver.Var best = null;
        int bd = Integer.MAX_VALUE;
        for (Resolver.Var v : vars) {
            int d = Math.abs(v.h - want);
            if (best == null || d < bd || (d == bd && v.h < best.h)) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    private static java.util.ArrayList<String> capsArr(
            java.util.ArrayList<Resolver.Cap> caps) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (caps != null) {
            for (Resolver.Cap c : caps) {
                out.add((c.lang == null ? "" : c.lang) + "|"
                        + (c.label == null ? "" : c.label) + "|" + c.url);
            }
        }
        return out;
    }

    // ---------- ডাউনলোড-শিট (SnapTube-ধাঁচ, অনুমোদিত ডিজাইন v2) ----------

    private static final java.util.concurrent.Executor sizePool =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private void showDownloadSheet() {
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, dp(12));
        android.graphics.drawable.GradientDrawable bgd =
                new android.graphics.drawable.GradientDrawable();
        bgd.setColor(0xFF1B1B1D);
        bgd.setCornerRadii(new float[]{dp(16), dp(16), 0, 0, 0, 0, 0, 0});
        box.setBackground(bgd);
        sv.addView(box);
        sv.setVerticalScrollBarEnabled(false);

        // গ্র্যাবার
        View grab = new View(this);
        grab.setBackgroundColor(0xFF4A4A4E);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(4);
        glp.bottomMargin = dp(8);
        box.addView(grab, glp);

        // হেডার: থাম্বনেইল + টাইটেল + চ্যানেল · দৈর্ঘ্য
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView th = new android.widget.ImageView(this);
        th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable thg =
                new android.graphics.drawable.GradientDrawable();
        thg.setColor(0xFF26262A);
        thg.setCornerRadius(dp(8));
        th.setBackground(thg);
        th.setClipToOutline(true);
        head.addView(th, new LinearLayout.LayoutParams(dp(100), dp(56)));
        LinearLayout hcol = new LinearLayout(this);
        hcol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(0, -2, 1f);
        hclp.leftMargin = dp(12);
        TextView htitle = new TextView(this);
        htitle.setText(pendingTitle != null ? pendingTitle : "");
        htitle.setTextColor(0xFFF2F2F2);
        htitle.setTextSize(13);
        htitle.setTypeface(null, 1);
        htitle.setMaxLines(2);
        htitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hcol.addView(htitle);
        TextView hsub = new TextView(this);
        long durSec = 0;
        try {
            durSec = player != null ? player.getDuration() / 1000 : 0;
        } catch (Throwable t) {
        }
        String sub = lastChName != null && lastChName.length() > 0 ? lastChName : "";
        if (durSec > 0) {
            sub = (sub.length() > 0 ? sub + " \u00B7 " : "") + fmtDur(durSec);
        }
        hsub.setText(sub);
        hsub.setTextColor(0xFF9A9A9F);
        hsub.setTextSize(11);
        hsub.setMaxLines(1);
        hsub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hcol.addView(hsub);
        head.addView(hcol, hclp);
        box.addView(head);
        if (videoId != null && videoId.length() == 11) {
            loadThumb(th, "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
        }
        box.addView(dividerRow());

        // কোয়ালিটি-তালিকা (ছোট→বড়)
        java.util.ArrayList<Resolver.Var> fv = new java.util.ArrayList<Resolver.Var>(ytVars);
        java.util.Collections.sort(fv, new java.util.Comparator<Resolver.Var>() {
            @Override
            public int compare(Resolver.Var a2, Resolver.Var b2) {
                return a2.h - b2.h;
            }
        });

        box.addView(sectionLabel("VIDEO \u00B7 MP4"));
        for (int i = 0; i < fv.size(); i++) {
            final Resolver.Var v = fv.get(i);
            TextView size = new TextView(this);
            size.setTextColor(0xFF9A9A9F);
            size.setTextSize(12);
            size.setText("\u2014");
            box.addView(tapRow(v.h + "p", v.h >= 720, size, new View.OnClickListener() {
                @Override
                public void onClick(View w) {
                    startVideoDownload(v);
                }
            }));
            if (i < fv.size() - 1) {
                box.addView(hairline());
            }
            fetchSizeInto(size, v.url, ytAudio); // ভিডিও+অডিও যোগফল
        }

        box.addView(sectionLabel("AUDIO \u00B7 MP3"));
        int[] kbpsList = {128, 192, 320};
        for (int k = 0; k < kbpsList.length; k++) {
            final int kb = kbpsList[k];
            TextView size = new TextView(this);
            size.setTextColor(0xFF9A9A9F);
            size.setTextSize(12);
            size.setText(durSec > 0 ? fmtMB((long) (durSec * kb * 1000L / 8)) : "\u2014");
            box.addView(tapRow(kb + " kbps", false, size, new View.OnClickListener() {
                @Override
                public void onClick(View w) {
                    startMp3Download(kb);
                }
            }));
            if (k < kbpsList.length - 1) {
                box.addView(hairline());
            }
        }

        // সাবটাইটেল
        java.util.ArrayList<Resolver.Cap> caps = ytCaps;
        if (caps != null && !caps.isEmpty()) {
            box.addView(sectionLabel("SUBTITLES \u00B7 SRT"));
            for (int c = 0; c < caps.size(); c++) {
                final Resolver.Cap cap = caps.get(c);
                TextView tag = new TextView(this);
                tag.setTextColor(0xFF9A9A9F);
                tag.setTextSize(12);
                tag.setText("SRT");
                box.addView(tapRow(cap.label != null && cap.label.length() > 0
                        ? cap.label : cap.lang, false, tag, new View.OnClickListener() {
                    @Override
                    public void onClick(View w) {
                        startSrtDownload(cap);
                    }
                }));
                if (c < caps.size() - 1) {
                    box.addView(hairline());
                }
            }
        }

        // অটো-ডাউনলোড টগল
        box.addView(dividerRow());
        final android.content.SharedPreferences pr = getSharedPreferences("mtw", 0);
        LinearLayout auto1 = new LinearLayout(this);
        auto1.setOrientation(LinearLayout.HORIZONTAL);
        auto1.setGravity(Gravity.CENTER_VERTICAL);
        TextView at = new TextView(this);
        at.setText("Auto-download");
        at.setTextColor(0xFFF2F2F2);
        at.setTextSize(14);
        auto1.addView(at, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView adef = new TextView(this);
        adef.setTextColor(0xFF9A9A9F);
        adef.setTextSize(12);
        adef.setText("Default: " + pr.getInt("dlVQ", 720) + "p");
        auto1.addView(adef);
        auto1.setPadding(0, dp(10), 0, 0);
        box.addView(auto1);
        LinearLayout auto2 = new LinearLayout(this);
        auto2.setOrientation(LinearLayout.HORIZONTAL);
        auto2.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(pr.getBoolean("dlAuto", false));
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(-2, -2);
        swlp.rightMargin = dp(10);
        auto2.addView(sw, swlp);
        TextView hint = new TextView(this);
        hint.setText("When on, downloads start at the default quality without asking."
                + "  Change it in Settings.");
        hint.setTextColor(0xFF6F6F75);
        hint.setTextSize(10);
        hint.setMaxLines(2);
        auto2.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        auto2.setPadding(0, dp(6), 0, 0);
        box.addView(auto2);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                pr.edit().putBoolean("dlAuto", on).apply();
                adef.setText("Default: " + pr.getInt("dlVQ", 720) + "p");
            }
        });

        // ডায়ালগ (নিচ-শিট)
        final android.app.Dialog dg = new android.app.Dialog(this);
        dg.setContentView(sv);
        dg.setCanceledOnTouchOutside(true);
        android.view.Window win = dg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(-1, -2);
            win.setGravity(Gravity.BOTTOM);
            win.setDimAmount(0.55f);
        }
        dg.show();
        if (win != null) {
            win.setLayout(-1, -2);
        }
        // উচ্চতা-সীমা: স্ক্রিনের ৭২%
        final float maxH = getResources().getDisplayMetrics().heightPixels * 0.72f;
        sv.post(new Runnable() {
            @Override
            public void run() {
                if (sv.getChildAt(0) != null
                        && sv.getChildAt(0).getHeight() > maxH) {
                    sv.getLayoutParams().height = (int) maxH;
                    sv.requestLayout();
                }
            }
        });
    }

    private View dividerRow() {
        View v = new View(this);
        v.setBackgroundColor(0xFF2A2A2D);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(0xFF242427);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        return v;
    }

    private View sectionLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFF8E8E93);
        v.setTextSize(10);
        v.setLetterSpacing(0.15f);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }

    private View tapRow(String title, boolean hd, View right, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFFF2F2F2);
        tv.setTextSize(15);
        row.addView(tv);
        if (hd) {
            TextView badge = new TextView(this);
            badge.setText("HD");
            badge.setTextColor(0xFF34D399);
            badge.setTextSize(9);
            badge.setTypeface(null, 1);
            android.graphics.drawable.GradientDrawable bgr =
                    new android.graphics.drawable.GradientDrawable();
            bgr.setColor(0x2E34D399);
            bgr.setCornerRadius(dp(4));
            badge.setBackground(bgr);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(8);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            row.addView(badge, blp);
        }
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (right != null) {
            row.addView(right);
        }
        row.setOnClickListener(click);
        return row;
    }

    private static String fmtMB(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String fmtDur(long sec) {
        long m = sec / 60;
        long ss = sec % 60;
        if (m >= 60) {
            return m / 60 + ":" + String.format(java.util.Locale.US, "%02d", m % 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", ss);
        }
        return m + ":" + String.format(java.util.Locale.US, "%02d", ss);
    }

    /** (u + extra) বাইট অ্যাসিঙ্কে এনে tv-তে বসায়; ব্যর্থ হলে "\u2014"। */
    private void fetchSizeInto(final TextView tv, final String u, final String extra) {
        sizePool.execute(new Runnable() {
            @Override
            public void run() {
                final long b = urlSize(u) + (extra != null && extra.length() > 0
                        ? urlSize(extra) : 0);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(b > 0 ? fmtMB(b) : "\u2014");
                    }
                });
            }
        });
    }

    private long urlSize(String u) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent",
                    "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip");
            long l = c.getContentLength();
            c.disconnect();
            if (l > 0) {
                return l;
            }
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent",
                    "com.google.android.youtube/20.05.38 (Linux; U; Android 11) gzip");
            String cr = c.getHeaderField("Content-Range");
            c.disconnect();
            if (cr != null && cr.contains("/")) {
                return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            }
        } catch (Throwable t) {
        }
        return -1;
    }

    private void startVideoDownload(Resolver.Var v) {
        startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACT_START)
                .putExtra(DownloadService.EXTRA_TITLE, pendingTitle)
                .putExtra(DownloadService.EXTRA_VID, videoId)
                .putExtra(DownloadService.EXTRA_CH, lastChName)
                .putExtra(DownloadService.EXTRA_V, v.url)
                .putExtra(DownloadService.EXTRA_A, ytAudio)
                .putExtra(DownloadService.EXTRA_LABEL, v.h + "p"));
        DownloadsSheet.show(this);
    }

    private void startMp3Download(int kbps) {
        if (ytAudio == null || ytAudio.length() == 0) {
            toast("Audio not available");
            return;
        }
        startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACT_START)
                .putExtra(DownloadService.EXTRA_TITLE, pendingTitle)
                .putExtra(DownloadService.EXTRA_VID, videoId)
                .putExtra(DownloadService.EXTRA_CH, lastChName)
                .putExtra(DownloadService.EXTRA_A, ytAudio)
                .putExtra(DownloadService.EXTRA_MP3, kbps)
                .putExtra(DownloadService.EXTRA_LABEL, kbps + "kbps"));
        DownloadsSheet.show(this);
    }

    private void startSrtDownload(Resolver.Cap cap) {
        startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACT_START)
                .putExtra(DownloadService.EXTRA_TITLE, pendingTitle)
                .putExtra(DownloadService.EXTRA_VID, videoId)
                .putExtra(DownloadService.EXTRA_CH, lastChName)
                .putExtra(DownloadService.EXTRA_SRT, cap.url)
                .putExtra(DownloadService.EXTRA_LABEL,
                        (cap.lang != null && cap.lang.length() > 0 ? cap.lang : "subtitle")));
        DownloadsSheet.show(this);
    }

    private void startTick() {
        if (tickOn) {
            return;
        }
        tickOn = true;
        h.post(tick);
    }

    private void stopTick() {
        tickOn = false;
        h.removeCallbacks(tick);
    }

    /** রিজলভ-সফল পর: সেগমেন্ট+ডিসলাইক আনা (ব্যাকগ্রাউন্ডে)। */
    private void fetchSbAndDis(final String vid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.ArrayList<Object[]> got = Resolver.sbSegs(vid);
                final long dis = Resolver.fetchDislikes(vid);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sbList.clear();
                            for (Object[] o : got) {
                                sbList.add(new SbS((Long) o[0], (Long) o[1], (String) o[2]));
                            }
                            if (disLine != null) {
                                if (dis >= 0) {
                                    disLine.setText("Dislikes: " + fmtCount(dis));
                                    disLine.setVisibility(View.VISIBLE);
                                } else {
                                    disLine.setVisibility(View.GONE);
                                }
                            }
                        } catch (Throwable t) {
                        }
                    }
                });
            }
        }).start();
    }

    private static String fmtCount(long n) {
        if (n >= 1000000) {
            return String.format(java.util.Locale.US, "%.1fM", n / 1000000f);
        }
        if (n >= 1000) {
            return String.format(java.util.Locale.US, "%.1fK", n / 1000f);
        }
        return String.valueOf(n);
    }

    private void showSpeedMenu() {
        if (player == null) {
            toast("Nothing playing");
            return;
        }
        final float[] sp = new float[]{0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] nm = new String[sp.length];
        for (int i = 0; i < sp.length; i++) {
            nm[i] = (sp[i] == (long) sp[i]
                    ? String.valueOf((long) sp[i]) : String.valueOf(sp[i])) + "x"
                    + (sp[i] == curSpeed ? "  (current)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Playback speed")
                .setItems(nm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            curSpeed = sp[w];
                            player.setPlaybackParameters(
                                    new androidx.media3.common.PlaybackParameters(sp[w]));
                            showHud(sp[w] + "x speed");
                        } catch (Throwable t) {
                            toast("Speed not available");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCcMenu() {
        if (ytCaps == null || ytCaps.isEmpty()) {
            toast("No captions for this video");
            return;
        }
        String[] items = new String[ytCaps.size() + 1];
        items[0] = "Off";
        for (int i = 0; i < ytCaps.size(); i++) {
            items[i + 1] = ytCaps.get(i).label + (ccOn && !capCues.isEmpty()
                    && ytCaps.get(i).lang.equals(ccLang) ? "  (on)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Captions")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) {
                            ccOn = false;
                            showHud("Captions off");
                            return;
                        }
                        fetchCues(ytCaps.get(w - 1));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String ccLang = "";

    private void fetchCues(final Resolver.Cap cap) {
        setStatus("Loading captions...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.ArrayList<Cue> got = new java.util.ArrayList<Cue>();
                String err = null;
                try {
                    String u = cap.url + (cap.url.contains("fmt=") ? "" : "&fmt=vtt");
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 10) Wtube");
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    java.io.InputStream in = c.getInputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        bo.write(b, 0, n);
                    }
                    in.close();
                    c.disconnect();
                    parseVtt(bo.toString("UTF-8"), got);
                    if (got.isEmpty()) {
                        err = "No caption cues found";
                    }
                } catch (Throwable t) {
                    err = "Caption load failed";
                }
                final String ferr = err;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("");
                        if (ferr != null) {
                            toast(ferr);
                            return;
                        }
                        capCues.clear();
                        capCues.addAll(got);
                        ccLang = cap.lang != null ? cap.lang : "";
                        ccOn = true;
                        showHud("Captions: " + cap.label);
                    }
                });
            }
        }).start();
    }

    /** হালকা VTT-পার্সার: "-->" সারি + পরের টেক্সট-ব্লক = কিউ। */
    private static void parseVtt(String body, java.util.ArrayList<Cue> out) {
        String[] lines = body.replace("\r", "").split("\n");
        int i = 0;
        while (i < lines.length) {
            if (lines[i].contains("-->")) {
                long[] se = parseTsPair(lines[i]);
                StringBuilder sb = new StringBuilder();
                int k = i + 1;
                while (k < lines.length && lines[k].trim().length() > 0
                        && !lines[k].contains("-->")) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(stripTags(lines[k]));
                    k++;
                }
                if (se != null && se[1] > se[0] && sb.length() > 0) {
                    out.add(new Cue(se[0], se[1], sb.toString()));
                }
                i = k;
            } else {
                i++;
            }
        }
    }

    private static long[] parseTsPair(String ln) {
        try {
            String[] pr = ln.split("-->");
            return new long[]{parseTs(pr[0]), parseTs(pr[1].split(" ")[0])};
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseTs(String t) {
        t = t.trim().replace(',', '.');
        String[] p = t.split(":");
        float sec = Float.parseFloat(p[p.length - 1]);
        long mn = p.length >= 2 ? Long.parseLong(p[p.length - 2]) : 0;
        long hr = p.length >= 3 ? Long.parseLong(p[p.length - 3]) : 0;
        return (long) ((hr * 3600 + mn * 60 + sec) * 1000f);
    }

    private static String stripTags(String x) {
        return x.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"");
    }

    private void showChapMenu() {
        if (chaps.isEmpty()) {
            toast("No chapters");
            return;
        }
        long pos = player != null ? player.getCurrentPosition() : 0;
        String[] items = new String[chaps.size()];
        int cur = 0;
        for (int i = 0; i < chaps.size(); i++) {
            Resolver.Chap c = chaps.get(i);
            items[i] = fmtMs(c.ms) + "  " + c.title;
            if (pos >= c.ms) {
                cur = i;
            }
        }
        items[cur] = items[cur] + "  (now)";
        new AlertDialog.Builder(this)
                .setTitle("Chapters")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            player.seekTo(chaps.get(w).ms);
                            showHud(chaps.get(w).title);
                        } catch (Throwable t) {
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static String fmtMs(long ms) {
        long t = ms / 1000;
        return (t / 3600 > 0 ? t / 3600 + ":" : "")
                + String.format(java.util.Locale.US, "%02d:%02d", (t % 3600) / 60, t % 60);
    }

    private void showEqMenu() {
        final String[] nm = new String[]{"Off", "Bass boost", "Treble boost", "Vocal clarity"};
        new AlertDialog.Builder(this)
                .setTitle("Equalizer")
                .setItems(nm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        applyEq(w);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyEq(int mode) {
        try {
            if (player == null) {
                toast("Nothing playing");
                return;
            }
            if (eq == null) {
                eq = new android.media.audiofx.Equalizer(0, player.getAudioSessionId());
            }
            if (mode == 0) {
                eq.setEnabled(false);
                showHud("Equalizer off");
                return;
            }
            eq.setEnabled(true);
            short nb = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            for (short b = 0; b < nb; b++) {
                float f = (b + 0.5f) / nb; // 0..1 ব্যান্ড-অবস্থান
                float g;
                if (mode == 1) {
                    g = (1f - f) * 2f; // বেজ: নিচের ব্যান্ড বুস্ট
                } else if (mode == 2) {
                    g = f * 2f; // ট্রেবল
                } else {
                    g = 1.6f - Math.abs(f - 0.5f) * 3.2f; // ভোকাল: মাঝ
                }
                short lvl = (short) Math.max(range[0],
                        Math.min(range[1], Math.round(g * range[1] * 0.6f)));
                eq.setBandLevel(b, lvl);
            }
            showHud(mode == 1 ? "Bass boost" : mode == 2 ? "Treble boost" : "Vocal clarity");
        } catch (Throwable t) {
            toast("Equalizer unavailable");
        }
    }

    private void showQualityMenu() {
        if (player == null) {
            return;
        }
        if (audioOnly) {
            toast("Exit audio mode to change quality");
            return;
        }
        if (isYoutube && ytVars != null && !ytVars.isEmpty()) {
            String[] items = new String[ytVars.size()];
            for (int i = 0; i < ytVars.size(); i++) {
                Resolver.Var v = ytVars.get(i);
                items[i] = v.h + "p" + (v.h == chosenHeight ? " (current)" : "");
            }
            final List<Resolver.Var> fv = ytVars;
            new AlertDialog.Builder(this)
                    .setTitle("Quality")
                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            applyYoutubeQuality(fv.get(which));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        List<QOpt> opts = new ArrayList<QOpt>();
        QOpt auto = new QOpt();
        auto.label = "Auto (recommended)";
        auto.height = 0;
        opts.add(auto);

        Set<Integer> seen = new LinkedHashSet<Integer>();
        Tracks tracks = player.getCurrentTracks();
        for (int g = 0; g < tracks.getGroups().size(); g++) {
            Tracks.Group grp = tracks.getGroups().get(g);
            if (grp.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int t = 0; t < grp.length; t++) {
                if (!grp.isTrackSupported(t)) {
                    continue;
                }
                Format f = grp.getTrackFormat(t);
                int hgt = f != null ? f.height : 0;
                if (hgt <= 0 || !seen.add(hgt)) {
                    continue;
                }
                QOpt o = new QOpt();
                o.label = hgt + "p";
                o.height = hgt;
                o.group = grp;
                o.trackIndex = t;
                opts.add(o);
            }
        }
        // sort: auto first, then by height desc
        for (int i = 1; i < opts.size(); i++) {
            for (int j = i + 1; j < opts.size(); j++) {
                if (opts.get(j).height > opts.get(i).height) {
                    QOpt tmp = opts.get(i);
                    opts.set(i, opts.get(j));
                    opts.set(j, tmp);
                }
            }
        }
        String[] items = new String[opts.size()];
        for (int i = 0; i < opts.size(); i++) {
            QOpt o = opts.get(i);
            items[i] = o.label;
            if (o.height == chosenHeight) {
                items[i] = o.label + " (current)";
            }
        }
        final List<QOpt> fo = opts;
        new AlertDialog.Builder(this)
                .setTitle("Quality")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        applyQuality(fo.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyYoutubeQuality(Resolver.Var v) {
        PlayerService s = PlayerService.sSelf;
        if (s == null || player == null || ytAudio == null) {
            return;
        }
        long pos = player.getCurrentPosition();
        s.switchTo(v.url, ytAudio, pos);
        chosenHeight = v.h;
        updateQualityLabel();
    }

    private void applyQuality(QOpt o) {
        if (player == null) {
            return;
        }
        try {
            TrackSelectionParameters.Builder b = player.getTrackSelectionParameters()
                    .buildUpon();
            if (o.height == 0) {
                b.clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            } else {
                b.setOverrideForType(new TrackSelectionOverride(
                        o.group.getMediaTrackGroup(), o.trackIndex));
            }
            player.setTrackSelectionParameters(b.build());
            chosenHeight = o.height;
        } catch (Throwable t) {
            toast("Cannot switch quality");
        }
        updateQualityLabel();
    }

    private int currentQualityHeight() {
        if (player == null) {
            return 0;
        }
        Tracks tracks = player.getCurrentTracks();
        for (int g = 0; g < tracks.getGroups().size(); g++) {
            Tracks.Group grp = tracks.getGroups().get(g);
            if (grp.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int t = 0; t < grp.length; t++) {
                if (grp.isTrackSelected(t)) {
                    Format f = grp.getTrackFormat(t);
                    if (f != null && f.height > 0) {
                        return f.height;
                    }
                }
            }
        }
        return 0;
    }

    private void updateQualityLabel() {
        if (btnQuality == null || player == null) {
            return;
        }
        int hgt = currentQualityHeight();
        if (chosenHeight > 0) {
            btnQuality.setText(chosenHeight + "p");
        } else if (hgt > 0) {
            btnQuality.setText("Auto " + hgt + "p");
        } else {
            btnQuality.setText("Auto");
        }
    }

    // ---------- helpers ----------

    private void showError(String msg) {
        if (playRoot == null) {
            return;
        }
        if (!resumed) {
            // ব্যাকগ্রাউন্ডে ডায়ালগ দেখানো যায় না — ফেরার পর দেখাব
            errPending = msg;
            return;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Playback error")
                    .setMessage(msg)
                    .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            startService(new Intent(PlayerActivity.this, PlayerService.class)
                                    .setAction(PlayerService.ACT_STOP));
                            showEntry();
                        }
                    })
                    .setCancelable(false)
                    .show();
        } catch (Throwable t) {
            toast("Playback error");
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
        }
    }

    private int hsl(int hue) {
        return Color.HSVToColor(new float[]{(float) hue, 0.75f, 0.60f});
    }

    private int dp(int v) {
        return Math.round(v * u);
    }

    private String fmt(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int s = ms / 1000;
        int m = s / 60;
        s = s % 60;
        if (m >= 60) {
            int hh = m / 60;
            m = m % 60;
            return hh + ":" + String.format(java.util.Locale.US, "%02d", m)
                    + ":" + String.format(java.util.Locale.US, "%02d", s);
        }
        return m + ":" + String.format(java.util.Locale.US, "%02d", s);
    }

    /** কোড-ড্রন মিডিয়া-আইকন (Material-জ্যামিতি — NewPipe-চেহারা, কোনো বিটম্যাপ নয়)। */
    private final class IconButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private String icon = "download";
        private int tint = 0xFFF2F2F2;

        IconButton(Context c) {
            super(c);
        }

        void setIconTint(int color) {
            tint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            float k = s / 24f;
            float x = (w - s) / 2f;
            float y = (h - s) / 2f;
            p.setColor(tint);
            p.setStyle(Paint.Style.FILL);
            if ("download".equals(icon)) {
                path.reset();
                path.moveTo(x + 12 * k, y + 4 * k);
                path.lineTo(x + 12 * k, y + 12.5f * k);
                path.lineTo(x + 9 * k, y + 9.8f * k);
                path.lineTo(x + 7.9f * k, y + 11 * k);
                path.lineTo(x + 12 * k, y + 15 * k);
                path.lineTo(x + 16.1f * k, y + 11 * k);
                path.lineTo(x + 15 * k, y + 9.8f * k);
                path.lineTo(x + 12 * k, y + 12.5f * k);
                path.close();
                cv.drawPath(path, p);
                rect.set(x + 7 * k, y + 17.4f * k, x + 17 * k, y + 19 * k);
                cv.drawRoundRect(rect, k, k, p);
            } else if ("headset".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                rect.set(x + 4 * k, y + 9 * k, x + 20 * k, y + 19 * k);
                cv.drawArc(rect, 180, 180, false, p);
                p.setStyle(Paint.Style.FILL);
                rect.set(x + 3.4f * k, y + 14 * k, x + 7 * k, y + 19.6f * k);
                cv.drawRoundRect(rect, 1.6f * k, 1.6f * k, p);
                rect.set(x + 17 * k, y + 14 * k, x + 20.6f * k, y + 19.6f * k);
                cv.drawRoundRect(rect, 1.6f * k, 1.6f * k, p);
            } else if ("pip".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.8f * k);
                rect.set(x + 3 * k, y + 3 * k, x + 21 * k, y + 19.5f * k);
                cv.drawRoundRect(rect, 2 * k, 2 * k, p);
                p.setStyle(Paint.Style.FILL);
                rect.set(x + 11.5f * k, y + 10.5f * k, x + 19.5f * k, y + 17 * k);
                cv.drawRoundRect(rect, 1.4f * k, 1.4f * k, p);
            } else if ("repeat".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(x + 6 * k, y + 13 * k);
                path.lineTo(x + 6 * k, y + 9 * k);
                path.quadTo(x + 6 * k, y + 6.4f * k, x + 9 * k, y + 6.4f * k);
                path.lineTo(x + 17.4f * k, y + 6.4f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 15.4f * k, y + 4 * k);
                path.lineTo(x + 18.4f * k, y + 6.4f * k);
                path.lineTo(x + 15.4f * k, y + 8.8f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 18 * k, y + 11 * k);
                path.lineTo(x + 18 * k, y + 15 * k);
                path.quadTo(x + 18 * k, y + 17.6f * k, x + 15 * k, y + 17.6f * k);
                path.lineTo(x + 6.6f * k, y + 17.6f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 8.6f * k, y + 15.2f * k);
                path.lineTo(x + 5.6f * k, y + 17.6f * k);
                path.lineTo(x + 8.6f * k, y + 20 * k);
                cv.drawPath(path, p);
            } else if ("timer".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                cv.drawCircle(x + 12 * k, y + 13 * k, 7.6f * k, p);
                path.reset();
                path.moveTo(x + 12 * k, y + 13 * k);
                path.lineTo(x + 12 * k, y + 8.6f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 12 * k, y + 13 * k);
                path.lineTo(x + 15.2f * k, y + 13 * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 9.6f * k, y + 3.4f * k);
                path.lineTo(x + 14.4f * k, y + 3.4f * k);
                cv.drawPath(path, p);
            } else if ("clock".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                cv.drawCircle(x + 12 * k, y + 12 * k, 8.2f * k, p);
                path.reset();
                path.moveTo(x + 12 * k, y + 12 * k);
                path.lineTo(x + 12 * k, y + 7.2f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 12 * k, y + 12 * k);
                path.lineTo(x + 15.4f * k, y + 13.8f * k);
                cv.drawPath(path, p);
            } else if ("playlist".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(x + 4.6f * k, y + 6.8f * k);
                path.lineTo(x + 13.4f * k, y + 6.8f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 4.6f * k, y + 11 * k);
                path.lineTo(x + 13.4f * k, y + 11 * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 4.6f * k, y + 15.2f * k);
                path.lineTo(x + 10.4f * k, y + 15.2f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 17.6f * k, y + 9.2f * k);
                path.lineTo(x + 17.6f * k, y + 16.4f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 14.2f * k, y + 12.8f * k);
                path.lineTo(x + 21 * k, y + 12.8f * k);
                cv.drawPath(path, p);
            } else if ("share".equals(icon)) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(x + 18 * k, y + 5.6f * k, 2.3f * k, p);
                cv.drawCircle(x + 6 * k, y + 12 * k, 2.3f * k, p);
                cv.drawCircle(x + 18 * k, y + 18.4f * k, 2.3f * k, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * k);
                path.reset();
                path.moveTo(x + 8.1f * k, y + 10.9f * k);
                path.lineTo(x + 15.9f * k, y + 6.5f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 8.1f * k, y + 13.1f * k);
                path.lineTo(x + 15.9f * k, y + 17.5f * k);
                cv.drawPath(path, p);
            } else if ("like".equals(icon)) {
                // Material thumb_up-জ্যামিতি (ভরা থাম্বস-আপ)
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(x + 1 * k, y + 20 * k);
                path.lineTo(x + 4 * k, y + 20 * k);
                path.lineTo(x + 4 * k, y + 9 * k);
                path.lineTo(x + 1 * k, y + 9 * k);
                path.close();
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 9.5f * k, y + 20 * k);
                path.lineTo(x + 17.7f * k, y + 20 * k);
                path.cubicTo(x + 18.4f * k, y + 20 * k, x + 19.05f * k, y + 19.6f * k,
                        x + 19.3f * k, y + 19 * k);
                path.lineTo(x + 21.75f * k, y + 13.3f * k);
                path.cubicTo(x + 21.85f * k, y + 13.05f * k, x + 21.9f * k, y + 12.85f * k,
                        x + 21.9f * k, y + 12.65f * k);
                path.lineTo(x + 21.9f * k, y + 11.05f * k);
                path.cubicTo(x + 21.9f * k, y + 10.5f * k, x + 21.45f * k, y + 10.05f * k,
                        x + 20.9f * k, y + 10.05f * k);
                path.lineTo(x + 15.6f * k, y + 10.05f * k);
                path.lineTo(x + 16.4f * k, y + 6.2f * k);
                path.lineTo(x + 16.45f * k, y + 5.9f * k);
                path.cubicTo(x + 16.45f * k, y + 5.55f * k, x + 16.3f * k, y + 5.25f * k,
                        x + 16.05f * k, y + 5f * k);
                path.lineTo(x + 15.15f * k, y + 4.1f * k);
                path.lineTo(x + 9.5f * k, y + 9.75f * k);
                path.cubicTo(x + 9.2f * k, y + 10.05f * k, x + 9 * k, y + 10.55f * k,
                        x + 9 * k, y + 11.1f * k);
                path.close();
                cv.drawPath(path, p);
            } else if ("dislike".equals(icon)) {
                // থাম্বস-ডাউন = থাম্বস-আপ ১৮০° ঘূর্ণায়
                cv.save();
                cv.rotate(180f, x + 12 * k, y + 12 * k);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(x + 1 * k, y + 20 * k);
                path.lineTo(x + 4 * k, y + 20 * k);
                path.lineTo(x + 4 * k, y + 9 * k);
                path.lineTo(x + 1 * k, y + 9 * k);
                path.close();
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 9.5f * k, y + 20 * k);
                path.lineTo(x + 17.7f * k, y + 20 * k);
                path.cubicTo(x + 18.4f * k, y + 20 * k, x + 19.05f * k, y + 19.6f * k,
                        x + 19.3f * k, y + 19 * k);
                path.lineTo(x + 21.75f * k, y + 13.3f * k);
                path.cubicTo(x + 21.85f * k, y + 13.05f * k, x + 21.9f * k, y + 12.85f * k,
                        x + 21.9f * k, y + 12.65f * k);
                path.lineTo(x + 21.9f * k, y + 11.05f * k);
                path.cubicTo(x + 21.9f * k, y + 10.5f * k, x + 21.45f * k, y + 10.05f * k,
                        x + 20.9f * k, y + 10.05f * k);
                path.lineTo(x + 15.6f * k, y + 10.05f * k);
                path.lineTo(x + 16.4f * k, y + 6.2f * k);
                path.lineTo(x + 16.45f * k, y + 5.9f * k);
                path.cubicTo(x + 16.45f * k, y + 5.55f * k, x + 16.3f * k, y + 5.25f * k,
                        x + 16.05f * k, y + 5f * k);
                path.lineTo(x + 15.15f * k, y + 4.1f * k);
                path.lineTo(x + 9.5f * k, y + 9.75f * k);
                path.cubicTo(x + 9.2f * k, y + 10.05f * k, x + 9 * k, y + 10.55f * k,
                        x + 9 * k, y + 11.1f * k);
                path.close();
                cv.drawPath(path, p);
                cv.restore();
            } else if ("more".equals(icon)) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(x + 12 * k, y + 5.2f * k, 1.9f * k, p);
                cv.drawCircle(x + 12 * k, y + 12 * k, 1.9f * k, p);
                cv.drawCircle(x + 12 * k, y + 18.8f * k, 1.9f * k, p);
            } else if ("full".equals(icon)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(x + 5 * k, y + 8.6f * k);
                path.lineTo(x + 5 * k, y + 5 * k);
                path.lineTo(x + 8.6f * k, y + 5 * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 15.4f * k, y + 5 * k);
                path.lineTo(x + 19 * k, y + 5 * k);
                path.lineTo(x + 19 * k, y + 8.6f * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 19 * k, y + 15.4f * k);
                path.lineTo(x + 19 * k, y + 19 * k);
                path.lineTo(x + 15.4f * k, y + 19 * k);
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x + 8.6f * k, y + 19 * k);
                path.lineTo(x + 5 * k, y + 19 * k);
                path.lineTo(x + 5 * k, y + 15.4f * k);
                cv.drawPath(path, p);
            } else if ("replay".equals(icon) || "forward".equals(icon)) {
                boolean fw = "forward".equals(icon);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * k);
                p.setStrokeCap(Paint.Cap.ROUND);
                rect.set(x + 4.6f * k, y + 4.6f * k, x + 19.4f * k, y + 19.4f * k);
                if (fw) {
                    cv.drawArc(rect, -33, 285, false, p);
                    path.reset();
                    path.moveTo(x + 19.4f * k, y + 4.2f * k);
                    path.lineTo(x + 22.4f * k, y + 7.4f * k);
                    path.lineTo(x + 18.4f * k, y + 9.2f * k);
                    path.close();
                } else {
                    cv.drawArc(rect, 153, 285, false, p);
                    path.reset();
                    path.moveTo(x + 4.6f * k, y + 4.2f * k);
                    path.lineTo(x + 1.6f * k, y + 7.4f * k);
                    path.lineTo(x + 5.6f * k, y + 9.2f * k);
                    path.close();
                }
                p.setStyle(Paint.Style.FILL);
                cv.drawPath(path, p);
                p.setTextSize(7.4f * k);
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                cv.drawText("10", x + 12 * k, y + 14.8f * k, p);
                p.setTextAlign(Paint.Align.LEFT);
                p.setFakeBoldText(false);
            }
        }
    }

    /** আইকন-বাটন ফ্যাক্টরি। */
    private IconButton iconBtn(String icon) {
        IconButton b = new IconButton(this);
        b.icon = icon;
        b.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        b.setLayoutParams(lp);
        return b;
    }

    /** ইউজার-অনুমোদিত পাওয়ার-প্লে আইকন (design/play-button-v1.svg — হুবহু জ্যামিতি)।
     *  চলছে = জ্বলা সবুজ + glow + অরা-রিং; পজ = নিভা ধূসর (স্ট্যান্ডবাই)। */
    private final class PowerIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean playing = false;

        PowerIcon(Context c) {
            super(c);
        }

        void setState(boolean play) {
            if (playing != play) {
                playing = play;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float k = Math.min(getWidth(), getHeight()) / 128f;
            // 128-গ্রিড জ্যামিতি: বৃত্ত-কেন্দ্র (64, 64.8), r=31; দণ্ড (64,29)-(64,61)
            rect.set(33 * k, 33.8f * k, 95 * k, 95.8f * k);
            float cx = 64 * k;
            float top = 29 * k;
            float bot = 61 * k;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (playing) {
                p.setColor(0xFF34D399);
                // নরম glow — দুই স্তরের চওড়া আধা-স্বচ্ছ স্ট্রোক
                p.setStrokeWidth(26 * k);
                p.setAlpha(26);
                drawSymbol(cv, cx, top, bot);
                p.setStrokeWidth(17 * k);
                p.setAlpha(46);
                drawSymbol(cv, cx, top, bot);
                // মূল প্রতীক
                p.setAlpha(255);
                p.setStrokeWidth(8 * k);
                drawSymbol(cv, cx, top, bot);
                // পাতলা অরা-রিং
                p.setAlpha(71);
                p.setStrokeWidth(1.6f * k);
                cv.drawCircle(64 * k, 62 * k, 57 * k, p);
                p.setAlpha(255);
            } else {
                p.setColor(0xFF9A9A9A);
                p.setAlpha(115);
                p.setStrokeWidth(8 * k);
                drawSymbol(cv, cx, top, bot);
                p.setAlpha(255);
            }
        }

        private void drawSymbol(Canvas cv, float cx, float top, float bot) {
            // বৃহৎ-চাপ ২৭৪.৭ ডিগ্রি (SVG: M43 42 a31 31 0 1 0 42 0) + দণ্ড
            cv.drawArc(rect, -47.35f, 274.7f, false, p);
            cv.drawLine(cx, top, cx, bot, p);
        }
    }

    /** Code-drawn play/pause icon (no emoji, no bitmaps). */
    private final class PlayIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean playing = false;

        PlayIcon(Context c) {
            super(c);
        }

        void setState(boolean isPlaying) {
            if (playing != isPlaying) {
                playing = isPlaying;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            p.setColor(0xFFF2F2F2);
            if (playing) {
                // pause: two bars
                float bw = w * 0.22f;
                float m = w * 0.14f;
                cv.drawRect(m, h * 0.2f, m + bw, h * 0.8f, p);
                cv.drawRect(w - m - bw, h * 0.2f, w - m, h * 0.8f, p);
            } else {
                // play: triangle
                path.reset();
                path.moveTo(w * 0.30f, h * 0.18f);
                path.lineTo(w * 0.30f, h * 0.82f);
                path.lineTo(w * 0.82f, h * 0.5f);
                path.close();
                cv.drawPath(path, p);
            }
        }
    }
}
