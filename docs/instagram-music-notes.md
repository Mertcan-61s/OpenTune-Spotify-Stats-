# Instagram Music Notes on Android

## Phone-only trial

The 1.1.4 preview includes a foreground, single-submission trial using the Instagram website. See the [Turkish phone guide](instagram-phone-trial.md).

## Experimental automatic sharing

The automatic setting uses OpenTune's background playback service and a personal Notes bridge. It attempts to publish matching audio to Close Friends after stable playback, even with the player screen closed. The bridge must remain reachable. A manual-sharing preference from an earlier APK never enables automatic publishing. See the [setup and verification guide](../services/instagram-notes/README.md).

## Manual sharing

1. In OpenTune, enable **Settings → Account & integrations → Connected services → Instagram Music Notes**.
2. Play a track and open **Now Playing → ⋯ → Instagram Music Notes**. The sheet shows the title and artist after three seconds of stable playback.
3. Tap **Copy and open Instagram**. In Instagram's inbox, create a Note, tap **Music**, and paste the copied search text. Select the recording, choose the audio clip and audience, and share it in Instagram. Catalog availability depends on the account and region. If Android cannot open Instagram, the search text remains on the clipboard.

## Publishing and account security

Meta's public Instagram platform has no documented endpoint for searching the licensed Notes catalog or publishing music Notes. Instagram and Spotify offer their own connected Spotify listening feature. OpenTune can supply a search query, and the Instagram app handles selecting the recording, sharing the Note, and playing its audio. OpenTune cannot verify whether a Note was shared, detect which catalog recording was selected, or update a published Note on a track change. [Meta publishing documentation](https://developers.facebook.com/documentation/instagram-platform/content-publishing) · [Spotify's Instagram Notes integration](https://newsroom.spotify.com/2025-08-21/spotify-takes-instagram-sharing-to-the-next-level-with-audio-previews-and-real-time-listening-notes/)

Unofficial endpoints can change without notice. Using them can trigger login challenges or account restrictions, and giving an integration a session cookie can expose the account. The manual action uses Android's launcher for the official Instagram app and writes only the title and artist to the system clipboard after a tap. The clipboard is marked sensitive to hide its preview on Android versions that support it. Instagram sign-in stays in Instagram.

## Playback and verification

The manual setting is off by default. While the sheet is open in the foreground, it observes the existing Media3 connection. Pauses, disconnections, and track changes clear the old suggestion. Rapid skips cancel the pending suggestion. The button rechecks the track against live playback before it copies anything. Opening the sheet is the only action that starts observing playback for this feature. The manual action runs only while its sheet is visible.

The **Spotify import Android APK** workflow runs the Notes, Spotify history and launcher regression tests and publishes a disposable smoke-test APK. To run the Notes checks locally, configure JDK 21, Android SDK Platform 37.0 (`platforms;android-37.0`), build-tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1, then run these commands.

```sh
./gradlew :core:testDebugUnitTest --tests '*SpotifyHistoryParserTest' --tests '*AccountHistoryMergeTest' --tests '*SettingsDefaultsTest'
./gradlew :app:testDebugUnitTest --tests '*MusicNoteTrackTest' --tests '*InstagramNoteHandoffTest' --tests '*SettingsLayoutTest'
./gradlew :app:assembleDebug -PuniversalOnly=true
```

For end-to-end verification on a phone, try rapid track changes, pause and resume, and repeat with Instagram uninstalled. To verify a playable Note, choose a catalog track and share it to a test audience in Instagram. Confirm playback from a second account that can see the Note. Compare cold launch time, frame timing and peak memory with the base build on the same device to measure any performance impact.

## Stable Spotify Stats releases

The stable Android package is `com.opentune.spotifystats`. Versions start at 1.1.2 (version code 49). Each release uses the permanent signing certificate pinned in `scripts/sign-stats-apk.sh`. Increase the app's version and version code before publishing a new release. The app's update checker reads stable releases from this fork.

Use **Spotify import Android APK → stable-signed** with the repository's `STATS_RELEASE_KEYSTORE_BASE64` and `STATS_RELEASE_KEYSTORE_PASSWORD` Actions secrets. The signing script accepts only an optimized Stats APK and the pinned signing certificate. It emits the APK, its checksum and its release metadata. Attach those three files to the matching GitHub release.

For signing outside GitHub, run the same workflow with `stable-candidate` to test and download the unsigned APK. On a trusted computer with JDK 21 and Android build-tools 36.0.0, set `OPENTUNE_STATS_KEYSTORE` and `OPENTUNE_STATS_PASSWORD_FILE` to the private recovery file paths, then run `bash scripts/sign-stats-apk.sh app-release-unsigned.apk signed-release`. Preserve the encrypted keystore and its password in separate private backups. The signing key is required for future Android updates.

## Update a legacy installation

Version 1.1.2 carries forward the Spotify history changes in 1.1.1. Android requires the same application ID and signing identity for an in-place update. The signed update workflow also requires a greater version code. The debug smoke-test artifact uses a temporary runner key.

In **Spotify import Android APK → Run workflow**, select the installed build. Each choice identifies a specific verified Actions artifact.

| Choice | Application ID | Previous APK |
| --- | --- | --- |
| `stats-1.1.0` | `com.opentune.spotifystats` | [Spotify Stats 1.1.0](https://github.com/Mertcan-61s/OpenTune-Spotify-Stats-/actions/runs/43702239829/artifacts/14157331982) |
| `stats-1.1.1` | `com.opentune.spotifystats` | [Spotify Stats 1.1.1](https://github.com/Mertcan-61s/OpenTune-Spotify-Stats-/actions/runs/43703942948/artifacts/14158021236) |
| `preview-1.1.1` | `com.opentune.spotifystats.preview` | [Stats Preview 1.1.1](https://github.com/Mertcan-61s/OpenTune-Spotify-Stats-/actions/runs/43703942948/artifacts/14158520868) |

Configure the original private signing key using the repository's `OPENTUNE_KEYSTORE_BASE64`, `OPENTUNE_KEYSTORE_PASSWORD`, `OPENTUNE_KEY_ALIAS`, and `OPENTUNE_KEY_PASSWORD` Actions secrets. The workflow compares its public certificate with the installed build's signer before building. After signing, it checks the APK's certificate, package name and version code before uploading it. An APK contains the public certificate. It cannot recover a private key generated by a discarded GitHub Actions runner.
