package com.mytube.web;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Nav-bar tab: vector icon + label, drawn (no emoji, no images). */
public class NavTab extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private String label = "";
    private int icon = 0;
    private boolean active = false;
    private int accent = 0xFFE8212F;
    private int base = 0xFF909090;
    private int bg = 0xFF0F0F0F;
    private final float u;

    public NavTab(Context c) {
        super(c);
        u = getResources().getDisplayMetrics().density;
    }

    public void set(String lbl, int iconType) {
        label = lbl;
        icon = iconType;
        invalidate();
    }

    public void setActive(boolean on, int acc) {
        active = on;
        accent = acc;
        invalidate();
    }

    public void setSkin(int background, int baseColor) {
        bg = background;
        base = baseColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float isz = 26 * u; // বড় আইকন (লেখা নেই — ইউটিউব-অ্যাপের মতো)
        float ix = (w - isz) / 2f;
        float iy = (h - isz) / 2f;
        p.setColor(active ? accent : base);
        p.setStyle(Paint.Style.FILL);
        p.setFakeBoldText(active);
        drawIcon(cv, ix, iy, isz);
    }

    private void bar(Canvas cv, float x1, float y1, float x2, float y2) {
        rect.set(x1, y1, x2, y2);
        float r = (y2 - y1) / 2f;
        cv.drawRoundRect(rect, r, r, p);
    }

    private void tri(Canvas cv, float x, float y, float s, int color) {
        path.reset();
        path.moveTo(x, y);
        path.lineTo(x, y + s);
        path.lineTo(x + s * 0.87f, y + s / 2f);
        path.close();
        int old = p.getColor();
        p.setColor(color);
        cv.drawPath(path, p);
        p.setColor(old);
    }

    private void drawIcon(Canvas cv, float x, float y, float s) {
        float k = s / 24f;
        switch (icon) {
            case 0: // Home
                path.reset();
                path.moveTo(x + 12 * k, y + 3.5f * k);
                path.lineTo(x + 21 * k, y + 11.5f * k);
                path.lineTo(x + 21 * k, y + 20.5f * k);
                path.lineTo(x + 15.5f * k, y + 20.5f * k);
                path.lineTo(x + 15.5f * k, y + 14.5f * k);
                path.lineTo(x + 8.5f * k, y + 14.5f * k);
                path.lineTo(x + 8.5f * k, y + 20.5f * k);
                path.lineTo(x + 3 * k, y + 20.5f * k);
                path.lineTo(x + 3 * k, y + 11.5f * k);
                path.close();
                cv.drawPath(path, p);
                break;
            case 1: // Shorts
                rect.set(x + 5 * k, y + 3 * k, x + 19 * k, y + 21 * k);
                cv.drawRoundRect(rect, 4.5f * k, 4.5f * k, p);
                tri(cv, x + 10 * k, y + 8.5f * k, 7 * k, bg);
                break;
            case 2: // Subscriptions
                bar(cv, x + 4 * k, y + 3 * k, x + 20 * k, y + 5 * k);
                rect.set(x + 3 * k, y + 7 * k, x + 21 * k, y + 21 * k);
                cv.drawRoundRect(rect, 2.5f * k, 2.5f * k, p);
                tri(cv, x + 10 * k, y + 10.5f * k, 7 * k, bg);
                break;
            case 4: // Player
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.7f * k);
                rect.set(x + 3 * k, y + 4 * k, x + 21 * k, y + 20 * k);
                cv.drawRoundRect(rect, 3 * k, 3 * k, p);
                p.setStyle(Paint.Style.FILL);
                tri(cv, x + 9.5f * k, y + 9.5f * k, 6.5f * k, p.getColor());
                break;
            default: // Library
                bar(cv, x + 3 * k, y + 5 * k, x + 14 * k, y + 7 * k);
                bar(cv, x + 3 * k, y + 10.5f * k, x + 14 * k, y + 12.5f * k);
                bar(cv, x + 3 * k, y + 16 * k, x + 14 * k, y + 18 * k);
                tri(cv, x + 16 * k, y + 12.5f * k, 8 * k, active ? accent : base);
                break;
        }
    }
}
