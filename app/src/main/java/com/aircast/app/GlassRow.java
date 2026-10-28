package com.aircast.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;

/** One cell inside a glass card or list. */
public final class GlassRow extends LinearLayout {

    private GlassRow(Context ctx, Ui ui) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(ui.dp(Tokens.SPACE_7), ui.dp(Tokens.SPACE_5),
                ui.dp(Tokens.SPACE_7), ui.dp(Tokens.SPACE_5));
    }

    public static GlassRow of(Context ctx, Ui ui) {
        return new GlassRow(ctx, ui);
    }
}
