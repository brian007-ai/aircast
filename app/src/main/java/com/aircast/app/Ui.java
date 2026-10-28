package com.aircast.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Liquid-glass design system.
 *
 * This class is now a facade: the palette lives in {@link Palette}, the metric
 * ramp in {@link Tokens}, and the materials in {@link GlassCard},
 * {@link GlassRow}, {@link GlassButtons} and {@link GlassDrawable}. Screens keep
 * talking to one object, so the visual language can change underneath without
 * touching a single call site.
 *
 * Everything here is deliberately restrained: one translucent material, one
 * accent, hairline dividers, and generous corner radii. Adding a second shadow
 * style or a third accent is what makes this kind of skin look cheap.
 */
public final class Ui {
    public static final float CARD_RADIUS = Tokens.RADIUS_CARD;
    public static final float FIELD_RADIUS = Tokens.RADIUS_FIELD;

    public final boolean dark;
    public final int bgTop, bgMid, bgBottom;
    public final int blobA, blobB, blobC;
    public final int glassTop, glassBottom, strokeTop, strokeBottom, glassSpec, glassShade;
    public final int textColor, dim, faint;
    public final int accent, danger;
    public final int fieldFill, dividerColor, trackColor;
    public final int trackFillStart, trackFillEnd;
    public final int accentFillTop, accentFillBottom;
    public final int stopFillTop, stopFillBottom;
    public final int rippleRow, ripplePill, rippleOnAccent;

    private final Context ctx;
    private final float density;
    private final Typeface medium;
    private final Palette p;

    public Ui(Context ctx) {
        this.ctx = ctx;
        density = this.ctx.getResources().getDisplayMetrics().density;
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        // The explicit day/night choice in Settings wins over the system mode.
        dark = Settings.isDark(this.ctx);
        p = dark ? Palette.dark() : Palette.light();

        bgTop = p.bgTop;
        bgMid = p.bgMid;
        bgBottom = p.bgBottom;
        blobA = p.blobA;
        blobB = p.blobB;
        blobC = p.blobC;
        glassTop = p.glassTop;
        glassBottom = p.glassBottom;
        glassSpec = p.glassSpec;
        glassShade = p.glassShade;
        strokeTop = p.strokeTop;
        strokeBottom = p.strokeBottom;
        textColor = p.text;
        dim = p.dim;
        faint = p.faint;
        accent = p.accent;
        danger = p.danger;
        fieldFill = p.fieldFill;
        dividerColor = p.divider;
        trackColor = p.track;
        trackFillStart = p.trackFillStart;
        trackFillEnd = p.trackFillEnd;
        accentFillTop = p.accentFillTop;
        accentFillBottom = p.accentFillBottom;
        stopFillTop = p.stopFillTop;
        stopFillBottom = p.stopFillBottom;
        rippleRow = p.rippleRow;
        ripplePill = p.ripplePill;
        rippleOnAccent = p.rippleOnAccent;
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
        TextView t = text(s, Tokens.TYPE_TITLE, textColor, true);
        t.setLetterSpacing(-0.02f);
        t.setIncludeFontPadding(false);
        return t;
    }

    public TextView subtitle(String s) {
        TextView t = text(s, Tokens.TYPE_BODY, dim, false);
        t.setLineSpacing(dpf(2), 1f);
        return t;
    }

