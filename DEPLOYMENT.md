# Build and deploy DFN Stage 4

DFN Stage 4 is an Android app for offline candidate review during meteorite field
campaigns. This guide serves field testers installing a prepared APK and maintainers
building or publishing test builds.

These instructions produce a DEBUG build for internal and field testing. They do not
produce a signed Play Store release.

## Install the app

This path is for field testers and does not require developer tools. The phone must run
Android 11 or newer because the app has `minSdk 30`.

1. Open the repository's [GitHub Releases][gh-releases] page.
2. Open the newest release and download `dfn-stage4-<tag>.apk`.
3. On the phone, allow **Install unknown apps** or **Install from unknown sources** for
   the browser or Files app used to open the download.
4. Tap the downloaded APK and choose to install it.
5. Android may warn that the app is from an unknown developer. Proceed only if the APK
   came from the repository's GitHub Releases page.

[gh-releases]: https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/releases

Sign in with the DFN account and pick a survey. The app requests camera and location
permissions contextually when a feature needs them. While connected to basecamp Wi-Fi,
claim candidates and download them. In the field, work offline by recording Yes or No,
a detection tag, and an evidence photo. Reconnect later to sync the results.

The app talks to `https://find.gfo.rocks/`. Internet access is needed only for login,
claiming candidates, downloading them, and syncing results.

## Build from source

Maintainers need:

- JDK 21 to run Gradle and the Android Gradle Plugin (AGP).
- JDK 17 only for the unit-test toolchain; `assembleDebug` does not need it.
- Git and a checked-out copy of this repository.
- The Android command-line tools, `platform-tools`, and SDK packages
  `platforms;android-37.0` and `build-tools;37.0.0`.
- A secret Mapbox downloads token and a public Mapbox access token.

Set `JAVA_HOME` to the JDK 21 installation. Gradle 9.6.1 auto-detects installed
JDKs; to register extra installations, set `org.gradle.java.installations.paths` in
`gradle.properties` or pass `-Porg.gradle.java.installations.paths=<dirs>`. JDK 17
is used only by tasks that need the test toolchain; `assembleDebug` runs on JDK 21.

### Install the Android command-line tools

On a bare machine, download the official command-line tools zip, for example build
`16111833`:

```text
https://dl.google.com/android/repository/commandlinetools-linux-<build>_latest.zip
```

Set `ANDROID_HOME`, unzip the tools to `$ANDROID_HOME/cmdline-tools/latest`, then add
the tools and `platform-tools` to `PATH`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools"
unzip commandlinetools-linux-16111833_latest.zip -d /tmp/cmdline-tools
mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Pin the command-line tools build you document; newer builds may change package names.
`sdkmanager` is deprecated in current command-line tools (the Android CLI is replacing
it), but the commands below still work.

Accept the licences before installing anything. `--licenses` is interactive and
requires non-interactive piping in automation or CI:

```bash
yes | sdkmanager --licenses
```

Then install the SDK packages, including `platform-tools` so `adb` is available:

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Create `local.properties` in the repository root:

```properties
MAPBOX_DOWNLOADS_TOKEN=sk.…
MAPBOX_ACCESS_TOKEN=pk.…
PRODUCTION_SERVER_URL=https://find.gfo.rocks/
```

`MAPBOX_ACCESS_TOKEN` is mandatory: the build fails fast when it is missing. Each of
these three values resolves in this precedence order:

1. Gradle property (for example `-PMAPBOX_ACCESS_TOKEN=...`).
2. Environment variable carrying the `ORG_GRADLE_PROJECT_` prefix, such as
   `ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN`.
3. `local.properties`.

`DEV_SERVER_URL` need not be set; blank is fine because it is reserved and unused.

The secret `sk.` token requires the Mapbox `downloads:read` scope. Gradle uses it only
to fetch Mapbox Maven artefacts, and it is never packaged in the APK. The public `pk.`
token is compiled into the app as the runtime map token.

Build the debug APK from the repository root:

```bash
./gradlew assembleDebug
```

