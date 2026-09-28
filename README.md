# JB23 Torque Bridge v0.4-test

AndroidIDE/TB320FC test build.

## v0.4 change

This version does **not** invoke the Android `aidl` compiler.  The TB320FC is
ARM64, while the SDK `aidl` binary previously installed under
`/opt/android_sdk/build-tools/34.0.0/aidl` was x86_64.

A minimal Java Binder client for Torque's public ITorqueService is bundled in
`app/src/main/java/org/prowl/torque/remote/ITorqueService.java`.

The plugin sends header `7E0`, command `2100`, locally reconstructs ISO-TP, then
reads local-data bytes 35/36 as big-endian RPM with scale 0.25.

## Build on TB320FC

Use the AndroidIDE terminal from the project root.  If AndroidIDE's Run button
still starts Java 8, run Gradle explicitly with JDK 17:

    export JAVA_HOME=/opt/java/jdk-17.0.20.1+1
    export PATH="$JAVA_HOME/bin:$PATH"
    ./gradlew assembleDebug

The debug APK should be produced under:

    app/build/outputs/apk/debug/app-debug.apk
