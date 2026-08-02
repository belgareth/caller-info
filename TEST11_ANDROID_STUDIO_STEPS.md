# Caller Info Test 1.1.0-test.11 — Android Studio validation

This source is based on `1.1.0-test.10` and adds immediate caller presentation plus deferred lookup when connectivity returns.

## Build environment

Use the same Windows account and Android Studio installation that built the earlier comparison APKs. Android's normal debug signing configuration should then reuse the existing debug keystore, allowing this build to update the installed test version.

## Run from the project root

```powershell
.\build-test11.ps1
```

The script runs JVM tests, runs connected instrumentation tests when an emulator/device is available, builds the debug APK, and writes release assets under `release-assets`.

Equivalent manual commands:

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

## Emulator checks

1. Install test.11 over test.10 without uninstalling.
2. Confirm existing settings, login state, and cached caller data remain.
3. Enable Live Caller ID and the display-over-other-apps permission.
4. Trigger the controlled caller-card preview and confirm it appears.
5. Test the incoming-call path while unlocked. The number and `Looking up caller…` should appear before remote lookup completes.
6. Disable network connectivity and trigger a lookup for a number without saved caller data. Confirm the card reports that lookup will retry later.
7. Re-enable connectivity. WorkManager should process the pending lookup without requiring a permanent service.
8. Open Settings and confirm `Pending caller lookups` decreases after successful durable save.
9. Test `Retry pending lookups` while connected and while offline.
10. Lock the emulator and run the existing locked-presentation instrumentation test. Locked presentation must remain redacted.
11. Confirm no SMS permission is requested.

## Physical Pixel 7 checks still required

Emulator tests cannot prove live carrier call-screening, system-dialer layering, or real bot response timing. Before daily use, test real incoming calls while unlocked and locked, rapid consecutive calls, call-end cleanup, offline queueing, and lookup completion after connectivity returns.
