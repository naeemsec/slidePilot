# SlidePilot 1.0.1

Offline Bluetooth presentation remote for Windows PowerPoint. Java + native XML, API 28–36, no laptop companion, accounts, analytics, or internet permission. The source is complete; real phone/Windows qualification is required before relying on it in class. See `VALIDATION.md` for what was actually verified.

## Open and build on Windows

1. Extract the project ZIP to a normal folder, for example `C:\AndroidProjects\SlidePilot`. Open that folder in Android Studio (**Open**, not New Project). The folder contains `settings.gradle`.
2. Use an Android Studio release that supports Android Gradle Plugin **8.13.2** (Otter 2025.2.1 or a compatible newer release). Retain the project's pinned versions when prompted about upgrades.
3. In **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select **JDK 17** as Gradle JDK. Install a JDK 17 if necessary. For terminal builds, point `JAVA_HOME` to that JDK and put its `bin` directory on PATH. Java source and target compatibility are 17.
4. In **Tools → SDK Manager**, install **Android SDK Platform 36**, **Android SDK Build-Tools 35.0.0** (enable Show Package Details), **Android SDK Platform-Tools**, and **Android SDK Command-line Tools (latest)**. No NDK, Kotlin, or emulator image is required for physical-device testing. Android Studio writes your machine-specific `local.properties`; do not copy another machine's SDK path.
5. Let Gradle sync. The included official wrapper downloads **Gradle 8.13**, protected by a SHA-256 distribution checksum. AGP and SDK/build-tool downloads need internet during setup; the installed app does not.
6. Enable Developer options → USB debugging on your phone, connect by USB, approve the computer's debugging prompt, select the phone in Android Studio, and click Run. Install a Windows USB driver for your phone if needed.

PowerShell, from the project root:

```powershell
.\gradlew.bat --version
.\gradlew.bat clean assembleDebug lintDebug
```

The debug APK is `app\build\outputs\apk\debug\app-debug.apk`. You can copy it to your phone and install it after allowing APK installation for your file manager. Debug builds are for testing; do not rely on debug signing keys for long-term updates.

For a shareable release: **Build → Generate Signed App Bundle or APK → APK → Create new keystore**, choose a strong password, save the keystore securely outside the project, select `release`, and finish. Keep the keystore: subsequent updates must use the same application ID and signing key. Release builds deliberately use no shrinking, avoiding unnecessary HID debugging variables. No signing passwords or private keys are shipped in source.

Build configuration:

| Item | Version / choice |
|---|---|
| Language / layouts | Java 17 / native Android XML |
| Minimum SDK | 28 (Android 9) |
| Compile / target SDK | 36 / 36 (Android 16) |
| Android Gradle Plugin | 8.13.2 |
| Gradle wrapper | 8.13 |
| SDK Build Tools | AGP default 35.0.0 |
| Runtime dependencies | Android framework only |
| UI | Framework Material theme, custom rounded ripple resources, day/night palettes |

Minimum SDK controls which Android versions can install the app. It does **not** establish a device-compatibility percentage or guarantee HID firmware support. The compile SDK supplies APIs to the compiler; the target SDK opts into that Android version's behavior rules.

## Pair with Windows, then connect HID

1. Turn Bluetooth on on both devices. Connect the laptop to the projector and open desktop PowerPoint.
2. Open SlidePilot, finish onboarding, allow **Nearby Devices**, and wait for **HID Ready** or **Not Paired**. A registration failure must be resolved before using the remote.
3. Tap **Pair a new laptop → Make discoverable**. Advertising permission is requested only for this action. Keep SlidePilot visible while pairing from Windows.
4. Windows 11: **Settings → Bluetooth & devices → Add device → Bluetooth**. Windows 10: **Settings → Devices → Bluetooth & other devices → Add Bluetooth or other device → Bluetooth**.
5. Select the phone; Windows may display the phone's Bluetooth name, rather than “SlidePilot.” Confirm matching codes on both devices. SlidePilot does not intercept or bypass Android's pairing dialog.
6. If Windows asks you to *type* a code on the phone as a keyboard, cancel that attempt. Initiate pairing from **Android Bluetooth settings** with Windows discoverable, then return to SlidePilot. This app has no arbitrary-text/passkey keyboard. If necessary remove stale pairing on both devices and repeat while HID is registered.
7. Return to SlidePilot, tap **Choose paired laptop**, select your Windows machine, then **Connect**. The chooser shows all paired devices because device-class labels are not a reliable way to identify Windows. Select the laptop, not a headset.
8. Wait for **Connected**. “Paired” in Windows or Android settings is not proof of a usable HID session. Registration alone is not a connection either.
9. Focus PowerPoint on the laptop. Try Start, Next, Previous, Black Screen, and End before presenting.

