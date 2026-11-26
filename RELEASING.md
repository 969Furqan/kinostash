# Releasing a new version

1. Put the new version in `VERSION` (for example `0.3.1`). Versions must always increase.
2. Add a `## 0.3.1` section at the top of `CHANGELOG.md`; it becomes the release notes the app shows.
3. Commit and push to `main`.

The **Release** workflow notices that `v0.3.1` hasn't been released yet, builds signed APKs (one per CPU type
plus universal), writes `SHA256SUMS.txt`, and publishes the GitHub Release (creating the `v0.3.1` tag).
Installed apps see it under Settings → App updates and in their daily check.

The version code is derived from the version (`1.2.3` → 10203). Signing uses the repository secrets
`KINOSTASH_KEYSTORE_BASE64`, `KINOSTASH_KEYSTORE_PASSWORD`, `KINOSTASH_KEY_ALIAS` and `KINOSTASH_KEY_PASSWORD`;
the app refuses updates signed with any other key.
