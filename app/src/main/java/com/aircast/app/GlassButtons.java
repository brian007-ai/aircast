package com.aircast.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The button materials.
 *
 * Every fill and every ripple mask is assembled here, so a screen never has to
 * build a {@link GlassDrawable} or remember that a ripple without a mask grows
 * into a circle.
 */
public final class GlassButtons {
    private GlassButtons() {}

    /** The screen's one call to action. */
    public static TextView primary(Context ctx, Ui ui, String label) {
        TextView b = ui.text(label, Tokens.TYPE_HEADING, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(ui.dp(Tokens.SPACE_9), ui.dp(Tokens.SPACE_BTN_PAD),
                ui.dp(Tokens.SPACE_9), ui.dp(Tokens.SPACE_BTN_PAD));
        paintPrimary(ui, b, false);
        return b;
    }

    /** Repaints a primary button; {@code destructive} turns it into the stop state. */
    public static void paintPrimary(Ui ui, TextView b, boolean destructive) {
        GlassDrawable fill = new GlassDrawable(
                destructive ? ui.stopFillTop : ui.accentFillTop,
                destructive ? ui.stopFillBottom : ui.accentFillBottom,
                0x73FFFFFF, 0x1FFFFFFF,
                0x40FFFFFF, 0x26000000,
                ui.dpf(Tokens.RADIUS_PILL), ui.dpf(Tokens.RIM_PRIMARY));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.rippleOnAccent), fill,
                ui.rounded(Color.WHITE, Tokens.RADIUS_PILL)));
    }

    public static TextView secondary(Context ctx, Ui ui, String label) {
        TextView b = ui.text(label, Tokens.TYPE_ROW, ui.textColor, false);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(ui.dp(Tokens.SPACE_8), ui.dp(Tokens.SPACE_5),
                ui.dp(Tokens.SPACE_8), ui.dp(Tokens.SPACE_5));
        paintSecondary(ui, b);
        return b;
    }

    public static void paintSecondary(Ui ui, TextView b) {
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.ripplePill), ui.pillFill(),
                ui.rounded(Color.WHITE, Tokens.RADIUS_PILL)));
    }

    /** Circular glass glyph button (back arrows, plus/minus). */
    public static TextView icon(Context ctx, Ui ui, String glyph) {
        TextView b = ui.text(glyph, Tokens.TYPE_GLYPH, ui.textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        paintSecondary(ui, b);
        return b;
    }
}
