# Release checklist

Things that are easy to forget, roughly in the order they bite.

## Decide once, before the first public build (can't change later)

- [x] **Android package id:** `io.github.docdphd.puffble`. Changing it after
      people install means they must uninstall and reinstall, and the watch app's
      `companionApp.android.apps[].package` must match it.
- [ ] **Watch app UUID** (`1b43e005-bf25-4e85-909e-80da9a074ece`). The store identifies the app
      by it and the companion only talks to this UUID. Never change it.
- [ ] **Release signing key.** Create it once, back it up in two places. If it's lost, existing
      users can't install updates over their copy. Keep it out of git (`*.jks` is ignored).

## Every release

- [ ] Bump `versionCode` / `versionName` (companion) and `version` (watch `package.json`) together.
- [ ] Update `CHANGELOG.md`.
- [ ] Build a **signed release** APK (not the debug one) and the `.pbw`.
- [ ] Test on each watch shape you list (round and rectangular), with the Puffco app closed:
      connect, change profile, start, stop, a full session to "All done", and reopening mid-session.
- [ ] Tag the commit `vX.Y.Z` and attach the APK to a GitHub release.

## Pebble app store listing

- [ ] `companionApp.android.url` in `watch/package.json` points at
      `https://github.com/DocDPhD/puffble/releases/latest`, so the Pebble app can send people there.
- [ ] Icons: `store/icon_144.png` (large), `store/icon_48.png` (small); banner
      `store/banner_720x320.png`.
- [ ] Screenshots for each platform you support (take them from the Pebble app's
      Devices → ⋮ → Screenshot, or the emulator).
- [ ] Description that says the Android companion is required, links to the releases page, and
      states that Puffble isn't affiliated with Puffco.
- [ ] Source code link, privacy policy link (`PRIVACY.md`), support link (GitHub issues).
- [ ] Check the store's content rules for apps that control vaporizers before submitting.

## Repository hygiene

- [ ] Only the allow-listed folders in `.gitignore` are committed. Never commit captures, bug
      reports, decompiled apps, keystores, `local.properties`, or anything naming your own
      device (Bluetooth address, device name, serials).
- [ ] License and credits are current (PuffcoBLE is MIT and needs its notice kept).
