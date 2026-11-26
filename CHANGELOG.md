# Changelog

## 0.5.0
**Stremio account**
- Create a Stremio account right in Kinostash (Settings → Stremio account → Create account).
- Signing in or signing up adds Torrentio, OpenSubtitles and Cinemeta (and the addons you already use here)
  to your Stremio account, then imports the account's addons.
- Your library syncs both ways: titles you add or remove in Kinostash update your Stremio library, titles in
  Stremio are imported, and titles removed in Stremio disappear here when the app starts. "Sync now" any time.

## 0.4.0
**Player**
- Double-tap the left/right side to skip; keep tapping to skip further (step and maximum in Settings → Player).
- Swipe up/down on the left for brightness and on the right for volume.
- Buffering now shows in the middle of the screen with the peer count and speed, and a play/pause button.
- Change the source (torrent) while watching; it continues from the same moment.
- Subtitle styling: default language, size, text colour, background, outline, position and delay (also adjustable while watching).
- Audio track picker, playback speed, fit/zoom/stretch, "Resumed at … · Start over".
- Open streams and downloads in VLC, MX Player, mpv or Just Player (Settings → Player).

**Titles and episodes**
- Episodes and movies show when they're downloaded (and download progress); tapping a downloaded one plays it straight away.
- Long-press an episode, a season's menu or a title to mark it watched / not watched, download it or add it to a category.
- "Download season" and "Download best" pick your preferred quality (falling back to the next best) with the most seeders.
- Movies show their entry (progress, watched, downloaded), director and cast. IMDb ratings on posters.

**App**
- Search bar at the top of Home (the separate Search tab is gone); recent searches as chips.
- Library: your own categories, and a History tab with watch and search history.
- Settings are organised into categories; choose which page the app opens on and your preferred quality.
- About lists the developer and contact.

## 0.3.1
- **Update settings**: Settings → App updates is now at the top, with "Check now", the last-checked time,
  automatic checks (every launch / daily / weekly), update notifications even when the app is closed,
  and optional automatic download on Wi-Fi so installing is one tap.
- Link to the release notes and all versions.

## 0.3.0
- **In-app updates**: Settings → App updates → "Check for updates" downloads and installs new versions
  from GitHub Releases. Kinostash also checks once a day and asks when an update is available.
  Updates are verified (SHA-256 checksum and the Kinostash signing key) before installing.

## 0.2.1
- Fixed a crash when searching.

## 0.2.0
- Library, upcoming-episode calendar, new-episode notifications, Stremio library import.
- Crash reporter.

## 0.1.0
- First release: browse, search, stream through Stremio's streaming server, offline downloads.
