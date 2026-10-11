# Validation record

Validated on 11 October 2026 in a Linux build environment. No physical Android device, Windows laptop, or Bluetooth radio was available.

## Executed successfully

- Clean Android Gradle build with AGP 8.13.2, Gradle 8.13, JDK 17, Android SDK Platform 36, and Build Tools 35.0.0.
- `clean assembleDebug assembleRelease lintDebug`: **BUILD SUCCESSFUL**. The release APK is unsigned; use Android Studio to generate your own signed release.
- Android lint: **0 errors, 1 warning**. `MonochromeLauncherIcon` flags the base adaptive icon. A dedicated `mipmap-anydpi-v33/ic_launcher.xml` provides the monochrome icon on API 33+. The pre-33 fallback deliberately does not contain that tag. No lint findings were suppressed.
- `KeyboardReportTest`: all six HID usage mappings, Shift modifier, reserved bytes, release isolation, descriptor item structure, balanced collections, 64-bit input length, and 8-bit LED output length passed.
- XML parser: all resources and manifest well-formed; Android resource linking also passed.
- `apksigner verify --verbose`: debug APK signature valid (v2).
- Packaged APK metadata: application ID `com.slidepilot.app`, version `1.0.1`, min SDK 28, target/compile SDK 36. Only the four documented Bluetooth permissions are present; the two legacy permissions are capped at API 30. No INTERNET permission.
- Gradle ZIP SHA-256 matched Gradle's published checksum: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

## Version 1.0.1 regression scope

The provided TECNO log shows `registerApp request accepted=false`, followed by a later service death-link registration, and a subsequent retry logging `register_app: application already registered`. This supports an asynchronous registration/cleanup overlap; the log does not establish the cause of the first false return or universal phone compatibility.

The update waits for authoritative app-status callbacks even after a false API return, retains the proxy during cleanup, serializes registration across Activity instances, starts initial registration in the resumed Activity, and avoids disconnecting a merely selected host. If cleanup cannot be confirmed, new registration is blocked until Bluetooth is reset. These lifecycle changes compiled and passed lint with no errors, but have not been exercised on a physical Bluetooth stack.

The replacement debug APK has the same signing certificate as the originally supplied debug APK and a higher versionCode (2).

## Not executed / not claimed

- App launch, on-device visual inspection, TalkBack/font/inset runtime checks.
- Bluetooth service acquisition, registration, pairing, or connection on real firmware.
- Windows PowerPoint shortcut delivery or key release over a physical Bluetooth link.
- OEM-specific volume-button, screen-lock, lifecycle, or battery behavior.
- Runtime tests on API 28, 33, 34, 35, or 36.

A build is not hardware qualification. Use the README checklist on every phone/firmware/laptop combination before classroom use. The implementation is complete, but it is not represented as field-tested or universally compatible.

## Repeat the pure-Java wire-contract check

From Windows PowerShell in the project root with JDK 17 available:

```powershell
New-Item -ItemType Directory -Force .\build\wire-tests | Out-Null
javac -d .\build\wire-tests .\app\src\main\java\com\slidepilot\app\KeyboardReport.java .\tools\KeyboardReportTest.java
java -cp .\build\wire-tests com.slidepilot.app.KeyboardReportTest
```

The test has no Android/runtime dependencies and does not simulate a Bluetooth connection.
