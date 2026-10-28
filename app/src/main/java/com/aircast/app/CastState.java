package com.aircast.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place that says what the cast is doing.
 *
 * The screen used to infer the state from a static boolean plus a one-second
 * poll, which is why a stop from the notification could leave the button
 * showing "Stop casting" for a while. Every transition now goes through here,
 * the screen renders a snapshot, and listeners are told on the main thread --
 * so the UI is event driven instead of polled.
 *
 * A snapshot is immutable and replaced atomically, so a listener that reads it
 * from another thread cannot see half a transition.
 */
public final class CastState {

    public enum Phase {
        /** Nothing running; the button offers to start. */
        IDLE,
        /** mDNS scan in progress (the sheet is open). */
        DISCOVERING,
        /** The capture is being brought up by the service. */
        CONNECTING,
        /** Audio is flowing. */
        CASTING,
        /** Teardown requested, service not gone yet. */
        STOPPING,
        /** Last attempt failed; the detail line says why. */
        ERROR
    }

    public static final class Snapshot {
        public final Phase phase;
        /** Host being cast to, empty when none is pending. */
        public final String target;
        /** Already-localised extra line for the status pill; may be empty. */
        public final String detail;

        Snapshot(Phase phase, String target, String detail) {
            this.phase = phase;
            this.target = target;
            this.detail = detail;
        }
    }

    public interface Listener {
        void onCastStateChanged(Snapshot snapshot);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile Snapshot current = new Snapshot(Phase.IDLE, "", "");

    private CastState() {}

    public static Snapshot get() {
        return current;
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static void idle(String detail) {
        set(Phase.IDLE, "", detail);
    }

    public static void discovering(String detail) {
        set(Phase.DISCOVERING, "", detail);
    }

    public static void connecting(String host) {
        set(Phase.CONNECTING, host, "");
    }

    public static void casting(String host) {
        set(Phase.CASTING, host, "");
    }

    public static void stopping() {
        Snapshot now = current;
        set(Phase.STOPPING, now.target, "");
    }

    public static void fail(String detail) {
        set(Phase.ERROR, "", detail);
    }

    /**
     * The service has gone away: only an active phase is dropped to idle. A
     * terminal error set right before {@code stopSelf()} is what the user needs
     * to read, so it survives its own teardown.
     */
    static void clearIfActive() {
        Phase p = current.phase;
        if (p == Phase.CASTING || p == Phase.STOPPING || p == Phase.CONNECTING) idle("");
    }

    private static void set(Phase phase, String target, String detail) {
        String t = target == null ? "" : target;
        String d = detail == null ? "" : detail;
        Snapshot now = current;
        if (now.phase == phase && now.target.equals(t) && now.detail.equals(d)) return;
        final Snapshot next = new Snapshot(phase, t, d);
        current = next;
        // Posted rather than called: a listener may itself drive a transition,
        // and rendering mid-transition would show a state that is already stale.
        MAIN.post(() -> {
            for (Listener l : listeners) l.onCastStateChanged(next);
        });
    }
}
