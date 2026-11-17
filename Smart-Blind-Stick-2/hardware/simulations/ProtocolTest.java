package eg.sbs;

final class ProtocolTest {
    public static void main(String[] args) {
        byte[] data = {1, 11, (byte)234, 0, 74, 0, (byte)190, 0,
            0x56, 0x34, 0x12, (byte)0xA9, 3, 0, 0, 0, (byte)210, 0};
        Packet packet = Packet.parse(data);
        assert packet.sequence() == 234;
        assert packet.centimeters() == 74;
        assert packet.waterRaw() == 190 && packet.wet();
        assert packet.boot() == 0xA9123456L && packet.event() == 3;
        assert packet.ageMs() == 210 && packet.recentSos();
        data[16] = -1;
        data[17] = -1;
        assert !Packet.parse(data).recentSos();
        boolean rejected = false;
        try { Packet.parse(new byte[17]); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;

        FallHeuristic fall = new FallHeuristic();
        for (int t = 0; t <= 2000; t += 20) assert !fall.sample(t, 1, 0, .02);
        assert !fall.sample(2100, .4, 2, .02);
        assert !fall.sample(2200, 3, 2, .02);
        for (int t = 2220; t < 2620; t += 20) assert !fall.sample(t, 1.4, 2, .02);
        for (int t = 2620; t < 4420; t += 20) assert !fall.sample(t, 1, .1, .02);
        assert fall.sample(4420, 1, .1, .02);
        assert !fall.sample(4440, 1, 0, .02);
    }
}
