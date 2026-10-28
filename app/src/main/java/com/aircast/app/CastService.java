package com.aircast.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.app.PendingIntent;

public class CastService extends Service {
    static {
        System.loadLibrary("native_sender");
    }

    private static volatile CastService instance;
    // Persisted + clamped in Settings; the spec's starting point is 10 %.
    private static volatile int remoteVolume = 10;
    // remoteVolume mirrors Settings, but a fresh process must not surface the
    // hardcoded 10: after the app was swiped away, the value the user last set
    // is what the UI has to show before any cast starts. Load it once, lazily,
    // from the same store every other path reads and writes.
    private static volatile boolean volLoaded = false;

    public static int getRemoteVolume() {
        if (!volLoaded) {
            App app = App.get();
            if (app != null) {
                remoteVolume = Settings.getVolume(app);
                volLoaded = true;
            }
        }
        return remoteVolume;
    }

    public static void setRemoteVolume(int v) {
        remoteVolume = Math.max(0, Math.min(100, v));
        volLoaded = true;
        // Any explicit non-zero volume cancels a cast mute (the slider or a
        // volume key was moved while muted).
        if (remoteVolume > 0) castMuted = false;
        // Persist even when no cast is running: the slider is live before the
        // cast starts, and the service re-reads this on start -- dropping the
        // write here is exactly what made a pre-cast volume snap back to the
        // old value the moment the cast began.
        Context store = instance != null ? instance : App.get();
        if (store != null) Settings.setVolume(store, remoteVolume);
        CastService svc = instance;
        if (svc != null) {
            svc.nativeSetVolume(remoteVolume);
            CastLog.d("cast", "volume -> " + remoteVolume + "%");
            svc.refreshNotification();
            // Keep the session's own volume in step, so the panel cannot show
            // (and push back) the value from before the move.
            if (svc.volumeProvider != null) svc.volumeProvider.setCurrentVolume(remoteVolume);
        }
    }

    public static void adjustRemoteVolume(int delta) {
        setRemoteVolume(remoteVolume + delta);
    }

    // --- phone-speaker mute ---
    //
    // The phone's own output is silenced while casting so the sound only comes
    // out of the speaker. The manual "Mute phone" tap works; the automatic one
    // did not, and the device logs say why:
    //   * it is not the sequence. An automatic replay of the manual two-tap
    //     flow, reported as "manual" in the log, was refused every time, the
    //     same way a single write was. Nothing about the calls differs from the
    //     tap that works.
    //   * it is not timing either. Sixteen attempts spread over a full 32 s all
    //     read back the old volume with the stream flag still clear.
    //   * focus is necessary but NOT sufficient. One log has a window that had
    //     held focus for five seconds being refused eleven times in a row, so
    //     "the app is on top" alone does not open the gate.
    //   * what has never been refused is a write with a real touch on this app
    //     behind it: the commit tap took 3 times out of 3, a tap on the mute
    //     button always takes, and the re-assert after the user swiped back into
    //     this app took on the first try. A write at the consent dialog's return
    //     -- with no touch behind it -- was honoured 2 of 3 times in one build
    //     and refused 4 of 4 in another.
    //   * so the mute is applied on the commit tap, before the consent dialog
    //     ({@link #armCastMute} then {@link #mutePhoneNow}), and re-asserted when
    //     the dialog returns and on every later focus gain. The service's retry
    //     stays as a last fallback. {@link #abandonCastStart} rolls the tap's
    //     mute back when the consent was refused or cancelled.
    //   * the mute must be reported as the volume ACTUALLY in effect, never as
    //     the one that was asked for, or the button ends up claiming a mute the
    //     ROM ignored. So "wants mute" (muteWanted) and "is muted" (phoneMuted)
    //     are tracked separately, and phoneMuted is re-derived from the stream at
    //     the start of every session, because a session that ended on a refused
    //     unmute leaves it true and a stale true ends the next session's retry
    //     chain on its first refused attempt.
    //   * savedVolume stays -1 until a real volume has been recorded, so
    //     unmuting can never invent a volume the phone never had.
    //   * the undo is deliberately tied to a touch. The only two paths that undo
    //     the mute automatically are the consent dialog being refused/cancelled
    //     and the "Stop casting" tap -- both frames where the user has just
    //     touched this app, which is the write this device takes. Undoing the
    //     mute anywhere else -- the phone being tapped anywhere on this screen, a
    //     stop from the notification shade, the app merely coming back to the
    //     front -- is a touch-less write: it gets refused, and the fallback that
    //     forces it through pops the system volume bar up the side of the screen.
    //     Those paths were removed on purpose. A mute left behind by one of them
    //     is cleared by the on-screen "Unmute phone" button, whose touch works.
    private static final int MUTE_RETRY_MS = 500;
    private static final int MUTE_GIVE_UP_MS = 5000;
    private static volatile boolean phoneMuted = false;
    // The state the user asked for. Survives a failed write so the re-asserts
    // keep trying; only an explicit unmute clears it.
    private static volatile boolean muteWanted = false;
    private static volatile int savedVolume = -1;
    // Bumped on every user request so a queued re-assert can tell whether the
    // user has toggled again in the meantime (and must then leave the volume
    // alone).
    private static volatile int muteGen = 0;
    // True while the current mute was made for a cast and the cast still owns
    // undoing it. A manual mute with no cast running leaves this false, so an
    // undo is never run for a mute the user asked for.
    private static volatile boolean castMuteOwned = false;
    // Drives the undo's retry chain. Static, so a chain started while the
    // activity is on screen is not cut short by the service being torn down.
    private static final android.os.Handler rollbackHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile int rollbackGen = 0;