    public TextView heading(String s) {
        TextView t = text(s, Tokens.TYPE_HEADING, textColor, true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Uppercase caption that opens a group of rows. */
    public TextView section(String s) {
        TextView t = text(s.toUpperCase(), Tokens.TYPE_SECTION, dim, true);
        t.setLetterSpacing(0.1f);
        return t;
    }

    public TextView body(String s) {
        TextView t = text(s, Tokens.TYPE_BODY, textColor, false);
        t.setLineSpacing(dpf(4), 1f);
        return t;
    }

    public TextView caption(String s) {
        TextView t = text(s, Tokens.TYPE_CAPTION, dim, false);
        t.setLineSpacing(dpf(3), 1f);
        return t;
    }

    public TextView value(String s) {
        return text(s, Tokens.TYPE_VALUE, accent, true);
    }

    public TextView chevron() {
        TextView t = text("\u203A", Tokens.TYPE_CHEVRON, faint, false);
        t.setPadding(dp(Tokens.SPACE_2), 0, 0, 0);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ── surfaces ──────────────────────────────────────────────────────

    public android.graphics.drawable.Drawable panel() {
        return new GlassDrawable(glassTop, glassBottom, strokeTop, strokeBottom,
                glassSpec, glassShade, dpf(Tokens.RADIUS_CARD), dpf(Tokens.RIM));
    }

    /**
     * A pane that meets the bottom edge of the screen: the same material, but
     * only the top corners are rounded. A sheet with all four corners rounded
     * would show the page through the two bottom ones.
     */
    public android.graphics.drawable.Drawable sheetFill() {
        return new GlassDrawable(glassTop, glassBottom, strokeTop, strokeBottom,
                glassSpec, glassShade,
                dpf(Tokens.RADIUS_CARD), 0f, dpf(Tokens.RIM));
    }

    /**
     * Fade painted behind the pinned action, so content scrolling under it
     * dissolves instead of being hard-clipped at a line. Built from the
     * backdrop's own bottom colour, so it matches the aurora rather than
     * sitting on it as a grey band.
     */
    public android.graphics.drawable.Drawable scrimBottom() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(bgBottom, 0x00), withAlpha(bgBottom, 0xB4),
                        withAlpha(bgBottom, 0xF2)});
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public android.graphics.drawable.Drawable pillFill() {
        return new GlassDrawable(p.pillTop, p.pillBottom,
                // A solid hairline: a short pill has no vertical room for a
                // rim gradient, so a white-to-transparent rim would vanish.
                p.pillRim, p.pillRim, p.pillSpec, p.pillShade,
                dpf(Tokens.RADIUS_PILL), dpf(Tokens.RIM));
    }

    /** A standalone glass card. */
    public LinearLayout card() {
        return GlassCard.card(ctx, this);
    }

    /** A glass container for a grouped list of rows. */
    public LinearLayout list() {
        return GlassCard.list(ctx, this);
    }

    public LinearLayout row() {
        return GlassRow.of(ctx, this);
    }

    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(Tokens.SPACE_7);
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
        // Not focusable on purpose: the ripple's focus layer has no mask, so a
        // focused row paints a hard-edged rectangle that stays on screen after
        // the keyboard closes.
        row.setFocusable(false);
        // A mask keeps the pressed ripple inside the cell's shape; an unmasked
        // (unbounded) ripple grows into a circle that spills over the card.
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleRow), null,
                rounded(Color.WHITE, Tokens.RADIUS_ROW)));
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
        return GlassButtons.primary(ctx, this, label);
    }

    /** Repaints a primary button; {@code destructive} turns it into the stop state. */
    public void setPrimary(TextView b, boolean destructive) {
        GlassButtons.paintPrimary(this, b, destructive);
    }

    public TextView secondaryButton(String label) {
        return GlassButtons.secondary(ctx, this, label);
    }

    public void setSecondary(TextView b) {
        GlassButtons.paintSecondary(this, b);
    }

    /** Circular glass glyph button (back arrows, plus/minus). */
    public TextView iconButton(String glyph) {
        return GlassButtons.icon(ctx, this, glyph);
    }

    public EditText field() {
        EditText e = new EditText(ctx);
        e.setTextSize(Tokens.TYPE_ROW);
        e.setTextColor(textColor);
        e.setHintTextColor(faint);
        e.setSingleLine(true);
        e.setPadding(dp(Tokens.SPACE_6), dp(Tokens.SPACE_FIELD_PAD),
                dp(Tokens.SPACE_6), dp(Tokens.SPACE_FIELD_PAD));
        GradientDrawable g = new GradientDrawable();
        g.setColor(fieldFill);
        g.setCornerRadius(dpf(Tokens.RADIUS_FIELD));
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
        labels.addView(text(title, Tokens.TYPE_ROW, textColor, false));
        if (subtitle != null) {
            TextView s = text(subtitle, Tokens.TYPE_CAPTION, dim, false);
            s.setLineSpacing(dpf(2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(Tokens.SPACE_1);
            s.setLayoutParams(lp);
            labels.addView(s);
        }

        GlassToggle toggle = new GlassToggle(ctx,
                accent,
                dark ? 0x2EFFFFFF : 0x26000000,
                dark ? 0x59FFFFFF : 0x40FFFFFF);
        toggle.setChecked(on);
        // The row owns the tap: a clickable toggle would swallow the touch and
        // flip its own state without ever reaching the row's persistence code.
        toggle.setClickable(false);
        toggle.setFocusable(false);

        LinearLayout row = row();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(Tokens.SPACE_5);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle, tlp);
        clickable(row);
        // The row is the tap target, but the toggle is what carries the state.
        // Without this, TalkBack reads a plain button and never announces
        // whether the switch is on -- the one thing a switch has to say.
        row.setContentDescription(subtitle == null ? title : title + ". " + subtitle);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });
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
        labels.addView(text(title, Tokens.TYPE_ROW, textColor, false));

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
        // PhoneWindow.getInsetsController() reads mDecor, which is only built by
        // installDecor() -- and that happens on the first getDecorView() or
        // setContentView(). Callers configure the window before their content
        // exists, so the decor has to be materialised here or the controller
        // call below throws NullPointerException.
        w.getDecorView();
        // The window background is painted before the first frame of the
        // aurora, and the resource-based theme only follows the *system* mode.
        // Painting it from the resolved palette keeps a forced light/dark choice
        // from flashing the other colour on launch.
        w.setBackgroundDrawable(new ColorDrawable(bgTop));
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
