package com.aircast.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Liquid-glass design system.
 *
 * The screens are built in code (no XML layouts), so this class owns the whole
 * visual language: two palettes that follow the system light/dark setting, the
 * glass material, the pill buttons, the grouped-list rows and the shared
 * spacing/typography ramp.
 *
 * Everything here is deliberately restrained: one translucent material, one
 * accent, hairline dividers, and generous corner radii. Adding a second shadow
 * style or a third accent is what makes this kind of skin look cheap.
 */
public final class Ui {
    public static final float CARD_RADIUS = 26f;
    public static final float FIELD_RADIUS = 18f;

    public final boolean dark;
    public final int bgTop, bgMid, bgBottom;
    public final int blobA, blobB, blobC;
    public final int glassTop, glassBottom, strokeTop, strokeBottom;
    public final int textColor, dim, faint;
    public final int accent, danger;
    public final int fieldFill, dividerColor, trackColor;
    public final int trackFillStart, trackFillEnd;
    public final int accentFillTop, accentFillBottom;
    public final int stopFillTop, stopFillBottom;

    private final Context ctx;
    private final float density;
    private final Typeface medium;

    public Ui(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        density = this.ctx.getResources().getDisplayMetrics().density;
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        int mode = this.ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Configuration.UI_MODE_NIGHT_YES;

        if (dark) {
            bgTop = 0xFF060A12;
            bgMid = 0xFF0A1120;
            bgBottom = 0xFF101C33;
            blobA = 0xA82F6BFF;
            blobB = 0x8C7B5CFF;
            blobC = 0x7A00C2B2;
            glassTop = 0x24FFFFFF;
            glassBottom = 0x0FFFFFFF;
            strokeTop = 0x42FFFFFF;
            strokeBottom = 0x14FFFFFF;
            textColor = 0xFFF5F8FD;
            dim = 0xFFA8B3C7;
            faint = 0xFF7E8A9E;
            accent = 0xFF5AC8FA;
            danger = 0xFFFF453A;
            fieldFill = 0x14FFFFFF;
            dividerColor = 0x1FFFFFFF;
            trackColor = 0x26FFFFFF;
            trackFillStart = 0xFF7BD8FF;
            trackFillEnd = 0xFF3A86FF;
            accentFillTop = 0xFF63D2FF;
            accentFillBottom = 0xFF2A6FE0;
            stopFillTop = 0xFFFF6F66;
            stopFillBottom = 0xFFD62B22;
        } else {
            bgTop = 0xFFF3F7FD;
            bgMid = 0xFFE7EFFA;
            bgBottom = 0xFFD8E6F7;
            blobA = 0x8C74ADFF;
            blobB = 0x7AA48FFF;
            blobC = 0x6E5FD3C8;
            glassTop = 0xBAFFFFFF;
            glassBottom = 0x66FFFFFF;
            strokeTop = 0xFFFFFFFF;
            strokeBottom = 0x59FFFFFF;
            textColor = 0xFF0B1220;
            dim = 0xFF4E5B70;
            faint = 0xFF76839A;
            accent = 0xFF0A7BFF;
            danger = 0xFFE8342B;
            fieldFill = 0x70FFFFFF;
            dividerColor = 0x16000000;
            trackColor = 0x22000000;
            trackFillStart = 0xFF3FA9FF;
            trackFillEnd = 0xFF0A6BE0;
            accentFillTop = 0xFF48B2FF;
            accentFillBottom = 0xFF0A6BE0;
            stopFillTop = 0xFFFF6B61;
            stopFillBottom = 0xFFE03A30;
        }
    }

    // ── metrics ────────────────────────────────────────────────────────

    public int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    public float dpf(float v) {
        return v * density;
    }

