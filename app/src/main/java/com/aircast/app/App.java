package com.aircast.app;

import android.app.Application;
import android.content.Context;

/**
 * Holds the app-level context so background code (the log buffer, the native
 * JNI callbacks) can read Settings without a live Activity or Service.
 */
public class App extends Application {
    /**
     * The app's display name. Every user-facing string reads it from here, so a
     * rename is a one-line change (the applicationId / package path are the
     * other two spots, in app/build.gradle.kts and the source tree).
     */
    public static final String APP_NAME = "AirCast";

    private static volatile App instance;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static App get() {
        return instance;
    }
}
