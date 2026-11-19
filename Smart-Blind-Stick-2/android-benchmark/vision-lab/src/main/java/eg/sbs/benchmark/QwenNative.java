package eg.sbs.benchmark;

import java.io.IOException;

final class QwenNative {
    static { System.loadLibrary("sbs_qwen"); }
    static native long begin();
    static native void cancel();
    static native byte[] describe(long request, String model, String projector,
                                  byte[] rgb, int width, int height, String prompt) throws IOException;
    private QwenNative() {}
}