If the phone is already paired, skip discoverability and select the laptop directly. An incoming connection from the selected paired laptop can also be accepted. With no selected laptop, the first bonded incoming host can be accepted; no commands are sent until the user presses a control. Once a host is selected, incoming connections from other hosts are rejected.

For classmates: one presenter at a time. Disconnect the previous phone before connecting the next. Everyone can install the same APK and pair their phone, but firmware and Windows adapter support must be checked individually. No presentation file is sent to the phone.

## Controls and behavior

| Control | Keyboard report |
|---|---|
| Next | Page Down, usage `0x4E` |
| Previous | Page Up, usage `0x4B` |
| Start | F5, usage `0x3E` |
| From current | Left Shift (`0x02`) + F5 |
| End, with confirmation | Escape, usage `0x29` |
| Black screen toggle | B, usage `0x05` |
| Volume up / down | Next / Previous, when enabled and the app has focus |

Reports use an eight-byte boot-compatible keyboard layout, no report ID (`0` in Android API calls), and a one-byte LED output report. Both boot and report protocol modes use that layout. There is no BLE GATT implementation or RFCOMM fallback.

The app sends a press then an all-zero release after 45 ms. Inputs are serialized, long-held volume keys do not repeat, and taps inside a 220 ms debounce window are ignored. Failed release submissions are retried twice before closing the session. Release is also attempted on focus loss, pause, disconnection, and cleanup. No Bluetooth implementation can guarantee delivery after radio loss, process termination, or host failure; an accepted `sendReport` call is not an acknowledgment that PowerPoint acted. The app never displays a fake slide number or a fabricated black-screen state.

Keep the phone **unlocked and SlidePilot visible**. The screen stays awake while connected. Leaving the Activity closes registration and the connection; on return, select/connect again (the selected host is remembered). No foreground service, passive/background control, lock-screen control, notification permission, or battery-exemption request is used. Window focus loss releases held keys. Android may unregister HID even before the Activity stops; callbacks invalidate the session immediately. A configuration change safely closes and rebuilds the session; reconnect afterward.

The timer is a separate offline stopwatch based on monotonic elapsed time. It continues through temporary backgrounding, preserves state across Activity recreation, and is independent of slide commands. Fresh launches without restored Activity state start at zero. It is not synchronized with PowerPoint.

PowerPoint must have keyboard focus. Shortcut behavior can vary with keyboard layout, PowerPoint mode, or custom shortcuts. Black-screen uses the B key; select an English keyboard layout on Windows if a different layout remaps it. Physical volume routing and haptic feedback can be overridden or disabled by the phone firmware or user settings.

## Permissions and privacy

* API 28–30: normal `BLUETOOTH` and `BLUETOOTH_ADMIN` permissions, capped at API 30.
* API 31+: runtime `BLUETOOTH_CONNECT` for paired devices and HID. `BLUETOOTH_ADVERTISE` only when making the phone discoverable.
* No scanning, location, internet, storage, microphone, accessibility-service, or account permissions.
* Preferences store onboarding, the volume-key choice, and the selected host's Bluetooth address only in app-private storage. Backup is disabled. Bluetooth names/addresses are shown locally in the chooser, not logged or uploaded.
* Diagnostics holds up to 120 events in memory and writes the same events to local Android Logcat. Copy is explicitly initiated by you, and includes the phone model/OS version. It does not contain laptop names/addresses or presentation content.

## Architecture

* `MainActivity.java`: XML UI, permissions, paired-device selection, insets, physical keys, timer, and visible lifecycle.
* `HidController.java`: one visible session, HID SDP registration, connection state machine, callbacks, bounded timeouts, input serialization, report/release handling, and teardown.
* `KeyboardReport.java`: pure Java keyboard descriptor and command mapping.
* `Diagnostics.java`: bounded local diagnostic events.
* `app/src/main/res`: all layouts, day/night colors, styles, strings, icons, and ripple backgrounds.
* `tools/KeyboardReportTest.java`: executable wire-contract test without third-party dependencies.

## Troubleshooting

