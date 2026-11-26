# Releasing a new version

1. Add a section for the version at the top of `CHANGELOG.md` (these become the release notes the app shows).
2. Commit, then tag and push:
   ```bash
   git tag v0.3.1
   git push origin main v0.3.1
   ```
3. The **Release** workflow builds signed APKs (one per CPU type plus universal), writes `SHA256SUMS.txt`
   and publishes a GitHub Release. Installed apps see it under Settings → App updates (and in their daily check).

The version code is derived from the tag (`v1.2.3` → 10203), so tags must always increase.
Signing uses the repository secrets `KINOSTASH_KEYSTORE_BASE64`, `KINOSTASH_KEYSTORE_PASSWORD`,
`KINOSTASH_KEY_ALIAS` and `KINOSTASH_KEY_PASSWORD`. Releases signed with any other key are rejected by the app.
