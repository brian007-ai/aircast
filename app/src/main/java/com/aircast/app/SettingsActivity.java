package com.aircast.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Everything that does not change while a cast is running: the audio/latency
 * knobs, the volume-key behaviour, and the logging toggles and licence text.
 * Values are read through {@link Settings}, which clamps them, so this screen
 * can never push an out-of-range value into the native layer.
 *
 * Latency is applied when the next cast starts; the volume step and the
 * logging switch take effect immediately.
 */
public class SettingsActivity extends Activity {
    private Ui ui;
    private LinearLayout diagnostics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        ui.applyWindow(this);

        FrameLayout shell = new FrameLayout(this);
        shell.addView(new AuroraView(this, ui), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(0x00000000);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int edge = ui.dp(20);
        // Android 15+ draws edge-to-edge by default, so the content is inset
        // past the system bars and the display cutout on every edge -- the top
        // in portrait, and the side navigation bar / camera cutout as well once
        // the device is turned landscape.
        ui.applyInsets(root, edge, ui.dp(14), edge, ui.dp(28));

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(header());
        root.addView(ui.section("AirPlay audio"), ui.top(24));
        root.addView(latencyCard(), ui.top(10));

        root.addView(ui.section("Volume"), ui.top(22));
        root.addView(stepCard(), ui.top(10));

        root.addView(ui.section("Diagnostics"), ui.top(22));
        diagnostics = ui.list();
        root.addView(diagnostics, ui.top(10));
        applyLogVisibility();

        root.addView(ui.section("About"), ui.top(22));
        root.addView(aboutCard(), ui.top(10));

        setContentView(shell);
    }

