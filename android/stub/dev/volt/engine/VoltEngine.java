package dev.volt.engine;

public final class VoltEngine {
    public static java.util.function.Function<byte[], byte[]> sink;
    public static void load(String lib) { System.loadLibrary(lib); }
    static byte[] host(byte[] req) { return sink.apply(req); }
    public static native long nOpen(String dir, String main, String harness);
    public static native byte[] nCall(long h, String fn, byte[] arg);
    public static native void nClose(long h);
}
