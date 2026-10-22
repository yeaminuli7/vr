package com.mytube.web;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    public static MainActivity ins;
    public static volatile boolean sForeground = false;
    static volatile MainActivity sInstance;
    /** এই আইডির ওয়েব-ফলব্যাক চলছে — প্লেয়ারে পুনরায় পাঠাব না (লুপ-গার্ড)। */
    static volatile String sSuppressVideo = null;
    public WebView web;
    private android.widget.LinearLayout root;
    private String injectJs = "";
    private String coreJs = "";
    private volatile String lastUrl = "https://m.youtube.com";
    private boolean notifAsked = false;
    private boolean crashShown = false;
    private long lastBack = 0;
    private android.content.SharedPreferences prefs;
    private android.app.Dialog libDlg;
    private android.view.View offOverlay;
    private static android.graphics.Bitmap accBmp;
    private static LibIcon accIconRef;
    /** Settings dialog handle, so sign-in can close it (it is not libDlg). */
    private android.app.Dialog settingsDlgRef;

    /** The Profile sheet opened from the Library screen. Previously this dialog
     *  was only ever held in a local variable, so nothing could dismiss it -
     *  tapping "Sign in with Google" loaded the sign-in page *behind* this
     *  still-open sheet, which is why the button looked dead. */
    private android.app.Dialog profDlg;

    /** Window opened by the page (Google sign-in uses popups for the account
     *  chooser and consent screens). */
    private android.app.Dialog popDlg;

    /** The Chrome-for-Android user agent we install on every WebView. Kept as a
     *  field so it can be re-asserted: WebView is known to reset a custom UA
     *  back to the default (which re-adds the "; wv" marker) when a page opens
     *  a new tab or popup. */
    private String mtUa;

    /** Injected on sign-in pages instead of the YouTube bundle: lets the page
     *  behave exactly as Google shipped it. Recognises Google's rejection both
     *  by its URL and by the wording on the page. */
        /** Present Chrome's brand list to JavaScript.
     *
     *  WebView exposes navigator.userAgentData with an "Android WebView" brand,
     *  which is a second, JS-readable copy of the Sec-CH-UA leak we cannot fix
     *  at the HTTP layer. Google's sign-in page is JavaScript-heavy and can read
     *  it directly, so report the same brands a real Chrome for Android would. */
        /** Chrome major version out of our user agent, for the JS brand list. */
        /** True for YouTube's own account/sign-in screens.
     *
     *  These live on youtube.com, so the host check alone would hand them the
     *  full bundle - but core.js rewrites global JSON.parse and fetch, which is
     *  safe on the watch feed and hazardous on a page whose whole job is to post
     *  credentials and follow redirects. Keep them clean like Google's pages. */
    private static boolean isSigninPath(String url) {
        try {
            String u = url == null ? "" : url.toLowerCase();
            int i = u.indexOf("://");
            if (i >= 0) {
                u = u.substring(i + 3);
            }
            int j = u.indexOf('/');
            String path = j >= 0 ? u.substring(j) : "/";
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            return path.startsWith("/signin") || path.startsWith("/signup")
                    || path.startsWith("/login") || path.startsWith("/logout")
                    || path.startsWith("/account") || path.startsWith("/reauth");
        } catch (Throwable t) {
            return false;
        }
    }
    private int hueTab = 357;
    private int hueNav = 220;
    private int huePanel = 357;
    private boolean dark = true;
    private boolean bgPlay = true; // Background-সিস্টেম সবসময় চালু (ডিফল্ট)
    private android.widget.LinearLayout navBar;
    private NavTab navHome, navShorts, navSubs, navLib;
    private View divider;
    private android.widget.FrameLayout wrap;
    private boolean resolving = false;
    private boolean inPip = false;

    public volatile boolean webPlaying = false;
    public volatile boolean audioMode = false;
    public volatile long posMs = 0;
    public volatile long durMs = 0;
    public volatile String title = "";

    @Override
    protected void onCreate(Bundle b) {
        NativeLoader.ensure(this);
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        super.onCreate(b);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleBack();
                            }
                        });
            }
        } catch (Throwable t) {
        }
        ins = this;
        prefs = getSharedPreferences("mtw", 0);
        int old = prefs.getInt("hue", -1);
        hueTab = prefs.getInt("hueTab", old >= 0 ? old : 357);
        hueNav = prefs.getInt("hueNav", 220);
        huePanel = prefs.getInt("huePanel", old >= 0 ? old : 357);
        dark = prefs.getBoolean("dark", true);
        bgPlay = prefs.getBoolean("bgPlayV2", true);
        coreJs = readAsset("core.js");
        injectJs = buildInject();
        String restore = b != null ? b.getString("mt_url") : null;
        setupWeb(restore != null ? restore : lastUrl);
        try {
            String vn = "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (!vn.equals(prefs.getString("seen_v", ""))) {
                prefs.edit().putString("seen_v", vn).apply();
                android.widget.Toast.makeText(this, "Wtube " + vn,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
        }
    }

    /** uBlock-এর কসমেটিক-সিলেক্টর + আমাদের inject.js একসাথে। */
    private String buildInject() {
        StringBuilder css = new StringBuilder();
        try {
            css.append(readAsset("cosmetic_generic.css"));
            String yt = readAsset("cosmetic_youtube.css");
            if (yt.length() > 0) {
                css.append(",").append(yt);
            }
        } catch (Throwable t) {
        }
        css.append(",ytm-promoted-video-renderer,ytm-ad-slot-renderer,"
                + "ytm-companion-slot-renderer,ytm-banner-promo-renderer,"
                + "ytd-promoted-video-renderer,#masthead-ad,#player-ads,#merch-shelf,"
                + ".ytp-ad-image-overlay,[class*=\"ad-badge\"]");
        String esc = css.toString()
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "");
        return "(function(){try{var s=document.createElement('style');"
                + "s.textContent=\"" + esc + "\";"
                + "document.documentElement.appendChild(s);}catch(e){}})();"
                + (prefs.getBoolean("amoled", false)
                    ? "try{var __as=document.createElement('style');__as.textContent="
                    + "'ytm-app,ytm-mobile-app,ytm-watch,header,body,html{background:#000!important}';"
                    + "(document.head||document.documentElement).appendChild(__as);}catch(e){}"
                    : "")
                + "try{window.__MTFLAGS={skick:"
                + (prefs.getBoolean("skick", true) ? "true" : "false")
                + ",navhide:"
                + (prefs.getBoolean("navhide", true) ? "true" : "false")
                + "};}catch(e){};"
                + readAsset("inject.js");
    }

    private void setupWeb(String url) {
        web = new WebView(this);
        root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        wrap = new android.widget.FrameLayout(this);
        wrap.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(wrap, new android.widget.LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        offOverlay = buildOffOverlay();
        wrap.addView(offOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        offOverlay.setVisibility(View.GONE);
        navHome = navBtn("Home", "https://m.youtube.com/", 0);
        navShorts = navBtn("Shorts", "https://m.youtube.com/shorts", 1);
        navSubs = navBtn("Subs", "https://m.youtube.com/feed/subscriptions", 2);
        navSubs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://m.youtube.com/feed/subscriptions");
            }
        });
        navLib = navBtn("Library", "https://m.youtube.com/feed/you", 3);
        navLib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibraryScreen();
            }
        });
        navBar = new android.widget.LinearLayout(this);
        navBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        applyNavBarColor();
        navBar.addView(navHome);
        navBar.addView(navShorts);
        navBar.addView(navSubs);
        navBar.addView(navLib);
        divider = new View(this);
        root.addView(divider, new android.widget.LinearLayout.LayoutParams(
                -1, Math.max(1, (int) (0.5f * getResources().getDisplayMetrics().density))));
        root.addView(navBar, new android.widget.LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(58)));
        setContentView(root);
        applySystemBars();

        try {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            /* Google sign-in opens popups (account chooser, consent). Without
               this the window.open() call is silently dropped and the flow
               looks frozen, which is why tapping did nothing. */
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            try {
                mtUa = chromeUa(s.getUserAgentString());
                s.setUserAgentString(mtUa);
                StreamStore.sUA = mtUa;
            } catch (Throwable t2) {
            }
        } catch (Throwable t) {
        }
        /* Google sign-in hops youtube.com -> accounts.google.com -> youtube.com,
           so third-party cookies must be allowed or the session breaks mid-flow. */
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) {
                cm.setAcceptThirdPartyCookies(web, true);
            }
        } catch (Throwable t) {
        }
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean onRenderProcessGone(WebView v,
                    android.webkit.RenderProcessGoneDetail d) {
                try {
                    v.destroy();
                } catch (Throwable t) {
                }
                final String u = lastUrl;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            setupWeb(u);
                            web.loadUrl(u);
                        } catch (Throwable t) {
                        }
                    }
                });
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                try {
                    Uri u = r.getUrl();
                    if (u == null) {
                        return false;
                    }
                    String sch = u.getScheme() == null ? "" : u.getScheme();
                    if (sch.equals("http") || sch.equals("https")) {
                        String host = u.getHost() == null ? "" : u.getHost();
                        if (host.endsWith("youtube.com") || host.equals("youtu.be")) {
                            String vid = vidFromUrl(u);
                            if (vid == null) {
                                // সার্চ-বারে লিংক পেস্ট করা হয়েছে, কিন্তু inject.js
                                // ধরতে পারেনি — /results?...search_query=<লিংক>
                                vid = vidFromPastedLink(u);
                            }
                            if (vid == null) {
                                // ইউটিউবের বাইরের লিংক — সার্চ না করে পেজটা খুলি
                                String ext = otherLinkFromPastedLink(u);
                                if (ext != null) {
                                    openExternalPage(ext);
                                    return true;
                                }
                            }
                            if (vid != null) {
                                if (vid.equals(sSuppressVideo)) {
                                    return false; // ফলব্যাক-পেজ — ওয়েবেই খুলুক
                                }
                                routeVideo(vid, null, false);
                                return true;
                            }
                        }
                    }
                    if (sch.equals("http")) {
                        v.loadUrl("https" + u.toString().substring(4));
                        return true;
                    }
                    if (sch.equals("https")) {
                        return false; // সাধারণ https — WebView-এই খোলে
                    }
                    // intent:// — the page wants to hand off to another app. We
                    // still never leave this app, but most intent:// URLs carry
                    // a browser_fallback_url. Loading that keeps the user inside
                    // Wtube instead of silently dropping the navigation, which
                    // is what makes an otherwise working sign-in appear to stall
                    // on the final redirect.
                    if (sch.equals("intent")) {
                        String fb = null;
                        try {
                            fb = u.getQueryParameter("browser_fallback_url");
                        } catch (Throwable t2) {
                        }
                        if (fb == null) {
                            try {
                                // intent://...#Intent;...;S.browser_fallback_url=<enc>;end
                                String frag = u.getFragment();
                                if (frag != null) {
                                    int k = frag.indexOf("browser_fallback_url=");
                                    if (k >= 0) {
                                        String rest = frag.substring(k + 21);
                                        int e = rest.indexOf(';');
                                        fb = android.net.Uri.decode(
                                                e > 0 ? rest.substring(0, e) : rest);
                                    }
                                }
                            } catch (Throwable t2) {
                            }
                        }
                        if (fb != null && fb.startsWith("https://")) {
                            v.loadUrl(fb);
                        }
                        return true;
                    }
                    // intent:/market:/youtube:/tel:/mailto: ... সম্পূর্ণ গ্রাস —
                    // এই অ্যাপ থেকে কখনোই অন্য অ্যাপে যাবে না
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    Uri u = req.getUrl();
                    if (prefs.getBoolean("adblock", true)
                            && AdBlock.block(MainActivity.this, u, lastUrl)) {
                        return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                    StreamStore.capture(u);
                    // শিট নিজেই ৬০০ms-এ রিফ্রেশ হয়, তাই এখানে কিছু করার নেই —
                    // কেবল ফাইলটা তালিকায় উঠলেই যথেষ্ট
                    MediaSniffer.capture(u);
                } catch (Throwable t) {
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                try {
                    if (v == web && offOverlay != null) {
                        offOverlay.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable t) {
                }
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                try {
                    if (v == web && req != null && req.isForMainFrame()
                            && offOverlay != null) {
                        offOverlay.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable t) {
                }
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                if (offOverlay != null) {
                    offOverlay.setVisibility(View.GONE);
                }
                if (v == web) {
                    if (url != null) {
                        lastUrl = url;
                    }
                    updateNav();
                }
                /* WebView resets a custom user agent back to the default - which
                   re-adds the "; wv" marker - when a page opens a new tab or
                   popup. Re-assert ours on every navigation so the sign-in flow
                   can never revert to a WebView-identifying UA mid-flow. */
                try {
                    if (mtUa != null) {
                        WebSettings vs = v.getSettings();
                        if (!mtUa.equals(vs.getUserAgentString())) {
                            vs.setUserAgentString(mtUa);
                        }
                    }
                } catch (Throwable t) {
                }
                if (v == web) {
                    final String inj = injectFor(url);
                    if (inj.length() > 0) {
                        try {
                            v.evaluateJavascript(inj, null);
                        } catch (Throwable t) {
                        }
                    }
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                /* Only the main frame owns lastUrl - a popup must not overwrite
                   it, or the ad blocker judges third-party requests against the
                   wrong page for the rest of the session. */
                if (v == web && url != null) {
                    lastUrl = url;
                }
                updateNav();
                if (v == web) {
                    final String inj = injectFor(url);
                    if (inj.length() > 0) {
                        try {
                            v.evaluateJavascript(inj, null);
                        } catch (Throwable t) {
                        }
                    }
                    // বহিঃ-পেজের নিজের <video> ঠিকানাও দেখি — নেটওয়ার্ক-লেয়ার
                    // কিছু মিস করলে (MSE) এখান থেকেই ধরা পড়বে
                    try {
                        if (MediaSniffer.isArmed() && url != null
                                && !url.contains("youtube.com")) {
                            v.evaluateJavascript(mediaProbeJs(), null);
                        }
                    } catch (Throwable t) {
                    }
                }
                /* write cookies through to disk so the signed-in session survives
                   a process kill - without this the user is logged out on restart */
                flushCookies();
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {

            /** window.open() -> a real second WebView in a full-screen dialog. */
            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean userGesture,
                                          android.os.Message resultMsg) {
                try {
                    final WebView pop = new WebView(MainActivity.this);
                    android.webkit.WebSettings ps = pop.getSettings();
                    ps.setJavaScriptEnabled(true);
                    ps.setDomStorageEnabled(true);
                    ps.setSupportMultipleWindows(true);
                    ps.setJavaScriptCanOpenWindowsAutomatically(true);
                    try {
                        if (mtUa == null) {
                            mtUa = chromeUa(ps.getUserAgentString());
                        }
                        ps.setUserAgentString(mtUa);
                    } catch (Throwable t2) {
                    }
                    try {
                        android.webkit.CookieManager.getInstance()
                                .setAcceptThirdPartyCookies(pop, true);
                    } catch (Throwable t2) {
                    }
                    pop.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v2,
                                WebResourceRequest r) {
                            return false;
                        }
                    });
                    pop.setWebChromeClient(new android.webkit.WebChromeClient() {
                        @Override
                        public void onCloseWindow(WebView w) {
                            try {
                                if (popDlg != null) {
                                    popDlg.dismiss();
                                    popDlg = null;
                                }
                            } catch (Throwable t2) {
                            }
                        }
                    });
                    final android.app.Dialog d = new android.app.Dialog(MainActivity.this,
                            android.R.style.Theme_Material_NoActionBar);
                    d.setContentView(pop);
                    android.view.Window win = d.getWindow();
                    if (win != null) {
                        win.setBackgroundDrawable(
                                new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
                        win.setLayout(-1, -1);
                    }
                    d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dd) {
                            try {
                                pop.destroy();
                            } catch (Throwable t2) {
                            }
                            popDlg = null;
                            flushCookies();
                        }
                    });
                    d.show();
                    popDlg = d;

                    WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                    tr.setWebView(pop);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public void onCloseWindow(WebView w) {
                try {
                    if (popDlg != null) {
                        popDlg.dismiss();
                        popDlg = null;
                    }
                } catch (Throwable t) {
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "MTApp");
        web.loadUrl(url);
    }

    /** কনটেন্ট স্ট্যাটাস-বারের নিচে থাকবে, বার-এ সাদা আইকন — নরমাল অ্যাপের মতো। */
    private void applySystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @Override
                    public android.view.WindowInsets onApplyWindowInsets(View v,
                            android.view.WindowInsets ins) {
                        android.graphics.Insets b = ins.getInsets(
                                android.view.WindowInsets.Type.systemBars());
                        v.setPadding(b.left, b.top, b.right, b.bottom);
                        return ins;
                    }
                });
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(0,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else {
                final View d = getWindow().getDecorView();
                d.setSystemUiVisibility(
                        d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable t) {
        }
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private int hslColor(int h) {
        return android.graphics.Color.HSVToColor(new float[]{(float) h, 0.75f, 0.60f});
    }

    private int hslDark(int h) {
        return android.graphics.Color.HSVToColor(new float[]{(float) h, 0.35f, 0.16f});
    }

    private void applyNavBarColor() {
        try {
            int bgc = dark ? hslDark(hueNav) : 0xFFF2F2F2;
            int base = dark ? 0xFF909090 : 0xFF606060;
            if (navBar != null) {
                navBar.setBackgroundColor(bgc);
            }
            if (divider != null) {
                divider.setBackgroundColor(dark ? 0xFF242424 : 0xFFDDDDDD);
            }
            NavTab[] all = new NavTab[]{navHome, navShorts, navSubs, navLib};
            for (int i = 0; i < all.length; i++) {
                if (all[i] != null) {
                    all[i].setSkin(bgc, base);
                }
            }
            updateNav();
        } catch (Throwable t) {
        }
    }

    /** ডার্ক/লাইট + অ্যাকসেন্ট-রঙ — প্রতি পেজে ইনজেক্ট হয়। */
    private String themeJs() {
        String css = "html[dark]{--yt-spec-base-background:#000!important;"
                + "--yt-spec-general-background-a:#000!important;"
                + "--yt-spec-general-background-b:#0a0a0a!important;"
                + "--yt-spec-menu-background:#0d0d0d!important;"
                + "--yt-spec-raised-background:#111!important}";
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){try{var h=document.documentElement;");
        if (dark) {
            sb.append("h.setAttribute('dark','');");
        } else {
            sb.append("h.removeAttribute('dark');");
        }
        sb.append("var o=document.getElementById('__mtt');if(o){o.remove();}");
        sb.append("var s=document.createElement('style');s.id='__mtt';s.textContent=\"");
        sb.append(css);
        sb.append("\";document.documentElement.appendChild(s);}catch(e){}})();");
        return sb.toString();
    }

    /** সেটিংস-প্যানেল: ডার্ক-সুইচ + রঙ-স্লাইডার */
    private android.widget.LinearLayout colorRow(String name, int hueNow,
            final int[] sink, final java.util.List<android.view.View> swatches,
            final java.util.List<android.widget.SeekBar> bars, int idx) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView lab = new android.widget.TextView(this);
        lab.setText(name);
        lab.setTextSize(15);
        lab.setTextColor(0xFFFFFFFF);
        row.addView(lab, new android.widget.LinearLayout.LayoutParams(0, -2, 0.30f));
        android.widget.SeekBar sk = new android.widget.SeekBar(this);
        sk.setMax(359);
        sk.setProgress(hueNow);
        bars.add(sk);
        row.addView(sk, new android.widget.LinearLayout.LayoutParams(0, -2, 0.50f));
        final View pv = new View(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0, dp(30), 0.20f);
        lp.leftMargin = dp(8);
        pv.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(hslColor(hueNow));
        pv.setBackgroundDrawable(g);
        swatches.add(pv);
        row.addView(pv);
        sk.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean u) {
                try {
                    sink[0] = v;
                    android.graphics.drawable.GradientDrawable g2 =
                            new android.graphics.drawable.GradientDrawable();
                    g2.setColor(hslColor(v));
                    swatches.get(idx).setBackgroundDrawable(g2);
                } catch (Throwable t2) {
                }
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        return row;
    }

    /** Settings: Theme (dark mode + per-part colors) and Background play. */
    /** প্রোফাইল-মেনু: অ্যাকাউন্ট-শর্টকাট + ডেটা-পরিচ্ছন্নতা + About। */
    /** অফলাইন-স্ক্রিন: Wtube-লোগো + No internet + Retry (ভাঙা-লোগো/এরর-পেজের বদলে)। */
    private android.view.View buildOffOverlay() {
        android.widget.LinearLayout o = new android.widget.LinearLayout(this);
        o.setOrientation(android.widget.LinearLayout.VERTICAL);
        o.setGravity(android.view.Gravity.CENTER);
        o.setBackgroundColor(0xFF0F0F0F);
        o.setPadding(dp(24), dp(24), dp(24), dp(24));

        android.view.View logo = new android.view.View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                float w = getWidth();
                float h = getHeight();
                android.graphics.Paint p =
                        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                float r = Math.min(w, h) * 0.22f;
                android.graphics.RectF rr = new android.graphics.RectF(
                        w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r);
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(hslColor(huePanel));
                cv.drawRoundRect(rr, r * 0.28f, r * 0.28f, p);
                p.setColor(0xFFFFFFFF);
                android.graphics.Path tri = new android.graphics.Path();
                tri.moveTo(w / 2 - r * 0.26f, h / 2 - r * 0.4f);
                tri.lineTo(w / 2 - r * 0.26f, h / 2 + r * 0.4f);
                tri.lineTo(w / 2 + r * 0.44f, h / 2);
                tri.close();
                cv.drawPath(tri, p);
                p.setTextSize(r * 0.5f);
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                cv.drawText("Wtube", w / 2, h / 2 + r * 1.5f, p);
            }
        };
        o.addView(logo, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(130)));

        android.widget.TextView t1 = new android.widget.TextView(this);
        t1.setText("No internet connection");
        t1.setTextSize(18);
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setTextColor(0xFFF2F2F2);
        android.widget.LinearLayout.LayoutParams t1p =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        t1p.topMargin = dp(30);
        o.addView(t1, t1p);

        android.widget.TextView t2 = new android.widget.TextView(this);
        t2.setText("Check your network and try again");
        t2.setTextSize(13);
        t2.setTextColor(0xFF9A9A9A);
        android.widget.LinearLayout.LayoutParams t2p =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        t2p.topMargin = dp(6);
        o.addView(t2, t2p);

        android.widget.Button b = new android.widget.Button(this);
        b.setText("Retry");
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        b.setTextSize(15);
        android.graphics.drawable.GradientDrawable bgd =
                new android.graphics.drawable.GradientDrawable();
        bgd.setColor(hslColor(huePanel));
        bgd.setCornerRadius(dp(24));
        b.setBackground(bgd);
        b.setPadding(dp(28), dp(10), dp(28), dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    offOverlay.setVisibility(View.GONE);
                    web.reload();
                } catch (Throwable t) {
                }
            }
        });
        android.widget.LinearLayout.LayoutParams bp =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(26);
        o.addView(b, bp);
        return o;
    }

    /** সময়-আগে লেখা। */
    private static String histWhen(long at) {
        if (at <= 0) {
            return "Earlier";
        }
        long day = 86400000L;
        long d = System.currentTimeMillis() / day - at / day;
        if (d <= 0) {
            return "Today";
        }
        if (d == 1) {
            return "Yesterday";
        }
        if (d < 7) {
            return d + " days ago";
        }
        return (d / 7) + " week" + (d / 7 > 1 ? "s" : "") + " ago";
    }

    /** সম্পূর্ণ দেখা-ইতিহাস তালিকা (ট্যাপে প্লে, Clear-সহ)। */
    /** হিস্ট্রি — এখন পূর্ণ স্ক্রিন: দিনভিত্তিক সেকশন + থাম্বনেইল-কার্ড। */
    private void showHistAll() {
        try {
            HistorySheet.show(this);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not open history", Toast.LENGTH_SHORT).show();
        }
    }

    private void showProfile() {
        try {
            android.widget.LinearLayout hh = new android.widget.LinearLayout(this);
            hh.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            hh.setGravity(android.view.Gravity.CENTER_VERTICAL);
            hh.setPadding(dp(22), dp(14), dp(22), dp(6));
            LibIcon av = new LibIcon(this, "person");
            hh.addView(av, new android.widget.LinearLayout.LayoutParams(dp(48), dp(48)));
            android.widget.LinearLayout ht = new android.widget.LinearLayout(this);
            ht.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams htp =
                    new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            htp.leftMargin = dp(14);
            android.widget.TextView hn = new android.widget.TextView(this);
            hn.setTextSize(17);
            hn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            hn.setTextColor(0xFFF2F2F2);
            hn.setText("Wtube");
            ht.addView(hn);
            android.widget.TextView hs = new android.widget.TextView(this);
            hs.setTextSize(12);
            hs.setTextColor(0xFF9A9A9A);
            hs.setText("Your activity and data");
            ht.addView(hs);
            hh.addView(ht, htp);
            android.widget.ScrollView psv = new android.widget.ScrollView(this);
            android.widget.LinearLayout pbox = new android.widget.LinearLayout(this);
            pbox.setOrientation(android.widget.LinearLayout.VERTICAL);
            pbox.setPadding(dp(20), dp(4), dp(20), dp(16));
            pbox.setBackgroundColor(0xFF0F0F0F);
            psv.addView(pbox);
            psv.setBackgroundColor(0xFF0F0F0F);
            psv.setVerticalScrollBarEnabled(false);
            pbox.addView(hh);
            pbox.addView(libSection("History"));
            final org.json.JSONArray ha = Library.hist(this);
            if (ha.length() == 0) {
                pbox.addView(libHint("Videos you watch will appear here"));
            } else {
                int phN = Math.min(3, ha.length());
                for (int phI = 0; phI < phN; phI++) {
                    final org.json.JSONObject phO = ha.optJSONObject(phI);
                    pbox.addView(libRow(phO.optString("t"),
                            histWhen(phO.optLong("at")),
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    openFromLib(phO.optString("id"),
                                            phO.optString("t"));
                                }
                            }));
                }
                pbox.addView(libRow("See all (" + ha.length() + ")",
                        "Watch history", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showHistAll();
                            }
                        }));
            }
            pbox.addView(libSection("Your library"));
            pbox.addView(libRow("Downloads", queryDownloads(this).size() + " saved",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            DownloadsSheet.show(MainActivity.this);
                        }
                    }));
            pbox.addView(libRow("Watch later",
                    Library.wl(this).length() + " videos",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showWatchLater();
                        }
                    }));
            pbox.addView(libRow("Playlists",
                    Library.playlists(this).size() + " lists",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showPlaylists();
                        }
                    }));
            pbox.addView(libRow("Notifications", "Opens in your browser",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissLib();
                            openUrl("https://m.youtube.com/feed/notifications");
                        }
                    }));
            pbox.addView(libRow("YouTube history", "Opens in your browser",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissLib();
                            openUrl("https://m.youtube.com/feed/history");
                        }
                    }));
            pbox.addView(libSection("Data"));
            pbox.addView(libRow("Clear watch history",
                    "Removes the watched-videos list",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDo("Clear watch history?",
                                    "Removes the watched-videos list",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            Library.clearHist(MainActivity.this);
                                            Toast.makeText(MainActivity.this,
                                                    "Watch history cleared",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        }
                    }));
            pbox.addView(libRow("Clear Watch Later", "Remove all saved videos",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDo("Clear Watch Later?", "Remove all saved videos",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            Library.clearWl(MainActivity.this);
                                            Toast.makeText(MainActivity.this,
                                                    "Watch Later cleared",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        }
                    }));
            pbox.addView(libRow("Clear download history",
                    "Only the history list is cleared",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDo("Clear download history?",
                                    "Only the history list is cleared",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            prefs.edit().remove("dhist").apply();
                                            Toast.makeText(MainActivity.this,
                                                    "Download history cleared",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        }
                    }));
            pbox.addView(libRow("About Wtube", "Version and info",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            aboutBox();
                        }
                    }));
            final android.app.Dialog pdg = new android.app.Dialog(this,
                    android.R.style.Theme_Material_NoActionBar);
            pdg.setContentView(psv);
            pdg.setCanceledOnTouchOutside(true);
            android.view.Window pw = pdg.getWindow();
            if (pw != null) {
                pw.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
                pw.setLayout(-1, -2);
            }
            profDlg = pdg;
            pdg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d4) {
                    if (profDlg == pdg) {
                        profDlg = null;
                    }
                }
            });
            pdg.show();
        } catch (Throwable t) {
        }
    }

    private void confirmDo(String title, String msg, final Runnable r) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d2, int w2) {
                            try {
                                r.run();
                            } catch (Throwable t) {
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    private void aboutBox() {
        try {
            String vn = "?";
            try {
                vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable t0) {
            }
            new AlertDialog.Builder(this)
                    .setTitle("Wtube")
                    .setMessage("Version " + vn
                            + "\nOwn player engine - ExoPlayer + FFmpeg + LAME"
                            + "\nDownloads save to Downloads/WTube")
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    /** Library-হেডারের গোল আইকন-বোতাম (gear/person)। */
    private final class LibIcon extends View {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final String icon;

        LibIcon(android.content.Context c, String icon) {
            super(c);
            this.icon = icon;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if ("person".equals(icon)) {
                accIconRef = this;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (accIconRef == this) {
                accIconRef = null;
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float k = w / 40f;
            boolean av = "person".equals(icon) && accBmp != null;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(isPressed() ? hslColor(huePanel) : 0xFF232326);
            cv.drawCircle(w / 2, h / 2, w / 2, p);
            if (av) {
                android.graphics.Path cp = new android.graphics.Path();
                cp.addCircle(w / 2, h / 2, w / 2 - 1, android.graphics.Path.Direction.CW);
                cv.save();
                cv.clipPath(cp);
                float bw = accBmp.getWidth();
                float bh = accBmp.getHeight();
                float sc = Math.max(w / bw, h / bh);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(sc, sc);
                m.postTranslate((w - bw * sc) / 2f, (h - bh * sc) / 2f);
                p.setFilterBitmap(true);
                cv.drawBitmap(accBmp, m, p);
                cv.restore();
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(2 * k);
                p.setColor(0xFF0F0F0F);
                cv.drawCircle(w / 2, h / 2, w / 2 - 1, p);
                return;
            }
            p.setColor(0xFFF2F2F2);
            if ("gear".equals(icon)) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(3 * k);
                cv.drawCircle(w / 2, h / 2, 7 * k, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    cv.drawLine((float) (w / 2 + Math.cos(a) * 10 * k),
                            (float) (h / 2 + Math.sin(a) * 10 * k),
                            (float) (w / 2 + Math.cos(a) * 13 * k),
                            (float) (h / 2 + Math.sin(a) * 13 * k), p);
                }
            } else {
                cv.drawCircle(w / 2, h / 2 - 5 * k, 6 * k, p);
                rect.set(w / 2 - 10 * k, h / 2 - 2 * k, w / 2 + 10 * k, h / 2 + 18 * k);
                cv.drawArc(rect, 180, 180, false, p);
            }
        }
    }

    /** Settings-সুইচ-সারি: তাৎক্ষণিক প্রিফ-লেখা। */
    private android.widget.Switch swRow2x(String label, final String key, boolean def) {
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        prefs.edit().putBoolean(key, on).apply();
                    }
                });
        return sw;
    }

    /** সব "mtw"-প্রিফ → Downloads/WTube/wtube-backup-{সময়}.json */
    private void backupNow() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "WTube");
            dir.mkdirs();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US);
            java.io.File out = new java.io.File(dir,
                    "wtube-backup-" + f.format(new java.util.Date()) + ".json");
            org.json.JSONObject o = new org.json.JSONObject();
            java.util.Map<String, ?> all = prefs.getAll();
            for (String k : all.keySet()) {
                Object v = all.get(k);
                if (v instanceof Boolean) {
                    o.put(k, "b:" + v);
                } else if (v instanceof Integer) {
                    o.put(k, "i:" + v);
                } else if (v instanceof Long) {
                    o.put(k, "l:" + v);
                } else if (v instanceof Float) {
                    o.put(k, "f:" + v);
                } else {
                    o.put(k, "s:" + v);
                }
            }
            java.io.FileWriter w = new java.io.FileWriter(out);
            w.write(o.toString());
            w.close();
            android.widget.Toast.makeText(this, "Backup saved: " + out.getName(),
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Backup failed",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** নতুনতম wtube-backup-*.json থেকে প্রিফ ফেরানো। */
    private void restoreBackup() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "WTube");
            java.io.File best = null;
            java.io.File[] fs = dir.listFiles();
            if (fs != null) {
                for (java.io.File f : fs) {
                    if (f.getName().startsWith("wtube-backup-") && f.getName().endsWith(".json")
                            && (best == null || f.lastModified() > best.lastModified())) {
                        best = f;
                    }
                }
            }
            if (best == null) {
                android.widget.Toast.makeText(this, "No backup found in Downloads/WTube",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(best));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln);
            }
            br.close();
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            android.content.SharedPreferences.Editor ed = prefs.edit();
            java.util.Iterator<String> it = o.keys();
            int n = 0;
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (v.startsWith("b:")) {
                    ed.putBoolean(k, Boolean.parseBoolean(v.substring(2)));
                } else if (v.startsWith("i:")) {
                    ed.putInt(k, Integer.parseInt(v.substring(2)));
                } else if (v.startsWith("l:")) {
                    ed.putLong(k, Long.parseLong(v.substring(2)));
                } else if (v.startsWith("f:")) {
                    ed.putFloat(k, Float.parseFloat(v.substring(2)));
                } else if (v.startsWith("s:")) {
                    ed.putString(k, v.substring(2));
                } else {
                    continue;
                }
                n++;
            }
            ed.apply();
            android.widget.Toast.makeText(this,
                    "Restored " + n + " items from " + best.getName() + " - restart app",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Restore failed",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettings() {
        try {
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = dp(20);
            box.setPadding(p, dp(16), p, p);
            box.setBackgroundColor(0xFF111111);
            android.widget.TextView t = new android.widget.TextView(this);
            String svn = "?";
            try {
                svn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable t0) {
            }
            t.setText("Wtube Settings  (v" + svn + ")");
            t.setTextSize(22);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(hslColor(huePanel));
            box.addView(t);
            android.widget.TextView sec = new android.widget.TextView(this);
            sec.setText("Theme");
            sec.setTextSize(13);
            sec.setTextColor(0xFF9A9A9A);
            sec.setPadding(0, dp(14), 0, dp(2));
            box.addView(sec);
            final android.widget.Switch swDark = new android.widget.Switch(this);
            swDark.setText("Dark mode");
            swDark.setTextSize(16);
            swDark.setTextColor(0xFFFFFFFF);
            swDark.setChecked(dark);
            box.addView(swDark);
            final java.util.List<android.view.View> swatches =
                    new java.util.ArrayList<android.view.View>();
            final java.util.List<android.widget.SeekBar> bars =
                    new java.util.ArrayList<android.widget.SeekBar>();
            final int[] vTab = new int[]{hueTab};
            final int[] vNav = new int[]{hueNav};
            final int[] vPanel = new int[]{huePanel};
            box.addView(colorRow("Active tab", hueTab, vTab, swatches, bars, 0));
            box.addView(colorRow("Nav bar", hueNav, vNav, swatches, bars, 1));
            box.addView(colorRow("Accent", huePanel, vPanel, swatches, bars, 2));
            android.widget.TextView sec2 = new android.widget.TextView(this);
            sec2.setText("Playback");
            sec2.setTextSize(13);
            sec2.setTextColor(0xFF9A9A9A);
            sec2.setPadding(0, dp(14), 0, dp(2));
            box.addView(sec2);
            final android.widget.Switch swBg = new android.widget.Switch(this);
            swBg.setText("Background play");
            swBg.setTextSize(16);
            swBg.setTextColor(0xFFFFFFFF);
            swBg.setChecked(bgPlay);
            box.addView(swBg);
            final android.widget.Switch swAN = new android.widget.Switch(this);
            swAN.setText("Autoplay next video");
            swAN.setTextSize(16);
            swAN.setTextColor(0xFFFFFFFF);
            swAN.setChecked(prefs.getBoolean("autoNext", true));
            swAN.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("autoNext", on).apply();
                        }
                    });
            box.addView(swAN);
            final int[] dqs = new int[]{240, 360, 480, 720};
            final android.widget.Button bDQ = new android.widget.Button(this);
            bDQ.setTextSize(13);
            bDQ.setTextColor(0xFF9A9A9A);
            bDQ.setBackgroundColor(0xFF242424);
            bDQ.setText("Default playback quality: " + prefs.getInt("defQ", 360) + "p");
            bDQ.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] names = new String[dqs.length];
                    for (int k = 0; k < dqs.length; k++) {
                        names[k] = dqs[k] + "p";
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Default playback quality")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w2) {
                                    prefs.edit().putInt("defQ", dqs[w2]).apply();
                                    bDQ.setText("Default playback quality: " + dqs[w2] + "p");
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
            box.addView(bDQ);
            android.widget.TextView secW = new android.widget.TextView(this);
            secW.setText("Web");
            secW.setTextSize(13);
            secW.setTextColor(0xFF9A9A9A);
            secW.setPadding(0, dp(14), 0, dp(2));
            box.addView(secW);
            final android.widget.Switch swAd = new android.widget.Switch(this);
            swAd.setText("Ad blocking");
            swAd.setTextSize(16);
            swAd.setTextColor(0xFFFFFFFF);
            swAd.setChecked(prefs.getBoolean("adblock", true));
            swAd.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("adblock", on).apply();
                        }
                    });
            box.addView(swAd);
            final android.widget.Switch swSk = new android.widget.Switch(this);
            swSk.setText("Shorts audio fix");
            swSk.setTextSize(16);
            swSk.setTextColor(0xFFFFFFFF);
            swSk.setChecked(prefs.getBoolean("skick", true));
            swSk.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("skick", on).apply();
                        }
                    });
            box.addView(swSk);
            final android.widget.Switch swNav = new android.widget.Switch(this);
            swNav.setText("Hide YouTube navigation");
            swNav.setTextSize(16);
            swNav.setTextColor(0xFFFFFFFF);
            swNav.setChecked(prefs.getBoolean("navhide", true));
            swNav.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("navhide", on).apply();
                        }
                    });
            box.addView(swNav);
            final android.widget.Switch swAcct = new android.widget.Switch(this);
            swAcct.setText("Open account pages in browser");
            swAcct.setTextSize(16);
            swAcct.setTextColor(0xFFFFFFFF);
            swAcct.setChecked(prefs.getBoolean("acctbrowser", false));
            swAcct.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("acctbrowser", on).apply();
                        }
                    });
            box.addView(swAcct);
            android.widget.TextView acctHint = new android.widget.TextView(this);
            acctHint.setText("Subscriptions, history and notifications open in your "
                    + "browser instead of inside the app. Off by default; turn on if you "
                    + "are signed into YouTube in Chrome.");
            acctHint.setTextSize(12);
            acctHint.setTextColor(0xFF6F6F75);
            acctHint.setPadding(0, 0, 0, dp(6));
            box.addView(acctHint);

            android.widget.TextView secD = new android.widget.TextView(this);
            secD.setText("Downloads");
            secD.setTextSize(13);
            secD.setTextColor(0xFF9A9A9A);
            secD.setPadding(0, dp(14), 0, dp(2));
            box.addView(secD);
            final android.content.SharedPreferences dlp = getSharedPreferences("mtw", 0);
            final android.widget.Switch swAuto = new android.widget.Switch(this);
            swAuto.setText("Auto-download");
            swAuto.setTextSize(16);
            swAuto.setTextColor(0xFFFFFFFF);
            swAuto.setChecked(dlp.getBoolean("dlAuto", false));
            swAuto.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            dlp.edit().putBoolean("dlAuto", on).apply();
                        }
                    });
            box.addView(swAuto);
            final int[] vqs = new int[]{144, 240, 360, 480, 720, 1080};
            final android.widget.Button bVQ = new android.widget.Button(this);
            bVQ.setTextSize(13);
            bVQ.setTextColor(0xFF9A9A9A);
            bVQ.setBackgroundColor(0xFF242424);
            bVQ.setText("Default video quality: " + dlp.getInt("dlVQ", 720) + "p");
            bVQ.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] names = new String[vqs.length];
                    for (int k = 0; k < vqs.length; k++) {
                        names[k] = vqs[k] + "p";
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Default video quality")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w2) {
                                    dlp.edit().putInt("dlVQ", vqs[w2]).apply();
                                    bVQ.setText("Default video quality: " + vqs[w2] + "p");
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
            box.addView(bVQ);
            final int[] aqs = new int[]{128, 192, 320};
            final android.widget.Button bAQ = new android.widget.Button(this);
            bAQ.setTextSize(13);
            bAQ.setTextColor(0xFF9A9A9A);
            bAQ.setBackgroundColor(0xFF242424);
            bAQ.setText("Default audio quality: " + dlp.getInt("dlAQ", 192) + " kbps");
            bAQ.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] names = new String[aqs.length];
                    for (int k = 0; k < aqs.length; k++) {
                        names[k] = aqs[k] + " kbps";
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Default audio quality")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w2) {
                                    dlp.edit().putInt("dlAQ", aqs[w2]).apply();
                                    bAQ.setText("Default audio quality: " + aqs[w2] + " kbps");
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
            box.addView(bAQ);
            final android.widget.Switch swFast = new android.widget.Switch(this);
            swFast.setText("Fast download mode (parallel parts)");
            swFast.setTextSize(16);
            swFast.setTextColor(0xFFFFFFFF);
            swFast.setChecked(prefs.getBoolean("fastdl", true));
            swFast.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            prefs.edit().putBoolean("fastdl", on).apply();
                        }
                    });
            box.addView(swFast);
            android.widget.TextView sec2x = new android.widget.TextView(this);
            sec2x.setText("Wtube 2.0 extras");
            sec2x.setTextSize(13);
            sec2x.setTextColor(0xFF9A9A9A);
            sec2x.setPadding(0, dp(14), 0, dp(2));
            box.addView(sec2x);
            box.addView(swRow2x("SponsorBlock auto-skip", "sbOn", true));
            box.addView(swRow2x("Incognito (no watch history)", "incognito", false));
            box.addView(swRow2x("Data saver (max 360p on mobile data)", "datasaver", false));
            box.addView(swRow2x("Download over Wi-Fi only", "wifiOnly", false));
            box.addView(swRow2x("AMOLED black theme", "amoled", false));
            android.widget.Button bBk = new android.widget.Button(this);
            bBk.setTextSize(13);
            bBk.setTextColor(0xFF9A9A9A);
            bBk.setBackgroundColor(0xFF242424);
            bBk.setText("Backup now (playlists, history, settings)");
            bBk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    backupNow();
                }
            });
            box.addView(bBk);
            android.widget.Button bRs = new android.widget.Button(this);
            bRs.setTextSize(13);
            bRs.setTextColor(0xFF9A9A9A);
            bRs.setBackgroundColor(0xFF242424);
            bRs.setText("Restore backup (newest file)");
            bRs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    restoreBackup();
                }
            });
            box.addView(bRs);
            android.widget.TextView secDa = new android.widget.TextView(this);
            secDa.setText("Data");
            secDa.setTextSize(13);
            secDa.setTextColor(0xFF9A9A9A);
            secDa.setPadding(0, dp(14), 0, dp(2));
            box.addView(secDa);
            android.widget.Button bCd = new android.widget.Button(this);
            bCd.setTextSize(13);
            bCd.setTextColor(0xFF9A9A9A);
            bCd.setBackgroundColor(0xFF242424);
            bCd.setText("Clear download history");
            bCd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDo("Clear download history?", "Only the history list is cleared",
                            new Runnable() {
                                @Override
                                public void run() {
                                    prefs.edit().remove("dhist").apply();
                                    android.widget.Toast.makeText(MainActivity.this,
                                            "Download history cleared",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                }
            });
            box.addView(bCd);
            android.widget.Button bCw = new android.widget.Button(this);
            bCw.setTextSize(13);
            bCw.setTextColor(0xFF9A9A9A);
            bCw.setBackgroundColor(0xFF242424);
            bCw.setText("Clear Watch Later");
            bCw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDo("Clear Watch Later?", "Remove all saved videos",
                            new Runnable() {
                                @Override
                                public void run() {
                                    Library.clearWl(MainActivity.this);
                                    android.widget.Toast.makeText(MainActivity.this,
                                            "Watch Later cleared",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                }
            });
            box.addView(bCw);
            android.widget.Button ok = new android.widget.Button(this);
            ok.setText("Apply");
            ok.setTextColor(0xFFFFFFFF);
            ok.setBackgroundColor(hslColor(huePanel));
            box.addView(ok);
            android.widget.Button wl = new android.widget.Button(this);
            wl.setText("Watch later");
            wl.setTextSize(13);
            wl.setTextColor(0xFF9A9A9A);
            wl.setBackgroundColor(0xFF242424);
            box.addView(wl);
            wl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showWatchLater();
                }
            });
            android.widget.Button pls = new android.widget.Button(this);
            pls.setText("Playlists");
            pls.setTextSize(13);
            pls.setTextColor(0xFF9A9A9A);
            pls.setBackgroundColor(0xFF242424);
            box.addView(pls);
            pls.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPlaylists();
                }
            });
            android.widget.Button dg = new android.widget.Button(this);
            dg.setText("Diagnostics");
            dg.setTextSize(13);
            dg.setTextColor(0xFF9A9A9A);
            dg.setBackgroundColor(0xFF242424);
            box.addView(dg);
            dg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.widget.ScrollView sc =
                                new android.widget.ScrollView(MainActivity.this);
                        android.widget.TextView tx =
                                new android.widget.TextView(MainActivity.this);
                        tx.setTextSize(12);
                        tx.setTypeface(android.graphics.Typeface.MONOSPACE);
                        tx.setTextColor(0xFFDDDDDD);
                        tx.setText(diagnosticsText());
                        sc.addView(tx);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Diagnostics")
                                .setView(sc)
                                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d3, int w3) {
                                        try {
                                            Intent si = new Intent(Intent.ACTION_SEND);
                                            si.setType("text/plain");
                                            si.putExtra(Intent.EXTRA_TEXT, diagnosticsText());
                                            startActivity(Intent.createChooser(si, "Share diagnostics"));
                                        } catch (Throwable t3) {
                                        }
                                    }
                                })
                                .setNegativeButton("Close", null).show();
                    } catch (Throwable t2) {
                    }
                }
            });
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(box);
             final AlertDialog d = new AlertDialog.Builder(this).setView(sv).create();
            settingsDlgRef = d;
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dark = swDark.isChecked();
                    bgPlay = swBg.isChecked();
                    hueTab = vTab[0];
                    hueNav = vNav[0];
                    huePanel = vPanel[0];
                    prefs.edit().putBoolean("dark", dark).putBoolean("bgPlayV2", bgPlay)
                            .putInt("hueTab", hueTab).putInt("hueNav", hueNav)
                            .putInt("huePanel", huePanel).apply();
                    applyNavBarColor();
                    updateNav();
                    try {
                        web.evaluateJavascript(themeJs(), null);
                    } catch (Throwable t2) {
                    }
                    d.dismiss();
                }
            });
            d.show();
        } catch (Throwable t) {
        }
    }

    private void enterPip() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.PictureInPictureParams p =
                        new android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(new android.util.Rational(16, 9)).build();
                boolean ok = enterPictureInPictureMode(p);
                StreamStore.note("pip enter " + ok);
                if (!ok) {
                    startAudioHandoff();
                }
            } else {
                startAudioHandoff();
            }
        } catch (Throwable t) {
            StreamStore.note("pip fail " + t.getClass().getSimpleName());
            startAudioHandoff();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip) {
        super.onPictureInPictureModeChanged(isInPip);
        inPip = isInPip;
        StreamStore.note("pip mode " + isInPip);
        if (!isInPip && !sForeground) {
            try {
                webPlaying = false;
                web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');"
                                + "if(v){v.pause();}})()", null);
            } catch (Throwable t) {
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // নতুন ব্যবস্থা: PlayerActivity-র নিজস্ব PiP-আচরণ আলাদা; ব্রাউজার থেকে
        // হোম-চাপলে প্লেব্যাক PlayerService-এ চলতে থাকে — এখানে কিছু করার নেই
    }

    /** অডিও-হ্যান্ডঅফ (Background বোতাম + onPause উভয় পথ)। */
    private void startAudioHandoff() {
        try {
            StreamStore.S au = StreamStore.bestAudio();
            if (au == null) {
                StreamStore.note("handoff: no stream");
                return;
            }
            StreamStore.note("handoff itag=" + au.itag);
            audioMode = true;
            webPlaying = false;
            WebView tgt = playTarget();
            if (tgt != null) {
                tgt.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');"
                                + "if(v){v.pause();}})()", null);
            }
            startSvcSafe(new Intent(this, BgService.class)
                    .setAction(BgService.ACT_AUDIO)
                    .putExtra("url", au.url)
                    .putExtra("pos", posMs)
                    .putExtra("title", title));
        } catch (Throwable t) {
        }
    }

    /** ব্যাকগ্রাউন্ড-নিরাপদ সার্ভিস-স্টার্ট (Android 12+ সীমাবদ্ধতা-সহিষ্ণু)। */
    private void startSvcSafe(Intent i) {
        try {
            startForegroundService(i);
            return;
        } catch (Throwable t1) {
            StreamStore.note("fgs-start fail: " + t1.getClass().getSimpleName());
        }
        try {
            startService(i);
            return;
        } catch (Throwable t2) {
            StreamStore.note("svc-start fail: " + t2.getClass().getSimpleName());
        }
    }

    private static final String MINI_JS = "(function(){try{var s=document.createElement('style');"
            + "s.textContent='ytm-header{display:none!important}"
            + ".player-container{position:fixed!important;top:0!important;left:0!important;"
            + "width:100%!important;height:100%!important;padding-bottom:0!important;"
            + "z-index:99999;background:#000!important}"
            + ".player-size,.player-api,.html5-video-player{padding-bottom:0!important;"
            + "width:100%!important;height:100%!important}"
            + "video{width:100%!important;height:100%!important;object-fit:contain}"
            + "body{overflow:hidden!important}';"
            + "document.documentElement.appendChild(s);}catch(e){}}"
            + "function __mp(){try{var v=document.querySelector('video');"
            + "if(v&&v.paused){v.play();}}catch(e){}}"
            + "__mp();setTimeout(__mp,500);setTimeout(__mp,1500);setTimeout(__mp,3000);})();";

    /** BgService যে ভিডিও টগল করবে: মিনি চালু থাকলে মিনি, নয়তো মেইন। */
    public WebView playTarget() {
        return web;
    }

    /** নোটিফ-টগল: ওয়েব-ভিডিও টগল। */
    public void bgToggle() {
        jsToggleWeb();
    }

    private void jsToggleWeb() {
        try {
            web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');"
                            + "if(v){if(v.paused){v.play();}else{v.pause();}}})()", null);
        } catch (Throwable t) {
        }
    }

    /** URL থেকে ভিডিও-আইডি। */
    static String videoIdOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            java.util.regex.Matcher m;
            m = java.util.regex.Pattern.compile("[?&]v=([\\w-]{11})").matcher(url);
            if (m.find()) { return m.group(1); }
            m = java.util.regex.Pattern.compile("youtu\\.be/([\\w-]{11})").matcher(url);
            if (m.find()) { return m.group(1); }
            m = java.util.regex.Pattern.compile("/shorts/([\\w-]{11})").matcher(url);
            if (m.find()) { return m.group(1); }
        } catch (Throwable t) {
        }
        return null;
    }

    private String diagnosticsText() {
        String vn = "?";
        try {
            vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
        }
        return "Wtube v" + vn + "\nUA: "
                + (StreamStore.sUA.length() > 0 ? StreamStore.sUA : "?")
                + "\nStream: "
                + (Resolver.lastDiag.length() > 0 ? Resolver.lastDiag : "?")
                + "\n\n" + StreamStore.dump();
    }

    /** URL থেকে ভিডিওআইডি (watch/shorts/youtu.be), না পেলে null। */
    /**
     * `/results?search_query=<লিংক>` — ইউজার সার্চ-বারে ইউটিউব-লিংক পেস্ট করে
     * এন্টার দিলে ইউটিউব পুরো লিংকটিকে কোয়েরি ধরে ফলাফল-পেজ লোড করে।
     * কোয়েরিটি যদি সম্পূর্ণ একটি ভিডিও-লিংক হয়, ভিডিও-আইডি ফেরত দিই।
     * সাধারণ শব্দ-সার্চে null — কিছুই বদলায় না।
     */
    private static String vidFromPastedLink(Uri u) {
        try {
            String path = u.getPath() == null ? "" : u.getPath();
            if (!path.startsWith("/results")) {
                return null;
            }
            String q = u.getQueryParameter("search_query");
            if (q == null) {
                return null;
            }
            q = q.trim();
            // দ্বি-এনকোডেড (https%253A%252F%252F...) হলেও একবার খুলে নিই
            if (q.startsWith("https%3A") || q.startsWith("http%3A")) {
                try {
                    q = Uri.decode(q);
                } catch (Throwable t2) {
                }
            }
            if (q.length() == 0 || q.length() > 300) {
                return null;
            }
            if (!q.startsWith("http://") && !q.startsWith("https://")) {
                if (q.startsWith("youtu.be/") || q.startsWith("youtube.com/")
                        || q.startsWith("www.youtube.com/") || q.startsWith("m.youtube.com/")) {
                    q = "https://" + q;
                } else {
                    return null;
                }
            }
            Uri lu = Uri.parse(q);
            String host = lu.getHost() == null ? "" : lu.getHost();
            if (!(host.equals("youtu.be") || host.equals("youtube.com")
                    || host.endsWith(".youtube.com"))) {
                return null;
            }
            String p = lu.getPath() == null ? "" : lu.getPath();
            String id = null;
            if (p.startsWith("/watch")) {
                id = lu.getQueryParameter("v");
            } else if (p.startsWith("/shorts/") || p.startsWith("/embed/")
                    || p.startsWith("/live/") || p.startsWith("/v/")) {
                String rest = p.substring(p.indexOf('/', 1) + 1);
                int slash = rest.indexOf('/');
                id = slash > 0 ? rest.substring(0, slash) : rest;
            } else if (host.equals("youtu.be")) {
                String rest = p.length() > 1 ? p.substring(1) : "";
                int slash = rest.indexOf('/');
                id = slash > 0 ? rest.substring(0, slash) : rest;
            }
            if (id == null || id.length() != 11 || !id.matches("[A-Za-z0-9_-]{11}")) {
                return null;
            }
            return id;
        } catch (Throwable t) {
        }
        return null;
    }

    /**
     * `/results?search_query=<ইউটিউব-বহির্ভূত লিংক>` হলে ওই লিংকটি ফেরত দিই।
     * ইউটিউব-হোস্ট হলে null (সেগুলো অন্য পথে সামলানো হয়)।
     */
    private static String otherLinkFromPastedLink(Uri u) {
        try {
            String path = u.getPath() == null ? "" : u.getPath();
            if (!path.startsWith("/results")) {
                return null;
            }
            String q = u.getQueryParameter("search_query");
            if (q == null) {
                return null;
            }
            q = q.trim();
            if (q.startsWith("https%3A") || q.startsWith("http%3A")) {
                try {
                    q = Uri.decode(q);
                } catch (Throwable t2) {
                }
            }
            if (q.length() == 0 || q.length() > 2000) {
                return null;
            }
            if (!q.startsWith("http://") && !q.startsWith("https://")) {
                return null;
            }
            Uri lu = Uri.parse(q);
            String host = lu.getHost() == null ? "" : lu.getHost().toLowerCase();
            if (host.length() == 0 || host.indexOf('.') < 0) {
                return null;
            }
            if (host.endsWith("youtube.com") || host.equals("youtu.be")
                    || host.endsWith("google.com") || host.endsWith("googleusercontent.com")
                    || host.endsWith("gstatic.com") || host.endsWith("ytimg.com")) {
                return null;
            }
            return q;
        } catch (Throwable t) {
        }
        return null;
    }

    private static String vidFromUrl(Uri u) {
        try {
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            if (host.equals("youtu.be")) {
                String id = path.length() > 1 ? path.substring(1) : "";
                return id.length() == 11 ? id : null;
            }
            if (path.startsWith("/watch")) {
                String id = u.getQueryParameter("v");
                return id != null && id.length() == 11 ? id : null;
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /** Watch Later তালিকা — ট্যাপে প্লে। */
    /** লাইব্রেরি-পেজ (নিচ-ডান ট্যাব): Downloads + Watch later + Playlists। */
    private void showLibraryScreen() {
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = dp(20);
            box.setPadding(p, dp(18), p, dp(16));
            box.setBackgroundColor(0xFF0F0F0F);
            sv.addView(box);
            sv.setBackgroundColor(0xFF0F0F0F);
            sv.setVerticalScrollBarEnabled(false);

            android.widget.LinearLayout hrow = new android.widget.LinearLayout(this);
            hrow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            hrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.TextView h = new android.widget.TextView(this);
            h.setText("Library");
            h.setTextSize(24);
            h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            h.setTextColor(0xFFF2F2F2);
            hrow.addView(h, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            LibIcon icProf = new LibIcon(this, "person");
            android.widget.LinearLayout.LayoutParams plp =
                    new android.widget.LinearLayout.LayoutParams(dp(40), dp(40));
            icProf.setLayoutParams(plp);
            icProf.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showProfile();
                }
            });
            hrow.addView(icProf);
            LibIcon icSet = new LibIcon(this, "gear");
            android.widget.LinearLayout.LayoutParams slp =
                    new android.widget.LinearLayout.LayoutParams(dp(40), dp(40));
            slp.leftMargin = dp(12);
            icSet.setLayoutParams(slp);
            icSet.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSettings();
                }
            });
            hrow.addView(icSet);
            box.addView(hrow);

            // ---- History (দেখা ভিডিও) ----
            box.addView(libSection("History"));
            final org.json.JSONArray hA = Library.hist(this);
            if (hA.length() == 0) {
                box.addView(libHint("Videos you watch will appear here"));
            } else {
                int hN = Math.min(4, hA.length());
                for (int hI = 0; hI < hN; hI++) {
                    final org.json.JSONObject hO = hA.optJSONObject(hI);
                    box.addView(libRow(hO.optString("t"),
                            histWhen(hO.optLong("at")),
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    openFromLib(hO.optString("id"), hO.optString("t"));
                                }
                            }));
                }
                box.addView(libRow("See all (" + hA.length() + ")",
                        "Watch history", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                dismissLib();
                                showHistAll();
                            }
                        }));
            }

            // ---- Downloads (Download/WTube) ----
            box.addView(libSection("Downloads"));
            java.util.ArrayList<DownloadService.Snap> snaps = DownloadService.snapshot();
            int nAct = 0;
            for (int i = 0; i < snaps.size(); i++) {
                if (!snaps.get(i).done) {
                    nAct++;
                }
            }
            if (nAct > 0) {
                box.addView(libRow(nAct + " downloads in progress",
                        "Tap to view progress", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                DownloadsSheet.show(MainActivity.this);
                            }
                        }));
                box.addView(libHair());
            }
            java.util.ArrayList<Object[]> dls = queryDownloads(this);
            if (dls.isEmpty()) {
                box.addView(libHint("No downloads yet - they save to Downloads/WTube"));
            } else {
                for (int i = 0; i < dls.size(); i++) {
                    final Object[] d = dls.get(i);
                    final String nm = (String) d[0];
                    box.addView(libRow(nm,
                            libSize((Long) d[1]) + "  \u00B7  " + libDate((Long) d[2]),
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    openDownloaded(MainActivity.this, (android.net.Uri) d[3], nm);
                                }
                            }));
                    if (i < dls.size() - 1) {
                        box.addView(libHair());
                    }
                }
            }

            // ---- Watch later ----
            box.addView(libSection("Watch later"));
            org.json.JSONArray wl = Library.wl(this);
            if (wl.length() == 0) {
                box.addView(libHint("Empty - use the WL button in the player"));
            } else {
                for (int i = 0; i < wl.length(); i++) {
                    org.json.JSONObject o = wl.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    final String vid = o.optString("id");
                    String tt = o.optString("t");
                    final String ftt = tt.length() > 0 ? tt : vid;
                    box.addView(libRow(ftt, "Tap to play", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openFromLib(vid, ftt);
                        }
                    }));
                    if (i < wl.length() - 1) {
                        box.addView(libHair());
                    }
                }
                android.widget.Button cw = libSmallBtn("Clear all");
                cw.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Library.clearWl(MainActivity.this);
                        Toast.makeText(MainActivity.this, "Watch later cleared",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                box.addView(cw);
            }

            // ---- Playlists ----
            box.addView(libSection("Playlists"));
            final java.util.ArrayList<String> pls = Library.playlists(this);
            if (pls.isEmpty()) {
                box.addView(libHint("No playlists yet"));
            } else {
                for (int i = 0; i < pls.size(); i++) {
                    final String nm = pls.get(i);
                    int cnt = Library.pl(this, nm).length();
                    box.addView(libRow(nm, cnt + " videos", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showPlItems(nm);
                        }
                    }));
                    if (i < pls.size() - 1) {
                        box.addView(libHair());
                    }
                }
            }
            android.widget.Button npb = libSmallBtn("New playlist");
            npb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        final android.widget.EditText et = new android.widget.EditText(MainActivity.this);
                        et.setHint("Playlist name");
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("New playlist")
                                .setView(et)
                                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        String n = et.getText().toString().trim();
                                        if (Library.createPl(MainActivity.this, n)) {
                                            Toast.makeText(MainActivity.this,
                                                    "Created: " + n, Toast.LENGTH_SHORT).show();
                                            showLibraryScreen();
                                        } else {
                                            Toast.makeText(MainActivity.this,
                                                    "Invalid or duplicate name",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } catch (Throwable t) {
                    }
                }
            });
            box.addView(npb);

            final android.app.Dialog dg = new android.app.Dialog(this,
                    android.R.style.Theme_Material_NoActionBar);
            dg.setContentView(sv);
            dg.setCanceledOnTouchOutside(true);
            android.view.Window win = dg.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
                win.setLayout(-1, -1);
            }
            dg.show();
            libDlg = dg;
            dg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d3) {
                    if (libDlg == dg) {
                        libDlg = null;
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private void dismissLib() {
        try {
            if (libDlg != null) {
                libDlg.dismiss();
            }
        } catch (Throwable t) {
        }
        libDlg = null;
        /* The Profile sheet is opened from the Library sheet, so closing one has
           to close the other. Otherwise the Profile sheet stays over the
           WebView and the page we just navigated to loads out of sight, making
           the button the user pressed look dead. */
        try {
            if (profDlg != null) {
                profDlg.dismiss();
            }
        } catch (Throwable t) {
        }
        profDlg = null;
    }

    /** অ্যাভাটার-URL থেকে ছবি নামিয়ে ক্যাশে রাখা (Bridge.account + লেজি-লোড) */
    private void fetchAvatar(final String pic) {
        if (pic == null || pic.length() < 8) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String u = pic.startsWith("//") ? "https:" + pic : pic;
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        bo.write(b, 0, n);
                    }
                    in.close();
                    c.disconnect();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                            .decodeByteArray(bo.toByteArray(), 0, bo.size());
                    if (bmp != null) {
                        accBmp = bmp;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                try {
                                    if (accIconRef != null) {
                                        accIconRef.postInvalidate();
                                    }
                                } catch (Throwable t) {
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                }
            }
        }).start();
    }

    /* ================================================================
       Google account sign-in
       ----------------------------------------------------------------
       Sign-in has to happen inside this WebView: the YouTube session
       lives in the WebView cookie jar, and a native Google Sign-In token
       cannot be turned into those cookies.  Google blocks OAuth inside
       embedded webviews that advertise "; wv" in the user-agent, which
       setupWeb() already strips, but it may still refuse - in that case
       the user sees Google's own "browser not secure" page.
       ================================================================ */

    /** Google's YouTube sign-in entry point (kept inside the app WebView). */
        private void flushCookies() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                android.webkit.CookieManager.getInstance().flush();
            }
        } catch (Throwable t) {
        }
    }

    /** A Chrome-for-Android user agent, built from scratch rather than by
     *  editing the WebView default.
     *
     *  Why this matters: WebView only populates the Sec-CH-UA client hints when
     *  the overridden UA still contains the default UA. Just deleting "; wv"
     *  from the default string leaves it close enough that WebView keeps
     *  advertising itself, and every request then carries
     *      Sec-CH-UA: "Chromium";v="138", "Android WebView";v="138"
     *  which is exactly what makes Google reject the sign-in with "This browser
     *  or app may not be secure". Those hints cannot be removed through any
     *  public WebView API, so the only lever is to stop WebView from deriving
     *  them: give it a UA that does not contain the default one, and it sends
     *  no WebView-branded hints at all.
     *
     *  The Chrome major version is still taken from the real WebView so the
     *  string matches the engine actually rendering the page. */
    private static String chromeUa(String def) {
        try {
            String chrome = "120.0.0.0";
            if (def != null) {
                int i = def.indexOf("Chrome/");
                if (i >= 0) {
                    String rest = def.substring(i + 7);
                    int sp = rest.indexOf(' ');
                    String ver = sp > 0 ? rest.substring(0, sp) : rest;
                    int dot = ver.indexOf('.');
                    if (dot > 0) {
                        chrome = ver.substring(0, dot) + ".0.0.0";
                    }
                }
            }
            String model = Build.MODEL;
            if (model == null || model.length() == 0) {
                model = "Pixel";
            }
            String rel = Build.VERSION.RELEASE;
            if (rel == null || rel.length() == 0) {
                rel = "13";
            }
            return "Mozilla/5.0 (Linux; Android " + rel + "; " + model
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                    + " Mobile Safari/537.36";
        } catch (Throwable t) {
            return "Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        }
    }

    /** Host of a URL, lower-cased, or "" when it cannot be parsed. */
    private static String hostOfUrl(String url) {
        try {
            String s = url == null ? "" : url;
            int i = s.indexOf("://");
            if (i >= 0) {
                s = s.substring(i + 3);
            }
            int j = s.indexOf('/');
            if (j >= 0) {
                s = s.substring(0, j);
            }
            int q = s.indexOf('?');
            if (q >= 0) {
                s = s.substring(0, q);
            }
            int k = s.indexOf(':');
            if (k >= 0) {
                s = s.substring(0, k);
            }
            return s.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    /** What to inject for this page.
     *
     *  The YouTube bundle rewrites JSON.parse and fetch and restyles the top
     *  bar. That is what we want on YouTube, and not on Google's sign-in pages
     *  - our rewriting can deadlock a page whose whole job is to post
     *  credentials and follow redirects. Those pages get nothing injected, so
     *  they behave exactly as Google shipped them. */
    private String injectFor(String url) {
        try {
            String h = hostOfUrl(url);
            if (h.length() == 0) {
                return "";
            }
            if (h.equals("youtube.com") || h.endsWith(".youtube.com")
                    || h.equals("youtu.be")) {
                /* YouTube's own sign-in screens live on youtube.com, so the host
                   check alone would give them the full bundle. */
                if (isSigninPath(url)) {
                    return "";
                }
                return coreJs + themeJs() + injectJs;
            }
            if (h.equals("accounts.google.com") || h.endsWith(".accounts.google.com")) {
                return "";
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** True when inject.js has reported an account name for the signed-in user. */
        /* ================================================================
       In-app browser (Chrome Custom Tabs)

       Google will not serve its sign-in pages to an embedded WebView -
       that is policy, enforced on Google's servers, and no amount of
       user-agent work changes it. A Custom Tab is a different thing: it
       renders with Chrome's engine and stores its cookies in Chrome, so
       Google serves it normally and the user really can sign in.

       The price of that is isolation: Chrome's cookie jar is not ours, and
       Chromium's Custom Tabs security model forbids the app from reading
       it. So the browser can be signed in while the WebView is not, and
       the two can never be merged. Pages that need an account are therefore
       shown out here; everything else stays in the WebView, which keeps
       our ad blocking, theming and native player.
       ================================================================ */

    /** Packages able to render a Custom Tab, best first. Chrome is preferred
     *  because it is the browser whose cookie jar the tab will use. */
    private static final String[] CCT_PKGS = {
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.google.android.apps.chrome",
    };

    private String cctPackage() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            for (int i = 0; i < CCT_PKGS.length; i++) {
                try {
                    pm.getPackageInfo(CCT_PKGS[i], 0);
                    return CCT_PKGS[i];
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /** Open a URL in a real browser surface.

        The extras below are the ones androidx.browser puts on the Intent;
        Chrome reads them straight off it, so no support library is needed.
        A null SESSION is valid - androidx.browser sends exactly that when no
        CustomTabsSession has been created. */
    private void openInAppBrowser(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null);
            extras.putInt("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF0F0F0F);
            extras.putInt("android.support.customtabs.extra.SECONDARY_TOOLBAR_COLOR",
                    0xFF0F0F0F);
            i.putExtras(extras);
            String pkg = cctPackage();
            if (pkg != null) {
                i.setPackage(pkg);
            }
            startActivity(i);
        } catch (Throwable t) {
            try {
                android.widget.Toast.makeText(this, "No browser available",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t2) {
            }
        }
    }

    /** YouTube pages that are meaningless without an account. */
    private static boolean isAccountPage(String url) {
        try {
            String u = url == null ? "" : url.toLowerCase();
            int i = u.indexOf("://");
            if (i >= 0) {
                u = u.substring(i + 3);
            }
            int j = u.indexOf('/');
            String host = j >= 0 ? u.substring(0, j) : u;
            String path = j >= 0 ? u.substring(j) : "/";
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
                return false;
            }
            return path.startsWith("/feed/subscriptions")
                    || path.startsWith("/feed/you")
                    || path.startsWith("/feed/history")
                    || path.startsWith("/feed/notifications");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Show a URL where it works best: account pages in the real browser,
     *  everything else in the WebView. */
    private void openUrl(String url) {
        try {
            if (prefs != null && prefs.getBoolean("acctbrowser", true)
                    && isAccountPage(url)) {
                openInAppBrowser(url);
                return;
            }
        } catch (Throwable t) {
        }
        try {
            web.loadUrl(url);
        } catch (Throwable t) {
        }
    }

            /** Avatar + account name header, or a "not signed in" line when signed out. */
        /** Prominent "Sign in with Google" row, shown when signed out. */
        private android.view.View libSection(String t) {
        android.widget.TextView v = new android.widget.TextView(this);
        v.setText(t);
        v.setTextColor(hslColor(huePanel));
        v.setTextSize(13);
        v.setTypeface(null, 1);
        v.setPadding(0, dp(20), 0, dp(4));
        return v;
    }

    private android.view.View libHint(String t) {
        android.widget.TextView v = new android.widget.TextView(this);
        v.setText(t);
        v.setTextColor(0xFF6F6F75);
        v.setTextSize(12);
        v.setPadding(0, dp(8), 0, dp(4));
        return v;
    }

    private android.view.View libHair() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFF232326);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1,
                Math.max(1, dp(1))));
        return v;
    }

    private android.widget.Button libSmallBtn(String t) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setTextColor(0xFFDDDDDD);
        b.setBackgroundColor(0xFF242424);
        return b;
    }

    private android.view.View libRow(String title, String sub, View.OnClickListener click) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        android.widget.TextView t1 = new android.widget.TextView(this);
        t1.setText(title);
        t1.setTextColor(0xFFF2F2F2);
        t1.setTextSize(15);
        t1.setMaxLines(2);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t1);
        if (sub != null && sub.length() > 0) {
            android.widget.TextView t2 = new android.widget.TextView(this);
            t2.setText(sub);
            t2.setTextColor(0xFF9A9A9A);
            t2.setTextSize(12);
            t2.setMaxLines(1);
            row.addView(t2);
        }
        row.setOnClickListener(click);
        return row;
    }

    private static String libSize(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " B";
    }

    private static String libDate(long millis) {
        try {
            return new java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                    .format(new java.util.Date(millis));
        } catch (Throwable t) {
            return "";
        }
    }

    /** Download/WTube-ফোল্ডারের ফাইলতালিকা (MediaStore / ফাইল-সিস্টেম)। */
    public static java.util.ArrayList<Object[]> queryDownloads(android.content.Context c) {
        java.util.ArrayList<Object[]> out = new java.util.ArrayList<Object[]>();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.database.Cursor q = c.getContentResolver().query(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{
                                android.provider.MediaStore.Downloads.DISPLAY_NAME,
                                android.provider.MediaStore.Downloads.SIZE,
                                android.provider.MediaStore.Downloads.DATE_MODIFIED,
                                android.provider.MediaStore.Downloads._ID},
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                        new String[]{"Download/WTube%"},
                        android.provider.MediaStore.Downloads.DATE_MODIFIED + " DESC");
                if (q != null) {
                    while (q.moveToNext() && out.size() < 100) {
                        out.add(new Object[]{q.getString(0), q.getLong(1),
                                q.getLong(2) * 1000L,
                                android.content.ContentUris.withAppendedId(
                                        android.provider.MediaStore.Downloads
                                                .EXTERNAL_CONTENT_URI, q.getLong(3))});
                    }
                    q.close();
                }
            } else {
                java.io.File dir = new java.io.File(android.os.Environment
                        .getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "WTube");
                java.io.File[] fs = dir.listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
                        @Override
                        public int compare(java.io.File a, java.io.File b) {
                            return Long.compare(b.lastModified(), a.lastModified());
                        }
                    });
                    for (java.io.File f : fs) {
                        if (f.isFile() && out.size() < 100) {
                            out.add(new Object[]{f.getName(), f.length(),
                                    f.lastModified(), android.net.Uri.fromFile(f)});
                        }
                    }
                }
            }
        } catch (Throwable t) {
        }
        return out;
    }

    /** নামানো ফাইল মুছে ফেলা (MediaStore-সারি বা ফাইল); সত্য = সফল। */
    /** শিট/অন্য জায়গা থেকে ভিডিও-খোলা */
    public void openVideoById(String vid, String title) {
        try {
            routeVideo(vid, title, false);
        } catch (Throwable t) {
        }
    }

    public static boolean deleteDownloaded(android.content.Context ctx,
            android.net.Uri uri, String name) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                int n = ctx.getContentResolver().delete(uri, null, null);
                return n > 0;
            }
            java.io.File f = new java.io.File(uri.getPath() == null ? "" : uri.getPath());
            return f.exists() && f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openDownloaded(android.content.Context ctx, android.net.Uri uri, String name) {
        try {
            String ext = name.endsWith(".mp3") ? "audio/mpeg"
                    : name.endsWith(".srt") ? "text/plain"
                    : name.endsWith(".m4a") ? "audio/mp4" : "video/mp4";
            Intent v = new Intent(Intent.ACTION_VIEW);
            v.setDataAndType(uri, ext);
            v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(v);
        } catch (Throwable t) {
            try {
                Intent sh = new Intent(Intent.ACTION_SEND);
                sh.setType("*/*");
                sh.putExtra(Intent.EXTRA_STREAM, uri);
                sh.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(Intent.createChooser(sh, name));
            } catch (Throwable t2) {
                Toast.makeText(ctx, "No app to open this file",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showWatchLater() {
        try {
            final org.json.JSONArray a = Library.wl(this);
            if (a.length() == 0) {
                Toast.makeText(this, "Watch later is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = new String[a.length()];
            final String[] ids = new String[a.length()];
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                ids[i] = o.optString("id");
                String t = o.optString("t");
                items[i] = t.length() > 0 ? t : ids[i];
            }
            new AlertDialog.Builder(this)
                    .setTitle("Watch later")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openFromLib(ids[w], items[w]);
                        }
                    })
                    .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Library.clearWl(MainActivity.this);
                            Toast.makeText(MainActivity.this, "Watch later cleared",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    /** প্লেলিস্ট-নামের তালিকা। */
    private void showPlaylists() {
        try {
            final java.util.ArrayList<String> names = Library.playlists(this);
            if (names.isEmpty()) {
                Toast.makeText(this, "No playlists yet", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Playlists")
                    .setItems(names.toArray(new String[0]),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    showPlItems(names.get(w));
                                }
                            })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    /** একটি প্লেলিস্টের ভিডিও — ট্যাপে প্লে। */
    private void showPlItems(final String name) {
        try {
            final org.json.JSONArray a = Library.pl(this, name);
            if (a.length() == 0) {
                Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = new String[a.length()];
            final String[] ids = new String[a.length()];
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                ids[i] = o.optString("id");
                String t = o.optString("t");
                items[i] = t.length() > 0 ? t : ids[i];
            }
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openFromLib(ids[w], items[w]);
                        }
                    })
                    .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Library.deletePl(MainActivity.this, name);
                            Toast.makeText(MainActivity.this, "Playlist deleted",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    private void openFromLib(String vid, String title) {
        try {
            if (vid == null || vid.length() < 11) {
                return;
            }
            startActivity(new Intent(MainActivity.this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_VIDEOID, vid)
                    .putExtra(PlayerService.EXTRA_TITLE,
                            title != null && title.length() > 0 ? title : vid));
        } catch (Throwable t) {
        }
    }

    /** ভিডিও-ক্লিক: সাধারণ ভিডিও = সরাসরি বড় প্লেয়ার;
     *  Shorts = আমাদের প্লেয়ারে নয় — ওয়েবেই চলে (আগের মতো)। */
    private void routeVideo(String vid, String title, boolean shortVid) {
        try {
            if (vid == null || vid.length() < 11 || shortVid) {
                return;
            }
            if (vid.equals(sSuppressVideo)) {
                sSuppressVideo = null;
                StreamStore.note("route " + vid + " suppressed");
                return;
            }
            StreamStore.note("route " + vid + " -> player");
            startActivity(new Intent(MainActivity.this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_VIDEOID, vid)
                    .putExtra(PlayerService.EXTRA_TITLE,
                            title != null && title.length() > 0 ? title : vid));
        } catch (Throwable t) {
        }
    }

    /** প্লেয়ারের ওয়েব-ফলব্যাক: watch-পেজ WebView-এ খোলে (একবার, লুপ ছাড়া)। */
    /** আমাদের ইঞ্জিন বাজছে তখন ওয়েব-ভিডিও পজ থাকে (ডাবল-অডিও গার্ড)। */
    private void syncWebPause() {
        try {
            PlayerService sv = PlayerService.sSelf;
            boolean ours = sv != null && sv.hasItem() && sv.player() != null
                    && sv.player().getPlayWhenReady();
            String js = ours
                    ? "(function(){try{window.__mtpause=Date.now()+600000;"
                      + "var vs=document.querySelectorAll('video');"
                      + "for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}}"
                      + "}catch(e){}})()"
                    : "(function(){try{window.__mtpause=0;}catch(e){}})()";
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
        }
    }

    private NavTab navBtn(String label, String url, int iconIdx) {
        final NavTab b = new NavTab(this);
        b.set(label, iconIdx);
        b.setSkin(dark ? 0xFF0F0F0F : 0xFFF2F2F2, dark ? 0xFF909090 : 0xFF606060);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0, -1, 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    web.loadUrl(url);
                } catch (Throwable t) {
                }
            }
        });
        return b;
    }

    private void updateNav() {
        try {
            String u = lastUrl == null ? "" : lastUrl;
            int acc = hslColor(hueTab);
            if (navHome != null) {
                navHome.setActive(!u.contains("/shorts") && !u.contains("/feed"), acc);
            }
            if (navShorts != null) {
                navShorts.setActive(u.contains("/shorts"), acc);
            }
            if (navSubs != null) {
                navSubs.setActive(u.contains("/feed/subscriptions"), acc);
            }
            if (navLib != null) {
                navLib.setActive(u.contains("/feed") && !u.contains("/subscriptions"), acc);
            }
        } catch (Throwable t) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        try {
            String ou = i != null ? i.getStringExtra("openUrl") : null;
            if (ou != null && ou.length() > 0 && web != null) {
                web.loadUrl(ou); // প্লেয়ার থেকে চ্যানেল-খোলা
                return;
            }
            if (i != null && i.getData() != null && web != null) {
                web.loadUrl(i.getData().toString());
            }
        } catch (Throwable t) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("mt_url", lastUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sForeground = true;
        sInstance = this;
        if (!crashShown) {
            crashShown = true;
            showCrashIfAny();
        }
        updateNav();
        syncWebPause();
        if (audioMode) {
            audioMode = false;
            try {
                startService(new Intent(this, BgService.class)
                        .setAction(BgService.ACT_AUDIO_STOP));
            } catch (Throwable t) {
            }
            try {
                final long p = posMs;
                web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){"
                                + "try{v.currentTime=" + (p / 1000.0) + ";}catch(e){}"
                                + "v.play();}})()", null);
            } catch (Throwable t) {
            }
        }
    }

    @Override
    protected void onPause() {
        sForeground = false;
        flushCookies();
        try {
            StreamStore.note("pause web=" + webPlaying + " pip=" + inPip
                    + " svc=" + BgService.sRunning + " caps=" + StreamStore.count());
            if (inPip) {
                super.onPause();
                return;
            }
            if (!bgPlay && webPlaying) {
                startSvcSafe(new Intent(this, BgService.class)
                        .setAction(BgService.ACT_STOP));
                webPlaying = false;
            } else if (webPlaying && !audioMode) {
                StreamStore.S au = StreamStore.bestAudio();
                if (au == null) {
                    StreamStore.note("no stream; web-continue");
                    startSvcSafe(new Intent(this, BgService.class)
                            .setAction(BgService.ACT_START));
                } else {
                    startAudioHandoff();
                }
            }
        } catch (Throwable t) {
        }
        super.onPause();
    }

    private void handleBack() {
        try {
            if (web != null) {
                if (web.canGoBack()) {
                    web.goBack();
                    return;
                }
                String hu = lastUrl;
                if (hu != null) {
                    String path = Uri.parse(hu).getPath();
                    boolean home = path == null || path.equals("")
                            || path.equals("/") || path.startsWith("/feed")
                            || path.startsWith("/shorts");
                    if (!home) {
                        web.loadUrl("https://m.youtube.com");
                        return;
                    }
                }
            }
        } catch (Throwable t) {
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2200) {
            finish();
        } else {
            lastBack = now;
            try {
                android.widget.Toast.makeText(this,
                        "Press back again to exit",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        ins = null;
        try {
        } catch (Throwable t) {
        }
        try {
            if (web != null) {
                web.destroy();
            }
        } catch (Throwable t) {
        }
        super.onDestroy();
    }

    private void askNotifOnce() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && !notifAsked
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifAsked = true;
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Throwable t) {
        }
    }

    private void showCrashIfAny() {
        try {
            final File f = CrashHandler.logFile(this);
            if (!f.exists() || f.length() == 0) {
                return;
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            FileInputStream fi = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int r;
            while ((r = fi.read(buf)) > 0) {
                bo.write(buf, 0, r);
            }
            fi.close();
            String txt = bo.toString("UTF-8");
            int i = txt.lastIndexOf("==== ");
            if (i < 0) {
                f.delete();
                return;
            }
            final String body = txt.substring(i);
            new AlertDialog.Builder(this)
                    .setTitle("App crashed last session")
                    .setMessage("Please share this crash log:\n\n"
                            + (body.length() > 3000 ? body.substring(0, 3000) : body))
                    .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Intent i = new Intent(Intent.ACTION_SEND);
                                i.setType("text/plain");
                                i.putExtra(Intent.EXTRA_TEXT, body);
                                startActivity(Intent.createChooser(i, "Crash log"));
                            } catch (Throwable t) {
                            }
                            f.delete();
                        }
                    })
                    .setNegativeButton("Dismiss", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            f.delete();
                        }
                    })
                    .setCancelable(false)
                    .show();
        } catch (Throwable t) {
        }
    }

    /**
     * ইউটিউবের বাইরের লিংক ব্রাউজারে খুলুন এবং মিডিয়া-স্নিফার চালু করুন।
     * "no media" হলে কিছুই দেখানো হয় না — শুধু পেজটা খোলে।
     */
    private void openExternalPage(String url) {
        try {
            final String u = url.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                return;
            }
            MediaSniffer.arm();
            String pageTitle = "";
            if (web != null) {
                web.loadUrl(u);
                try {
                    pageTitle = web.getTitle();
                } catch (Throwable t) {
                }
            }
            if (navBar != null) {
                navBar.setVisibility(View.GONE);
            }
            // yt-dlp-স্টাইল এক্সট্র্যাকশন আগে চালাই (TikTok/Facebook/Instagram):
            // পেজের ভেতরের JSON থেকেই সরাসরি স্ট্রিম-লিংক বের হয়, তাই ভিডিওটা
            // ব্রাউজারে লোড হওয়ার অপেক্ষা করতে হয় না
            final boolean isDirect = Extractors.directExt(u) != null;
            if (Extractors.canHandle(u)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            java.util.ArrayList<Extractors.Hit> hits = Extractors.extract(u);
                            for (int i = 0; i < hits.size(); i++) {
                                Extractors.Hit h = hits.get(i);
                                MediaSniffer.offer(h.url, h.ext, h.audio, null);
                            }
                            StreamStore.note("extract " + hits.size() + " hits "
                                    + Extractors.host(u));
                        } catch (Throwable t) {
                        }
                    }
                }).start();
            }
            // ইউটিউবে ডাউনলোড চাপলে যেমন অপশন-শিট আসে, এখানেও তাই — পেজ
            // লোড হওয়ার সাথে সাথেই খুলে যায় এবং ফাইল পেলেই সারি যোগ হয়
            MediaOptionsSheet.show(this,
                    pageTitle == null || pageTitle.length() == 0 ? hostOfUrl(u) : pageTitle,
                    new MediaOptionsSheet.Source() {
                        @Override
                        public java.util.ArrayList<MediaSniffer.M> list() {
                            return MediaSniffer.list();
                        }

                        @Override
                        public boolean done() {
                            return false; // নিজের ১৫-সেকেন্ড সময়সীমা শিটেই আছে
                        }
                    },
                    new MediaOptionsSheet.OnPick() {
                        @Override
                        public void pick(MediaSniffer.M m) {
                            try {
                                startService(new Intent(MainActivity.this,
                                        DownloadService.class)
                                        .setAction(DownloadService.ACT_START)
                                        .putExtra(DownloadService.EXTRA_TITLE,
                                                titleOr(m, u))
                                        .putExtra(DownloadService.EXTRA_DIRECT, m.url)
                                        .putExtra(DownloadService.EXTRA_DEXT, m.ext));
                                DownloadsSheet.show(MainActivity.this);
                            } catch (Throwable t) {
                            }
                        }
                    },
                    // সত্যি কথাটাই দেখাই: সরাসরি ফাইল-লিংক = resolve, নাহলে scan
                    isDirect ? "Preparing download..." : "Resolving link...");
        } catch (Throwable t) {
        }
    }

    private String titleOr(MediaSniffer.M m, String fallback) {
        try {
            String t = web != null ? web.getTitle() : null;
            if (t != null && t.length() > 0) {
                return t.length() > 80 ? t.substring(0, 80) : t;
            }
        } catch (Throwable t) {
        }
        return hostOfUrl(fallback);
    }

    /**
     * বহিঃ-পেজে ঢোকানো ছোট স্ক্রিপ্ট: পেজের <video>/<audio> ও <source>
     * এলিমেন্টের ঠিকানা বারবার পড়ে অ্যাপকে জানায়। blob: ঠিকানা কাজে লাগে
     * না, তাই bridge সেগুলো নিজেই বাদ দেয়।
     */
    private static String mediaProbeJs() {
        return "(function(){try{if(window.__mtprobe){return;}window.__mtprobe=1;"
                + "function grab(u){try{if(!u){return;}MTApp.mediaFound(u);}catch(e){}}"
                + "function scan(){try{"
                + "var vs=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<vs.length;i++){var v=vs[i];grab(v.currentSrc);grab(v.src);"
                + "var ss=v.querySelectorAll('source');"
                + "for(var j=0;j<ss.length;j++){grab(ss[j].src);}}}catch(e){}}"
                + "setInterval(scan,1200);scan();}catch(e){}})();";
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private class Bridge {

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null || msg.length() == 0) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(MainActivity.this, msg,
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                    }
                }
            });
        }

        /**
         * সার্চ-বারে ইউটিউবের বাইরের লিংক পেস্ট করা হয়েছে — পেজটা খুলে দিই
         * এবং মিডিয়া-স্নিফার চালু করি। ফাইল পেলে ডাউনলোড-শিট আসবে,
         * না পেলে কিছুই হবে না (ব্যবহারকারী শুধু পেজটা দেখবেন)।
         */
        @JavascriptInterface
        public void openLink(final String url) {
            if (url == null || url.length() == 0) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        openExternalPage(url);
                    } catch (Throwable t) {
                    }
                }
            });
        }

        /** পেজের নিজের <video> থেকে পাওয়া মিডিয়া-URL (স্নিফার মিস করলে)। */
        @JavascriptInterface
        public void mediaFound(final String url) {
            try {
                if (url == null || url.length() == 0) {
                    return;
                }
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    return; // blob — ডাউনলোড করার মতো ঠিকানা নয়
                }
                MediaSniffer.capture(url);
            } catch (Throwable t) {
            }
        }

        @JavascriptInterface
        public void account(final String name, final String pic) {
            try {
                prefs.edit().putString("accName", name == null ? "" : name)
                        .putString("accPic", pic == null ? "" : pic).apply();
                fetchAvatar(pic);
            } catch (Throwable t) {
            }
        }

        @JavascriptInterface
        public void openVideo(final String vid, final String title, final boolean shortVid) {
            StreamStore.note("js openVideo " + vid + (shortVid ? " (short)" : ""));
            runOnUiThread(new Runnable() {
                public void run() {
                    routeVideo(vid, title, shortVid);
                }
            });
        }

        @JavascriptInterface
        public void play() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (audioMode) {
                            return;
                        }
                        webPlaying = true;
                        if (sForeground) {
                            askNotifOnce();
                        }
                        if (BgService.sRunning) {
                            startSvcSafe(new Intent(MainActivity.this, BgService.class)
                                    .setAction(BgService.ACT_RESUME));
                        } else if (sForeground) {
                            startSvcSafe(new Intent(MainActivity.this, BgService.class)
                                    .setAction(BgService.ACT_START));
                        }
                    } catch (Throwable t) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    showSettings();
                }
            });
        }

        @JavascriptInterface
        public void fmt(String url, String itag, String mime) {
            StreamStore.addFmt(url, itag, mime);
        }

        @JavascriptInterface
        public void nav(String url) {
            if (url != null && url.contains("youtube.com")) {
                lastUrl = url;
                // ইউটিউবে ফিরে এলে বহিঃ-পেজের স্নিফিং বন্ধ
                MediaSniffer.disarm();
            }
        }

        @JavascriptInterface
        public void pip() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        enterPip();
                    } catch (Throwable t) {
                        StreamStore.note("pip err " + t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void bgaudio() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startAudioHandoff();
                    } catch (Throwable t) {
                        StreamStore.note("bgaudio err " + t);
                    }
                    try {
                        android.widget.Toast.makeText(MainActivity.this,
                                "Playing in background",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (audioMode) {
                            return;
                        }
                        webPlaying = false;
                        if (!BgService.sRunning) {
                            return;
                        }
                        Intent i = new Intent(MainActivity.this, BgService.class)
                                .setAction(sForeground ? BgService.ACT_STOP
                                        : BgService.ACT_PAUSE);
                        startSvcSafe(i);
                    } catch (Throwable t) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void pos(double cur, double dur, String t, String url) {
            try {
                if (url != null && url.contains("youtube.com")) {
                    lastUrl = url;
                }
                webPlaying = true;
                if (sForeground && !BgService.sRunning) {
                    StreamStore.note("pos: ensure service");
                    startSvcSafe(new Intent(MainActivity.this, BgService.class)
                            .setAction(BgService.ACT_START));
                }
                if (Double.isFinite(cur)) {
                    posMs = (long) (cur * 1000.0);
                }
                if (Double.isFinite(dur) && dur > 0) {
                    durMs = (long) (dur * 1000.0);
                }
                if (t != null && t.length() > 0) {
                    title = t;
                }
            } catch (Throwable th) {
            }
        }
    }
}
