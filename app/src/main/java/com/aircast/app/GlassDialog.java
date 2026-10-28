package com.aircast.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app's only dialog: a glass panel over a dimmed, blurred-looking backdrop.
 * Material's AlertDialog cannot be tinted into this language, so every notice
 * (start disclosure, log viewer, about) is built here instead.
 */
public final class GlassDialog extends Dialog {
    private final Ui ui;
    private final LinearLayout panel;
    private final LinearLayout body;
    private final LinearLayout actions;

    public GlassDialog(Activity activity, Ui ui, CharSequence title) {
        super(activity);
        this.ui = ui;

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        setCanceledOnTouchOutside(true);

        FrameLayout root = new FrameLayout(activity);
        View scrim = new View(activity);
        scrim.setBackgroundColor(ui.dark ? 0x99000000 : 0x73000000);
        scrim.setOnClickListener(v -> cancel());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(ui.panel());
        panel.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(18));

        int width = Math.min(
                activity.getResources().getDisplayMetrics().widthPixels - ui.dp(56),
                ui.dp(420));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, lp);
        setContentView(root);

        if (title != null && title.length() > 0) {
            TextView t = ui.text(title.toString(), 20, ui.textColor, true);
            t.setLetterSpacing(-0.01f);
            panel.addView(t);
        }

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = ui.dp(10);
        panel.addView(body, bodyLp);

        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = ui.dp(8);
        panel.addView(actions, actLp);
    }

    public GlassDialog setMessage(CharSequence message) {
        body.removeAllViews();
        body.addView(ui.body(message.toString()));
        return this;
    }

    public GlassDialog setBody(View view) {
        body.removeAllViews();
        body.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** Same, with a fixed height so scrollable bodies cannot outgrow the screen. */
    public GlassDialog setBody(View view, int heightPx) {
        body.removeAllViews();
        body.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return this;
    }

    /**
     * Adds a full-width action. Actions run before the dialog closes, so a
     * "Continue" handler can still read state (e.g. a checkbox) from the body.
     */
    public GlassDialog addAction(String label, boolean emphasized, final Runnable run) {
        if (actions.getChildCount() > 0) actions.addView(ui.divider());
        TextView b = ui.text(label, 17, ui.accent, emphasized);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(ui.dp(8), ui.dp(15), ui.dp(8), ui.dp(15));
        ui.clickable(b);
        b.setOnClickListener(v -> {
            if (run != null) run.run();
            dismiss();
        });
        actions.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    @Override
    public void show() {
        super.show();
        // The window only exists once the dialog is shown; sizing it earlier is
        // ignored and would leave the dim backdrop wrapped around the panel.
        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
