# SlidePilot 1.0.1

Offline Bluetooth presentation remote for Windows PowerPoint. Java + native XML, API 28–36, no laptop companion, accounts, analytics, or internet permission. The source is complete; real phone/Windows qualification is required before relying on it in class. See `VALIDATION.md` for what was actually verified.

## Features
- Next / Previous slide
- Start from beginning / Start from current slide
- Black screen toggle and End show
- Volume keys as Next / Previous
- Built-in presentation timer
- Local-only diagnostics, no internet permission, no data collection

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

1. Turn Bluetooth on on both devices.
2. Open SlidePilot, finish onboarding, allow **Nearby Devices**, and wait for **HID Ready** or **Not Paired**.
3. Tap **Pair a new laptop → Make discoverable**. Advertising permission is requested only for this action. Keep SlidePilot visible while pairing from Windows.
4. Windows 11: **Settings → Bluetooth & devices → Add device → Bluetooth**. Windows 10: **Settings → Devices → Bluetooth & other devices → Add Bluetooth or other device → Bluetooth**.
5. Select the phone; Windows display the phone's Bluetooth name. Confirm matching codes on both devices. SlidePilot does not intercept or bypass Android's pairing dialog.
6. Return to SlidePilot, tap **Choose paired laptop**, select your Windows machine, then **Connect**. The chooser shows all paired devices because device-class labels are not a reliable way to identify Windows. Select the laptop, not a headset.
7. Wait for **Connected**. “Paired” in SlidePilot app.
8. Focus PowerPoint on the laptop. Try Start, Next, Previous, Black Screen, and End before presenting.

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

## Official references

- [BluetoothHidDevice API](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
- [HID callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice.Callback)
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [AGP 8.13 compatibility and patch versions](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

The bundled Gradle wrapper is from the official Gradle v8.13.0 repository. Its license is included in `gradle/GRADLE-LICENSE.txt`.
