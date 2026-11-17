package eg.sbs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

record Packet(int flags, int sequence, int centimeters, int waterRaw, long boot, long event, int ageMs) {
    static Packet parse(byte[] bytes) {
        if (bytes.length != 18 || bytes[0] != 1) throw new IllegalArgumentException("Unknown telemetry");
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.get();
        return new Packet(data.get() & 255, u16(data), u16(data), u16(data),
            Integer.toUnsignedLong(data.getInt()), Integer.toUnsignedLong(data.getInt()), u16(data));
    }
    private static int u16(ByteBuffer data) { return Short.toUnsignedInt(data.getShort()); }
    boolean wet() { return (flags & 2) != 0; }
    boolean recentSos() { return (flags & 8) != 0 && event != 0 && ageMs < 60000; }
}
