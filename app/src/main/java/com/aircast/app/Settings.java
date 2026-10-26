package com.aircast.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One place to read every user-facing knob, so the defaults and the clamps
 * stay in sync between the main screen, the settings screen and the service.
 *
 * Stored in the "cast" prefs the app already used; every accessor re-clamps
 * on read, so an out-of-range value (an older build, a hand-edited pref) can
 * never reach the native layer.
 */
public final class Settings {
    private static final String FILE = "cast";

    // --- keys ----------------------------------------------------------
    private static final String K_VOLUME = "volume";            // int, 0..100
    private static final String K_LATENCY = "latency_sec";      // float, seconds
    private static final String K_VOL_STEP = "vol_step";        // int, percent
    private static final String K_LOG_ENABLED = "log_enabled";  // boolean
    private static final String K_LOG_LEVEL = "log_level";      // "error".."debug"
    private static final String K_SKIP_DISCLOSURE = "skip_disclosure"; // boolean

    // --- ranges (mirrored in the settings UI) -------------------------
    public static final float LATENCY_MIN = 0.25f;
    public static final float LATENCY_MAX = 5.0f;
    public static final float LATENCY_DEFAULT = 2.0f;
    public static final int VOL_STEP_MIN = 1;
    public static final int VOL_STEP_MAX = 20;
    public static final int VOL_STEP_DEFAULT = 2;

    private Settings() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Receiver volume in percent, 0..100. Task spec: starts at 10 %. */
    public static int getVolume(Context ctx) {
        return clamp(prefs(ctx).getInt(K_VOLUME, 10), 0, 100);
    }

    public static void setVolume(Context ctx, int v) {
        prefs(ctx).edit().putInt(K_VOLUME, clamp(v, 0, 100)).apply();
    }

    /** End-to-end AirPlay latency in seconds, 0.25..5, default 2. */
    public static float getLatency(Context ctx) {
        return clamp(prefs(ctx).getFloat(K_LATENCY, LATENCY_DEFAULT), LATENCY_MIN, LATENCY_MAX);
    }

    /** Same value rounded to whole milliseconds for the JNI call. */
    public static int getLatencyMs(Context ctx) {
        return Math.round(getLatency(ctx) * 1000f);
    }

    public static void setLatency(Context ctx, float seconds) {
        prefs(ctx).edit().putFloat(K_LATENCY, clamp(seconds, LATENCY_MIN, LATENCY_MAX)).apply();
    }

    /** How far one volume key press / notification tick moves the bar. */
    public static int getVolStep(Context ctx) {
        return clamp(prefs(ctx).getInt(K_VOL_STEP, VOL_STEP_DEFAULT), VOL_STEP_MIN, VOL_STEP_MAX);
    }

    public static void setVolStep(Context ctx, int step) {
        prefs(ctx).edit().putInt(K_VOL_STEP, clamp(step, VOL_STEP_MIN, VOL_STEP_MAX)).apply();
    }

    public static boolean isLogEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_LOG_ENABLED, false);
    }

    public static void setLogEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_LOG_ENABLED, on).apply();
    }

    /** 0 = errors only, 1 = warn, 2 = info, 3 = debug chatty. */
    public static int getLogLevel(Context ctx) {
        String l = prefs(ctx).getString(K_LOG_LEVEL, "info");
        switch (l) {
            case "error": return 0;
            case "warn":  return 1;
            case "debug": return 3;
            default:      return 2;
        }
    }

    public static void setLogLevel(Context ctx, String level) {
        prefs(ctx).edit().putString(K_LOG_LEVEL, level).apply();
    }

    /** True once the user ticked "Don't show again" on the start-casting note. */
    public static boolean isDisclosureSkipped(Context ctx) {
        return prefs(ctx).getBoolean(K_SKIP_DISCLOSURE, false);
    }

    public static void setDisclosureSkipped(Context ctx, boolean skip) {
        prefs(ctx).edit().putBoolean(K_SKIP_DISCLOSURE, skip).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
