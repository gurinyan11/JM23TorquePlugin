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
