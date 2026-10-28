package com.aircast.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The main screen: pick a speaker, start/stop the cast, and set the level.
 * Everything that does not change while casting lives in
 * {@link SettingsActivity}.
 *
 * The screen renders {@link CastState} instead of tracking its own copy of
 * "running": one strip of the screen is the current speaker, one is the state,
 * and the action is pinned to the bottom so it never scrolls away from the
 * thumb. A cast that ends from the notification therefore reaches this screen
 * as an event, not as a one-second poll.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;
    /** Room left under the last card for the pinned button. */
    private static final int ACTION_SPACE_DP = 104;
    private static final long DISCOVERY_TIMEOUT_MS = 15000;

    /**
     * Whether this activity is on screen right now. The service's mute attempts
     * log it, because whether the ROM accepts a volume write is the one thing
     * that tracks the app being visible rather than anything about the write.
     */
    static volatile boolean visible;

    /**
     * Whether this window currently has input focus -- i.e. it is the window
     * the user is actually touching. Weaker than "on screen" (a window can be
     * resumed but not focused, e.g. behind a dialog or the notification shade),
     * and the service's mute log records it because the ROM takes a volume
     * write only for the app the user is driving.
     */
    static volatile boolean focused;

    private MediaProjectionManager mpm;
    private SharedPreferences prefs;
    private Ui ui;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView status;
    private View statusDot;
    private TextView go;
    private TextView targetName;
    private TextView targetHost;
    private View targetRow;
    private SeekBar volSlider;
    private TextView volPct;
    private boolean volDragging;
    private View muteRow;
    private GlassToggle muteToggle;
    private TextView diagLine;

    private GlassSheet sheet;
    private LinearLayout sheetDevices;
    private TextView sheetStatus;
    private TextView sheetScan;
    private EditText sheetField;
    private int foundCount;

    private AirplayDiscovery discovery;
    private String createdTheme;
    private String createdLang;
    private String pendingHost = "";

    private final Runnable discoveryTimeout = this::onDiscoveryTimeout;
    private final CastState.Listener castListener = this::render;

    /** Runs only while a cast is live and this screen is visible. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (diagLine != null && CastService.isRunning()) {
                long secs = CastService.diagFrames / Math.max(1, CastService.diagRate);
                diagLine.setText(getString(R.string.diag_line, CastService.diagRate,
                        CastService.takePeak(), CastService.diagErrors, (int) secs));
            }
            syncVolume();
            refreshMuteRow();
            if (visible && CastService.isRunning()) uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdTheme = Settings.getThemeMode(this);
        createdLang = Settings.getLanguage(this);
        try {
            buildStartupUi();
        } catch (Throwable t) {
            showStartupFailure(t);
        }
    }

    /**
     * Builds the whole launch screen. Kept apart from onCreate so a failure
     * anywhere in the build is shown on the device itself instead of killing
     * the process with nothing on screen.
     */
    private void buildStartupUi() {
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences("cast", MODE_PRIVATE);
        ui = new Ui(this);
        ui.applyWindow(this);
        // Force the native library load here, inside the guard: the service class
        // would otherwise be initialised from onResume(), where a failed dlopen
        // would take the process down before anything becomes visible.
        CastService.isPhoneMuted();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }

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
        ui.applyInsets(root, edge, ui.dp(20), edge, ui.dp(20));
        // Tapping anywhere that isn't an input drops its focus and closes the
        // keyboard. The root has to be focusable in touch mode, otherwise
        // requestFocus() is a no-op and the field keeps the cursor forever.
        root.setFocusableInTouchMode(true);
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                clearFieldFocus(v);
            }
            return false;
        });
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(ui.subtitle(getString(R.string.tagline)));
        root.addView(statusPill());
        root.addView(targetCard(), ui.top(20));
        root.addView(volumeCard(), ui.top(14));

        diagLine = ui.text("", Tokens.TYPE_DIAG, ui.faint, false);
        diagLine.setTypeface(Typeface.MONOSPACE);
        diagLine.setPadding(0, ui.dp(14), 0, 0);
        diagLine.setVisibility(View.GONE);
        root.addView(diagLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(settingsRow(), ui.top(14));
        // Clearance so the last row can scroll out from under the pinned button.
        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(ACTION_SPACE_DP)));

        shell.addView(bottomBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(shell);
        // Launch with no field focused so the cursor and the keyboard don't pop
        // up on their own; the field still focuses normally on tap.
        clearFieldFocus(root);
        refreshTarget();
        CastState.addListener(castListener);
        render(CastState.get());
    }

    /**
     * Renders the throwable that stopped the launch screen. Platform views only
     * -- the design system may be exactly what failed -- and the text is
     * selectable so the stack can be copied off the phone.
     */
    private void showStartupFailure(Throwable t) {
        java.io.StringWriter trace = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(trace));
        TextView body = new TextView(this);
        body.setText(trace.toString());
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(32, 64, 32, 32);
        body.setBackgroundColor(0xFF0E1218);
        body.setTextColor(0xFFFF6B61);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        setContentView(sv);
    }

    /** Drops the input focus (if any) and closes the soft keyboard. */
    private void clearFieldFocus(View focusTarget) {
        View current = getCurrentFocus();
        if (current != null) current.clearFocus();
        focusTarget.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focusTarget.getWindowToken(), 0);
        }
    }

    // --- state rendering ----------------------------------------------------

    /**
     * Paints the whole screen from one snapshot: the action's label and
     * tappability, the status strip, and whether the live capture line exists.
     */
    private void render(CastState.Snapshot s) {
        if (status == null || go == null) return;
        boolean casting = s.phase == CastState.Phase.CASTING;

        String label;
        boolean enabled;
        switch (s.phase) {
            case CASTING:
                label = getString(R.string.stop_casting);
                enabled = true;
                break;
            case CONNECTING:
                label = getString(R.string.connecting);
                enabled = false;
                break;
            case STOPPING:
                label = getString(R.string.stopping);
                enabled = false;
                break;
            default:
                label = getString(R.string.start_casting);
                enabled = hasTarget();
                break;
        }
        go.setText(label);
        go.setEnabled(enabled);
        go.setAlpha(enabled ? 1f : 0.5f);
        ui.setPrimary(go, casting);

        status.setText(statusText(s));
        tintStatusDot(s.phase);

        // The capture line only exists while there is a capture to describe.
        diagLine.setVisibility(casting ? View.VISIBLE : View.GONE);
        if (!casting) diagLine.setText("");
        if (casting && visible) startTicker();
        else stopTicker();

        refreshMuteRow();
    }

    private String statusText(CastState.Snapshot s) {
        switch (s.phase) {
            case CASTING:
                return s.target.isEmpty() ? getString(R.string.status_casting)
                        : getString(R.string.status_casting_to, labelFor(s.target));
            case CONNECTING:
                return s.target.isEmpty() ? getString(R.string.connecting)
                        : getString(R.string.status_connecting, labelFor(s.target));
            case STOPPING:
                return getString(R.string.status_stopping);
            case DISCOVERING:
                return s.detail.isEmpty() ? getString(R.string.status_searching) : s.detail;
            case ERROR:
                return s.detail.isEmpty() ? getString(R.string.status_cast_failed) : s.detail;
            default:
                return s.detail.isEmpty() ? getString(R.string.status_ready) : s.detail;
        }
    }

    /** Recolours the status dot, so the strip reads as a state and not decoration. */
    private void tintStatusDot(CastState.Phase phase) {
        int color;
        switch (phase) {
            case ERROR:
                color = ui.danger;
                break;
            case CASTING:
            case CONNECTING:
            case DISCOVERING:
                color = ui.accent;
                break;
            default:
                color = ui.faint;
                break;
        }
        Drawable bg = statusDot.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bg).setColor(color);
        }
    }

    private void startTicker() {
        uiHandler.removeCallbacks(tick);
        uiHandler.postDelayed(tick, 1000);
    }

    private void stopTicker() {
        uiHandler.removeCallbacks(tick);
    }

    /** Re-reads the remote volume, except while the user's thumb is on the slider. */
    private void syncVolume() {
        if (volSlider == null || volDragging) return;
        int v = CastService.getRemoteVolume();
        if (volSlider.getProgress() != v) volSlider.setProgress(v);
        if (volPct != null) volPct.setText(getString(R.string.percent, v));
    }

    /** Keeps the mute switch in step with the volume actually in effect. */
    private void refreshMuteRow() {
        if (muteToggle == null) return;
        boolean muted = CastService.isPhoneMuted();
        if (muteToggle.isChecked() != muted) muteToggle.setChecked(muted, true);
    }

    // --- header ------------------------------------------------------------

    /** Glass capsule holding the current state line. */
    private View statusPill() {
        status = ui.text(getString(R.string.status_ready), Tokens.TYPE_CAPTION, ui.dim, false);
        statusDot = ui.dot(ui.faint);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(ui.pillFill());
        pill.setPadding(ui.dp(14), ui.dp(9), ui.dp(16), ui.dp(9));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dotLp.rightMargin = ui.dp(9);
        pill.addView(statusDot, dotLp);
        pill.addView(status);

        // Full width, so the capsule lines up with the cards under it instead of
        // sitting on a ragged left edge of its own.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(16);
        pill.setLayoutParams(lp);
        return pill;
    }

    // --- current speaker ---------------------------------------------------

    /**
     * One row showing the speaker the cast will go to. The scan list and the
     * manual address live behind it, in a sheet: they are only needed while
     * choosing, and keeping them on the page pushed the action around.
     */
    private View targetCard() {
        LinearLayout list = ui.list();

        LinearLayout row = ui.row();
        row.setPadding(ui.dp(18), ui.dp(15), ui.dp(18), ui.dp(15));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        targetName = ui.text("", Tokens.TYPE_ROW, ui.textColor, false);
        targetHost = ui.caption("");
        targetHost.setPadding(0, ui.dp(2), 0, 0);
        labels.addView(targetName);
        labels.addView(targetHost);

        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ui.chevron());
        ui.clickable(row);
        row.setOnClickListener(v -> openSheet());
        targetRow = row;

        ui.addRow(list, row);
        return list;
    }

    private void refreshTarget() {
        if (targetName == null) return;
        String name = prefs.getString("host_name", "");
        String host = prefs.getString("host", "");
        boolean empty = TextUtils.isEmpty(host);
        targetName.setText(empty ? getString(R.string.no_speaker)
                : (TextUtils.isEmpty(name) ? host : name));
        targetHost.setText(empty ? getString(R.string.no_speaker_note) : host);
        // One announcement for the whole cell; the two lines are read together.
        targetRow.setContentDescription(targetName.getText() + ", " + targetHost.getText());
    }

    /** The display name for a host, falling back to the address itself. */
    private String labelFor(String host) {
        if (host == null || host.isEmpty()) return "";
        if (host.equals(prefs.getString("host", ""))) {
            String name = prefs.getString("host_name", "");
            if (!TextUtils.isEmpty(name)) return name;
        }
        return host;
    }

    private boolean hasTarget() {
        String host = prefs.getString("host", "");
        return host != null && !host.trim().isEmpty();
    }

    // --- actions -----------------------------------------------------------

    /**
     * The screen's one call to action, pinned above the navigation bar. It sits
     * on a fade so the cards dissolve under it instead of being cut off.
     */
    private View bottomBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(ui.scrimBottom());
        ui.applyInsets(bar, ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(18));

        go = ui.primaryButton(getString(R.string.start_casting));
        go.setOnClickListener(v -> onActionTap());
        bar.addView(go, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void onActionTap() {
        CastState.Phase phase = CastState.get().phase;
        if (phase == CastState.Phase.CASTING) {
            // Undo the mute on the tap itself, before the service is torn down:
            // this is the same touch-backed moment the mute rides on, and the
            // touch-less writes from the service's teardown get refused (see
            // CastService's mute notes).
            CastService.unmuteOnStopTap();
            CastState.stopping();
            if (!stopService(new Intent(this, CastService.class))) {
                // Nothing to stop: the service was already gone (killed, or a
                // stop from the notification that this screen missed).
                CastState.clearIfActive();
            }
            return;
        }
        if (phase == CastState.Phase.CONNECTING || phase == CastState.Phase.STOPPING) return;
        pendingHost = prefs.getString("host", "").trim();
        if (pendingHost.isEmpty()) return;
        showDisclosureThenCapture();
    }

    private void onMuteTap() {
        boolean want = !CastService.isPhoneMuted();
        boolean now = CastService.setPhoneMuteFromUi(this, want);
        if (muteToggle != null) muteToggle.setChecked(now, true);
        // The write reports the state actually in effect, so an unchanged state
        // means the ROM refused it. Say so rather than showing a mute the phone
        // does not have.
        if (now != want) status.setText(getString(R.string.status_volume_refused));
    }

    // --- volume ------------------------------------------------------------

    private View volumeCard() {
        LinearLayout card = ui.card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.heading(getString(R.string.heading_speaker_volume)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volPct = ui.value(getString(R.string.percent, Settings.getVolume(this)));
        head.addView(volPct);
        card.addView(head);

        volSlider = ui.slider();
        volSlider.setMax(100);
        volSlider.setProgress(Settings.getVolume(this));
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (volPct != null) volPct.setText(getString(R.string.percent, p));
                if (fromUser) CastService.setRemoteVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { volDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { volDragging = false; }
        });
        card.addView(volSlider, ui.top(4));

        TextView note = ui.caption(getString(R.string.volume_note));
        note.setMaxLines(2);
        card.addView(note, ui.top(2));

        // The phone's own speaker lives here rather than as a second full-width
        // button: it is a thing you set while routing audio, not an action.
        card.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)));
        card.addView(ui.divider());

        muteRow = ui.switchRow(getString(R.string.mute_phone),
                getString(R.string.mute_phone_note), CastService.isPhoneMuted());
        muteRow.setPadding(0, ui.dp(8), 0, ui.dp(2));
        muteToggle = Ui.switchOf(muteRow);
        muteRow.setOnClickListener(v -> onMuteTap());
        card.addView(muteRow);
        return card;
    }

    private View settingsRow() {
        LinearLayout list = ui.list();
        View row = ui.tapRow(getString(R.string.settings), "");
        row.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ui.addRow(list, row);
        return list;
    }

    // --- speaker sheet ------------------------------------------------------

    /**
     * Opens the speaker sheet and starts a scan. The scan is tied to the
     * sheet's lifetime: closing it is how the user says "never mind", and there
     * is no reason to keep the radio busy behind a page that cannot show the
     * results.
     */
    private void openSheet() {
        if (sheet != null && sheet.isShowing()) return;

        sheet = new GlassSheet(this, ui, getString(R.string.choose_speaker));
        LinearLayout content = sheet.content();

        sheetStatus = ui.caption(getString(R.string.status_searching));
        content.addView(sheetStatus);

        sheetScan = ui.secondaryButton(getString(R.string.scan_again));
        sheetScan.setOnClickListener(v -> {
            stopDiscovery();
            startDiscovery();
        });
        content.addView(sheetScan, ui.top(10));

        sheetDevices = new LinearLayout(this);
        sheetDevices.setOrientation(LinearLayout.VERTICAL);
        content.addView(sheetDevices, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(14)));
        content.addView(ui.divider());
        content.addView(ui.section(getString(R.string.manual_address)), ui.top(12));

        sheetField = ui.field();
        sheetField.setHint(getString(R.string.hint_host));
        sheetField.setText(prefs.getString("host", ""));
        content.addView(sheetField, ui.top(8));

        TextView use = ui.primaryButton(getString(R.string.use_address));
        use.setOnClickListener(v -> {
            String host = sheetField.getText().toString().trim();
            if (TextUtils.isEmpty(host)) return;
            selectTarget("", host);
        });
        content.addView(use, ui.top(10));

        sheet.setOnDismissListener(d -> {
            stopDiscovery();
            sheet = null;
        });
        sheet.show();
        startDiscovery();
    }

    private void startDiscovery() {
        if (discovery != null) return;
        foundCount = 0;
        if (sheetDevices != null) sheetDevices.removeAllViews();
        if (sheetStatus != null) sheetStatus.setText(getString(R.string.status_searching));
        if (sheetScan != null) {
            sheetScan.setText(getString(R.string.status_searching));
            sheetScan.setEnabled(false);
        }
        CastState.discovering(getString(R.string.status_searching));

        discovery = new AirplayDiscovery(this, new AirplayDiscovery.Listener() {
            @Override public void onDevice(String name, String host) {
                runOnUiThread(() -> addDeviceRow(name, host));
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    String text = getString(R.string.status_discovery_error, message);
                    if (sheetStatus != null) sheetStatus.setText(text);
                    CastState.fail(text);
                    stopDiscovery();
                });
            }
        });
        discovery.start();
        uiHandler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
    }

    private void addDeviceRow(String name, String host) {
        if (sheetDevices == null) return;

        LinearLayout row = ui.row();
        row.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));

        View dot = ui.dot(ui.accent);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dotLp.rightMargin = ui.dp(12);
        row.addView(dot, dotLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(name, Tokens.TYPE_ROW, ui.textColor, true));
        TextView addr = ui.caption(host);
        addr.setPadding(0, ui.dp(2), 0, 0);
        labels.addView(addr);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ui.chevron());
        ui.clickable(row);
        row.setOnClickListener(v -> selectTarget(name, host));
        row.setContentDescription(name + ", " + host);

        ui.addRow(sheetDevices, row);
        foundCount++;
        String text = foundCount == 1 ? getString(R.string.status_found_one)
                : getString(R.string.status_found_many, foundCount);
        if (sheetStatus != null) sheetStatus.setText(text);
        CastState.discovering(text);
    }

    private void selectTarget(String name, String host) {
        prefs.edit().putString("host", host).putString("host_name", name).apply();
        refreshTarget();
        stopDiscovery();
        if (sheet != null) sheet.dismiss();
        CastState.idle(getString(R.string.status_selected,
                TextUtils.isEmpty(name) ? host : name, host));
    }

    private void onDiscoveryTimeout() {
        if (foundCount == 0) {
            String text = getString(R.string.status_no_speakers);
            if (sheetStatus != null) sheetStatus.setText(text);
            CastState.fail(text);
        }
        stopDiscovery();
    }

    private void stopDiscovery() {
        uiHandler.removeCallbacks(discoveryTimeout);
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        if (sheetScan != null) {
            sheetScan.setText(getString(R.string.scan_again));
            sheetScan.setEnabled(true);
        }
        // A scan that ends without a result (the sheet was simply dismissed)
        // must not leave the status strip claiming to still be searching. A
        // failure or a selection has already moved the phase on, so only a
        // still-discovering phase is dropped here.
        if (CastState.get().phase == CastState.Phase.DISCOVERING) CastState.idle("");
    }

    // --- flow ---------------------------------------------------------------

    private void showDisclosureThenCapture() {
        // Skip if the user checked "don't show again" last time.
        if (Settings.isDisclosureSkipped(this)) {
            commitToCast();
            return;
        }
        // A checkbox needs a custom body; setMessage() alone has no hook for it.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = ui.body(getString(R.string.disclosure_body, App.APP_NAME));
        box.addView(msg);
        CheckBox cb = new CheckBox(this);
        cb.setText(getString(R.string.dont_show_again));
        cb.setTextColor(ui.textColor);
        cb.setButtonTintList(ColorStateList.valueOf(ui.accent));
        box.addView(cb, ui.top(12));

        GlassDialog dialog = new GlassDialog(this, ui, getString(R.string.disclosure_title));
        dialog.setBody(box);
        dialog.addAction(getString(R.string.cancel), false, null);
        dialog.addAction(getString(R.string.continue_label), true, () -> {
            Settings.setDisclosureSkipped(this, cb.isChecked());
            commitToCast();
        });
        dialog.show();
    }

    /**
     * The tap that commits to casting: the phone is silenced here, on the tap
     * itself, and the capture-consent dialog is raised after it.
     *
     * Not after the dialog, even though that is the moment the user has finished
     * choosing: on this device the only volume write that is never refused is one
     * with a real touch on this app behind it (3 of 3 here, against 2 of 3 and
     * then 0 of 4 for the dialog's return -- see the mute notes in
     * {@link CastService}). The dialog's return still re-asserts the mute as a
     * second shot, and {@link #onActivityResult} rolls the mute back if the
     * consent was never granted.
     */
    private void commitToCast() {
        CastService.armCastMute();
        CastService.mutePhoneNow(this);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    // --- lifecycle / callbacks ---------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        // Appearance is baked into the view tree when it is built, so a change
        // made in Settings is applied by rebuilding this screen on return.
        if (!createdTheme.equals(Settings.getThemeMode(this))
                || !createdLang.equals(Settings.getLanguage(this))) {
            recreate();
            return;
        }
        visible = true;
        if (status == null || go == null) return;   // startup screen failed; nothing to sync
        CastState.addListener(castListener);
        // A rotation or a return from another app rebuilds nothing here -- the
        // listener is added again and the current snapshot is rendered, so a
        // cast that is still live shows as live.
        render(CastState.get());
        refreshTarget();
        syncVolume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        stopTicker();
        CastState.removeListener(castListener);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        // The mute only lands from a window the user is driving (see
        // commitToCast), so it is re-asserted every time this window comes back
        // to the front -- on the return from the consent dialog, and again if
        // the phone was left audible while the user was in whatever app the
        // cast audio comes from.
        if (hasFocus && CastService.wantsPhoneMute() && CastService.isRunning()) {
            CastService.mutePhoneNow(this);
        }
    }

    @Override
    protected void onDestroy() {
        // The ticker re-posts itself every second and would otherwise keep the
        // whole Activity (and its view tree) alive after the screen is gone.
        uiHandler.removeCallbacksAndMessages(null);
        CastState.removeListener(castListener);
        stopDiscovery();
        if (sheet != null) {
            try {
                sheet.dismiss();
            } catch (Exception ignored) {
                // The dialog's window is already gone with the activity.
            }
            sheet = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (CastState.get().phase == CastState.Phase.CASTING
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            int step = Settings.getVolStep(this);
            CastService.adjustRemoteVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? step : -step);
            status.setText(getString(R.string.status_volume, CastService.getRemoteVolume()));
            syncVolume();
            return true; // consume — don't change the phone's own volume
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            String host = TextUtils.isEmpty(pendingHost)
                    ? prefs.getString("host", "") : pendingHost;
            prefs.edit().putString("host", host).apply();
            CastState.connecting(host);
            Intent i = new Intent(this, CastService.class);
            i.putExtra("resultCode", resultCode);
            i.putExtra("data", data);
            i.putExtra("host", host);
            startForegroundService(i);
            // Second shot at the mute, on the dialog's return: the user has
            // finished choosing and this app is on top in that instant. That
            // moment alone is not reliable on this device (see commitToCast), so
            // it only ever re-asserts the mute the commit tap already made --
            // unless the volume was raised while the dialog was up, which this
            // covers. The focus callback and the service's retry follow.
            CastService.mutePhoneNow(this);
        } else if (requestCode == REQ_CAPTURE) {
            // Consent refused or the dialog cancelled: no cast, so the tap's
            // mute has to come back off rather than leave the phone silent. The
            // return from this dialog is one of the two frames the undo is run
            // automatically in -- the user has just touched this app, so the
            // write is taken. A dismissal that never reaches this callback at all
            // (tapping outside the dialog) deliberately does not undo the mute:
            // that write would be touch-less and refused, and forcing it through
            // is what popped the system volume bar. The mute switch clears a mute
            // left behind that way.
            CastService.abandonCastStart();
            CastState.fail(getString(R.string.status_capture_denied));
        }
    }
}
