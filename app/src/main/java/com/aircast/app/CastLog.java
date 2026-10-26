package com.aircast.app;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory rotating log shared by the Java side and the native sender.
 *
 * Enabled/disabled from Settings. When on, every line is timestamped and
 * appended to a fixed-capacity ring buffer; when off, calls are no-ops so a
 * running cast pays nothing for logging. The buffer is dumped to a file on
 * demand so a dropout can be exported after the fact.
 *
 * Native reaches here via JNI (CastLog_nativeAppend) so the AirPlay
 * handshake, the latency decisions and the audio stats land in the same
 * timeline as the Java-side events.
 */
public final class CastLog {
    public static final int LEVEL_ERROR = 0;
    public static final int LEVEL_WARN  = 1;
    public static final int LEVEL_INFO  = 2;
    public static final int LEVEL_DEBUG = 3;

    private static final int CAPACITY = 4000;
    private static final String[] TAGS = {"E", "W", "I", "D"};

    private static final ArrayDeque<String> buffer = new ArrayDeque<>(CAPACITY);
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private CastLog() {}

    /** Called from the UI thread when the toggle flips, to (re)seed the log. */
    public static synchronized void mark(String msg) {
        if (!enabled()) return;
        append(LEVEL_INFO, "CAST", msg);
    }

    public static void d(String tag, String msg) { log(LEVEL_DEBUG, tag, msg); }
    public static void i(String tag, String msg) { log(LEVEL_INFO,  tag, msg); }
    public static void w(String tag, String msg) { log(LEVEL_WARN,  tag, msg); }
    public static void e(String tag, String msg) { log(LEVEL_ERROR, tag, msg); }

    private static void log(int level, String tag, String msg) {
        if (!enabled() || level > level()) return;
        append(level, tag, msg);
    }

    /** JNI entry point from the native RaopLogSink (level matches RaopLogLevel). */
    public static void nativeAppend(int nativeLevel, String msg) {
        if (!enabled()) return;
        // RaopLogLevel only has Info(0) / Warn(1); map to our scale.
        int level = nativeLevel == 1 ? LEVEL_WARN : LEVEL_INFO;
        if (level > level()) return;
        append(level, "raop", msg);
    }

    private static synchronized void append(int level, String tag, String msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(TS.format(new Date())).append(' ')
          .append(TAGS[Math.max(0, Math.min(level, TAGS.length - 1))]).append('/')
          .append(tag).append(": ").append(msg);
        String line = sb.toString();
        if (buffer.size() >= CAPACITY) buffer.pollFirst();
        buffer.addLast(line);
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(buffer);
    }

    public static synchronized void clear() {
        buffer.clear();
    }

    /**
     * Writes the current buffer to a file in the app's cache dir and returns
     * it. Returns null if nothing was logged or the write failed.
     */
    public static synchronized File export(android.content.Context ctx) {
        if (buffer.isEmpty()) return null;
        try {
            File dir = ctx.getCacheDir();
            File f = new File(dir, "aircast-" + System.currentTimeMillis() + ".log");
            try (FileOutputStream out = new FileOutputStream(f)) {
                for (String line : buffer) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean enabled() {
        App a = App.get();
        return a != null && Settings.isLogEnabled(a.getApplicationContext());
    }

    private static int level() {
        App a = App.get();
        return a == null ? LEVEL_INFO : Settings.getLogLevel(a.getApplicationContext());
    }
}
