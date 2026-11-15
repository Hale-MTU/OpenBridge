# OpenBridge

OpenVitals is offline: it has no internet permission. OpenBridge is a separate app, published by [Hale](https://hale.ngo) like OpenVitals, that reads the data OpenVitals shares and can reach the internet, so it can pass that data on to other services. The first service is [VertMatch](#vertmatch).

## How it gets data

OpenVitals shares data through a read-only content provider, split into 11 groups: activity, workouts, routes, sleep, heart, vitals, body, nutrition, recovery, cycle and sexual activity.

1. In OpenVitals, turn on Settings, Share with other apps. It starts off.
2. In the bridge, tap **Ask for all groups**, or **Ask** on one group. Android shows its own dialog for each group. Change a group later in Android's settings for the bridge.
3. Tap **Read** on a group, or **Read everything**.

The bridge reads, per group:

- **daily:** the last 7 days
- **samples:** the last 24 hours
- **sessions:** the last 30 days (workouts, sleep and mindfulness)
- **routes:** the latest 3 workouts of the last 30 days; this also needs the workouts group

Every answer carries a status. `sharing_off` means the switch in OpenVitals is off. `background_read_missing` means OpenVitals may not read Health Connect while it is closed, so only its own data comes through. `partial` means some reads failed, but the rows that came back are right.

## VertMatch

[VertMatch](https://vertmatch.run) is a training service for runners. Through its [partner API](https://vertmatch.run/developers), the bridge can send a workout to your VertMatch training and show your VertMatch training summary.

1. On the bridge's first screen, tap **Open VertMatch**, then **Connect VertMatch**. Sign-in happens in your browser, at `auth.vertmatch.run`.
2. Allow **workouts**. Allow **workout routes** too if a workout should go with its GPS track.
3. Tap **Send to VertMatch** on a workout of the last 30 days.

How it behaves:

- **Nothing is sent automatically.** A workout goes only when you tap Send on it.
- **What is sent** is the FIT file OpenVitals itself writes for "Save as FIT": time, sport, duration, distance, calories, heart rate, speed, cadence, and the route when routes are allowed. The bridge asks OpenVitals for the file; it does not build one.
- **Sending again is safe.** The workout's id on VertMatch is `openvitals-` and its Health Connect session id, so VertMatch answers `unchanged`.
- **Deleting a sent workout in OpenVitals deletes it on VertMatch.** OpenVitals tells no app about a delete, so the bridge asks about each sent workout when the VertMatch screen opens, and after a send. Only a clean answer with no rows counts as deleted: sharing off, a missing grant or a failed read changes nothing. **Remove from VertMatch** deletes VertMatch's copy and keeps the workout.
- **A workout you deleted on VertMatch** is not offered again.
- **A workout without a distance** gets a warning first: VertMatch matches a copy it already has from Health Connect by start time and distance, so the workout can show up there twice.
- **What comes back**: your goal race and, with VertMatch Premium, your load, fitness and fatigue for the last seven days.
- **Tokens** are encrypted with a key in the Android Keystore. **Disconnect** deletes them, the key and the list of sent workouts from the phone. Your grant stays on VertMatch until you remove it there.
- **Nothing is logged**: no token, file or id.

### Building with VertMatch

VertMatch issues a client id to an app it approved. It is not a secret, but it names the approved app, so it stays out of the repository. Give it to the build in one of three ways:

```
./gradlew :app:assembleRelease -Popenbridge.vertmatch.clientId=<client id>
echo "openbridge.vertmatch.clientId=<client id>" >> local.properties
export OPENBRIDGE_VERTMATCH_CLIENT_ID=<client id>
```

Without one the app builds, VertMatch shows as "not set up" and nothing can connect. `openbridge.vertmatch.refreshTokens=true` asks for a refresh token, once VertMatch agreed them for the app; without it you sign in again when the access token expires.

The sign-in comes back to `<application id>://vertmatch/callback`, so VertMatch registers one redirect for each build: `tech.mmarca.openvitals.bridge://vertmatch/callback` and `tech.mmarca.openvitals.bridge.debug://vertmatch/callback`.

## OpenVitals builds

Each OpenVitals build names its permissions and its provider after its own application id: `tech.mmarca.openvitals`, `.debug` or `.ci`. The bridge asks for all three and reads from whichever is installed; with more than one, it lets you choose. The sharing API is in OpenVitals' nightly builds, and in its releases after 2.17.0. A workout's FIT file comes from the same builds.

Install OpenVitals before the bridge. Android may not offer a permission that another app defined after the bridge was installed; reinstall the bridge if a group never shows a dialog.

## Code

- `openvitals/OpenVitalsApi.kt`: the API's names, copied from `sharing/contract/DataSharingContract.kt` in the [OpenVitals](https://github.com/Hale-MTU/OpenVitals) repository. Keep the two in step.
- `openvitals/OpenVitalsReader.kt`: finds the installed build and reads any table into rows.
- `ui/`: the first screen and its ViewModel.
- `vertmatch/` (in `app`): the VertMatch screen, the connection (sign-in, send, delete, summary) and the encrypted token store.
- `vertmatch/` (the `:vertmatch` module): the partner API client, plain Kotlin/JVM. It began as [OpenVitals pull request 360](https://github.com/Hale-MTU/OpenVitals/pull/360) by Juhani Lehtimäki.

## Build

Same toolchain as the [OpenVitals](https://github.com/Hale-MTU/OpenVitals) repository:

```
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk LC_ALL=en_US.utf8
./gradlew :app:assembleDebug :app:testDebugUnitTest :vertmatch:test
./gradlew verifyCi    # the whole gate: tests, the debug build, lint
```

## Releases

Nightly and versioned builds are signed and published to [GitHub releases](https://github.com/Hale-MTU/OpenBridge/releases) by the same pipeline as OpenVitals and OpenWatch: a gate on every pull request and push (`./gradlew verifyCi` locally), a nightly at 01:00 UTC, and a version for each `vX.Y.Z` tag, approved before it becomes the stable release. [docs/releasing.md](docs/releasing.md) has the steps, the version code counter, and the secrets and variables the repository needs.

## License

OpenBridge is licensed under the [`GNU Affero General Public License v3.0 or later`](LICENSE).

## Wiki

The [wiki](https://github.com/Hale-MTU/OpenBridge/wiki) mirrors this repository's Markdown on every push to `main` (`.github/workflows/wiki.yml`, `scripts/sync-wiki.py`, the same script as in OpenVitals, OpenWatch and OpenLink). Edit the files here; the wiki is overwritten. `python3 scripts/sync-wiki.py --repo Hale-MTU/OpenBridge --check` fails on a relative link to a file that does not exist.
