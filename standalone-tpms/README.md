# JM23 TPMS — independent Torque plugin (Phase 0)

Standalone Android app with a **different application ID** from the existing JM23 ECU plugin.

- Existing app: `jp.jb23.torqueplugin`
- New app: `jp.jb23.tpmsplugin`
- This project is held in an isolated development branch under `standalone-tpms/`, not merged to main.

## First checkpoint: Torque binding only

1. Build and install as a second Android app; existing JM23 must remain unchanged.
2. Run Torque Pro, open JM23 TPMS Test and press `START TORQUE LINK TEST`.
3. `TORQUE_BOUND` is evidence of Android service binding only, **not** PID publication or simultaneous plugin stability.
4. Press STOP. Check existing JM23 still operates.

Phase 0 has **no USB receiver, no tire sensor operations, no ECU command and no dummy PID**, and is not automatically started on boot.

## Build

From JM23 repository root, using its existing Gradle wrapper:

```sh
./gradlew -p standalone-tpms :app:assembleDebug
```

Alternatively open the `standalone-tpms` directory as a separate AndroidIDE project.

## Next phases (not implemented yet)

After build and two-app coexistence verification: reuse previously analyzed `TpmsFrameDecoder`, add USB VID/PID discovery and sensor transport, then publish real pressure and temperature values. Tire mounting waits for air pressure gauge.

**Verification**: source/manifest structural checks passed; Android Gradle build has not been performed in this environment. Nothing in existing JM23 `app/` was changed.


## Phase 1 — manually scan USB device IDs (2026-11-16)

Build after the successful Phase 0 coexistence test. Same independent application ID,
new version code 2. The baseline Torque binding service and original app logic are unchanged.

- Select **SCAN CONNECTED USB DEVICES** to display visible VID:PID and interface counts.
- CH340 candidate is tagged only when VID:PID is **1A86:7523**; an APK USB filter alone
  does not establish the physical receiver's ID.
- This diagnostic only enumerates devices. It does not request USB permission,
  open a serial port, read RF data, register a PID, or touch the ECU.
- Android USB Host is declared optional, so Phase 0 function is preserved on devices
  without USB Host hardware.
- Compilation of this phase has **not** been completed in AndroidIDE.

Test sequence: assembleDebug and install this package over Phase 0 (same debug signing key).
Confirm Torque still binds; later attach the USB receiver to S8MS and scan once.
On TB320FC, a USB-A receiver requires an OTG adapter and is not an assumed test target.
Tire installation waits for the pressure gauge.

## Git pull workflow check — 2026-11-16

TPMS-GIT-PULL-CHECK-20261116: README-only change for verifying AndroidIDE fetch/pull; no app code changed.
