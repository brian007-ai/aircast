package com.aircast.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
 * The main screen: pick a speaker, start/stop the cast, and set the speaker's
 * volume. Everything that does not change while casting lives in
 * {@link SettingsActivity}.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;

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
    private EditText hostField;
    private TextView status;
    private TextView go;
    private TextView muteBtn;
    private SeekBar volSlider;
    private TextView volPct;
    private boolean volDragging;
    private LinearLayout devicesBox;
    private AirplayDiscovery discovery;
    private SharedPreferences prefs;
    private boolean running = false;
    // When the button was last set from this screen. The one-second tick only
    // overrides the button once the service has had time to start or stop, so a
    // tap cannot flip back before the service catches up.
    private long uiStateAt;
    private static final long SYNC_GRACE_MS = 3000;
    private TextView diagLine;
    private Ui ui;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Discovery gives up after 15 s; kept as a field so it can be cancelled
    // with the rest of the UI callbacks when the screen goes away.
    private final Runnable discoveryTimeout = this::stopDiscovery;

    private final Runnable diagTick = new Runnable() {
        @Override public void run() {
            if (diagLine != null) {
                if (CastService.isRunning()) {
                    long secs = CastService.diagFrames / Math.max(1, CastService.diagRate);
                    diagLine.setText("capture " + CastService.diagRate + " Hz \u00b7 peak "
                            + CastService.takePeak() + " \u00b7 err " + CastService.diagErrors
                            + " \u00b7 " + secs + "s sent");
                } else {
                    diagLine.setText("");
                }
            }
            // A cast stopped from the notification, or by the system reclaiming
            // the projection, fires no callback here -- so re-check the button
            // on the tick as well.
            syncRunning();
            // The mute is re-asserted for a couple of seconds after a cast
            // starts, so the state can settle after the button was last shown.
            // Keep its label in step with the mute actually in effect.
            if (muteBtn != null) {
                String label = CastService.isPhoneMuted() ? "Unmute phone" : "Mute phone";
                if (!label.contentEquals(muteBtn.getText())) muteBtn.setText(label);
            }
            // The slider also moves from the volume keys, the notification
            // shade and the other end of a hand-off, so pull it back in sync
            // -- but never while the user's thumb is on it.
            if (volSlider != null && !volDragging) {
                int v = CastService.getRemoteVolume();
                if (volSlider.getProgress() != v) volSlider.setProgress(v);
                if (volPct != null) volPct.setText(v + "%");
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences("cast", MODE_PRIVATE);
        ui = new Ui(this);
        ui.applyWindow(this);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }

        FrameLayout shell = new FrameLayout(this);
        shell.addView(new AuroraView(this, ui), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int edge = ui.dp(20);
        // Android 15+ draws edge-to-edge by default, so the content is inset
        // past the system bars and the display cutout on every edge -- the top
        // in portrait, and the side navigation bar / camera cutout as well once
        // the device is turned landscape.
        ui.applyInsets(root, edge, ui.dp(20), edge, ui.dp(28));
        // Tapping anywhere that isn't the IP field drops its focus and closes
        // the keyboard. The root has to be focusable in touch mode, otherwise
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

        root.addView(ui.title(App.APP_NAME));
        TextView tagline = ui.subtitle("Send this phone's sound to AirPlay speakers");
        tagline.setPadding(0, ui.dp(6), 0, 0);
        root.addView(tagline);
        root.addView(statusPill());

        root.addView(deviceCard(), ui.top(22));
        root.addView(goButton(), ui.top(18));
        root.addView(volumeCard(), ui.top(14));
        root.addView(muteButton(), ui.top(12));

        diagLine = ui.text("", 11, ui.faint, false);
        diagLine.setTypeface(Typeface.MONOSPACE);
        diagLine.setPadding(0, ui.dp(16), 0, 0);
        root.addView(diagLine);
        uiHandler.postDelayed(diagTick, 1000);

        root.addView(settingsRow(), ui.top(18));

        setContentView(shell);
        // Launch with the IP field unfocused so the cursor and the keyboard
        // don't pop up on their own; the field still focuses normally on tap.
        clearFieldFocus(root);
        // Sync the button LAST: the TextWatcher inside deviceCard() fires while
        // hostField.setText() runs, which is before goButton() exists, and its
        // callback bails on the null button. Without this call the button would
        // keep its initial enabled state and a fresh install (empty field, no
        // speaker picked) would still offer a tappable "Start casting".
        updateStartState();
    }

    /** Drops the IP field's focus (if any) and closes the soft keyboard. */
    private void clearFieldFocus(View focusTarget) {
        View focused = getCurrentFocus();
        if (focused != null) focused.clearFocus();
        focusTarget.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focusTarget.getWindowToken(), 0);
        }
    }

    /** "Start casting" is only tappable once there is a speaker address to use. */
    private void updateStartState() {
        if (go == null) return;
        // While a cast is live the button is "Stop casting" and must stay
        // tappable no matter what the address field holds.
        boolean ok = running || !TextUtils.isEmpty(hostField.getText().toString().trim());
        go.setEnabled(ok);
        go.setAlpha(ok ? 1f : 0.5f);
    }

    // --- header ------------------------------------------------------------

    /** Glass capsule holding the current state line. */
    private View statusPill() {
        status = ui.text("Ready to cast", 13, ui.dim, false);
        View dot = ui.dot(ui.accent);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(ui.pillFill());
        pill.setPadding(ui.dp(14), ui.dp(9), ui.dp(16), ui.dp(9));
        pill.setElevation(ui.dp(4));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dotLp.rightMargin = ui.dp(9);
        pill.addView(dot, dotLp);
        pill.addView(status);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(16);
        pill.setLayoutParams(lp);
        return pill;
    }

    // --- device card -------------------------------------------------------

    private View deviceCard() {
        LinearLayout card = ui.card();
        card.addView(ui.section("Speaker"));

        hostField = ui.field();
        // Generic placeholder only — mDNS discovery is the normal path. Never a
        // real address here: this string ships inside every user's APK.
        // Accepts "IP" or "IP:port" (port defaults to 7000 on AirPlay).
        hostField.setHint("192.168.1.8:7000 or 192.168.1.8");
        hostField.setText(prefs.getString("host", ""));
        // Enable/disable "Start casting" as the address field fills and empties.
        // (Also fires when mDNS picks a speaker, since that sets this text.)
        hostField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateStartState(); }
        });
        card.addView(hostField, ui.top(10));

        TextView find = ui.secondaryButton("Scan for speakers");
        find.setOnClickListener(v -> startDiscovery());
        card.addView(find, ui.top(12));

        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(devicesBox);
        return card;
    }

    // --- actions -----------------------------------------------------------

    private View goButton() {
        go = ui.primaryButton("Start casting");
        go.setOnClickListener(v -> {
            if (!running) {
                showDisclosureThenCapture();
            } else {
                // Undo the mute on the tap itself, before the service is torn
                // down: this is the same touch-backed moment the mute rides on,
                // and the touch-less writes from the service's teardown get
                // refused (see CastService's mute notes).
                CastService.unmuteOnStopTap();
                stopService(new Intent(this, CastService.class));
                running = false;
                setRunningUi(false);
                status.setText("Stopped");
            }
        });
        return go;
    }

    private View muteButton() {
        muteBtn = ui.secondaryButton("Mute phone");
        muteBtn.setOnClickListener(v -> {
            boolean before = CastService.isPhoneMuted();
            boolean now = CastService.togglePhoneMute();
            muteBtn.setText(now ? "Unmute phone" : "Mute phone");
            // togglePhoneMute() reports the volume actually in effect, so an
            // unchanged state means the ROM refused the change. Say so instead
            // of showing a mute the phone does not have.
            status.setText(now == before ? "The phone's volume could not be changed"
                    : (now ? "Phone muted" : "Phone unmuted"));
        });
        return muteBtn;
    }

    // --- volume ------------------------------------------------------------

    private View volumeCard() {
        LinearLayout card = ui.card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.heading("Speaker volume"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volPct = ui.value(Settings.getVolume(this) + "%");
        head.addView(volPct);
        card.addView(head);

        volSlider = ui.slider();
        volSlider.setMax(100);
        volSlider.setProgress(Settings.getVolume(this));
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (volPct != null) volPct.setText(p + "%");
                if (fromUser) CastService.setRemoteVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { volDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { volDragging = false; }
        });
        card.addView(volSlider, ui.top(4));

        TextView note = ui.caption("Adjusts the speaker, not the phone. The volume-key step "
                + "is in Settings.");
        note.setMaxLines(2);
        card.addView(note, ui.top(2));
        return card;
    }

    private View settingsRow() {
        LinearLayout list = ui.list();
        View row = ui.tapRow("Settings", "");
        row.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ui.addRow(list, row);
        return list;
    }

    // --- mDNS discovery ----------------------------------------------------

    private void startDiscovery() {
        if (discovery != null) return;
        devicesBox.removeAllViews();
        status.setText("Searching for AirPlay speakers\u2026");
        discovery = new AirplayDiscovery(this, new AirplayDiscovery.Listener() {
            @Override public void onDevice(String name, String host) {
                runOnUiThread(() -> addDeviceRow(name, host));
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> status.setText("Discovery error: " + message));
            }
        });
        discovery.start();
        uiHandler.postDelayed(discoveryTimeout, 15000);
    }

    private void addDeviceRow(String name, String host) {
        if (devicesBox.getChildCount() > 0) devicesBox.addView(ui.divider());

        View dot = ui.dot(ui.accent);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(name, 16, ui.accent, true));
        TextView addr = ui.caption(host);
        addr.setPadding(0, ui.dp(2), 0, 0);
        labels.addView(addr);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(12), 0, ui.dp(12));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dotLp.rightMargin = ui.dp(12);
        row.addView(dot, dotLp);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ui.chevron());
        ui.clickable(row);
        row.setOnClickListener(v -> {
            hostField.setText(host);
            prefs.edit().putString("host", host).apply();
            status.setText("Selected " + name + " (" + host + ")");
            stopDiscovery();
        });
        devicesBox.addView(row);
        int n = devicesBox.getChildCount();
        status.setText(n == 1 ? "Found a speaker \u2014 tap it"
                : "Found " + n + " speakers \u2014 tap one");
    }

    private void stopDiscovery() {
        uiHandler.removeCallbacks(discoveryTimeout);
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
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
        TextView msg = ui.body(App.APP_NAME
                + " captures the audio playing on this phone and streams it only to the "
                + "speaker you pick, over your local Wi-Fi network.\n\nNothing is recorded, stored, "
                + "or uploaded anywhere.");
        box.addView(msg);
        CheckBox cb = new CheckBox(this);
        cb.setText("Don't show again");
        cb.setTextColor(ui.textColor);
        cb.setButtonTintList(ColorStateList.valueOf(ui.accent));
        box.addView(cb, ui.top(12));

        GlassDialog dialog = new GlassDialog(this, ui, "Before we start");
        dialog.setBody(box);
        dialog.addAction("Cancel", false, null);
        dialog.addAction("Continue", true, () -> {
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

    private void setRunningUi(boolean on) {
        uiStateAt = SystemClock.uptimeMillis();
        go.setText(on ? "Stop casting" : "Start casting");
        ui.setPrimary(go, on);
        updateStartState();
    }

    /**
     * Pulls the start/stop button in step with the service. A cast can also be
     * stopped without this screen knowing -- the notification's stop action, or
     * the system revoking the projection -- and no activity callback fires for
     * those, so the state is re-checked here as well as in onResume.
     */
    private void syncRunning() {
        boolean live = CastService.isRunning();
        if (live == running) return;
        // Right after a tap on this screen the service has not caught up yet
        // (it reports itself running only once its sender is up), so the button
        // is left alone for a moment before the live state wins.
        if (SystemClock.uptimeMillis() - uiStateAt < SYNC_GRACE_MS) return;
        running = live;
        setRunningUi(live);
        status.setText(live ? "Casting to " + prefs.getString("host", "") : "Ready to cast");
    }

    // --- lifecycle / callbacks ---------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        muteBtn.setText(CastService.isPhoneMuted() ? "Unmute phone" : "Mute phone");
        // Sync with the service: after the activity is recreated (rotation,
        // process restore) while a cast runs, running/go/status would otherwise
        // show the idle UI while capture is still live.
        syncRunning();
        if (volSlider != null && !volDragging) {
            int v = CastService.getRemoteVolume();
            volSlider.setProgress(v);
            if (volPct != null) volPct.setText(v + "%");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
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
        // diagTick re-posts itself every second and would otherwise keep the
        // whole Activity (and its view tree) alive after the screen is gone.
        uiHandler.removeCallbacksAndMessages(null);
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (running && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            int step = Settings.getVolStep(this);
            CastService.adjustRemoteVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? step : -step);
            status.setText("Volume: " + CastService.getRemoteVolume());
            return true; // consume — don't change the phone's own volume
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            String host = hostField.getText().toString().trim();
            prefs.edit().putString("host", host).apply();
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
            running = true;
            setRunningUi(true);
            status.setText("Casting to " + host);
        } else if (requestCode == REQ_CAPTURE) {
            // Consent refused or the dialog cancelled: no cast, so the tap's
            // mute has to come back off rather than leave the phone silent. The
            // return from this dialog is one of the two frames the undo is run
            // automatically in -- the user has just touched this app, so the
            // write is taken. A dismissal that never reaches this callback at all
            // (tapping outside the dialog) deliberately does not undo the mute:
            // that write would be touch-less and refused, and forcing it through
            // is what popped the system volume bar. The "Unmute phone" button
            // clears a mute left behind that way.
            CastService.abandonCastStart();
            status.setText("Capture consent denied");
        }
    }
}
