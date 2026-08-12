# Caller Info Test

Enhanced Android Caller ID application based on the original `rakibulcodes/caller-info` project.

## About This Fork

This fork preserves the original Telegram/TDLib caller-lookup concept while adding substantial reliability, privacy, localization and usability improvements.

Original project: `rakibulcodes/caller-info`

The original project established the core idea of using TDLib with a Telegram caller-lookup bot. The changes in this fork are additional work on top of that foundation and should not be attributed to the original author.

## Current Release

Current validated testing release: [`v1.1.0-test.15`](https://github.com/belgareth/caller-info/releases/tag/v1.1.0-test.15)

Physical-device, OEM, lock-screen and cellular-call behavior testing is continuing after the emulator/software validation gate.

See all published builds on the [GitHub Releases page](https://github.com/belgareth/caller-info/releases).

## Major Changes in This Fork

- Local/national number normalization to E.164-style lookup numbers.
- Configurable calling code, local prefixes and national number length.
- Optional acceptance of national numbers without a prefix.
- Immediate caller presentation with deferred remote enrichment.
- Strict Telegram response correlation.
- Temporary TDLib outgoing message ID to server message ID remapping.
- Edited Telegram response handling.
- Rejection of unverified, stale and intermediate/progress responses.
- Successful-result caching with configurable freshness windows.
- Force Refresh to intentionally bypass fresh cache.
- Encrypted Room caller cache.
- Android Keystore-backed encryption.
- HMAC/non-reversible local lookup identifiers.
- Secure v3 to v4 encrypted-cache migration and legacy plaintext privacy cleanup.
- History search.
- Favorites and Recent History filtering.
- Aliases and private notes.
- Dial, Copy, Share and Save Contact actions.
- Optional Contacts permission behavior.
- Modern `TelephonyCallback` handling on supported Android versions.
- Safer post-call enrichment isolation.
- Expanded App Health checks.
- Full-screen intent status reporting.
- Compact and Expanded caller-card modes.
- Upper, Center and Lower caller-card positioning.
- SIM/account-friendly labeling where Android exposes the relevant information.
- Import/export support.
- Safer Telegram Disconnect confirmation.
- Removed a redundant History Clear control while preserving Lookup Clear.

## How Lookup Works

The lookup flow is:

1. An incoming or manually entered number is normalized.
2. The app checks the encrypted local caller cache.
3. If a remote lookup is needed, the app sends the actual normalized number to the Telegram bot through TDLib.
4. TDLib responses are strictly correlated to the active request.
5. Intermediate/progress responses are ignored until a verified final response is available.
6. Useful identity, carrier and country data is parsed.
7. The result is displayed and saved back to the encrypted cache.

HMAC and encrypted lookup identifiers are local persistence mechanisms only. They are not substituted for the real normalized number in Telegram lookup requests.

## Privacy / Security

- Caller cache data is encrypted at rest.
- Database lookup identifiers are non-reversible.
- Aliases and private notes are local user metadata and are stored inside the encrypted caller persistence design.
- The app avoids SMS/MMS permissions.
- Dial actions use `Intent.ACTION_DIAL` rather than directly placing calls.
- Public notification and caller-card content should remain appropriately limited.
- Users remain responsible for complying with applicable privacy laws, carrier rules and Telegram terms.

These measures reduce exposure risk, but they are not a claim of absolute security.

## Permissions

Typical app functionality may involve:

- Phone state / call screening permissions for incoming-call caller ID behavior.
- Call log access where Android allows recent-call context.
- Overlay permission for floating caller cards.
- Notification permission on Android versions that require it.
- Full-screen intent status where applicable.
- Internet/network access for Telegram/TDLib communication.
- Contacts permission is optional and is used only for contact-related behavior such as saving or resolving contacts.

The app does not require SMS or MMS permissions.

## Build

1. Clone the repository.
2. Open it in Android Studio.
3. Ensure the Android SDK and Gradle wrapper can resolve the configured dependencies.
4. Build with:

   ```powershell
   .\gradlew.bat assembleDebug --no-daemon --console=plain
   ```

5. Run JVM tests with:

   ```powershell
   .\gradlew.bat testDebugUnitTest --no-daemon --console=plain
   ```

Telegram API credentials must be supplied through the app's normal configuration flow or another secure local mechanism. Do not commit Telegram API credentials, phone numbers, OTPs, signing keys or local machine paths.

## Testing Status

For `v1.1.0-test.15`:

- JVM tests: 169/169 PASS.
- Android debug build: PASS.
- Emulator/software validation completed.
- Live Telegram lookup proven.
- Cache reuse and Force Refresh proven.
- test.14 to test.15 in-place upgrade proven.
- Telegram authenticated session survived upgrade.
- Encrypted cache migration proven.
- No Caller Info crashes/ANRs observed during the completed emulator gate.

Physical cellular-call, lock-screen/OEM and dual-SIM behavior testing is still in progress on real devices.

## Credits

This repository is a fork containing additional work based on:

`rakibulcodes/caller-info`

Credit remains due to the original project and author for the initial application concept and implementation foundation.

## License

This fork preserves the existing MIT license status. Follow the license terms and preserve required attribution notices when using or distributing this code.
