package jp.jb23.torqueplugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for the eight-byte USB TPMS protocol recovered from com.syt.tmps
 * (USB new / tpms-V1.1.64M). This class has no Android/USB dependencies.
 *
 * Frame: 55 AA 08 [wheel] [pressure] [temperature] [flags] [xor].
 * Pressure is truncated to integer kPa by the source APK (raw * 3.44).
 * Caller is responsible for USB transport, disconnects and freshness policy.
 */
public final class TpmsFrameDecoder {
    private static final int HEADER_0 = 0x55;
    private static final int HEADER_1 = 0xAA;
    private static final int MAX_FRAME_LENGTH = 64;
    private final byte[] pending = new byte[MAX_FRAME_LENGTH];
    private int pendingSize;

    public enum Wheel { FL, FR, RL, RR, SPARE }

    public static final class Reading {
        public final Wheel wheel;
        public final int sensorId;
        public final int pressureKpa;
        public final int temperatureC;
        public final boolean lowBattery;
        public final boolean airLeak;
        public final boolean noSignal;
        public final long receivedAtMillis;

        private Reading(Wheel wheel, int sensorId, int pressureKpa, int temperatureC,
                        int flags, long receivedAtMillis) {
            this.wheel = wheel;
            this.sensorId = sensorId;
            this.pressureKpa = pressureKpa;
            this.temperatureC = temperatureC;
            this.lowBattery = (flags & 0x10) != 0;
            this.airLeak = (flags & 0x08) != 0;
            this.noSignal = (flags & 0x20) != 0;
            this.receivedAtMillis = receivedAtMillis;
        }

        public double pressureBar() {
            return pressureKpa / 100.0;
        }
    }

    /** Return a reading for one complete, verified tire-state frame, or null. */
    public static Reading decode(byte[] frame, long receivedAtMillis) {
        if (frame == null || frame.length != 8 ||
                (frame[0] & 0xFF) != HEADER_0 ||
                (frame[1] & 0xFF) != HEADER_1 ||
                (frame[2] & 0xFF) != 8) {
            return null;
        }
        int xor = 0;
        for (int i = 0; i < frame.length - 1; i++) {
            xor ^= frame[i] & 0xFF;
        }
        if (xor != (frame[7] & 0xFF)) return null;

        int sensorId = frame[3] & 0xFF;
        Wheel wheel;
        switch (sensorId) {
            case 0x00: wheel = Wheel.FL; break;
            case 0x01: wheel = Wheel.FR; break;
            case 0x10: wheel = Wheel.RL; break;
            case 0x11: wheel = Wheel.RR; break;
            case 0x05: wheel = Wheel.SPARE; break;
            default: return null;
        }
        int pressureKpa = (int) ((frame[4] & 0xFF) * 3.44d);
        int temperatureC = (frame[5] & 0xFF) - 50;
        return new Reading(wheel, sensorId, pressureKpa, temperatureC,
                frame[6] & 0xFF, receivedAtMillis);
    }

    /** Accept arbitrarily chunked USB serial bytes; returns only verified tire reports. */
    public synchronized List<Reading> feed(byte[] chunk, int length, long receivedAtMillis) {
        if (chunk == null || length < 0 || length > chunk.length)
            throw new IllegalArgumentException("Invalid USB input");
        if (length == 0) return Collections.emptyList();
        List<Reading> reports = new ArrayList<>();
        for (int offset = 0; offset < length; offset++) {
            if (pendingSize >= pending.length) discardOne();
            pending[pendingSize++] = chunk[offset];
            scan(reports, receivedAtMillis);
        }
        return reports;
    }

    public synchronized void reset() {
        pendingSize = 0;
    }

    private void discardOne() {
        pendingSize--;
        if (pendingSize > 0) System.arraycopy(pending, 1, pending, 0, pendingSize);
    }

    private void scan(List<Reading> reports, long receivedAtMillis) {
        while (pendingSize >= 3) {
            if ((pending[0] & 0xFF) != HEADER_0 ||
                    (pending[1] & 0xFF) != HEADER_1) {
                discardOne();
                continue;
            }
            int frameLength = pending[2] & 0xFF;
            if (frameLength < 4 || frameLength > MAX_FRAME_LENGTH) {
                discardOne();
                continue;
            }
            if (pendingSize < frameLength) return;
            byte[] frame = new byte[frameLength];
            System.arraycopy(pending, 0, frame, 0, frameLength);
            Reading report = decode(frame, receivedAtMillis);
            if (report != null) {
                reports.add(report);
                pendingSize -= frameLength;
                if (pendingSize > 0)
                    System.arraycopy(pending, frameLength, pending, 0, pendingSize);
            } else {
                // Unsupported command/bad checksum: search for the next sync header.
                discardOne();
            }
        }
    }
}
