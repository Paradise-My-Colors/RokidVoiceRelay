package io.github.up9cloud.td;

/**
 * Minimal JNI declarations for TDLib's JSON C interface.
 * Native libtdjson.so is supplied by the pinned Android build in CI.
 */
public final class JsonClient {
    static {
        System.loadLibrary("tdjson");
    }

    private JsonClient() {}

    public static native int td_create_client_id();
    public static native void td_send(int client_id, String request);
    public static native String td_receive(double timeout);
    public static native String td_execute(String request);
}
