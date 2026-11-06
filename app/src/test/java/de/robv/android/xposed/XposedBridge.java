package de.robv.android.xposed;

/** JVM fixture for error paths; no hook installation is performed by these tests. */
public final class XposedBridge {
    public static void log(String message) { }
    public static void log(Throwable error) { }
}