    public static boolean isPhoneMuted() {
        return phoneMuted;
    }

    /** True while the user wants the phone silent (set at cast start). */
    public static boolean wantsPhoneMute() {
        return muteWanted;
    }

    /**
     * Silences the phone from a context that is ON SCREEN -- call it from the
     * activity, the instant the capture-consent dialog returns and again
     * whenever this window regains focus. That is the situation the manual
     * "Mute phone" tap is made in, and the only one where this device has been
     * seen to take the write: every attempt made from the service, or from the
     * activity while it was not the one being touched, came back with the old
     * volume unchanged.
     *
     * @return the state actually in effect, read back from AudioManager.
     */
    public static boolean mutePhoneNow(Context ctx) {
        if (ctx == null) return phoneMuted;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return phoneMuted;
        muteWanted = true;
        if (savedVolume < 0) {
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur > 0) savedVolume = cur;
        }
        if (writeMute(am, "on-screen mute (activity)")) phoneMuted = true;
        return phoneMuted;
    }

    /**
     * Called on the "Start casting" tap, before the consent dialog is raised.
     *
     * The mute that follows rides on that tap -- the one write this device never
     * refuses -- so the session's pre-cast volume has to be recorded from here,
     * not from whatever the volume panel happens to show once the capture is up.
     */
    public static void armCastMute() {
        muteWanted = true;
        // Re-record the pre-cast volume from this tap, but only while the phone is
        // actually audible. If it is still silent from an earlier session whose
        // undo was refused, the volume that session recorded is the one to keep:
        // wiping it here and then re-reading a muted stream would record nothing,
        // and the next undo would have no volume to put back.
        if (readVolume(audioManager()) > 0) savedVolume = -1;
        // This mute belongs to the cast about to start: if the cast never starts
        // (the consent dialog is refused or cancelled) or later ends, undoing it
        // is ours. Cancels any undo left over from the previous session.
        castMuteOwned = true;
        rollbackGen++;
    }

    /**
     * The consent dialog came back refused or cancelled: no cast is running, so
     * the tap's mute has to come off.
     *
     * This is one of only two frames the undo is run automatically in, and both
     * are frames where the user has just touched this app -- the dialog's return
     * and the "Stop casting" tap. Undoing from any other moment is a touch-less
     * write this device refuses, and forcing it through would pop the system
     * volume bar up the side of the screen, so those paths are gone on purpose.
     */
    public static void abandonCastStart() {
        if (isRunning() || !castMuteOwned) return;
        rollbackCastMute("consent denied");
    }

    /**
     * Undoes the cast's mute on the "Stop casting" tap, before the service is
     * torn down.
     *
     * This is the same touch-backed moment the mute itself is applied on, and
     * the undo is only reliable while a real touch is behind it: the touch-less
     * writes made while the service goes away get refused, which is what left
     * the phone silent after a stop. The retry chain still follows as a
     * fallback, and it finds the phone already audible if this pass worked.
     */
    public static void unmuteOnStopTap() {
        if (!castMuteOwned) return;
        rollbackCastMute("stop tap");
    }

    /**
     * Undoes the cast's mute through a short retry chain.
     *
     * The undo is made from a frame where the user has just touched this app (the
     * consent dialog's return, or the "Stop casting" tap), so the first write
     * normally lands. The chain only matters when that one write is dropped
     * anyway; it retries while the stream keeps reading back silent. When it runs
     * out of time {@code castMuteOwned} stays set, and the on-screen "Unmute
     * phone" button is what clears the mute from there.
     *
     * @param cause printed in the log, so a refused undo names the path it came
     *              from.
     */
    static void rollbackCastMute(String cause) {
        int gen = ++rollbackGen;
        rollbackAttempt(gen, 0, cause);
    }

    private static void rollbackAttempt(int gen, int elapsed, String cause) {
        if (gen != rollbackGen || !castMuteOwned) return;
        AudioManager am = audioManager();
        if (am == null) return;
        if (readVolume(am) != 0) {
            // Already audible: the mute never took, or someone else raised it.
            CastLog.d("mute", "undo (" + cause + "): already audible, nothing to restore");
            finishRollback();
            return;
        }
        if (savedVolume <= 0) {
            // The phone was already silent before the cast, so no earlier volume
            // was recorded and there is nothing to put back. Release the undo
            // instead of retrying a volume that never existed.
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception ignored) {
            }
            boolean still = readVolume(am) == 0;
            CastLog.i("mute", "rollback (" + cause + "): no volume recorded, muted=" + still);
            castMuteOwned = false;
            muteWanted = false;
            phoneMuted = still;
            savedVolume = -1;
            rollbackGen++;
            return;
        }
        if (replayUnmuteTap(am, "undo (" + cause + ")")) {
            CastLog.i("mute", "rolled back (" + cause + ") after +" + elapsed + "ms");
            finishRollback();
            return;
        }
        if (elapsed >= MUTE_GIVE_UP_MS) {
            // Out of time. Keep owning it -- nothing retries on its own any more,
            // so the "Unmute phone" button is what clears the mute from here.
            CastLog.w("mute", "rollback still refused (" + cause + ") after " + elapsed + "ms");
            return;
        }
        rollbackHandler.postDelayed(
                () -> rollbackAttempt(gen, elapsed + MUTE_RETRY_MS, cause), MUTE_RETRY_MS);
    }

    /** The undo is done for real: only now is the recorded volume safe to drop. */
    private static void finishRollback() {
        rollbackGen++;
        castMuteOwned = false;
        muteWanted = false;
        phoneMuted = false;
        savedVolume = -1;
    }

    private static AudioManager audioManager() {
        Context c = App.get();
        return c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    private static int readVolume(AudioManager am) {
        try {
            return am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The mute write itself, and the only place it is made.
     *
     * The plain pair -- exactly what a manual tap performs -- and nothing else.
     * There is deliberately no FLAG_SHOW_UI repeat: that flag asks the system to
     * put the volume bar on screen, and it never once made a write land that the
     * plain pair had missed (every time it was reached the log read
     * "via=show-ui -> refused"), so all it added was the bar. The log carries the
     * full picture -- the volume before and the stream's bounds, whether the ROM
     * reports the volume as fixed, the ringer mode and whether audio is playing --
     * so a refused write can be told apart from one that was clamped.
     *
     * @return true only when the read-back confirms the stream is at 0.
     */
    private static boolean writeMute(AudioManager am, String tag) {
        int before = -1, max = -1, min = -1;
        try {
            before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        } catch (Exception ignored) {
        }
        boolean ok = false;
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            ok = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        } catch (Exception e) {
            CastLog.w("mute", tag + " write threw: " + e);
        }
        CastLog.i("mute", tag + " | app fg=" + MainActivity.focused
                + " vol " + before + "/" + max + " (min " + min + ")"
                + " -> " + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                + " flag=" + streamMuted(am)
                + " fixed=" + am.isVolumeFixed()
                + " ringer=" + am.getRingerMode()
                + " music=" + am.isMusicActive()
                + " mode=" + am.getMode()
                + " -> " + (ok ? "MUTED" : "refused"));
        return ok;
    }

    /**
     * Mutes or unmutes the phone's media stream.
     *
     * @return the state ACTUALLY in effect afterwards, read back from
     *         AudioManager. When a mute does not stick the caller sees
     *         {@code false} and can tell the user instead of lying.
     */
    public static boolean setPhoneMute(AudioManager am, boolean mute) {
        return setPhoneMute(am, mute, -1);
    }

    /**
     * @param elapsedMs when &gt;= 0, the time since the cast started; the log
     *                  prints it so the retry loop shows WHEN a mute finally
     *                  takes, which is what separates a time gate from a
     *                  sequence problem.
     */
    public static boolean setPhoneMute(AudioManager am, boolean mute, int elapsedMs) {
        if (am == null) return phoneMuted;
        muteWanted = mute;
        try {
            if (mute) {
                // Remember the pre-cast volume once, and only once, for the
                // whole session -- a later re-assert must not overwrite it with
                // a value the system restored in the meantime.
                if (savedVolume < 0) {
                    int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (cur > 0) savedVolume = cur;
                }
                // Same write the automatic path makes, so the two are directly
                // comparable in the log. Whether it takes is up to the ROM --
                // the read-back is the only thing that decides phoneMuted.
                if (writeMute(am, "tap mute")) phoneMuted = true;
                // A mute made with no cast running is the user's own (the button
                // also works before and after a cast), so no cast undo owns it.
                if (instance == null) castMuteOwned = false;
            } else {
                replayUnmuteTap(am, "unmute tap");
                phoneMuted = readVolume(am) == 0;
                if (!phoneMuted) savedVolume = -1;
                // The user has taken the mute back: stop owning the undo. If the
                // ROM refused even this write the phone is still silent, so
                // castMuteOwned stays set -- pressing the button again retries.
                if (!phoneMuted) {
                    castMuteOwned = false;
                    rollbackGen++;
                }
            }
        } catch (Exception e) {
            // Some ROMs reject adjustStreamVolume(ADJUST_MUTE) outright; keep
            // whatever the plain volume write achieved rather than crash.
            CastLog.w("mute", "volume write rejected: " + e);
        }
        CastLog.i("mute", (mute ? "mute" : "unmute") + " -> streamVolume="
                + am.getStreamVolume(AudioManager.STREAM_MUSIC) + " muted=" + phoneMuted
                + " streamMute=" + streamMuted(am) + " wanted=" + muteWanted
                + (elapsedMs >= 0 ? " +" + elapsedMs + "ms" : ""));
        return phoneMuted;
    }

    /**
     * The two writes that undo the mute: clear the stream's mute flag and put the
     * recorded volume back.
     *
     * Plain, like the mute write, and for the same reason: the FLAG_SHOW_UI
     * repeat only ever put the system volume bar on screen, never a write that
     * the plain pair had missed (see {@link #writeMute}). The log line mirrors
     * the mute's, so a refused undo can be told apart from one that was clamped.
     *
     * @return true only when the read-back confirms the stream is audible again.
     */
    private static boolean replayUnmuteTap(AudioManager am, String tag) {
        int before = readVolume(am);
        boolean ok = false;
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            if (savedVolume > 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            }
            ok = readVolume(am) != 0;
        } catch (Exception e) {
            CastLog.w("mute", tag + " write threw: " + e);
        }
        CastLog.i("mute", tag + " | app fg=" + MainActivity.focused
                + " vol " + before + " -> " + readVolume(am) + " (restore=" + savedVolume + ")"
                + " streamMute=" + streamMuted(am)
                + " -> " + (ok ? "AUDIBLE" : "refused"));
        return ok;
    }

    /** Reads the stream's mute flag; deprecated but still the only flag probe. */
    private static boolean streamMuted(AudioManager am) {
        try {
            return am.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            return false;
        }
    }

    /** Mutes the phone on cast start, then keeps trying while the ROM refuses. */
    private void applyStartupMute() {
        muteWanted = true;
        // This session owns undoing the mute; cancel any undo left over from the
        // previous one.
        castMuteOwned = true;
        rollbackGen++;
        // Re-derive the reported state from the stream instead of trusting the
        // flag: the previous session's stop-time unmute may itself have been
        // refused, which leaves phoneMuted true. A stale true would end this
        // session's retry chain on its first refused attempt (only the +0ms pass
        // would ever run) and would have the button claim a mute that is not
        // there. Usually this is the tap-time mute reading back as still in
        // effect, in which case there is nothing left to re-assert.
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            phoneMuted = am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        } catch (Exception ignored) {
            phoneMuted = false;
        }
        if (phoneMuted) return;
        muteAttempt(++muteGen, 0);
    }

    /**
     * Mutes or unmutes the phone from the on-screen switch.
     *
     * Works with no cast running, too: after a stop the service instance is
     * gone, and the switch used to be a silent no-op exactly when a left-over
     * mute most needs clearing. The write here rides on the switch's touch,
     * which is the one this device is most willing to take.
     *
     * @return the state ACTUALLY in effect, so the caller can put the switch
     *         back if the ROM refused the change.
     */
    public static boolean setPhoneMuteFromUi(Context ctx, boolean mute) {
        AudioManager am = ctx == null ? audioManager()
                : (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return phoneMuted;
        int gen = ++muteGen;
        boolean now = setPhoneMute(am, mute);
        CastService svc = instance;
        // Keep retrying from the live service when there is one; from the switch
        // the write already carries a touch, so a single attempt is enough.
        if (mute && !now && svc != null) svc.muteAttempt(gen, 0);
        return now;
    }

    /**
     * One mute attempt, another every {@link #MUTE_RETRY_MS} while the phone
     * still reads back audible, up to {@link #MUTE_GIVE_UP_MS}. The write is
     * the exact pair the manual tap performs; the log line also records whether
     * the app was on screen, which is the one thing that decides whether this
     * device takes the write at all.
     */
    private void muteAttempt(final int gen, final int elapsed) {
        if (elapsed > MUTE_GIVE_UP_MS) {
            CastLog.w("mute", "gave up after " + elapsed + " ms with the phone audible");
            return;
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (savedVolume < 0) {
            try {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (cur > 0) savedVolume = cur;
            } catch (Exception ignored) {
            }
        }
        tryLevers(am, elapsed);
        if (phoneMuted) return;
        muteHandler.postDelayed(() -> {
            if (instance != this || muteGen != gen || !muteWanted) return;
            muteAttempt(gen, elapsed + MUTE_RETRY_MS);
        }, MUTE_RETRY_MS);
    }

    /**
     * Writes the mute and reports what the stream reads back. The volume is
     * only ever reported as muted when the read-back confirms it, so a refused
     * write cannot leave the button claiming a mute the phone does not have.
     */
    private void tryLevers(AudioManager am, int elapsed) {
        String visible = MainActivity.visible ? "on-screen" : "background";
        if (writeMute(am, "service attempt +" + elapsed + "ms " + visible)) phoneMuted = true;
    }

    // --- cast (speaker) mute ---
    // 0 % on the wire is -144 dBFS, i.e. a true mute at the receiver, so we
    // just remember where the slider was and push it back on unmute.
    private static volatile boolean castMuted = false;
    private static volatile int mutedVolume = 10;

    public static boolean isCastMuted() {
        return castMuted;
    }

    public static boolean toggleCastMute() {
        if (!castMuted) {
            mutedVolume = remoteVolume;
            castMuted = true;
            setRemoteVolume(0);
        } else {
            castMuted = false;
            setRemoteVolume(mutedVolume > 0 ? mutedVolume : 10);
        }
        return castMuted;
    }

    private AudioRecord record;
    private Thread thread;
    private MediaSession session;
    private VolumeProvider volumeProvider;
    private MediaProjection projection;
    // Drives the mute re-assert chain; one handler for the session so onDestroy
    // can cancel every pending pass at once.
    private final android.os.Handler muteHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            // The projection ended from outside (user tapped "Stop now" on the
            // cast affordance, or the system revoked it). A mediaProjection
            // foreground service must not outlive its projection, so stop.
            stopSelf();
        }
    };
    private volatile boolean stop = false;
    private int rate = 44100;
    private String currentHost = "";

    // Live capture diagnostics: actual negotiated rate, peak sample magnitude
    // since the last poll, cumulative read errors, frames read.
    static volatile int diagRate;
    static volatile int diagPeak;
    static volatile int diagErrors;
    static volatile long diagFrames;

    static boolean isRunning() { return instance != null; }

    static int takePeak() {
        int p = diagPeak;
        diagPeak = 0;
        return p;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Notification action intents carry no capture extras -- handle them
        // here and bail out before we try to read the projection data.
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "STOP_CAST":
                    stopSelf();
                    return START_NOT_STICKY;
                case "VOL_UP":
                    adjustRemoteVolume(Settings.getVolStep(this));
                    return START_NOT_STICKY;
                case "VOL_DOWN":
                    adjustRemoteVolume(-Settings.getVolStep(this));
                    return START_NOT_STICKY;
                case "TOGGLE_MUTE":
                    toggleCastMute();
                    refreshNotification();
                    return START_NOT_STICKY;
            }
        }
        if (instance == this) {
            // A stale "Start casting" tap while a cast is already live would
            // double the capture -- and, before the session below was released,
            // leave two "AirCast" entries in the volume panel.
            CastLog.w("cast", "ignoring a start while a cast is already running");
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        String host = intent.getStringExtra("host");
        if (host == null || host.isEmpty() || data == null) {
            CastState.fail(getString(R.string.status_cast_failed));
            stopSelf();
            return START_NOT_STICKY;
        }
        currentHost = host;

        // Remote-volume media session: this is the "AirCast" column in the
        // system volume panel and the place the speaker volume is set from
        // outside the app. It is not what carries the volume keys -- those are
        // handled by MainActivity while this app is on screen, and were never
        // routed here once the app is hidden.
        remoteVolume = Settings.getVolume(this);
        volLoaded = true;
        if (session != null) {
            // Release before replacing: a second start would otherwise leave the
            // old session behind as a second "AirCast" entry.
            session.release();
        }
        session = new MediaSession(this, App.APP_NAME);
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
        final int step = Settings.getVolStep(this);
        volumeProvider = new VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, remoteVolume) {
            @Override
            public void onAdjustVolume(int direction) {
                CastService.adjustRemoteVolume(direction * step);
                setCurrentVolume(remoteVolume);
            }

            @Override
            public void onSetVolumeTo(int volume) {
                CastService.setRemoteVolume(volume);
                setCurrentVolume(remoteVolume);
            }
        };
        session.setPlaybackToRemote(volumeProvider);

        // Foreground notification (mediaProjection type is declared in the manifest).
        // MediaStyle + a remote VolumeProvider makes the system render a volume
        // slider in the shade and on the lock screen -- that is the "pull down
        // to control the speaker volume" path (task 4).
        NotificationChannel ch = new NotificationChannel(
                "cast", getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        startForeground(1, buildNotification());

        MediaProjection mp = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(resultCode, data);
        if (mp == null) {
            CastState.fail(getString(R.string.status_cast_failed));
            stopSelf();
            return START_NOT_STICKY;
        }
        mp.registerCallback(projectionCallback, new android.os.Handler(getMainLooper()));
        projection = mp;

        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build();

        record = buildRecord(cfg, rate);
        if (record == null) { // some devices only allow 48 kHz capture
            rate = 48000;
            record = buildRecord(cfg, rate);
        }
        if (record == null) {
            CastState.fail(getString(R.string.status_cast_failed));
            stopSelf();
            return START_NOT_STICKY;
        }
        // The device may silently override the requested rate, so tell the
        // native sender the rate the record ACTUALLY runs at.
        int actual = record.getSampleRate();
        if (actual > 0) rate = actual;

        int rc = nativeStart(host, rate, Settings.getLatencyMs(this));
        if (rc != 0) {
            record.release();
            record = null;
            CastState.fail(getString(R.string.status_cast_failed));
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        // From here the capture is live, which is what the screen draws its
        // "stop" affordance and the capture line from.
        CastState.casting(host);
        nativeSetVolume(remoteVolume);
        CastLog.i("cast", "started to " + host + " rate=" + rate
                + " latency=" + String.format("%.2fs", (double) Settings.getLatencyMs(this) / 1000.0));

        diagRate = rate;
        diagPeak = 0;
        diagErrors = 0;
        diagFrames = 0;

        record.startRecording();
        thread = new Thread(() -> {
            short[] buf = new short[2048];
            while (!stop) {
                int got = record.read(buf, 0, buf.length);
                if (got > 0) {
                    int pk = 0;
                    for (int i = 0; i < got; i++) {
                        int a = buf[i] < 0 ? -buf[i] : buf[i];
                        if (a > pk) pk = a;
                    }
                    if (pk > diagPeak) diagPeak = pk;
                    diagFrames += got;
                    nativeWrite(buf, got);
                } else if (got < 0) {
                    diagErrors++;
                    if (got == AudioRecord.ERROR_DEAD_OBJECT
                            || got == AudioRecord.ERROR_INVALID_OPERATION
                            || got == AudioRecord.ERROR) break;
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        }, "capture");
        thread.start();
        // Mute the phone by default. Started here, right after the capture
        // pipeline, and re-asserted for the next couple of seconds so the
        // audio-policy reconfiguration cannot leave the phone audible.
        applyStartupMute();
        return START_NOT_STICKY;
    }

    private AudioRecord buildRecord(AudioPlaybackCaptureConfiguration cfg, int rate) {
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(rate * 4)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            return r.getState() == AudioRecord.STATE_INITIALIZED ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        CastLog.i("cast", "stopping");
        stop = true;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            record = null;
        }
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        nativeStop();
        if (session != null) {
            session.release();
            session = null;
        }
        volumeProvider = null;
        if (projection != null) {
            // The callback fires stopSelf() again — harmless when going away.
            try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        // The session is over from here on.
        instance = null;
        // A mute this cast applied is deliberately left in place. The ways a cast
        // can end without a button tap -- a stop from the notification shade, the
        // projection being revoked -- carry no touch, and a touch-less volume
        // write is the one this device refuses, so there is no reliable undo for
        // them. Clearing it is the on-screen "Unmute phone" button's job, and the
        // recorded volume is kept so that button can put the right level back.
        // castMuteOwned is left alone so an undo chain started by the "Stop
        // casting" tap is not cut short here.
        if (!phoneMuted) savedVolume = -1;
        // A queued re-assert must not touch the volume after the session is gone
        // (the undo has its own handler and keeps running).
        muteGen++;
        muteHandler.removeCallbacksAndMessages(null);
        // Ending the session is the last thing this service reports. A phase set
        // just before stopSelf() (the failure paths) is left alone, so the reason
        // survives its own teardown.
        CastState.clearIfActive();
        super.onDestroy();
    }

    /**
     * Foreground notification. Layout matches AirMusic / vivo remote PC cards
     * in the user's screenshot:
     *   [icon]  IP · vol%     ← single-line title, compact so vol% isn't cut
     *           4 × icon-only ← four actions on the bottom row, no text labels
     *
     * MediaStyle + setShowActionsInCompactView(0,1,2,3) puts all four actions
     * in the collapsed view, so they are visible without pulling the card up.
     * The MediaSession / VolumeProvider drives the hardware volume keys.
     */
    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopPi = actionPi("STOP_CAST", 1);
        PendingIntent volDownPi = actionPi("VOL_DOWN", 2);
        PendingIntent volUpPi = actionPi("VOL_UP", 3);
        PendingIntent mutePi = actionPi("TOGGLE_MUTE", 4);

        String volText = (castMuted || remoteVolume == 0) ? "0%" : remoteVolume + "%";

        Notification.Builder b = new Notification.Builder(this, "cast")
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF5AC8FA)
                .setContentTitle(currentHost + "  ·  " + volText)
                .setContentIntent(contentPi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                // Empty titles everywhere: MediaStyle renders these as pure
                // icon buttons (the mute toggle used to leak "mut"/"unm").
                .addAction(R.drawable.ic_act_stop, "", stopPi)
                .addAction(R.drawable.ic_act_vol_down, "", volDownPi)
                .addAction(R.drawable.ic_act_vol_up, "", volUpPi)
                .addAction(R.drawable.ic_act_mute, "", mutePi);
        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2, 3));
        }
        return b.build();
    }

    private PendingIntent actionPi(String action, int reqCode) {
        Intent i = new Intent(this, CastService.class);
        i.setAction(action);
        return PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, buildNotification());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private native int nativeStart(String host, int rate, int latencyMs);
    private native void nativeWrite(short[] pcm, int samples);
    private native void nativeSetVolume(int pct);
    private native void nativeStop();
}
