# Instagram Music Notes integration (experimental)

## Status
This branch contains a **non-functional integration foundation**, not a working Instagram music-note publisher. It is intentionally disabled by default. No credentials are requested, stored, or transmitted.

## Why not ship a publisher yet?
Instagram's public Graph API does not provide a supported endpoint to publish music Notes. Unofficial private APIs can change without notice and may cause login challenges or account restrictions. A working demonstration on a throwaway test account and an explicit security review are prerequisites before enabling a publisher. The Spotify Instagram integration is not a general third-party music-note API.

## Architecture
- `MusicNoteTrack` carries title, artist and optional ISRC.
- `MusicNoteSyncCoordinator` deduplicates consecutive published songs and enforces a 15-minute minimum between attempts.
- `MusicNotePublisher` is a suspendable boundary for a future verified implementation.
- The feature must be explicitly enabled by the user and must run off the playback/UI thread.
- No Python runtime should be bundled into the Android app; a remote bridge would require explicit consent, secure token handling and separate maintenance.

## Before release
1. Confirm Instagram music catalog search and real playable Note publishing on a test account.
2. Verify update/delete behavior and rate limits; do not claim a 15-minute interval is safe.
3. Implement secure authentication and revoke/logout, never persist raw passwords.
4. Add settings UI, player event wiring, network handling, error visibility, and tests.
5. Benchmark cold start, playback FPS, RAM, CPU, battery, and request count against the existing app.
6. Run Android build and instrumentation tests before generating a distributable APK.

This branch is **not ready to merge or distribute**.
