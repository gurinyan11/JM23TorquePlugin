# JM23 USB TPMS: phase 1 (offline decoder)

Status: parser and Torque PID publisher added as **inactive standalone classes**.
The live `BridgeService`, ECU polling, Trip, fuel logic, Manifest, and Gradle files are unchanged.

## Evidence from four user-supplied APKs (2026-11-16)

- `USB new新（recommend）.apk` and `tpms-V1.1.64M_USB_L10_20221113.apk` contain `TpmsApplication -> TpmsDataSrcUsb -> Tpms3 -> FrameDecode3`.
- At initialization `FrameDecode3.init` installs a `PackBufferFrame3`; USB reads are delivered to the inherited buffer, which dispatches to the new-frame parser.
- `TpmsDataSrcUsb.openUsbPort` calls `setParameters(19200, 8, 1, 0)` on the Android USB serial port.
- `FrameDecode3.onEventMainThread` parses a report where `frame[2] == 0x08`:
  - wheel ID `frame[3]`: `0x00` FL, `0x01` FR, `0x10` RL, `0x11` RR, `0x05` spare;
  - pressure: `(int) ((frame[4] & 255) * 3.44)` kPa;
  - temperature: `(frame[5] & 255) - 50` °C;
  - flags in `frame[6]`: `0x20` no signal, `0x08` leakage, `0x10` low battery (latter present in USB new, not in V1.1.64M's target method).
- `PackBufferFrame3` verifies `0x55 0xAA` and XOR of bytes 0..length-2 against the final byte.
- Actual delivered USB hardware VID/PID and real radio measurements have **not yet been verified**.

## Public reverse engineering cross-check

- https://github.com/zugetor/usb-tpms-reverse-engineer
- https://github.com/SamSkjord/TPMS
- https://github.com/danderik88/usb-tpms-home-assistant

The reconstruction follows the installed APK's arithmetic, including integer-kPa truncation (rather than unrounded kPa).

## Modules

- `TpmsFrameDecoder.java`: dependency-free 8-byte report and stream parser; no USB stack, no physical sensor required for offline tests.
- `TpmsPidPublisher.java`: 8 values (`FL/FR/RL/RR Pressure` in bar, `Temperature` in °C) using the existing Torque `setPIDInformation` interface. It is **not called yet**; no fake sensor values are published.
- `tools/tpms/TpmsFrameDecoderSelfTest.java`: Java-only self-test.

Run locally with JDK:

```bash
javac -d /tmp/jm23-tpms-test \
  app/src/main/java/jp/jb23/torqueplugin/TpmsFrameDecoder.java \
  tools/tpms/TpmsFrameDecoderSelfTest.java
java -cp /tmp/jm23-tpms-test jp.jb23.torqueplugin.TpmsFrameDecoderSelfTest
```

## Deferred until gauge delivery / first S8MS USB test

1. Determine actual USB VID/PID on S8MS; confirm device permission and 19200 8N1 reception.
2. Collect a **small raw USB frame log**; no tire mounting is necessary for USB enumeration, but actual pressure validation must wait for safe installation and gauge.
3. Validate frame format and wheel mapping against received bytes and cold inflation measurements.
4. Add an independent Android USB transport to the JM23 plugin and connect to `TpmsPidPublisher`, without modifying ECU 2100 polling.
5. Test sleep/reconnect, status flags, and Torque display. The current main release is untouched.


## Phase 2 — S8MS USB transport (2026-11-16)

Implemented on branch `feature/tpms-usb-parser-20261116`; deliberately **not merged into main**.

- `TpmsUsbService.java`: manually enabled Android USB Host/USB Serial receiver.
- Only probes `1A86:7523` CH340/CH341 family at first. Other USB VID/PID values are listed in the UI but not automatically used.
- USB settings copied from the new APK: 19200 baud, 8N1.
- Requests Android USB permission, handles disconnect, keeps a small raw hex capture, and records decoded pressure, temperature, low-battery, leak, no-signal and last-received timestamps to `jm23_tpms_usb` preferences.
- Uses `TpmsFrameDecoder` and `TpmsPidPublisher` for 8 Torque values. No arbitrary or placeholder values are published before an actual valid frame.
- `MainActivity` has independent START/STOP USB TPMS buttons and a diagnostic readout.
- `settings.gradle` adds JitPack and `app/build.gradle` pins `com.github.mik3y:usb-serial-for-android:3.11.0` (CH340-compatible USB serial library).
- `AndroidManifest.xml` declares USB Host (not required for install) and the separate foreground service.
- Existing `BridgeService` ECU 2100 loop, Trip/fuel logic and automatic boot behavior are unchanged. TPMS does **not** auto-start; reconnect after USB unplug requires START.

### Verification / limits

- Offline parser self-test passed: 15 checks on Linux/JDK.
- Complete Android Gradle build **has not been run** in this workspace (Android SDK/Gradle not available).
- Hardware USB enumeration and received frames **have not been tested**; receiver may use a VID/PID different from `1A86:7523`.
- The TPMS sensors have **not been fitted** to tires. Wait until the tire pressure gauge arrives, then verify cold pressure against the actual readings. No road-use acceptance based only on emulator/fixture data.
- The posted raw timestamps are receipt times, **not proof of fresh radio measurements**.
- Live USB listener has no automatic sleep recovery yet and all integration still requires device test and Gradle build.

### Minimal S8MS validation once a build has succeeded

1. Connect the USB dongle with sensors not installed; press `START USB TPMS TEST` in JM23.
2. Observe reported VID:PID, USB permission prompt, and connection status. If the ID differs, stop and inspect the receiver before updating the allowlist.
3. Check `raw_reads` and the first twelve raw chunks; lack of frames without sensors transmitting is not a failure.
4. Stop the TPMS test. Leave the existing Torque plugin ECU path running unchanged.
5. After the pressure gauge arrives, fit sensors and compare pressure/temperature. Then test sleep/resume and stale values separately.

