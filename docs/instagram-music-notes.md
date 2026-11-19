# Instagram Music Notes on Android

1. In OpenTune, enable **Settings → Account & integrations → Connected services → Instagram Music Notes**.
2. Play a track and open **Now Playing → ⋯ → Instagram Music Notes**. The sheet shows the title and artist after three seconds of stable playback.
3. Tap **Copy and open Instagram**. In Instagram's inbox, create a Note, tap **Music**, and paste the copied search text. Select the recording, choose the audio clip and audience, and share it in Instagram. Catalog availability depends on the account and region. If Android cannot open Instagram, the search text remains on the clipboard.

## Publishing and account security

Meta's public Instagram platform has no documented endpoint for searching the licensed Notes catalog or publishing music Notes. Instagram and Spotify offer their own connected Spotify listening feature. OpenTune can supply a search query, and the Instagram app handles selecting the recording, sharing the Note, and playing its audio. OpenTune cannot verify whether a Note was shared, detect which catalog recording was selected, or update a published Note on a track change. [Meta publishing documentation](https://developers.facebook.com/documentation/instagram-platform/content-publishing) · [Spotify's Instagram Notes integration](https://newsroom.spotify.com/2025-08-21/spotify-takes-instagram-sharing-to-the-next-level-with-audio-previews-and-real-time-listening-notes/)

Unofficial endpoints can change without notice. Using them can trigger login challenges or account restrictions, and giving an integration a session cookie can expose the account. This feature uses Android's launcher for the official Instagram app and writes only the title and artist to the system clipboard after a tap. The clipboard is marked sensitive to hide its preview on Android versions that support it. Instagram sign-in stays in Instagram.

## Playback and verification

The setting is off by default. While the sheet is open in the foreground, it observes the existing Media3 connection. Pauses, disconnections, and track changes clear the old suggestion. Rapid skips cancel the pending suggestion. The button rechecks the track against live playback before it copies anything. Opening the sheet is the only action that starts observing playback for this feature. There is no background work, polling, or Instagram network client in OpenTune.

The **Spotify import Android APK** Actions workflow runs the Notes and Spotify regression tests and publishes the universal debug APK as `OpenTune-Spotify-Stats-debug-apk`. To run those checks locally, configure JDK 21, Android SDK Platform 37.0 (`platforms;android-37.0`), build-tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1, then run these commands.

```sh
./gradlew :core:testDebugUnitTest --tests '*SpotifyHistoryParserTest' --tests '*AccountHistoryMergeTest' --tests '*SettingsDefaultsTest'
./gradlew :app:testDebugUnitTest --tests '*MusicNoteTrackTest' --tests '*InstagramNoteHandoffTest' --tests '*SettingsLayoutTest'
./gradlew :app:assembleDebug -PuniversalOnly=true
```

For end-to-end verification on a phone, try rapid track changes, pause and resume, and repeat with Instagram uninstalled. To verify a playable Note, choose a catalog track and share it to a test audience in Instagram. Confirm playback from a second account that can see the Note. Compare cold launch time, frame timing and peak memory with the base build on the same device to measure any performance impact.