The first build needs internet access for the Gradle distribution, Google and Maven
Central dependencies, and Mapbox artefacts from `api.mapbox.com`. It also needs
several GB of free disk space and may take several minutes. Later builds use caches.

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install or update it on a USB-connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To verify the APK, use the tools under `$ANDROID_HOME/build-tools/37.0.0/` (they are
not on `PATH` by default):

```bash
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  app/build/outputs/apk/debug/app-debug.apk
```

Android Studio is optional. The command-line build is sufficient.

## Configure the server

`PRODUCTION_SERVER_URL` is resolved in this exact precedence order:

1. Gradle property `PRODUCTION_SERVER_URL`.
2. Environment variable `ORG_GRADLE_PROJECT_PRODUCTION_SERVER_URL`.
3. `PRODUCTION_SERVER_URL` in `local.properties`.
4. An empty value, which falls back to the built-in default
   `https://find.gfo.rocks/`.

Use HTTPS on a real device. Cleartext HTTP is permitted only for `localhost` and
`10.0.2.2`, for emulator development, as defined in
[`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml).

`DEV_SERVER_URL` is reserved and currently unused, so it need not be set; leaving it
blank is fine.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs for pushes to `main` and for
pull requests. It executes:

```bash
./gradlew --no-daemon ktlintCheck detekt test assembleDebug assembleDebugAndroidTest
```

After a successful run, CI uploads the debug APK as a workflow artefact.
[`.github/workflows/release.yml`](.github/workflows/release.yml) handles tag-based test
releases.

Configure these repository secrets:

- `MAPBOX_DOWNLOADS_TOKEN`
- `MAPBOX_ACCESS_TOKEN`
- `PRODUCTION_SERVER_URL`

To add each secret in the GitHub web interface:

1. Open the repository on GitHub.
2. Select **Settings**.
3. Select **Secrets and variables**, then **Actions**.
4. Select **New repository secret**.
5. Enter the secret name and value, then select **Add secret**.
6. Repeat for all three secrets.

Alternatively, use the GitHub CLI:

```bash
gh secret set MAPBOX_DOWNLOADS_TOKEN \
  -R desertfireballnetwork/dfn-meteorite-drone-android-app --body "sk.…"
gh secret set MAPBOX_ACCESS_TOKEN \
  -R desertfireballnetwork/dfn-meteorite-drone-android-app --body "pk.…"
gh secret set PRODUCTION_SERVER_URL \
  -R desertfireballnetwork/dfn-meteorite-drone-android-app \
  --body "https://find.gfo.rocks/"
```

## Releasing a test build

1. Bump `app-version-name` in
   [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
2. Commit the version change.
3. Create a version tag:

   ```bash
   git tag v0.1.0
   ```

4. Push the tag:

   ```bash
   git push origin v0.1.0
   ```

The `v*` tag triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml). It builds
`app-debug.apk`, creates a GitHub Release, and attaches the renamed
`dfn-stage4-v0.1.0.apk`. Share the resulting GitHub Release URL with field testers.

The tag name should match the version in `app-version-name`.

## Troubleshooting

### Build fails with `MAPBOX_ACCESS_TOKEN is required`

Set `MAPBOX_ACCESS_TOKEN` in `local.properties` or provide it as the corresponding
Gradle environment property.

### Mapbox artefacts fail to resolve

Check that `MAPBOX_DOWNLOADS_TOKEN` is a valid secret `sk.` token with the
`downloads:read` scope.

### Login or candidate claiming is unreachable

Check `PRODUCTION_SERVER_URL`. It must point to the intended server, and a real device
must use HTTPS.

### APK will not install

Allow unknown-app installation for the browser or Files app that opened the APK, then
try again.

### Android reports “App not installed” or “Not compatible”

Use a device running Android 11 or newer. Devices below Android 11 do not meet
`minSdk 30`.

### The map is blank

Check that `MAPBOX_ACCESS_TOKEN` is a valid public `pk.` token. Also check whether its
Mapbox URL restrictions permit this app's runtime requests.

## Out of scope

This guide does not cover Android release signing or keystore management, Play Store
distribution, or publication of the internal gitignored documentation harness.
