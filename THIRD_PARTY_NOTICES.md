# Third-party components

| Component | How it is used | License |
|---|---|---|
| [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) `libnode.so` v18.20.4 | Downloaded at build time (SHA-256 pinned) and bundled in the APK | MIT (Node.js license) |
| Android NDK `libc++_shared.so` | Bundled C++ runtime needed by `libnode.so` | Apache-2.0 with LLVM exception |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer) | Video player | Apache-2.0 |
| Jetpack Compose, AndroidX, Kotlin coroutines | UI and app framework | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | HTTP client | Apache-2.0 |
| [Coil](https://github.com/coil-kt/coil) | Image loading | Apache-2.0 |
| [Timber](https://github.com/JakeWharton/timber) | Logging | Apache-2.0 |

**Not included in this repository or the APK:** Stremio's streaming server (`server.js`). Kinostash
downloads it at runtime from Stremio's own download server (`dl.strem.io`) and runs it on the device,
the same way Stremio's desktop apps obtain it. It remains subject to Stremio's terms.

Kinostash is not affiliated with or endorsed by Stremio. Addons are third-party services chosen by the user.