    /** Kept for callers that only need a plain rounded rect (fields, dots). */
    public GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(radiusDp));
        return g;
    }

    private ViewOutlineProvider outline(final float radiusPx) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        };
    }

    // ── type ──────────────────────────────────────────────────────────

    public TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(bold ? medium : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    /** Large screen title, iOS-style tight tracking. */
    public TextView title(String s) {
        TextView t = text(s, 32, textColor, true);
        t.setLetterSpacing(-0.02f);
        t.setIncludeFontPadding(false);
        return t;
    }

    public TextView subtitle(String s) {
        TextView t = text(s, 15, dim, false);
        t.setLineSpacing(dpf(2), 1f);
        return t;
    }

    public TextView heading(String s) {
        TextView t = text(s, 17, textColor, true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Uppercase caption that opens a group of rows. */
    public TextView section(String s) {
        TextView t = text(s.toUpperCase(), 12, faint, true);
        t.setLetterSpacing(0.1f);
        return t;
    }

    public TextView body(String s) {
        TextView t = text(s, 15, textColor, false);
        t.setLineSpacing(dpf(4), 1f);
        return t;
    }

    public TextView caption(String s) {
        TextView t = text(s, 12, faint, false);
        t.setLineSpacing(dpf(3), 1f);
        return t;
    }

    public TextView value(String s) {
        return text(s, 14, accent, true);
    }

    public TextView chevron() {
        TextView t = text("\u203A", 20, faint, false);
        t.setPadding(dp(6), 0, 0, 0);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ── surfaces ──────────────────────────────────────────────────────

    public android.graphics.drawable.Drawable panel() {
        return new GlassDrawable(glassTop, glassBottom, strokeTop, strokeBottom,
                dpf(CARD_RADIUS), dpf(1.2f));
    }

    public android.graphics.drawable.Drawable pillFill() {
        return new GlassDrawable(
                dark ? 0x24FFFFFF : 0x94FFFFFF,
                dark ? 0x14FFFFFF : 0x54FFFFFF,
                dark ? 0x4DFFFFFF : 0xDFFFFFFF,
                dark ? 0x14FFFFFF : 0x66FFFFFF,
                dpf(999), dpf(1.2f));
    }

    /** A standalone glass card. */
    public LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(panel());
        l.setPadding(dp(20), dp(18), dp(20), dp(18));
        l.setElevation(dpf(dark ? 10 : 6));
        l.setOutlineProvider(outline(dpf(CARD_RADIUS)));
        l.setClipToOutline(false);
        return l;
    }

    /** A glass container for a grouped list of rows. */
    public LinearLayout list() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(panel());
        l.setPadding(0, dp(6), 0, dp(6));
        l.setElevation(dpf(dark ? 10 : 6));
        l.setOutlineProvider(outline(dpf(CARD_RADIUS)));
        l.setClipToOutline(false);
        return l;
    }

    public LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        return r;
    }

    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(18);
        v.setLayoutParams(lp);
        return v;
    }

    public void addRow(LinearLayout list, View row) {
        if (list.getChildCount() > 0) list.addView(divider());
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Makes a row behave like a tappable cell without changing its look. */
    public void clickable(View row) {
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x14000000), null, null));
    }

    public View dot(int color) {
        View v = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }

    // ── controls ──────────────────────────────────────────────────────

    public TextView primaryButton(String label) {
        TextView b = text(label, 17, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(22), dp(17), dp(22), dp(17));
        setPrimary(b, false);
        return b;
    }

    /** Repaints a primary button; {@code destructive} turns it into the stop state. */
    public void setPrimary(TextView b, boolean destructive) {
        GlassDrawable fill = new GlassDrawable(
                destructive ? stopFillTop : accentFillTop,
                destructive ? stopFillBottom : accentFillBottom,
                0x73FFFFFF, 0x1FFFFFFF, dpf(999), dpf(1.4f));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), fill, null));
        b.setElevation(dpf(12));
        b.setOutlineProvider(outline(dpf(999)));
        b.setClipToOutline(false);
    }

    public TextView secondaryButton(String label) {
        TextView b = text(label, 16, textColor, false);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        setSecondary(b);
        return b;
    }

    public void setSecondary(TextView b) {
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), pillFill(), null));
        b.setElevation(dpf(4));
    }

    /** Circular glass glyph button (back arrows, plus/minus). */
    public TextView iconButton(String glyph) {
        TextView b = text(glyph, 18, textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), pillFill(), null));
        b.setElevation(dpf(4));
        return b;
    }

    public EditText field() {
        EditText e = new EditText(ctx);
        e.setTextSize(16);
        e.setTextColor(textColor);
        e.setHintTextColor(faint);
        e.setSingleLine(true);
        e.setPadding(dp(16), dp(13), dp(16), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(fieldFill);
        g.setCornerRadius(dpf(FIELD_RADIUS));
        g.setStroke(Math.max(1, dp(1)), strokeBottom);
        e.setBackground(g);
        return e;
    }

    public GlassSeekBar slider() {
        return new GlassSeekBar(ctx, this);
    }

    public View switchRow(String title, String subtitle, boolean on) {
        LinearLayout labels = new LinearLayout(ctx);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, textColor, false));
        if (subtitle != null) {
            TextView s = text(subtitle, 12, faint, false);
            s.setLineSpacing(dpf(2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            s.setLayoutParams(lp);
            labels.addView(s);
        }

        GlassToggle toggle = new GlassToggle(ctx,
                accent,
                dark ? 0x2EFFFFFF : 0x26000000,
                dark ? 0x59FFFFFF : 0x40FFFFFF);
        toggle.setChecked(on);

        LinearLayout row = row();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(14);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle, tlp);
        clickable(row);
        return row;
    }

    public static GlassToggle switchOf(View row) {
        if (!(row instanceof LinearLayout)) return null;
        LinearLayout l = (LinearLayout) row;
        for (int i = 0; i < l.getChildCount(); i++) {
            if (l.getChildAt(i) instanceof GlassToggle) return (GlassToggle) l.getChildAt(i);
        }
        return null;
    }

    public View tapRow(String title, String value) {
        LinearLayout labels = new LinearLayout(ctx);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, textColor, false));

        LinearLayout row = row();
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && value.length() > 0) {
            TextView v = value(value);
            v.setPadding(0, 0, dp(2), 0);
            row.addView(v);
        }
        row.addView(chevron());
        clickable(row);
        return row;
    }

    public LinearLayout.LayoutParams top(int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(marginDp), 0, 0);
        return p;
    }

    // ── window ────────────────────────────────────────────────────────

    /**
     * Draws edge to edge and flips the system-bar icons to whichever contrasts
     * with the palette in use. Android 15 forces edge-to-edge, so the same
     * treatment is applied on every version the app supports.
     */
    public void applyWindow(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * Pads a view so its content clears the system bars and the display cutout
     * on every edge. The base padding is applied first and the live insets are
     * added on top; attaching the listener dispatches the current insets
     * immediately, so a later setPadding() would clobber them.
     */
    public void applyInsets(View v, int left, int top, int right, int bottom) {
        v.setPadding(left, top, right, bottom);
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = bars.bottom;
            } else {
                @SuppressWarnings("deprecation")
                int ls = insets.getSystemWindowInsetLeft();
                @SuppressWarnings("deprecation")
                int ts = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation")
                int rs = insets.getSystemWindowInsetRight();
                @SuppressWarnings("deprecation")
                int bs = insets.getSystemWindowInsetBottom();
                l = ls;
                t = ts;
                r = rs;
                b = bs;
            }
            view.setPadding(left + l, top + t, right + r, bottom + b);
            return insets;
        });
    }
}
