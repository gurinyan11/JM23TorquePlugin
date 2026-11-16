package jp.jb23.torqueplugin;

import java.util.List;

public final class TpmsFrameDecoderSelfTest {
    private static byte[] frame(int id, int pressure, int temp, int flags) {
        byte[] raw = {(byte) 0x55, (byte) 0xAA, 8, (byte) id,
                (byte) pressure, (byte) temp, (byte) flags, 0};
        int xor = 0;
        for (int i = 0; i < 7; ++i) xor ^= raw[i] & 0xff;
        raw[7] = (byte) xor;
        return raw;
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        byte[] fl = frame(0, 52, 75, 0x10);
        TpmsFrameDecoder.Reading sample = TpmsFrameDecoder.decode(fl, 1234);
        check(sample != null, "frame decode");
        check(sample.wheel == TpmsFrameDecoder.Wheel.FL, "FL position");
        check(sample.pressureKpa == 178, "kPa conversion; APK truncates to int");
        check(sample.temperatureC == 25, "temperature conversion");
        check(sample.lowBattery && !sample.noSignal && !sample.airLeak, "status");
        check(sample.receivedAtMillis == 1234, "timestamp");
        check(TpmsFrameDecoder.decode(frame(1, 50, 65, 0x28), 0).airLeak, "leak bit");
        check(TpmsFrameDecoder.decode(frame(1, 50, 65, 0x28), 0).noSignal, "no-signal bit");
        check(TpmsFrameDecoder.decode(frame(0x10, 50, 70, 0), 0).wheel == TpmsFrameDecoder.Wheel.RL, "RL");
        check(TpmsFrameDecoder.decode(frame(0x11, 50, 70, 0), 0).wheel == TpmsFrameDecoder.Wheel.RR, "RR");
        check(TpmsFrameDecoder.decode(frame(5, 50, 70, 0), 0).wheel == TpmsFrameDecoder.Wheel.SPARE, "spare");
        check(TpmsFrameDecoder.decode(frame(8, 50, 70, 0), 0) == null, "unknown ID rejected");
        byte[] wrong = fl.clone(); wrong[7] ^= 1;
        check(TpmsFrameDecoder.decode(wrong, 0) == null, "checksum reject");
        TpmsFrameDecoder decoder = new TpmsFrameDecoder();
        check(decoder.feed(fl, 3, 5).isEmpty(), "partial frame waits");
        byte[] rest = new byte[5]; System.arraycopy(fl, 3, rest, 0, 5);
        check(decoder.feed(rest, 5, 6).size() == 1, "split-frame reassembly");
        byte[] combined = new byte[18];
        combined[0] = (byte) 0xff; combined[1] = 0x01;
        System.arraycopy(frame(1, 54, 70, 0), 0, combined, 2, 8);
        System.arraycopy(frame(0x10, 56, 72, 0), 0, combined, 10, 8);
        List<TpmsFrameDecoder.Reading> readings = decoder.feed(combined, combined.length, 7);
        check(readings.size() == 2, "resync + consecutive frames");
        check(readings.get(0).wheel == TpmsFrameDecoder.Wheel.FR, "FR");
        check(readings.get(1).wheel == TpmsFrameDecoder.Wheel.RL, "RL");
        decoder.reset();
        System.out.println("TPMS decoder self-test: 15 checks passed");
    }
}
