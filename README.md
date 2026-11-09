# OpenBridge

OpenVitals is offline: it has no internet permission. OpenBridge is a separate app, published by [Hale](https://hale.ngo) like OpenVitals, that reads the data OpenVitals shares and can reach the internet, so it can pass that data on to other services. No service is connected yet.

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

## OpenVitals builds

Each OpenVitals build names its permissions and its provider after its own application id: `tech.mmarca.openvitals`, `.debug` or `.ci`. The bridge asks for all three and reads from whichever is installed; with more than one, it lets you choose. The sharing API is only in debug and ci builds of OpenVitals for now.

Install OpenVitals before the bridge. Android may not offer a permission that another app defined after the bridge was installed; reinstall the bridge if a group never shows a dialog.

## Code

- `openvitals/OpenVitalsApi.kt`: the API's names, copied from `sharing/contract/DataSharingContract.kt` in the [OpenVitals](https://github.com/Hale-MTU/OpenVitals) repository. Keep the two in step.
- `openvitals/OpenVitalsReader.kt`: finds the installed build and reads any table into rows.
- `ui/`: one screen and its ViewModel.

## Build

Same toolchain as the [OpenVitals](https://github.com/Hale-MTU/OpenVitals) repository:

```
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk LC_ALL=en_US.utf8
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

## License

OpenBridge is licensed under the [`GNU Affero General Public License v3.0 or later`](LICENSE).

## Wiki

The [wiki](https://github.com/Hale-MTU/OpenBridge/wiki) mirrors this repository's Markdown on every push to `main` (`.github/workflows/wiki.yml`, `scripts/sync-wiki.py`, the same script as in OpenVitals, OpenWatch and OpenLink). Edit the files here; the wiki is overwritten. `python3 scripts/sync-wiki.py --repo Hale-MTU/OpenBridge --check` fails on a relative link to a file that does not exist.
