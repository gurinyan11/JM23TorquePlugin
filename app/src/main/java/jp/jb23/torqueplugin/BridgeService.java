package jp.jb23.torqueplugin;

import android.app.*;
import android.content.*;
import android.os.*;
import org.prowl.torque.remote.ITorqueService;
import java.util.*;
import java.util.concurrent.*;

public class BridgeService extends Service {
    private static final String CHANNEL = "jb23_bridge";
    private static final String PLUGIN = "JM23 Torque Bridge";
    private static final String PID_NAME = "JM23 RPM";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile ITorqueService torque;
    private volatile boolean bound;
    private volatile long bindStartedAt;
    private volatile int consecutiveFailures;
    private long ecuRxCount = 0L;
    private long lastEcuRxTime = 0L;
    private long lastRxSaveTime = 0L;
    private SharedPreferences rxPrefs;
    private SharedPreferences tripPrefs;
    private double tripDistanceKm = 0.0;
    private long lastTripUpdateMs = 0L;

    // JM23 fuel management
    private SharedPreferences fuelPrefs;
    private double fuelOpenMinutes = 0.0;
    private double fuelFullTankOpenMinutes = 0.0;
    private double fuelOperationalFullTankOpenMinutes = 0.0;
    private double fuelLitersPerOpenMinute = 0.0;
    private double fuelTankCapacityLiters = 40.0;
    private int fuelFullTankCount = 0;
    private boolean fuelFullTankBaselineSet = false;
    private double fuelCalibrationTotalOpenMinutes = 0.0;
    private double fuelCalibrationTotalLiters = 0.0;
    private double fuelCalibrationLastLiters = Double.NaN;
    private double fuelLastEngineOpenMinutes = 0.0;
    private double fuelPreviousStopOpenMinutes = 0.0;
    private boolean fuelEngineSeen = false;
    private long lastFuelUpdateMs = 0L;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            torque = ITorqueService.Stub.asInterface(service);
            bound = true;
            bindStartedAt = 0L;
            consecutiveFailures = 0;
            debug("Torque service connected: " + name.flattenToShortString());
            try {
                probeTorquePidRegistry(service);
                debug("PID-REGISTRY BEFORE");
                boolean prePublished = torque.setPIDInformation(
                        PID_NAME,
                        "JM23 RPM",
                        "rpm",
                        8000f,
                        0f,
                        0f,
                        null
                );
                debug("PRE-PUBLISH JM23 RPM=" + prePublished);
                probeTorquePidRegistry(service);
            } catch (Throwable e) {
                debug("PRE-PUBLISH failed: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            debug("Torque service disconnected");
            reconnectTorque("service disconnected");
        }

        @Override public void onBindingDied(ComponentName name) {
            debug("Torque binding died");
            reconnectTorque("binding died");
        }

        @Override public void onNullBinding(ComponentName name) {
            debug("Torque null binding");
            reconnectTorque("null binding");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        rxPrefs = getSharedPreferences("jm23_rx_test", MODE_PRIVATE);
        tripPrefs = getSharedPreferences("jm23_trip", MODE_PRIVATE);
        tripDistanceKm = Double.longBitsToDouble(tripPrefs.getLong("trip_km_bits", Double.doubleToRawLongBits(0.0)));
        fuelPrefs = getSharedPreferences("jm23_fuel", MODE_PRIVATE);
        fuelOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        fuelFullTankOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("full_tank_open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        fuelOperationalFullTankOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("operational_full_tank_open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        fuelLitersPerOpenMinute = Double.longBitsToDouble(fuelPrefs.getLong("liters_per_open_minute_bits", Double.doubleToRawLongBits(0.0)));
        fuelTankCapacityLiters = Double.longBitsToDouble(fuelPrefs.getLong("tank_capacity_liters_bits", Double.doubleToRawLongBits(40.0)));
        fuelFullTankCount = fuelPrefs.getInt("full_tank_count", 0);
        fuelFullTankBaselineSet = fuelPrefs.getBoolean("full_tank_baseline_set", false);
        fuelCalibrationTotalOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("calibration_total_open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        fuelCalibrationTotalLiters = Double.longBitsToDouble(fuelPrefs.getLong("calibration_total_liters_bits", Double.doubleToRawLongBits(0.0)));
        fuelCalibrationLastLiters = Double.longBitsToDouble(fuelPrefs.getLong("calibration_last_liters_bits", Double.doubleToRawLongBits(Double.NaN)));
        fuelLastEngineOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("last_engine_open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        fuelPreviousStopOpenMinutes = Double.longBitsToDouble(fuelPrefs.getLong("previous_stop_open_minutes_bits", Double.doubleToRawLongBits(0.0)));
        ecuRxCount = rxPrefs.getLong("rx_count", 0L);
        lastEcuRxTime = rxPrefs.getLong("last_rx_time", 0L);
        createChannel();
        debug("BridgeService created");
        startForeground(23, notification("Waiting for Torque"));
        bindTorque();
        worker.scheduleWithFixedDelay(this::pollOnce, 500, 500, TimeUnit.MILLISECONDS);
    }

    private synchronized void startFuelFullTankBaseline() {
        fuelFullTankOpenMinutes = fuelOpenMinutes;
        fuelFullTankCount = 0;
        fuelFullTankBaselineSet = true;
        fuelCalibrationTotalOpenMinutes = 0.0;
        fuelCalibrationTotalLiters = 0.0;
        fuelCalibrationLastLiters = Double.NaN;

        fuelPrefs.edit()
                .putLong("open_minutes_bits", Double.doubleToRawLongBits(fuelOpenMinutes))
                .putLong("full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelFullTankOpenMinutes))
            .putLong("operational_full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelOperationalFullTankOpenMinutes))
                .putInt("full_tank_count", fuelFullTankCount)
                .putBoolean("full_tank_baseline_set", fuelFullTankBaselineSet)
                .putLong("calibration_total_open_minutes_bits", Double.doubleToRawLongBits(fuelCalibrationTotalOpenMinutes))
                .putLong("calibration_total_liters_bits", Double.doubleToRawLongBits(fuelCalibrationTotalLiters))
                .putLong("calibration_last_liters_bits", Double.doubleToRawLongBits(fuelCalibrationLastLiters))
                .apply();
    }

    private synchronized boolean recordFuelCalibrationFill(double liters) {
        if (!fuelFullTankBaselineSet || fuelFullTankCount >= 5) return false;
        if (Double.isNaN(liters) || Double.isInfinite(liters) || liters <= 0.0) return false;

        double fillBoundaryOpenMinutes = fuelPreviousStopOpenMinutes;
        double openMinutes = fillBoundaryOpenMinutes - fuelFullTankOpenMinutes;
        if (openMinutes <= 0.0) return false;

        fuelCalibrationTotalOpenMinutes += openMinutes;
        fuelCalibrationTotalLiters += liters;
        fuelCalibrationLastLiters = liters;
        fuelFullTankCount++;
        fuelFullTankOpenMinutes = fillBoundaryOpenMinutes;

        if (fuelFullTankCount >= 5 && fuelCalibrationTotalOpenMinutes > 0.0) {
            fuelLitersPerOpenMinute = fuelCalibrationTotalLiters / fuelCalibrationTotalOpenMinutes;
            fuelOperationalFullTankOpenMinutes = fillBoundaryOpenMinutes;
        }

        fuelPrefs.edit()
                .putLong("open_minutes_bits", Double.doubleToRawLongBits(fuelOpenMinutes))
                .putLong("full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelFullTankOpenMinutes))
            .putLong("operational_full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelOperationalFullTankOpenMinutes))
                .putInt("full_tank_count", fuelFullTankCount)
                .putLong("calibration_total_open_minutes_bits", Double.doubleToRawLongBits(fuelCalibrationTotalOpenMinutes))
                .putLong("calibration_total_liters_bits", Double.doubleToRawLongBits(fuelCalibrationTotalLiters))
                .putLong("calibration_last_liters_bits", Double.doubleToRawLongBits(fuelCalibrationLastLiters))
                .putLong("liters_per_open_minute_bits", Double.doubleToRawLongBits(fuelLitersPerOpenMinute))
                .apply();

        return true;
    }


    private synchronized boolean recordFuelNormalFullTank() {
        if (fuelFullTankCount < 5 || fuelLitersPerOpenMinute <= 0.0) return false;

        fuelOperationalFullTankOpenMinutes = fuelOpenMinutes;

        fuelPrefs.edit()
                .putLong("open_minutes_bits", Double.doubleToRawLongBits(fuelOpenMinutes))
                .putLong("operational_full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelOperationalFullTankOpenMinutes))
                .apply();

        return true;
    }

    private synchronized void reconnectTorque(String reason) {
        debug("Torque reconnect: " + reason);
        torque = null;
        consecutiveFailures = 0;

        if (bound) {
            try { unbindService(connection); } catch (Throwable ignored) {}
        }

        bound = false;
        bindStartedAt = 0L;

        try {
            worker.schedule(() -> {
                if (torque == null && !bound) bindTorque();
            }, 500, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        debug("BridgeService start requested");
        if (intent != null && "jp.jb23.torqueplugin.ACTION_FUEL_BASELINE".equals(intent.getAction())) {
            startFuelFullTankBaseline();
            debug("Fuel full tank baseline set");
        } else if (intent != null && "jp.jb23.torqueplugin.ACTION_FUEL_FILL".equals(intent.getAction())) {
            double liters = intent.getDoubleExtra("liters", Double.NaN);
            boolean recorded = recordFuelCalibrationFill(liters);
            debug("Fuel calibration fill: liters=" + liters + " recorded=" + recorded);
        }
        else if (intent != null && "jp.jb23.torqueplugin.ACTION_FUEL_FULL_TANK".equals(intent.getAction())) {
            boolean recorded = recordFuelNormalFullTank();
            debug("Fuel normal full tank: recorded=" + recorded);
        }
        else if (intent != null && "jp.jb23.torqueplugin.ACTION_FUEL_TANK_CAPACITY".equals(intent.getAction())) {
            double capacity = intent.getDoubleExtra("capacity", Double.NaN);
            if (capacity >= 39.0 && capacity <= 41.0) {
                fuelTankCapacityLiters = capacity;
                fuelPrefs.edit()
                        .putLong("tank_capacity_liters_bits", Double.doubleToRawLongBits(fuelTankCapacityLiters))
                        .apply();
                debug("Fuel tank capacity: " + capacity + " L");
            }
        }
        if (torque == null && !bound) bindTorque();
        return START_STICKY;

    }

    private void bindTorque() {
        try {
            Intent i = new Intent();
            i.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService");
            debug("Binding Torque service...");
            bindStartedAt = SystemClock.elapsedRealtime();
            bound = bindService(i, connection, Context.BIND_AUTO_CREATE);
            debug("bindService returned: " + bound);
            if (!bound) updateNotification("Torque service not found");
        } catch (Throwable t) {
            debug("Torque bind failed: " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
            updateNotification("Torque bind failed: " + t.getClass().getSimpleName());
        }
    }

    private void pollOnce() {
        long now = SystemClock.elapsedRealtime();
        ITorqueService t = torque;

        if (t == null) {
            if (!bound) {
                bindTorque();
            } else if (bindStartedAt > 0L && now - bindStartedAt > 3000L) {
                reconnectTorque("bind timeout");
            }
            return;
        }
        try {
            // Mode 21 is explicitly permitted by current Torque API.
            debug("Sending 2100 (Torque-managed header)");
            String[] raw = t.sendCommandGetResponse(null, "2100");
            if (raw == null || raw.length == 0) {
                consecutiveFailures++;
                if (consecutiveFailures == 12 && fuelEngineSeen) {
                    fuelPreviousStopOpenMinutes = fuelLastEngineOpenMinutes;
                    fuelEngineSeen = false;
                    fuelPrefs.edit()
                            .putLong("previous_stop_open_minutes_bits", Double.doubleToRawLongBits(fuelPreviousStopOpenMinutes))
                            .apply();
                    debug("Fuel engine stop boundary=" + fuelPreviousStopOpenMinutes);
                }
                if (consecutiveFailures >= 12) {
                    reconnectTorque("repeated no response");
                    return;
                }
                debug("2100: no response");
                updateNotification("2100: no response");
                return;
            }

            consecutiveFailures = 0;
            debug("RAW[" + raw.length + "]: " + joinRaw(raw));

            // Recombine ISO-TP locally.  This avoids Torque's AIDL helper call and
            // keeps this ARM64 build independent of the Android aidl compiler.
            Double rpm = extractRpm(raw);
Double coolant = extractCoolantTemp(raw);
Double intake = extractIntakeTemp(raw);
Double map = extractMapPressure(raw);
        Double barPressure = extractBarPressure(raw);
        Double relPressure = (map != null && barPressure != null) ? (map - barPressure) / 100.0 : null;
Double throttle = extractThrottle(raw);
            Double targetThrottle = extractTargetThrottle(raw);
            Double iscIacControl = extractIscIacControl(raw);
            Double targetIdleSpeed = extractTargetIdleSpeed(raw);
            Double boostPressure = extractBoostPressure(raw);
            Double mainThrottleSensor = extractMainThrottleSensor(raw);
            Double assistantThrottleSensor = extractAssistantThrottleSensor(raw);
            Double mainAcceleratorSensor = extractMainAcceleratorSensor(raw);
        Double ignition = extractIgnitionAdvance(raw);
        Double shortTrim = extractShortTrim(raw);
        Double longTrim = extractLongTrim(raw);
        Double afSensor1 = extractAfSensor1(raw);
        Double afSensor2 = extractAfSensor2(raw);
        Double injectionTime = extractInjectionTime(raw);
        Double batteryVoltage = extractBatteryVoltage(raw);
        Double speed = extractSpeed(raw);
        long fuelNowMs = SystemClock.elapsedRealtime();
        if (rpm != null && injectionTime != null && rpm >= 0 && rpm < 10000 && injectionTime >= 0) {
            double fuelRate = injectionTime * rpm / 2000.0;
            if (lastFuelUpdateMs > 0L) {
                long fuelDeltaMs = fuelNowMs - lastFuelUpdateMs;
                if (fuelDeltaMs > 0L && fuelDeltaMs <= 10000L) {
                    fuelOpenMinutes += fuelRate * (fuelDeltaMs / 3600000.0);
                }
            }
        fuelLastEngineOpenMinutes = fuelOpenMinutes;
        fuelEngineSeen = true;
            lastFuelUpdateMs = fuelNowMs;
        } else {
            lastFuelUpdateMs = 0L;
        }
        long tripNowMs = SystemClock.elapsedRealtime();
        if (speed != null && speed >= 0 && speed <= 255) {
            if (lastTripUpdateMs > 0L) {
                long deltaMs = tripNowMs - lastTripUpdateMs;
                if (deltaMs > 0L && deltaMs <= 10000L) {
                    tripDistanceKm += speed * (deltaMs / 3600000.0);
                }
            }
            lastTripUpdateMs = tripNowMs;
        Intent widgetTrip = new Intent("jp.jm23.counterwidget.TRIP_UPDATE");
        widgetTrip.setClassName(
            "jp.jm23.counterwidget",
            "jp.jm23.counterwidget.PluginTripReceiver");
        widgetTrip.putExtra("trip_km", tripDistanceKm);
        sendBroadcast(widgetTrip);
        } else {
            lastTripUpdateMs = 0L;
        }
            Double engineLoad = extractEngineLoad(raw);
            Double evapPurge = extractEvapPurge(raw);
            Double wastegateValve = extractWastegateValve(raw);
            Double vvtInDiff = extractVvtInDiff(raw);
            Integer acSwitch = extractAcSwitch(raw);
            Integer acCompressorClutch = extractAcCompressorClutch(raw);
            Integer acFan = extractAcFan(raw);
            Double acceleratorPosition = extractAcceleratorPosition(raw);
            Double refrigerantPressure = extractRefrigerantPressure(raw);
            Double consumptionCurrent = extractConsumptionCurrent(raw);
            Double generatorControlDuty = extractGeneratorControlDuty(raw);
            Double generatorFieldMonitorDuty = extractGeneratorFieldMonitorDuty(raw);
            Double batteryCurrent = extractBatteryCurrent(raw);
            Double generatorRegulatorVoltage = extractGeneratorRegulatorVoltage(raw);
            Double fuelLevel = extractFuelLevel(raw);
        Integer control1 = extractControl1(raw);
        Double totalTrim = (shortTrim != null && longTrim != null) ? shortTrim + longTrim : null;
        Double injectorRate = (rpm != null && injectionTime != null && rpm >= 0 && rpm < 10000 && injectionTime >= 0)
                ? (injectionTime * rpm / 2000.0) : null;
        Double fuelUsedLiters = (fuelLitersPerOpenMinute > 0.0)
                ? Math.max(0.0, (fuelOpenMinutes - fuelOperationalFullTankOpenMinutes) * fuelLitersPerOpenMinute)
                : null;

        Double fuelRemainingLiters = (fuelUsedLiters != null)
                ? Math.max(0.0, Math.min(fuelTankCapacityLiters, fuelTankCapacityLiters - fuelUsedLiters))
                : null;

        Double fuelRemainingPercent = (fuelRemainingLiters != null && fuelTankCapacityLiters > 0.0)
                ? (fuelRemainingLiters / fuelTankCapacityLiters * 100.0)
                : null;

        Double injector = (rpm != null && injectionTime != null && speed != null && speed > 0) ? (rpm * injectionTime / (20.0 * speed)) : null;
            if (rpm != null && rpm >= 0 && rpm < 10000) {
                ecuRxCount++;
                lastEcuRxTime = System.currentTimeMillis();

                if (lastEcuRxTime - lastRxSaveTime >= 5000L) {
                    rxPrefs.edit()
                            .putLong("rx_count", ecuRxCount)
                            .putLong("last_rx_time", lastEcuRxTime)
                            .apply();
            tripPrefs.edit()
                    .putLong("trip_km_bits", Double.doubleToRawLongBits(tripDistanceKm))
                    .apply();
                fuelPrefs.edit()
                        .putLong("open_minutes_bits", Double.doubleToRawLongBits(fuelOpenMinutes))
                    .putLong("last_engine_open_minutes_bits", Double.doubleToRawLongBits(fuelLastEngineOpenMinutes))
                        .putLong("full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelFullTankOpenMinutes))
            .putLong("operational_full_tank_open_minutes_bits", Double.doubleToRawLongBits(fuelOperationalFullTankOpenMinutes))
                    .putLong("fuel_used_liters_bits", Double.doubleToRawLongBits(fuelUsedLiters != null ? fuelUsedLiters : Double.NaN))
                    .putLong("fuel_remaining_liters_bits", Double.doubleToRawLongBits(fuelRemainingLiters != null ? fuelRemainingLiters : Double.NaN))
                    .putLong("fuel_remaining_percent_bits", Double.doubleToRawLongBits(fuelRemainingPercent != null ? fuelRemainingPercent : Double.NaN))
                        .putLong("liters_per_open_minute_bits", Double.doubleToRawLongBits(fuelLitersPerOpenMinute))
                        .putLong("tank_capacity_liters_bits", Double.doubleToRawLongBits(fuelTankCapacityLiters))
                    .putInt("full_tank_count", fuelFullTankCount)
                    .putBoolean("full_tank_baseline_set", fuelFullTankBaselineSet)
                    .putLong("calibration_total_open_minutes_bits", Double.doubleToRawLongBits(fuelCalibrationTotalOpenMinutes))
                    .putLong("calibration_total_liters_bits", Double.doubleToRawLongBits(fuelCalibrationTotalLiters))
                    .putLong("calibration_last_liters_bits", Double.doubleToRawLongBits(fuelCalibrationLastLiters))
                        .apply();
                    debug("ECU-RX count=" + ecuRxCount
                            + " last=" + lastEcuRxTime);
                    lastRxSaveTime = lastEcuRxTime;
                }
        boolean published = t.setPIDInformation(
           PID_NAME,
           "JM23 RPM",
           "rpm",
           8000f,
           0f,
           rpm.floatValue(),
           null
     );

     updateNotification(String.format(
           Locale.US,
           "RPM %.0f publish=%s",
           rpm,
           published
     ));
	debug(String.format(Locale.US, "RPM %.0f publish=%s", rpm, published));
            } else {
                debug("2100 received; RPM bytes not found");
                updateNotification("2100 received; RPM bytes not found");
            }

        if (coolant != null && coolant >= -40 && coolant <= 150) {
            boolean coolantPublished = t.setPIDInformation(
                    "JM23 Coolant Temp",
                    "JM23 Coolant",
                    "°C",
                    150f,
                    -40f,
                    coolant.floatValue(),
                    null
            );
            debug(String.format(Locale.US,
                    "Coolant %.0f publish=%s",
                    coolant,
                    coolantPublished));
        }  
if (intake != null && intake >= -40 && intake <= 100) {
    boolean intakePublished = t.setPIDInformation(
        "JM23 Intake Temp",
        "JM23 Intake",
        "°C",
        100f,
        -40f,
        intake.floatValue(),
        null
    );

    debug(String.format(
        Locale.US,
        "Intake %.0f publish=%s",
        intake,
        intakePublished
    ));
}     
if (map != null && map >= 20 && map <= 250) {
    t.setPIDInformation(
        "JM23 MAP",
        "JM23 MAP",
        "kPa",
        250f,
        20f,
        map.floatValue(),
        null
    );
}
        if (throttle != null && throttle >= 0 && throttle <= 100) {
            t.setPIDInformation(
                "JM23 Throttle",
                "JM23 Throttle",
                "%",
                100f,
                0f,
                throttle.floatValue(),
                null
            );
        }
        if (ignition != null && ignition >= -64 && ignition <= 64) {
            t.setPIDInformation(
                "JM23 Ignition Advance",
                "JM23 Ign Adv",
                "°",
                64f,
                -64f,
                ignition.floatValue(),
                null
            );
        }
        if (shortTrim != null && shortTrim >= -100 && shortTrim <= 100) {
            t.setPIDInformation(
                "JM23 Short Fuel Trim",
                "JM23 STFT",
                "%",
                100f,
                -100f,
                shortTrim.floatValue(),
                null
            );
        }
        if (longTrim != null && longTrim >= -100 && longTrim <= 100) {
            t.setPIDInformation(
                "JM23 Long Fuel Trim",
                "JM23 LTFT",
                "%",
                100f,
                -100f,
                longTrim.floatValue(),
                null
            );
        }
        if (totalTrim != null && totalTrim >= -200 && totalTrim <= 200) {
            t.setPIDInformation(
                "JM23 Total Fuel Trim",
                "JM23 Total Trim",
                "%",
                200f,
                -200f,
                totalTrim.floatValue(),
                null
            );
        }
        if (afSensor1 != null && afSensor1 >= -128 && afSensor1 <= 128) {
            t.setPIDInformation(
                "JM23 A/F Sensor 1",
                "JM23 A/F 1",
                "mA",
                128f,
                -128f,
                afSensor1.floatValue(),
                null
            );
        }
        if (afSensor2 != null && afSensor2 >= 0 && afSensor2 <= 5) {
            t.setPIDInformation(
                "JM23 A/F Sensor 2",
                "JM23 A/F 2",
                "V",
                5f,
                0f,
                afSensor2.floatValue(),
                null
            );
        }
        if (false && mainAcceleratorSensor != null && mainAcceleratorSensor >= 0 && mainAcceleratorSensor <= 5) {
            t.setPIDInformation(
                "JM23 Main Accelerator Sensor",
                "JM23 Main Accelerator Sensor",
                "V",
                5f,
                0f,
                mainAcceleratorSensor.floatValue(),
                null
            );
        }

        if (false && assistantThrottleSensor != null && assistantThrottleSensor >= 0 && assistantThrottleSensor <= 5) {
            t.setPIDInformation(
                "JM23 Assistant Throttle Sensor",
                "JM23 Assistant Throttle Sensor",
                "V",
                5f,
                0f,
                assistantThrottleSensor.floatValue(),
                null
            );
        }

        if (mainThrottleSensor != null && mainThrottleSensor >= 0 && mainThrottleSensor <= 5) {
            t.setPIDInformation(
                "JM23 Main Throttle Sensor",
                "JM23 Main Throttle Sensor",
                "V",
                5f,
                0f,
                mainThrottleSensor.floatValue(),
                null
            );
        }

        if (false && boostPressure != null && boostPressure >= 0) {
            t.setPIDInformation(
                "JM23 Boost Pressure",
                "JM23 Boost Pressure",
                "kPa",
                255f,
                0f,
                boostPressure.floatValue(),
                null
            );
        }

        if (false && vvtInDiff != null && vvtInDiff >= -50 && vvtInDiff <= 50) {
            t.setPIDInformation(
                "JM23 VVT In Diff",
                "JM23 VVT In Diff",
                "deg",
                50f,
                -50f,
                vvtInDiff.floatValue(),
                null
            );
        }

        if (false && generatorRegulatorVoltage != null && generatorRegulatorVoltage >= 0 && generatorRegulatorVoltage <= 25) {
            t.setPIDInformation(
                "JM23 Generator Regulator Voltage",
                "JM23 Generator Regulator Voltage",
                "V",
                25f,
                0f,
                generatorRegulatorVoltage.floatValue(),
                null
            );
        }

        if (false && batteryCurrent != null) {
            t.setPIDInformation(
                "JM23 Battery Current",
                "JM23 Battery Current",
                "A",
                100f,
                -100f,
                batteryCurrent.floatValue(),
                null
            );
        }

        if (generatorFieldMonitorDuty != null && generatorFieldMonitorDuty >= 0 && generatorFieldMonitorDuty <= 100) {
            t.setPIDInformation(
                "JM23 Generator Field Monitor Duty",
                "JM23 Generator Field Monitor Duty",
                "%",
                100f,
                0f,
                generatorFieldMonitorDuty.floatValue(),
                null
            );
        }

        if (refrigerantPressure != null) {
            t.setPIDInformation(
                "JM23 Refrigerant Pressure",
                "JM23 Refrigerant Pressure",
                "kPa",
                10000f,
                -2000f,
                refrigerantPressure.floatValue(),
                null
            );
        }

        if (false && consumptionCurrent != null) {
            t.setPIDInformation(
                "JM23 Consumption Current",
                "JM23 Consumption Current",
                "A",
                127.5f,
                0f,
                consumptionCurrent.floatValue(),
                null
            );
        }

        if (generatorControlDuty != null && generatorControlDuty >= 0 && generatorControlDuty <= 100) {
            t.setPIDInformation(
                "JM23 Generator Control Duty",
                "JM23 Generator Control Duty",
                "%",
                100f,
                0f,
                generatorControlDuty.floatValue(),
                null
            );
        }

        if (targetIdleSpeed != null && targetIdleSpeed >= 0 && targetIdleSpeed <= 2000) {
            t.setPIDInformation(
                "JM23 Target Idle Speed",
                "JM23 Target Idle Speed",
                "rpm",
                2000f,
                0f,
                targetIdleSpeed.floatValue(),
                null
            );
        }

        if (iscIacControl != null && iscIacControl >= 0 && iscIacControl <= 100) {
            t.setPIDInformation(
                "JM23 ISC/IAC Control",
                "JM23 ISC/IAC",
                "%",
                100f,
                0f,
                iscIacControl.floatValue(),
                null
            );
        }

        if (false && targetThrottle != null && targetThrottle >= 0 && targetThrottle <= 100) {
            t.setPIDInformation(
                "JM23 Target Throttle",
                "JM23 Target Throttle",
                "%",
                100f,
                0f,
                targetThrottle.floatValue(),
                null
            );
        }

        if (false && acceleratorPosition != null && acceleratorPosition >= 0 && acceleratorPosition <= 100) {
            t.setPIDInformation(
                "JM23 Accelerator Position",
                "JM23 Accelerator",
                "%",
                100f,
                0f,
                acceleratorPosition.floatValue(),
                null
            );
        }

        if (false && acFan != null) {
            t.setPIDInformation(
                "JM23 A/C Fan",
                "JM23 A/C Fan",
                "",
                1f,
                0f,
                acFan.floatValue(),
                null
            );
        }

        if (acCompressorClutch != null) {
            t.setPIDInformation(
                "JM23 A/C Compressor Clutch",
                "JM23 A/C Compressor Clutch",
                "",
                1f,
                0f,
                acCompressorClutch.floatValue(),
                null
            );
        }

        if (acSwitch != null) {
            t.setPIDInformation(
                "JM23 A/C Switch",
                "JM23 A/C Switch",
                "",
                1f,
                0f,
                acSwitch.floatValue(),
                null
            );
        }

        if (wastegateValve != null && wastegateValve >= 0 && wastegateValve <= 100) {
            t.setPIDInformation(
                "JM23 Wastegate Valve",
                "JM23 Wastegate Valve",
                "%",
                100f,
                0f,
                wastegateValve.floatValue(),
                null
            );
        }

        if (evapPurge != null && evapPurge >= 0 && evapPurge <= 100) {
            t.setPIDInformation(
                "JM23 EVAP Purge",
                "JM23 EVAP Purge",
                "%",
                100f,
                0f,
                evapPurge.floatValue(),
                null
            );
        }

        if (engineLoad != null && engineLoad >= 0 && engineLoad <= 100) {
            t.setPIDInformation(
                "JM23 Engine Load",
                "JM23 Engine Load",
                "%",
                100f,
                0f,
                engineLoad.floatValue(),
                null
            );
        }

        if (injectionTime != null && injectionTime >= 0 && injectionTime <= 100) {
            t.setPIDInformation(
                "JM23 Injection Time",
                "JM23 Injection",
                "ms",
                100f,
                0f,
                injectionTime.floatValue(),
                null
            );
        }
        if (injectorRate != null && injectorRate >= 0 && injectorRate <= 100) {
            t.setPIDInformation(
                    "JM23 Injector Rate",
                    "JM23 Injector Rate",
                    "min/hour",
                    100f,
                    0f,
                    injectorRate.floatValue(),
                    null
            );
        }
        if (injector != null && injector >= 0 && injector <= 100) {
            t.setPIDInformation(
                "JM23 Injector",
                "JM23 Injector",
                "min/100 km",
                100f,
                0f,
                injector.floatValue(),
                null
            );
        }
        if (barPressure != null && barPressure >= 50 && barPressure <= 120) {
            t.setPIDInformation(
                "JM23 Barometric Pressure",
                "JM23 Baro",
                "kPa",
                120f,
                50f,
                barPressure.floatValue(),
                null
            );
        }
        if (relPressure != null && relPressure >= -2 && relPressure <= 2) {
            t.setPIDInformation(
                "JM23 Relative Pressure",
                "JM23 Rel Press",
                "bar",
                2f,
                -2f,
                relPressure.floatValue(),
                null
            );
        }
            if (batteryVoltage != null && batteryVoltage >= 0 && batteryVoltage <= 20) {
                t.setPIDInformation(
                    "JM23 Battery Voltage",
                    "JM23 Battery",
                    "V",
                    20f,
                    0f,

                    batteryVoltage.floatValue(),
                    null
                );
            }
            if (false && fuelLevel != null && fuelLevel >= 0 && fuelLevel <= 255) {
                t.setPIDInformation(
                    "JM23 Fuel Level",
                    "JM23 Fuel",
                    "%",
                    255f,
                    0f,
                    fuelLevel.floatValue(),
                    null
                );
            }
        if (speed != null && speed >= 0 && speed <= 255) {
            t.setPIDInformation(
                "JM23 Vehicle Speed",
                "JM23 Speed",
                "km/h",
                255f,
                0f,
                speed.floatValue(),
                null
            );
        }
        t.setPIDInformation(
            "JM23 Trip Distance",
            "JM23 Trip",
            "km",
            9999f,
            0f,
            (float) tripDistanceKm,
            null
        );

        t.setPIDInformation(
                "JM23 Fuel Open Minutes",
                "JM23 Fuel Open Minutes",
                "min",
                99999f,
                0f,
                (float) fuelOpenMinutes,
                null
        );

        if (fuelUsedLiters != null) {
            t.setPIDInformation(
                "JM23 Fuel Used",
                "JM23 Fuel Used",
                "L",
                (float) fuelTankCapacityLiters,
                0f,
                fuelUsedLiters.floatValue(),
                null
            );
        }

        if (fuelRemainingLiters != null) {
            t.setPIDInformation(
                "JM23 Fuel Remaining",
                "JM23 Fuel Remaining",
                "L",
                (float) fuelTankCapacityLiters,
                0f,
                fuelRemainingLiters.floatValue(),
                null
            );
        }

        if (fuelRemainingPercent != null) {
            t.setPIDInformation(
                "JM23 Fuel Remaining Percent",
                "JM23 Fuel Remaining Percent",
                "%",
                100f,
                0f,
                fuelRemainingPercent.floatValue(),
                null
            );
        }

        if (control1 != null) {
            t.setPIDInformation(
                "JM23 Control 1",
                "JM23 Control 1",
                "",
                255f,
                0f,
                control1.floatValue(),
                control1Text(control1)
            );
        }


    } catch (Throwable e) {
            debug("Torque API exception: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
            updateNotification("Torque API: " + e.getClass().getSimpleName());
            reconnectTorque("Torque API exception");
        }
    }

    /**
     * ECU positive response is 61 00 followed by the local-data block.
     * SZ Viewer reverse engineering + real-car validation established:
     * local-data[35..36] = big-endian raw RPM, scale 0.25 rpm/bit.
     *
     * This parser accepts Torque strings with spaces, CAN headers, ISO-TP PCI bytes,
     * or already recombined payloads. It searches for the positive-response marker
     * then indexes into the payload rather than depending on Torque's custom-PID
     * A/B/N/R expression parser.
     */
    static Integer extractControl1(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() == 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (15 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            return Integer.parseInt(hex.substring(pos, pos + 2), 16);
        } catch (Exception e) {
            return null;
        }
    }

    static String control1Text(int value) {
        switch (value) {
            case 1: return "Open loop";
            case 2: return "Closed loop (Sensor)";
            case 4: return "Fuel cut / Load";
            case 8: return "Open loop failure";
            case 16: return "Closed loop failure";
            default: return "State " + value;
        }
    }

    /** SZ Viewer compatible: local-data[34], raw * 100 / 255 %. */
    /** SZ Viewer compatible: local-data[45], raw * 100 / 255 %. */
    /** SZ Viewer compatible: local-data[63], raw * 100 / 255 %. */
    static Double extractAcceleratorPosition(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (63 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * (100.0 / 255.0);
        } catch (Exception e) {
            return null;
        }
    }

    /** SZ Viewer: local-data[47], raw * 100 / 255 %. */
    /** SZ Viewer: local-data[132], bit 3 = A/C switch. */
    /** SZ Viewer: local-data[124], bit 4 = A/C compressor clutch. */
    /** SZ Viewer candidate: local-data[124], bit 1 = A/C fan. */
    static Integer extractAcFan(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (124 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return (raw >> 1) & 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer extractAcCompressorClutch(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (124 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return (raw >> 4) & 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer extractAcSwitch(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (132 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return (raw >> 3) & 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SZ Viewer Engine_CAN_00_Local: local-data[49], raw * 0.35294117647 - 45.2 deg. */
    static Double extractVvtInDiff(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (49 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 0.35294117647058826 - 45.2;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractWastegateValve(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (47 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 100.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractEvapPurge(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (45 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * (100.0 / 255.0);
        } catch (Exception e) {
            return null;
        }
    }

    static Double extractEngineLoad(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (34 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * (100.0 / 255.0);
        } catch (Exception e) {
            return null;
        }
    }

    static Double extractSpeed(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() == 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (40 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            return (double) Integer.parseInt(hex.substring(pos, pos + 2), 16);
        } catch (Exception e) {
            return null;
        }
    }

   static Double extractRpm(String[] responses) {
    if (responses == null) return null;

    StringBuilder all = new StringBuilder();

    for (String response : responses) {
        if (response == null) continue;

        String s = response.trim().toUpperCase(Locale.US);

        // Last item can be "BUFFER FULL".
        if (s.contains("BUFFER")) continue;

        // Torque returns chunks such as:
        // 0:6100FFFFFF
        // 1:FFFFFFFFFFFFFF
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }

        s = s.replaceAll("[^0-9A-F]", "");

        // First item is response length, e.g. "092".
        if (s.length() == 3) continue;

        // Only complete bytes.
        if (s.length() == 0 || (s.length() & 1) != 0) continue;

        all.append(s);
    }

    int marker = all.indexOf("6100");
    if (marker < 0) return null;

    // local data byte 35/36 = RPM word, big endian, ×0.25
    int rpmPos = marker + 4 + (35 * 2);

    if (rpmPos + 4 > all.length()) return null;

    try {
        int hi = Integer.parseInt(all.substring(rpmPos, rpmPos + 2), 16);
        int lo = Integer.parseInt(all.substring(rpmPos + 2, rpmPos + 4), 16);

        return (((hi << 8) | lo) * 0.25);
    } catch (NumberFormatException e) {
        return null;
    }
}
   static Double extractCoolantTemp(String[] responses) {
    if (responses == null) return null;

    StringBuilder all = new StringBuilder();

    for (String response : responses) {
        if (response == null) continue;

        String s = response.trim().toUpperCase(Locale.US);

        if (s.contains("BUFFER")) continue;

        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }

        s = s.replaceAll("[^0-9A-F]", "");

        if (s.length() == 3) continue;

        if (s.length() == 0 || (s.length() & 1) != 0) continue;

        all.append(s);
    }

    int marker = all.indexOf("6100");
    if (marker < 0) return null;

    // SZ Viewer Engine_CAN_00_Local:
    // local-data byte 42 = engine coolant temperature
    // value = raw - 40
    int tempPos = marker + 4 + (42 * 2);

    if (tempPos + 2 > all.length()) return null;

    try {
        int raw = Integer.parseInt(
                all.substring(tempPos, tempPos + 2),
                16
        );

        return (double) (raw - 40);

    } catch (NumberFormatException e) {
        return null;
    }
}

  static Double extractIntakeTemp(String[] responses) {
    if (responses == null) return null;

    StringBuilder all = new StringBuilder();

    for (String response : responses) {
        if (response == null) continue;

        String s = response.trim().toUpperCase(Locale.US);

        if (s.contains("BUFFER")) continue;

        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }

        s = s.replaceAll("[^0-9A-F]", "");

        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;

        all.append(s);
    }

    int marker = all.indexOf("6100");
    if (marker < 0) return null;

    int tempPos = marker + 4 + (43 * 2);

    if (tempPos + 2 > all.length()) return null;

    try {
        int raw = Integer.parseInt(
                all.substring(tempPos, tempPos + 2),
                16
        );

        return (double) (raw - 40);

    } catch (NumberFormatException e) {
        return null;
    }
}
 static Double extractMapPressure(String[] responses) {
    if (responses == null) return null;

    StringBuilder all = new StringBuilder();

    for (String response : responses) {
        if (response == null) continue;

        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;

        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }

        s = s.replaceAll("[^0-9A-F]", "");

        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;

        all.append(s);
    }

    int marker = all.indexOf("6100");
    if (marker < 0) return null;

    int mapPos = marker + 4 + (41 * 2);
    if (mapPos + 2 > all.length()) return null;

    try {
        int raw = Integer.parseInt(
                all.substring(mapPos, mapPos + 2),
                16
        );
        return (double) raw;
    } catch (NumberFormatException e) {
        return null;
    }
}
static Double extractBarPressure(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (44 * 2);
    if (pos + 2 > all.length()) return null;
    try {
        return (double) Integer.parseInt(all.substring(pos, pos + 2), 16);
    } catch (NumberFormatException e) {
        return null;
    }
}

static Double extractShortTrim(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (17 * 2);
    if (pos + 2 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 2), 16);
        return raw * 0.78125 - 100.0;
    } catch (NumberFormatException e) {
        return null;
    }
}
static Double extractLongTrim(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (18 * 2);
    if (pos + 2 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 2), 16);
        return raw * 0.78125 - 100.0;
    } catch (NumberFormatException e) {
        return null;
    }
}


static Double extractAfSensor1(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (82 * 2);
    if (pos + 4 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 4), 16);
        return raw * 0.00390625 - 128.0;
    } catch (NumberFormatException e) {
        return null;
    }
}

static Double extractAfSensor2(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (75 * 2);
    if (pos + 2 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 2), 16);
        return raw * 0.005;
    } catch (NumberFormatException e) {
        return null;
    }
}

static Double extractBatteryVoltage(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (97 * 2);
    if (pos + 4 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 4), 16);
        return raw * 0.001;
    } catch (NumberFormatException e) {
        return null;
    }
}

static Double extractInjectionTime(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (23 * 2);
    if (pos + 4 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 4), 16);
        return raw * 0.001;
    } catch (NumberFormatException e) {
        return null;
    }
}

static Double extractIgnitionAdvance(String[] responses) {
    if (responses == null) return null;
    StringBuilder all = new StringBuilder();
    for (String response : responses) {
        if (response == null) continue;
        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.replaceAll("[^0-9A-F]", "");
        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;
        all.append(s);
    }
    int marker = all.indexOf("6100");
    if (marker < 0) return null;
    int pos = marker + 4 + (27 * 2);
    if (pos + 2 > all.length()) return null;
    try {
        int raw = Integer.parseInt(all.substring(pos, pos + 2), 16);
        return raw * 0.5 - 64.0;
    } catch (NumberFormatException e) {
        return null;
    }
}

    /** SZ Viewer compatible: local-data[65], raw * 100 / 255 %. */
    /** SZ Viewer compatible: local-data[58], raw * 100 / 255 %. */
    /** SZ Viewer compatible: local-data[59], raw * 5 / 255 V. */
    /** SZ Viewer compatible candidate: local-data[60], raw * 5 / 255 V. */
    /** SZ Viewer compatible candidate: local-data[61], raw * 5 / 255 V. */
    static Double extractMainAcceleratorSensor(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (61 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 5.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractAssistantThrottleSensor(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (60 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 5.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractMainThrottleSensor(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (59 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 5.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SZ Viewer: local-data[37], raw * 2000 / 255 rpm. */
    /** SZ Viewer Engine_CAN_00_Local: local-data[147], direct kPa. */
    static Double extractBoostPressure(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (147 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            return (double) Integer.parseInt(hex.substring(pos, pos + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractTargetIdleSpeed(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (37 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 2000.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractIscIacControl(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (58 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 100.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractTargetThrottle(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (65 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 100.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

 static Double extractThrottle(String[] responses) {
    if (responses == null) return null;

    StringBuilder all = new StringBuilder();

    for (String response : responses) {
        if (response == null) continue;

        String s = response.trim().toUpperCase(Locale.US);
        if (s.contains("BUFFER")) continue;

        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }

        s = s.replaceAll("[^0-9A-F]", "");

        if (s.length() == 3) continue;
        if (s.length() == 0 || (s.length() & 1) != 0) continue;

        all.append(s);
    }

    int marker = all.indexOf("6100");
    if (marker < 0) return null;

    int throttlePos = marker + 4 + (64 * 2);
    if (throttlePos + 2 > all.length()) return null;

    try {
        int raw = Integer.parseInt(
                all.substring(throttlePos, throttlePos + 2),
                16
        );

        return raw * 100.0 / 255.0;

    } catch (NumberFormatException e) {
        return null;
    }
}
    private static Double rpmFromPayload(List<Integer> b) {
        if (b == null) return null;
        for (int i = 0; i + 2 + 36 < b.size(); i++) {
            if (b.get(i) == 0x61 && b.get(i + 1) == 0x00) {
                int hi = b.get(i + 2 + 35);
                int lo = b.get(i + 2 + 36);
                return (((hi << 8) | lo) * 0.25);
            }
        }
        return null;
    }

    /** SZ Viewer Engine_CAN_00_Local: local-data[102], raw * 100 / 255 %. */
    /** SZ Viewer Engine_CAN_00_Local: local-data[103], raw * 100 / 255 %. */
    /** SZ Viewer Engine_CAN_00_Local: local-data[120], raw * 0.1 V. */
    static Double extractGeneratorRegulatorVoltage(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (120 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 0.1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SZ Viewer Engine_CAN_00_Local: local-data[107], raw * 0.78431372549 - 100 A. */
    static Double extractBatteryCurrent(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (107 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 0.7843137254901961 - 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractGeneratorFieldMonitorDuty(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (103 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 100.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SZ Viewer Engine_CAN_00_Local: local-data[100], raw * 40 - 2000 kPa. */
    /** SZ Viewer Engine_CAN_00_Local: local-data[101], raw * 0.5 A. */
    static Double extractConsumptionCurrent(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (101 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 0.5;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractRefrigerantPressure(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (100 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 40.0 - 2000.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractGeneratorControlDuty(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() < 3 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (102 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return raw * 100.0 / 255.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double extractFuelLevel(String[] responses) {
        if (responses == null) return null;
        StringBuilder all = new StringBuilder();
        for (String response : responses) {
            if (response == null) continue;
            String s = response.trim().toUpperCase(Locale.US);
            if (s.contains("BUFFER")) continue;
            int colon = s.lastIndexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replaceAll("[^0-9A-F]", "");
            if (s.length() == 3 || s.length() == 0 || (s.length() & 1) != 0) continue;
            all.append(s);
        }
        String hex = all.toString();
        int marker = hex.indexOf("6100");
        if (marker < 0) return null;
        int pos = marker + 4 + (99 * 2);
        if (hex.length() < pos + 2) return null;
        try {
            int raw = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            return (double) raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Integer> hexBytes(String s) {
        String cleaned = s.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", " ").trim();
        if (cleaned.isEmpty()) return new ArrayList<>();
        String[] tok = cleaned.split("\\s+");
        List<Integer> out = new ArrayList<>();
        for (String x : tok) {
            // Ignore three-digit CAN IDs such as 7E8.
            if (x.length() == 2) {
                try { out.add(Integer.parseInt(x, 16)); } catch (NumberFormatException ignored) {}
            } else if (x.length() > 2 && x.length() != 3 && (x.length() & 1) == 0) {
                // Compact payload such as 10926100FFFF...
                for (int p = 0; p < x.length(); p += 2) {
                    try { out.add(Integer.parseInt(x.substring(p, p + 2), 16)); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return out;
    }


    private void debug(String text) {
        String line = String.format(Locale.US, "%tT  %s", new Date(), text);
        getSharedPreferences("debug", MODE_PRIVATE).edit()
                .putString("last", line)
                .putString("log", trimLog(getSharedPreferences("debug", MODE_PRIVATE).getString("log", "") + line + "\n"))
                .apply();
    }

    private static String trimLog(String s) {
        if (s == null) return "";
        int max = 12000;
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String joinRaw(String[] raw) {
        if (raw == null) return "<null>";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            if (i > 0) b.append(" || ");
            b.append(i).append(':').append(String.valueOf(raw[i]).replace('\n', '/').replace('\r', '/'));
        }
        String out = b.toString();
        return out.length() > 3000 ? out.substring(0, 3000) + "…" : out;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "JM23 Torque Bridge", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("JM23 Torque Bridge").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        try { getSystemService(NotificationManager.class).notify(23, notification(text)); } catch (Throwable ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        worker.shutdownNow();
        if (bound) { try { unbindService(connection); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    private void probeTorquePidRegistry(IBinder binder) {
        final String DESCRIPTOR = "org.prowl.torque.remote.ITorqueService";

        Parcel data = null;
        Parcel reply = null;

        try {
            data = Parcel.obtain();
            reply = Parcel.obtain();

            data.writeInterfaceToken(DESCRIPTOR);

            boolean tx = binder.transact(31, data, reply, 0);

            if (!tx) {
                debug("PID-REGISTRY tx31=false");
                return;
            }

            reply.readException();

            String[] ids = reply.createStringArray();

            debug("PID-REGISTRY tx31 count="
                    + (ids == null ? -1 : ids.length));

            if (ids == null || ids.length == 0) {
                return;
            }

            probeTorquePidInfo(binder, ids);

        } catch (Throwable e) {
            debug("PID-REGISTRY tx31 failed: "
                    + e.getClass().getName()
                    + ": "
                    + String.valueOf(e.getMessage()));
        } finally {
            if (reply != null) reply.recycle();
            if (data != null) data.recycle();
        }
    }

    private void probeTorquePidInfo(IBinder binder, String[] ids) {
        final String DESCRIPTOR = "org.prowl.torque.remote.ITorqueService";

        Parcel data = null;
        Parcel reply = null;

        try {
            data = Parcel.obtain();
            reply = Parcel.obtain();

            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStringArray(ids);

            boolean tx = binder.transact(35, data, reply, 0);

            if (!tx) {
                debug("PID-REGISTRY tx35=false");
                return;
            }

            reply.readException();

            String[] info = reply.createStringArray();

            boolean found = false;
            String foundInfo = null;

            if (info != null) {
                for (String s : info) {
                    if (s != null && s.contains("JM23 RPM")) {
                        found = true;
                        foundInfo = s;
                        break;
                    }
                }
            }

            debug("PID-REGISTRY tx35 count="
                    + (info == null ? -1 : info.length)
                    + " JM23_RPM="
                    + found
                    + (found ? " info=" + foundInfo : ""));

        } catch (Throwable e) {
            debug("PID-REGISTRY tx35 failed: "
                    + e.getClass().getName()
                    + ": "
                    + String.valueOf(e.getMessage()));
        } finally {
            if (reply != null) reply.recycle();
            if (data != null) data.recycle();
        }
    }

}
