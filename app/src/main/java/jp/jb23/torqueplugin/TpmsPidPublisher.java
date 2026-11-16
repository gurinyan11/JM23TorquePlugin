package jp.jb23.torqueplugin;

import android.os.RemoteException;
import org.prowl.torque.remote.ITorqueService;

/**
 * Publishes TPMS observations separately from the existing ECU 0x2100 loop.
 * No dummy pressure or temperature is published before actual sensor data.
 * Call only from a future USB TPMS transport once live frames are verified.
 */
public final class TpmsPidPublisher {
    private TpmsPidPublisher() { }

    public static boolean publish(ITorqueService torque, TpmsFrameDecoder.Reading reading)
            throws RemoteException {
        if (torque == null || reading == null || reading.noSignal ||
                reading.wheel == TpmsFrameDecoder.Wheel.SPARE) {
            return false;
        }
        final String side = reading.wheel.name();
        boolean pressureOk = torque.setPIDInformation(
                "JM23 TPMS " + side + " Pressure", "TPMS " + side + " P",
                "bar", 10f, 0f, (float) reading.pressureBar(), null);
        boolean temperatureOk = torque.setPIDInformation(
                "JM23 TPMS " + side + " Temperature", "TPMS " + side + " T",
                "°C", 125f, -50f, (float) reading.temperatureC, null);
        // LowBattery, AirLeak and NoSignal are preserved in Reading.
        // They will be displayed only after the status UI is verified.
        return pressureOk && temperatureOk;
    }
}
