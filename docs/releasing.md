# Releasing

How an OpenBridge build reaches people. The pipeline is the one OpenVitals and OpenWatch use,
with the same scripts under other names; when one learns something, the others should too.

## What runs when

| Event | Workflow | What it does |
| --- | --- | --- |
| A pull request, a push to `main` | `verify.yml` | The gate: unit tests, the debug build, the documents' links; lint on a push to `main`. Locally: `./gradlew verifyCi`. |
| 01:00 UTC every day, or a manual run with target `nightly` | `release.yml` | A nightly: signs the APK, the debug APK and the app bundle once, moves the `nightly` tag, and replaces the assets of the `nightly` GitHub prerelease. A scheduled run with nothing new since the last nightly or release does nothing. |
| A pushed `vX.Y.Z` tag | `release.yml` | A version: the same one build, a GitHub prerelease, then a wait for the `production` environment's approval. Approved, the prerelease becomes the stable release and the notes go to Zulip. |
| A manual run with target `production`, started from a tag | `release.yml` | The fallback for a tag whose run is gone: it takes the app bundle from the tag's prerelease, checks its sha256, and continues at the approval. |

A release is only cut from a commit that passed `verify.yml` on its push to `main`. The release
run waits for that run when it is still going, and stops when there is none or it failed.

## Cutting a version

1. Write `docs/releases/X.Y.Z.md` (it becomes the tag message, and so the GitHub release notes and
   the Zulip post), move the "Unreleased" entries of `CHANGELOG.md` under `## X.Y.Z - YYYY-MM-DD`,
   and write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (490 characters at
   most). The version code is `sh scripts/version-code.sh next --floor <baseVersionCode>`.
2. `bash scripts/release.sh X.Y.Z`. It sets `baseVersionCode` and `baseVersionName` in
   `app/build.gradle.kts`, commits `chore: release X.Y.Z`, tags it and pushes `main` and the tag
   together. Check the version code it prints is the one in the changelog's file name.
3. Approve the `production` environment on the tag's run (Actions, the run, Review deployments).

## Version codes

`versionCode` is a counter, independent of the version name, and nightlies and versions share it:
a nightly takes the next free one, and a version is given the next free one by
`scripts/release.sh`. The counter's memory is the `<!-- OpenBridge-Version-Code: N -->` marker in
each GitHub release's notes and the append-only `refs/version-code/N` refs
(`scripts/version-code.sh`, `scripts/move-nightly-tag.sh`), so a deleted release cannot rewind it.
A code that went backwards would be an APK Android refuses to install over the one a user has.

## What the repository needs

**Secrets**

- `OPENBRIDGE_RELEASE_KEYSTORE_BASE64`: the release keystore (PKCS12), base64 on one line.
- `OPENBRIDGE_RELEASE_STORE_PASSWORD`: its password. A PKCS12 store has one password for the
  store and its key.
- `OPENBRIDGE_RELEASE_KEY_ALIAS`: the key's alias.
- `ZULIP_BOT_EMAIL`, `ZULIP_BOT_API_KEY`: the bot that posts to the shared `releases` channel.
  Without them a version is released and not announced.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64`: only for Google Play, below.

The keystore is OpenBridge's own: an app's signing key is its identity, and every later version
must be signed with it. Keep a copy outside GitHub. To make one:

```
keytool -genkeypair -storetype PKCS12 -keystore openbridge-release.p12 -alias openbridge \
    -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=OpenBridge, O=MTU Hale, C=EE"
base64 -w0 openbridge-release.p12
```

**Variables**

- `OPENBRIDGE_VERTMATCH_CLIENT_ID`: the client id VertMatch issued. Without it the build shows
  VertMatch as "not set up". `OPENBRIDGE_VERTMATCH_REFRESH_TOKENS=true` once VertMatch agreed
  refresh tokens.
- `OPENBRIDGE_PLAY_ENABLED=true`: turns the Google Play uploads on. See below.

**Environment**

- `production`, with at least one required reviewer. Without a reviewer the approval passes at
  once and a tag goes straight to a stable release.

## Google Play

Off until `OPENBRIDGE_PLAY_ENABLED` is `true`: OpenBridge has no Play listing yet, and an upload
would only fail. With it on, a nightly and a version's first build go to open testing, and the
approved version is promoted to production with the store text in `fastlane/metadata/android/`.
Set the variable back to anything else to hold the uploads while a release sits in Play review.

Before turning it on: create the listing for `tech.mmarca.openvitals.bridge` in Play Console,
upload a first bundle by hand (Play's API cannot create an app), and give the service account
access to it.
