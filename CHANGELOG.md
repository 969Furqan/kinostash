# Changelog

## 0.7.1

- Videos (especially downloads) start much faster. The player used to wait for the subtitle addons and then download up to 40 subtitle files one by one before playing anything; offline this could take a long time. Playback now starts immediately.
- Subtitles in your default language are fetched in the background and added a moment later, only if the video doesn't already have them built in.
- Other languages are listed in the CC menu under "More from addons"; tap one to download and switch to it.

## 0.7.0

- Fixed watched marks disappearing after playing an episode. History was capped at 300 entries, so long series (One Piece has 1100+ episodes) lost the oldest marks. Watched marks are now never dropped. Marks already lost need to be set again once (long-press → Mark all before this as watched).
- Episode list: **filter** (All, Not watched, Watched, Downloaded, Not downloaded) and **sort** (episode number either way, newest/oldest, unwatched first, title). Your choice is remembered.
- Player: **swipe sideways to seek** — slide left/right anywhere, see the target time, release to jump. Toggle in Settings → Player → Gestures.
- Player: the subtitle style panel is wider and readable, hides the player controls while open, and tapping the video closes it.

## 0.6.2

- The download button next to the seasons now opens a menu: **Next unwatched episodes…** (pick how many: 1, 3, 5, 10, any number with −/+, or All; continues after your last watched episode, across seasons), **Unwatched in season**, **Whole season**, and **Select episodes…**. The same options are in the season menu (⋮).
- Episodes that are already downloaded or queued are skipped automatically.

## 0.6.1

- Fixed "Couldn't check for updates: HTTP 403". GitHub's API allows only 60 checks per hour per network (often shared on mobile data); when it refuses, Kinostash now checks the GitHub releases page directly instead, still verifying the download's checksum and signature.

## 0.6.0

- Download many episodes at once: long-press an episode → **Select**, tick as many as you like (across seasons too, or **Select season**), then **Download**. They join the queue and download two at a time; the rest wait their turn.
- **Download season** button next to the season chips, plus **Download all seasons** in the season menu (⋮). Queuing keeps going if you leave the screen.
- "Play best" and "Download best" now pick the stream with the **most peers** by default (fastest to start, least buffering). Prefer resolution? Choose **Highest quality** or a specific quality in Settings → General.

## 0.5.3

- Subtitles keep their size when you switch the video between Fit, Zoom (crop) and Stretch.
- New subtitle style panel in the player (CC → Style): size, text colour, background, outline, vertical and horizontal position, and text width (squeeze or stretch). Changes show live over the video and are saved as your defaults.
- Settings → Subtitles also has the new horizontal position and text width options.

## 0.5.2

- Updates: if the install dialog didn't appear or you closed it, tap **Install again**. The downloaded update is kept (even after restarting the app), so it isn't downloaded twice. "Check now" also works while an install is pending.
- A cancelled or failed install now goes back to "Install" instead of losing the download.

## 0.5.1

- Fixed "Playback error: ERROR_CODE_FAILED_RUNTIME_CHECK" on some releases (MKV files with Blu-ray/DVD image subtitles). The player now decodes those subtitles, and if a file's subtitles still can't be handled it automatically continues without them instead of stopping.
- Playback errors now show the underlying reason, so reports are easier to diagnose.

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