| Symptom | Check / action |
|---|---|
| Bluetooth Off | Use Turn on Bluetooth and accept Android's prompt. No silent adapter toggle is attempted. |
| Permission required | Allow Nearby Devices. If permanently denied, use App settings → Permissions. Retry after returning. |
| Unsupported / unavailable | No adapter, proxy request rejected, or HID service timeout. Restart Bluetooth, reboot once, check official firmware updates. A timeout is suggestive, not proof of permanent lack of support. |
| Registration rejected | Close other Bluetooth keyboard/gamepad/remote apps. Android permits only one registered HID app. Keep SlidePilot open, then retry. |
| Registration timeout/lost | Check that the screen is visible, unlocked, and not covered by another app. OEM lifecycle restrictions can interfere. Retry; copy diagnostics if repeatable. |
| Paired but cannot connect | Check Windows Bluetooth is on, disconnect other phone remotes, verify the selected host, retry. If stale HID discovery is suspected, remove the phone/laptop on **both** ends and pair again with SlidePilot registered. |
| Connect request rejected or timeout | Check Windows Bluetooth adapter drivers via the laptop manufacturer's official support, restart both radios, and retry. The 20-second timeout closes the HID registration to reject late callbacks. |
| Connected but no slide changes | Click into PowerPoint; verify keyboard shortcuts using the physical laptop keyboard. Try B in an empty Notepad file: one tap should enter one `b` and stop. Do not test against unsaved important content. |
| Key seems held | Disconnect SlidePilot or turn off Bluetooth on the phone; use the laptop keyboard to clear the relevant modifier/key, then reconnect. Gather diagnostics. Radio-level delivery cannot be guaranteed. |
| Volume keys change audio | Enable the toggle, focus SlidePilot, verify Connected. The firmware may reserve volume events; use on-screen buttons. Locked-phone operation is not implemented. |
| Another classmate cannot connect | Disconnect the prior phone first. Use one active phone remote per laptop. |

There is **no verified model-specific compatibility list** in this delivery. TECNO Smart 20, Realme C50, and Infinix Hot 50 are test targets, not claimed supported hardware. Even identical marketing names can have different chipsets/firmware. No app using only official APIs can enable an OEM-disabled HID Device implementation. A compatible phone or a physical presentation clicker is necessary in that case; this project does not silently switch to a companion application.

Local diagnostic commands (USB debugging, Windows PowerShell):

```powershell
adb devices
adb logcat -s SlidePilot:I '*:S'
adb shell dumpsys bluetooth_manager
adb shell dumpsys package com.slidepilot.app
```

Reproduce once from a fresh launch and note the sequence: profile service obtained → registration accepted → registration callback true → HID connected callback → command transmission. The app logs registration acceptance/callbacks and HID state/errors, not command content. The `registerApp` return value is not registration state. Version 1.0.1 retains the proxy even after a false return and waits up to 10 seconds for the app-status callback. A false callback or no callback is a failure. Review system dumps before sharing; unlike app logs, they may contain unrelated device identifiers.

## Test before classroom use

For **each physical phone/OS/firmware**, and for Windows 10 and 11 where available:

- [ ] Fresh install shows onboarding; controls remain disabled before a real connection.
- [ ] Deny Nearby Devices, deny permanently, grant in settings, revoke while connected: no crash, no enabled remote with stale state.
- [ ] Bluetooth off at startup; turn on; switch off while registering/connecting/connected; restore and reconnect.
- [ ] HID proxy unavailable/rejected/timeout gives actionable state; registration rejection and unsolicited unregistration disable commands.
- [ ] Register succeeds only after callback; close another HID app and verify retry.
- [ ] Pair through Windows; pair through Android; cancel pairing; test old pairing removal and re-pairing.
- [ ] Select correct laptop among multiple paired devices; reject a different host; unpair selected laptop and reconnect.
- [ ] No host answer times out; cancel while connecting; late callbacks do not falsely enable controls.
- [ ] Each of the six shortcuts works in desktop PowerPoint. Verify Shift releases: after Shift+F5 and Escape, one B in Notepad should be lowercase with Caps Lock off.
- [ ] Single taps, rapid double taps, and holding volume keys cause no runaway repetition. Test 100 sequential Next/Previous operations at ordinary presentation pace.
- [ ] Disconnect explicitly, reconnect, walk out of range, recover; disable Bluetooth on Windows and recover.
- [ ] Home, another app, pairing/settings activity, lock/unlock, incoming call, rotation, theme change, and process recreation do not leave an armed stale remote. Reconnect when returning.
- [ ] Timer start/pause/reset, background time, rotation restoration, and device clock changes behave correctly.
- [ ] Dark/light themes, 320dp and larger widths, 200% font, TalkBack, gesture navigation, three-button navigation, and display cutout remain usable.
- [ ] Disable Wi-Fi/mobile data while keeping Bluetooth on: full operation continues offline.
- [ ] Classmate handoff: disconnect one phone, connect another, verify commands affect only the intended laptop.
- [ ] Rehearse for 30–60 minutes with the actual laptop/projector arrangement before live use.

Record exact phone model, Android version, firmware/build number, Windows build, Bluetooth adapter/driver, registration result, connect result, and test outcome. Do not mark a device “supported” just because the APK installs.

## Official references

- [BluetoothHidDevice API](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- [HID callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice.Callback)
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [AGP 8.13 compatibility and patch versions](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

The bundled Gradle wrapper is from the official Gradle v8.13.0 repository. Its license is included in `gradle/GRADLE-LICENSE.txt`.
