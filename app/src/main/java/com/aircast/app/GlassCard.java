package com.aircast.app;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * A glass container.
 *
 * {@code card} is a padded panel for free-form content; {@code list} is the
 * same material with the rows flush to the edges so the dividers can run from
 * the text gutter outward.
 */
public final class GlassCard extends LinearLayout {

    private GlassCard(Context ctx, android.graphics.drawable.Drawable bg, int padH, int padV) {
        super(ctx);
        setOrientation(VERTICAL);
        setBackground(bg);
        setPadding(padH, padV, padH, padV);
    }

    public static GlassCard card(Context ctx, Ui ui) {
        return new GlassCard(ctx, ui.panel(), ui.dp(Tokens.SPACE_8), ui.dp(Tokens.SPACE_7));
    }

    public static GlassCard list(Context ctx, Ui ui) {
        return new GlassCard(ctx, ui.panel(), 0, ui.dp(Tokens.SPACE_2));
    }
}
