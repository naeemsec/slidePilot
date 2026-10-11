# 1.0.1 — HID registration lifecycle correction

- Do not treat the return value of registerApp as final registration status. Wait for onAppStatusChanged, including when the immediate return is false.
- Keep registration pending for at most 10 seconds; no controls are enabled until actual callbacks/state checks confirm readiness and connection.
- Preserve the HID proxy while waiting for pending registration and unregistration callbacks.
- Serialize cleanup and replacement registration across Activity instances; prevent rapid Retry overlaps.
- Block fresh registration if cleanup times out; explain how to reset Bluetooth instead of claiming success.
- Register initially from the resumed Activity, rather than onStart.
- Hide redundant retry while already registered.
- Disconnect only an established host during teardown, eliminating the selected-only Unknown device cleanup request.
- Version code 2; version name and diagnostic header 1.0.1.

## First test after updating

Close the old app, install the new APK, turn phone Bluetooth off and on once to clear the old session, then open SlidePilot unlocked and wait up to 10 seconds. If HID Ready appears, select the laptop and tap Connect; do not press Retry. If it fails, copy the new in-app diagnostics, which now include callback and cleanup events. Registration success does not itself guarantee Windows connection or command delivery.

Build and wire-format tests passed. TECNO hardware verification remains pending.
