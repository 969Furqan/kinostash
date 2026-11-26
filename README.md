# Kinostash

**Watch and download your Stremio addon streams on Android, including torrents, and keep them for offline viewing.**

Kinostash is an independent, open-source Android player for the [Stremio addon](https://github.com/Stremio/stremio-addon-sdk)
ecosystem (Cinemeta, Torrentio, OpenSubtitles, …). It plays streams through **Stremio's own streaming server**, so streams
start and buffer like the official app, and adds what the official app lacks: **real offline downloads**.

> Not affiliated with Stremio. Only stream and download content you have the right to.

## Features

- **Browse & search** every catalog from your addons; movie and series pages with seasons and episodes.
- **Streams from all your addons** in one list, sorted by quality and seeders, with quality filters.
- **Watch now**: torrents stream through Stremio's official streaming server running on the phone; direct (debrid) links play directly.
- **Download for offline**: one tap per stream. Downloads run in the background, resume after interruptions,
  pause automatically while you stream, and respect a **Wi-Fi only** switch. Grouped by series/movie and season.
- **Offline playback** with resume position. Picking a stream you've downloaded plays the local file.
- **Subtitles** from your subtitle addons, plus the video's own audio/subtitle tracks.
- **Continue watching** row (stored on the phone only).
- **Stremio account import** (optional): sign in once to import the addons you installed in Stremio.
- **Self-updating streaming server**: Kinostash follows the server version Stremio ships and rolls back automatically if an update fails.

## How it works

```
Kinostash (Kotlin, Jetpack Compose, Media3)
 ├─ data/       Stremio addon protocol client, addon list, Stremio account import, watch history
 ├─ server/     Runs Stremio's server.js on nodejs-mobile in its own process; auto-updates it
 ├─ downloads/  Download queue: reads the torrent through the local server into a file (Wi-Fi only, resume)
 ├─ player/     Media3 ExoPlayer: torrents via 127.0.0.1:11470, direct links, local files
 └─ ui/         Home, Search, Details, Streams, Downloads, Settings
node-launcher/  Tiny C launcher that runs libnode.so as a process (built with the Android NDK)
```

`server.js` is **not** in this repository: the app downloads it from Stremio's download server on first use
(the pinned baseline version is SHA-256 checked) and runs it locally. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build

Requirements: JDK 17+, Android SDK (platform 35).

```bash
./gradlew assembleDebug          # debug APK (app/build/outputs/apk/debug)
./gradlew assembleRelease        # release APKs; unsigned unless signing variables are set
```

`libnode.so` (nodejs-mobile v18.20.4) is downloaded and checksum-verified automatically during the build.

Release signing uses a private keystore passed through environment variables (never commit it):

| Variable | |
|---|---|
| `KINOSTASH_KEYSTORE` | path to the `.jks` file |
| `KINOSTASH_KEYSTORE_PASSWORD`, `KINOSTASH_KEY_ALIAS`, `KINOSTASH_KEY_PASSWORD` | keystore credentials |

On GitHub, set repository secrets `KINOSTASH_KEYSTORE_BASE64` (base64 of the `.jks`) and the three variables above;
the **Build** workflow then publishes signed APKs as artifacts.

Rebuild the launcher (optional, binaries are committed): `NDK=… NODE_LIBS=… node-launcher/build.sh app/src/main/jniLibs`.

## License

[MIT](LICENSE) © 2026 Furqan Azhar. Third-party components: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
