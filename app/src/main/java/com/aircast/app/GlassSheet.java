package com.aircast.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A bottom sheet in the app's glass language.
 *
 * The panel is anchored to the bottom edge and only its top corners are
 * rounded, so it reads as having slid up over the page rather than floating on
 * it -- the same distinction iOS draws between a sheet and a dialog.
 *
 * The content is a scroll view so a long speaker list shrinks to the display
 * instead of running off it; the dialog's window is measured against the screen
 * height, which is what makes that work.
 */
public final class GlassSheet extends Dialog {
    private final Ui ui;
    private final LinearLayout panel;
    private final LinearLayout content;

    public GlassSheet(Activity activity, Ui ui, CharSequence title) {
        super(activity);
        this.ui = ui;

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // The keyboard has to push the sheet up, or the address field ends
            // up behind it with its own action button.
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        panel.setBackground(ui.sheetFill());
        ui.applyInsets(panel, ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(18));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, lp);
        setContentView(root);

        panel.addView(grabber(activity));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, ui.dp(6), 0, 0);

        TextView t = ui.heading(title == null ? "" : title.toString());
        header.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = ui.iconButton("\u2715");
        close.setContentDescription(activity.getString(R.string.close));
        close.setOnClickListener(v -> cancel());
        // Matches the settings header's back button, and stays a usable target
        // for a thumb rather than a 32 dp dot.
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        closeLp.leftMargin = ui.dp(10);
        header.addView(close, closeLp);
        panel.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, ui.top(10));
    }

    /** The grab handle that says "this can be dismissed". */
    private View grabber(Activity activity) {
        FrameLayout wrap = new FrameLayout(activity);
        View bar = new View(activity);
        bar.setBackground(ui.rounded(ui.dark ? 0x3DFFFFFF : 0x33000000, 999f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER);
        wrap.addView(bar, lp);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(14)));
        return wrap;
    }

    /** Add sheet content here; it lives inside the scroll view. */
    public LinearLayout content() {
        return content;
    }

    @Override
    public void show() {
        super.show();
        Window w = getWindow();
        if (w != null) {
            // The window is only real once shown; sizing it earlier is ignored
            // and would leave the scrim wrapped around the panel.
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        panel.setAlpha(0f);
        panel.setTranslationY(ui.dp(48));
        panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(Tokens.MOTION_SHEET)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