    /** Back affordance plus the large title, in place of a system action bar. */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = ui.iconButton("\u2039");
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));

        TextView title = ui.title("Settings");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(14);
        bar.addView(title, tlp);
        return bar;
    }

    // ── cards ───────────────────────────────────────────────────────

    private View latencyCard() {
        LinearLayout card = ui.card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.heading("Audio latency"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = ui.value(formatLatency(Settings.getLatency(this)));
        head.addView(value);
        card.addView(head);

        SeekBar sb = ui.slider();
        // 0.25 s .. 5.00 s in 0.05 s steps -- the bar is integer-backed, so
        // work in 50 ms units (5..100).
        sb.setMax(100);
        sb.setProgress(Math.round(Settings.getLatency(this) * 20f));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float sec = Math.max(p, 5) / 20f;   // never below the 0.25 s floor
                value.setText(formatLatency(sec));
                Settings.setLatency(SettingsActivity.this, sec);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        card.addView(sb, ui.top(4));

        TextView note = ui.caption("How much audio the speaker buffers before it plays. Lower = "
                + "more responsive, but below roughly 0.5 s a slow Wi-Fi link may stutter. The "
                + "wire's own floor is 0.25 s, so that is as low as the slider goes. Applies to the "
                + "next cast.");
        note.setMaxLines(6);
        card.addView(note, ui.top(2));
        return card;
    }

    private static String formatLatency(float sec) {
        return String.format("%.2f s", sec);
    }

    private View stepCard() {
        LinearLayout card = ui.card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.heading("Volume key step"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = ui.value(Settings.getVolStep(this) + " %");
        head.addView(value);
        card.addView(head);

        SeekBar sb = ui.slider();
        sb.setMax(Settings.VOL_STEP_MAX - Settings.VOL_STEP_MIN);
        sb.setProgress(Settings.getVolStep(this) - Settings.VOL_STEP_MIN);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int step = p + Settings.VOL_STEP_MIN;
                value.setText(step + " %");
                Settings.setVolStep(SettingsActivity.this, step);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        card.addView(sb, ui.top(4));

        card.addView(ui.caption("How far one volume-key press, notification-slider tick or "
                + "on-screen tap moves the speaker's volume."), ui.top(2));
        return card;
    }

    // ── diagnostics ─────────────────────────────────────────────────

    /**
     * The level/view/export rows only exist with the buffer on, so the group is
     * rebuilt rather than hidden: leaving the dividers behind a GONE row would
     * show lines that separate nothing.
     */
    private void applyLogVisibility() {
        diagnostics.removeAllViews();
        ui.addRow(diagnostics, logRow());
        if (!Settings.isLogEnabled(this)) return;
        ui.addRow(diagnostics, logLevelRow());
        ui.addRow(diagnostics, viewLogRow());
        ui.addRow(diagnostics, exportLogRow());
    }

    private View logRow() {
        View row = ui.switchRow("Keep a log",
                "Capture the AirPlay handshake and audio stats in a rotating in-memory "
                + "buffer so a dropout can be exported afterwards.",
                Settings.isLogEnabled(this));
        row.setOnClickListener(v -> {
            boolean now = !Settings.isLogEnabled(this);
            Settings.setLogEnabled(this, now);
            ui.switchOf(row).setChecked(now, true);
            if (now) CastLog.mark("logging enabled");
            else CastLog.clear();
            applyLogVisibility();
        });
        return row;
    }

    private View logLevelRow() {
        String[] ids = {"error", "warn", "info", "debug"};
        String[] names = {"Errors only", "Warnings", "Info", "Debug (chatty)"};
        int cur = Settings.getLogLevel(this);
        View row = ui.tapRow("Log level", names[Math.min(cur, names.length - 1)]);
        row.setOnClickListener(v -> {
            final GlassDialog dialog = new GlassDialog(this, ui, "Log level");
            LinearLayout options = new LinearLayout(this);
            options.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < names.length; i++) {
                final int which = i;
                final boolean selected = i == Math.min(cur, names.length - 1);
                View choice = optionRow(names[i], selected, () -> {
                    Settings.setLogLevel(this, ids[which]);
                    dialog.dismiss();
                    applyLogVisibility();
                });
                ui.addRow(options, choice);
            }
            dialog.setBody(options);
            dialog.show();
        });
        return row;
    }

    private View optionRow(String label, boolean selected, Runnable onClick) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(4), ui.dp(13), ui.dp(4), ui.dp(13));
        row.addView(ui.text(label, 16, selected ? ui.accent : ui.textColor, selected),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) row.addView(ui.text("\u2713", 16, ui.accent, true));
        ui.clickable(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private View viewLogRow() {
        View row = ui.tapRow("View log", "");
        row.setOnClickListener(v -> {
            java.util.List<String> lines = CastLog.snapshot();
            StringBuilder sb = new StringBuilder();
            if (lines.isEmpty()) {
                sb.append("(empty -- turn on \"Keep a log\" and cast for a while)");
            } else {
                for (String l : lines) sb.append(l).append('\n');
            }

            ScrollView sv = new ScrollView(this);
            TextView tv = ui.text(sb.toString(), 11, ui.textColor, false);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setLineSpacing(ui.dpf(3), 1f);
            tv.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            sv.addView(tv);

            GlassDialog dialog = new GlassDialog(this, ui,
                    App.APP_NAME + " log \u00b7 " + lines.size() + " lines");
            dialog.setBody(sv, ui.dp(300));
            dialog.addAction("Clear", false, () -> {
                CastLog.clear();
                CastLog.mark("log cleared");
            });
            dialog.addAction("Close", true, null);
            dialog.show();
        });
        return row;
    }

    private View exportLogRow() {
        View row = ui.tapRow("Export log", "share");
        row.setOnClickListener(v -> {
            java.util.List<String> lines = CastLog.snapshot();
            if (lines.isEmpty()) {
                GlassDialog dialog = new GlassDialog(this, ui, "Nothing logged yet");
                dialog.setMessage("Turn on \"Keep a log\" above, cast for a while, "
                        + "then come back here.");
                dialog.addAction("OK", true, null);
                dialog.show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            // Share as plain text -- no FileProvider / androidx dependency needed.
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, App.APP_NAME + " log");
            share.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(share, "Export " + App.APP_NAME + " log"));
        });
        return row;
    }

    // ── about ───────────────────────────────────────────────────────

    private View aboutCard() {
        LinearLayout list = ui.list();
        View row = ui.tapRow("About & open-source licences", versionLabel());
        row.setOnClickListener(v -> showAbout());
        ui.addRow(list, row);
        return list;
    }

    private String versionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void showAbout() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        ScrollView sv = new ScrollView(this);
        TextView body = ui.body("Streams audio from this phone to AirPlay 2 speakers.\n\n"
                + "This build is a community fork of the AirCast project: the interface and "
                + "the app code have been reworked, while the copyright notices of the "
                + "original authors and of every bundled component are preserved in the "
                + "NOTICE and LICENSE files.\n\n"
                + "Includes open-source code licensed under Apache-2.0.\n\n"
                + "Not affiliated with, or endorsed by, Apple Inc. \"AirPlay\" and "
                + "\"HomePod\" are trademarks of Apple Inc., used only to describe "
                + "compatibility.\n\n"
                + "Open-source components:\n"
                + "\u2022 airplay2-sender-cpp \u2014 Apache-2.0\n"
                + "\u2022 mbedTLS \u2014 Apache-2.0\n"
                + "\u2022 ed25519 (orlp) \u2014 zlib\n\n"
                + "Audio is processed entirely on-device; nothing is collected.");
        body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        sv.addView(body);

        GlassDialog dialog = new GlassDialog(this, ui, App.APP_NAME + " " + version);
        dialog.setBody(sv, ui.dp(320));
        dialog.addAction("Close", true, null);
        dialog.show();
    }
}
