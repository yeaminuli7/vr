package com.mytube.web;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ডাউনলোড-স্ক্রিন ও হিস্ট্রি-স্ক্রিনের শেয়ার্ড UI-টুকরো — এক জায়গায় রাখলে
 * দুটো স্ক্রিনের চেহারা হুবহু একই থাকে।
 */
public final class Ui {

    private Ui() {
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** দুই সারির মাঝের পাতলা রেখা। */
    static View hair(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF1C1C1F);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1))));
        return v;
    }

    /** সেকশন-হেডার (TODAY / DOWNLOADED ইত্যাদি)। */
    static View sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFF8E8E96);
        t.setTextSize(11);
        t.setTypeface(null, 1);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(c, 14), 0, dp(c, 6));
        return t;
    }

    static GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * 3f);
        return g;
    }

    /** থাম্বনেইলের জায়গায় বসানো গ্রেডিয়েন্ট (কালো বাক্সের বদলে)। */
    static GradientDrawable placeholder(Context c, boolean audio) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                audio ? new int[]{0xFF2A2A32, 0xFF15151A}
                      : new int[]{0xFF34343D, 0xFF1A1A20});
        g.setCornerRadius(dp(c, 4));
        return g;
    }

    /** তিন-বিন্দু (ওভারফ্লো) আইকন — কোনো ইমোজি নয়, নিজে আঁকা। */
    static final class DotsIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsIcon(Context c) {
            super(c);
            p.setColor(0xFF9A9A9A);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) / 9f;
            cv.drawCircle(cx, cy - r * 3.1f, r, p);
            cv.drawCircle(cx, cy, r, p);
            cv.drawCircle(cx, cy + r * 3.1f, r, p);
        }
    }

    /** সবুজ টিক-চিহ্ন ("নামানো হয়েছে")। */
    static final class CheckIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckIcon(Context c) {
            super(c);
            p.setColor(0xFF34D399);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f,
                    1.6f * c.getResources().getDisplayMetrics().density));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            android.graphics.Path pa = new android.graphics.Path();
            pa.moveTo(w * 0.18f, h * 0.55f);
            pa.lineTo(w * 0.42f, h * 0.80f);
            pa.lineTo(w * 0.86f, h * 0.26f);
            cv.drawPath(pa, p);
        }
    }

    /** ছোট গোলাকার বোতাম (Pause / Cancel / Retry)। */
    static final class ActionButton extends android.widget.Button {
        ActionButton(Context c, String text) {
            super(c);
            setText(text);
            setTextSize(11);
            setTextColor(0xFFF2F2F5);
            setAllCaps(false);
            setBackground(roundRect(0xFF242424, 3));
            setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(c, 6);
            setLayoutParams(lp);
        }
    }

    static String sizeStr(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " B";
    }

    /**
     * ডাউনলোড শেষ হতে কত সময় বাকি। গতি জানা না থাকলে null।
     * "~45s left", "~3m 20s left", "~1h 05m left"
     */
    static String etaStr(long got, long total, double mbs) {
        try {
            if (got <= 0 || total <= 0 || got >= total || !(mbs > 0.001)) {
                return null;
            }
            long sec = (long) Math.ceil((total - got) / 1048576.0 / mbs);
            if (sec < 0) {
                return null;
            }
            if (sec < 60) {
                return "~" + sec + "s left";
            }
            if (sec < 3600) {
                long m = sec / 60, r = sec % 60;
                return r > 0 ? "~" + m + "m " + r + "s left" : "~" + m + "m left";
            }
            long h = sec / 3600, m = (sec % 3600) / 60;
            return "~" + h + "h " + (m < 10 ? "0" + m : String.valueOf(m)) + "m left";
        } catch (Throwable t) {
            return null;
        }
    }

    static String fmtDur(long sec) {
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }
}
