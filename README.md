# Appium Flutter Mobile Automation

JUnit 5 + `appium-java-client` tests driving a Flutter app's UI over the Flutter Driver
protocol (needed for Flutter text fields that UiAutomator2 can't type into).

**What this project demonstrates**

- Flutter Driver typing workaround on Android and iOS Simulator (`FlutterBaseTest`)
- Layered structure: tests -> steps -> base/driver, test data and API clients kept separate
- Per-step scenario reporting (`StepExecutor`, `ScenarioReport`) feeding a Slack-ready summary
- A custom `@RetryingTest` JUnit 5 extension that retries only transient connection failures
- Parallel runs across multiple emulators (Surefire forks, one device per fork)
- CI on GitHub Actions: KVM-accelerated Android emulator, pinned Appium + drivers
- Test data / secrets kept out of source via `.env` (see [Configuration](#configuration))

> **The app under test is a private Flutter app and is not included.** Element keys such as
> `signup_password_field` belong to that app, so the scenarios here are a worked example:
> point `APP_APK_PATH` at your own Flutter build (with the Flutter Driver extension enabled) and
> adapt the keys in `steps/` to run them. No `.apk`/`.app` is ever committed.

## Tool versions

There is no `requirements.txt`-style file because Java dependencies are pinned in `pom.xml`;
the external tools are pinned here (and in CI):

| Tool | Version |
| --- | --- |
| JDK | 25 (`java.version` in `pom.xml`) |
| Maven | 3.9+ |
| Node.js | 20+ |
| Appium | 3.8.0 (`npm install -g appium@3.8.0`) |
| `uiautomator2` driver | 7.6.2 |
| `appium-flutter-driver` | 3.9.1 (3.10.0 pulls an ESM-only finder that fails to load) |
| Postman CLI | latest (journey tests only) |

## Configuration

Settings come from, in order: `-DKEY=...` > environment variable > `.env` file.

```
cp .env.example .env     # then edit; .env is git-ignored
```

| Key | Purpose |
| --- | --- |
| `APP_APK_PATH` / `APP_IOS_PATH` | Build under test (default `src/test/resources/app-staging-release.*`) |
| `PLATFORM`, `ANDROID_DEVICES`, `IOS_DEVICE` | Target device(s) |
| `APPIUM_MAIN_JS` | Only if Appium isn't on `PATH` |
| `TEST_EMAIL_LOCAL_PART`, `TEST_EMAIL_DOMAIN`, `TEST_PASSWORD` | Generated test accounts |
| `POSTMAN_API_KEY`, `POSTMAN_COLLECTION_ID`, `POSTMAN_ENVIRONMENT_ID`, `POSTMAN_INTEGRATION_ID` | Journey tests (invitation collection) |

## Prerequisites

1. **Appium installed and on `PATH`.** Any install method works (`npm install -g appium`,
   `brew install appium`, etc.) — verify with:
   ```
   appium --version
   ```
   `FlutterBaseTest` resolves Appium's server entry script (`main.js`) via `command -v appium`
   at test-start time, so it works regardless of how Appium was installed. If it can't find a
   binary on `PATH`, the test fails fast with a clear error instead of the opaque
   `InvalidServerInstanceException`.

   To use an Appium install that isn't on `PATH`, override the location explicitly by pointing
   at its `main.js` directly (not the `appium` binary):
   ```
   APPIUM_MAIN_JS=/path/to/appium/build/lib/main.js mvn test
   # or
   mvn test -Dappium.main.js=/path/to/appium/build/lib/main.js
   ```

2. **Drivers/plugins for the target app:**
   - `uiautomator2` driver (`appium driver install uiautomator2`) for Android, and/or
     `xcuitest` driver (`appium driver install xcuitest`) for iOS
   - The `flutter` Appium driver, required because these tests use `appium:automationName:
     Flutter` (via `io.github.5v1988:appium-flutter-client`) to type into Flutter text
     fields that `uiautomator2` can't reach:
     ```
     appium driver install --source=npm appium-flutter-driver
     ```
     Without this, `mvn test` fails with `SessionNotCreatedException: Could not find a
     driver for automationName 'Flutter'`. Verify with `appium driver list --installed`.
   - Separately, the **app build under test** must have the Flutter Driver extension
     enabled (this is an app-side build flag, not an Appium driver)

3. **A running Android emulator/device** named `emulator-5554` (or update
   `ANDROID_DEVICES` in `.env`) for
   the Android target — see "Running on iOS" below for the iOS target.

   The signup phone field expects an Egyptian number, and `SignupSteps.enterPhone` currently
   works around country detection by tapping the country-code picker explicitly. To instead
   have the emulator itself report Egypt (via the system locale's region and a simulated
   SIM/carrier) while keeping the UI in English, run once per emulator boot, before `mvn test`:
   ```
   ./scripts/setup-emulator-egypt.sh
   ```
   Pass a device serial as an argument if it's not `emulator-5554`. The locale defaults to
   `en-EG` (English UI, Egypt region); the test script relies on English visible text, so the
   emulator must never come up in Arabic. Override for a one-off run with
   `EMULATOR_LOCALE=ar-EG ./scripts/setup-emulator-egypt.sh`.

4. **Postman CLI, for journey tests only** (`JourneyHighschoolTest` and friends). These
   invite a premium test user through a Postman collection before signing up as them
   ([`InvitationApiClient`](src/test/java/automation/clients/InvitationApiClient.java)), so they
   need the `postman` CLI on `PATH` and logged in:
   ```
   ./scripts/install-postman-cli.sh    # macOS/Linux
   ./scripts/install-postman-cli.ps1   # Windows
   postman login --with-api-key "$POSTMAN_API_KEY"
   ```
   Plain `Signup*` tests don't need this - only tests that call `InvitationApiClient`.

## Running tests

```
mvn test
```

Runs against Android by default, using the staging APK at
`src/test/resources/app-staging-release.apk` and the emulator named `emulator-5554`.

Every run reinstalls the app from `src/test/resources/app-staging-release.apk`
(`appium:enforceAppInstall`), so a previously-installed build can't linger on the emulator
even when the new APK carries the same version number.

To also get the parsed pass/fail report (the same one CI posts to Slack, including the
per-step trail and the Postman response on a failing `JourneyHighschoolTest` run) printed to
your terminal automatically once the run finishes, use `scripts/run-tests.sh` instead of
`mvn test` directly - it accepts the same arguments and exits with the real test outcome.

```
./scripts/run-tests.sh [-n|--repeat <count>] [-p|--parallel] [TestClassName] [any extra mvn test args]
```

A bare test class name (anything not starting with `-`) is shorthand for
`-Dtest=<name>`, so the common case is just:

```
./scripts/run-tests.sh JourneyHighschoolTest
```

Each run wipes `target/surefire-reports/` first, so the report printed afterward only
ever reflects that run - it can't show stale results from an earlier `-Dtest=` run.

Useful arguments (all optional, combine freely):

| Arg | Effect |
| --- | --- |
| `TestClassName` | Bare name shorthand for `-Dtest=TestClassName`, e.g. `JourneyHighschoolTest`. Can't be combined with an explicit `-Dtest=`. |
| `-n <count>` / `--repeat <count>` | Run the whole suite `<count>` times in a row (e.g. to manually check for flakiness). Handled by the script itself, not forwarded to Maven. Defaults to 3 if omitted. |
| `-p` / `--parallel` | Run across two emulators at once (see "Running on two emulators" below). Adds `-Dtest.forkCount=2` and, unless you pass your own, `-Dandroid.devices=emulator-5554,emulator-5556`. |
| `-h` / `--help` | Print usage and exit without running anything. |
| `-Dtest=<ClassName>` | Only run that test class - the explicit form of the bare-name shorthand above. |
| `-Dretry.skip=true` | Disable the built-in `@RetryingTest` retries (see below) for this run. |
| `-Dretry.maxAttempts=1` | Same effect as `-Dretry.skip=true` (equivalent, lower precedence - see below). |
| `-Dplatform=ios` | Target iOS Simulator instead of Android (see "Running on iOS" below). |
| `-Ddevice="<name>"` | Override the iOS simulator name (only relevant with `-Dplatform=ios`). |

Examples:
- Run the full suite once, no retries: `./scripts/run-tests.sh -n 1`
- Run only `JourneyHighschoolTest`, once, no retries:
  `./scripts/run-tests.sh -n 1 JourneyHighschoolTest -Dretry.skip=true`
- Check `JourneyHighschoolTest` for flakiness across 5 runs:
  `./scripts/run-tests.sh -n 5 JourneyHighschoolTest`

### Running on two emulators

The suite can run in two forked JVMs, one per emulator, roughly halving
wall-clock time for a full run (the four test classes split two-and-two). Each
test still drives a single device; there's no in-JVM concurrency, so retries and
step reporting behave exactly as in a normal run.

1. **Create a second AVD** (once). Duplicate your existing AVD in Android
   Studio's Device Manager, or:
   ```
   avdmanager create avd -n MyAvd2 -k "<same system-image as your first AVD>"
   ```
2. **Boot both emulators** on ports 5554 and 5556 and apply the English UI /
   Egypt-region locale + simulated SIM config:
   ```
   ./scripts/start-emulators.sh MyAvd MyAvd2
   ```
   Check `adb devices` shows `emulator-5554` and `emulator-5556`.
3. **Run in parallel:**
   ```
   ./scripts/run-tests.sh -p
   # or directly:
   mvn test -Dtest.forkCount=2 -Dandroid.devices=emulator-5554,emulator-5556
   ```

`-Dandroid.devices` is a comma-separated serial list; fork *n* takes the *n*-th
serial (wrapping if there are more forks than serials). Without these flags a
plain `mvn test` is unchanged: one fork, `emulator-5554`. More than two
emulators works the same way (`-Dtest.forkCount=3 -Dandroid.devices=emulator-5554,emulator-5556,emulator-5558`),
bounded only by host RAM/CPU.

#### Pinning specific tests to specific emulators

The parallel run above lets Surefire decide which class lands on which
emulator. To choose explicitly - test A always on `emulator-5554`, test B always
on `emulator-5556` - run one Maven per emulator instead.

If you don't care *which* emulator, only that the two run at the same time on
different ones, just name both classes on a normal parallel run:

```
./scripts/run-tests.sh -p -Dtest=JourneyHighschoolTest,SignupHighschoolUserTypeTest
```

For a deterministic pin, compile once, then launch a `surefire:test` per
emulator in the background. Pass every invocation the **same**
`-Dandroid.devices` list but a different `-Dfork.index` (1-based; it selects the
serial and a distinct `systemPort`), and a separate reports dir so the two
don't overwrite each other's XML:

```
mvn -o test-compile
mvn -o surefire:test -Dtest=JourneyHighschoolTest \
    -Dandroid.devices=emulator-5554,emulator-5556 -Dfork.index=1 \
    -Dsurefire.reportsDirectory="$PWD/target/emu-5554" &
mvn -o surefire:test -Dtest=SignupHighschoolUserTypeTest \
    -Dandroid.devices=emulator-5554,emulator-5556 -Dfork.index=2 \
    -Dsurefire.reportsDirectory="$PWD/target/emu-5556" &
wait
```

`-Dfork.index=1` -> `emulator-5554` + `systemPort` 8201; `-Dfork.index=2` ->
`emulator-5556` + 8202. Parse either run's report with
`./scripts/parse-surefire-results.sh target/emu-5554`.

### Tab completion (optional)

Completions for `-n`/`--repeat`, `-h`/`--help`, the `-D` properties above, and every
test class name live in `scripts/completions/`. To enable them, add to your shell rc
(adjust the path if your checkout isn't at `~/appium_mobile_automation_project`) and open a new shell:

- **zsh** (`~/.zshrc`):
  ```
  fpath=(~/appium_mobile_automation_project/scripts/completions $fpath)
  autoload -Uz compinit && compinit
  ```
- **bash** (`~/.bashrc`):
  ```
  source ~/appium_mobile_automation_project/scripts/completions/run-tests.bash
  ```

Then `./scripts/run-tests.sh <Tab>` offers the flags and the `*Test` class names.

### Troubleshooting: `INSTALL_FAILED_INSUFFICIENT_STORAGE` / `SessionNotCreatedException` at session start

```
adb: failed to install .../app-staging-release.apk:
  Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location]
```

The staging APK is ~430 MB and is reinstalled on every session
(`appium:enforceAppInstall=true` in `FlutterBaseTest`), which needs several times
its size free on the emulator's `/data` partition (staged copy + dexopt + extracted
native libs). A default 6 GB AVD data partition fills up fast and the install fails,
which surfaces as a `SessionNotCreatedException` before any step runs.

`scripts/run-tests.sh` now checks this before starting and aborts with the numbers
and these steps if `/data` is too tight (skip it with `RUN_TESTS_SKIP_PREFLIGHT=1`).

**Fix — grow the AVD data partition** (replace `<avd>` with your AVD name, e.g. `MyAvd`):

1. Quit the emulator.
2. Edit `~/.android/avd/<avd>.avd/config.ini`: set `disk.dataPartition.size=16G`.
3. Cold boot with a wipe so the new size takes effect:
   `emulator -avd <avd> -wipe-data -no-snapshot-load`
4. Re-apply the English UI / Egypt-region locale + SIM (the wipe clears it):
   `./scripts/setup-emulator-egypt.sh`

**Stopgap without a wipe** (may not free enough on a 6 GB AVD):

```
adb -s emulator-5554 uninstall <your.app.package>
adb -s emulator-5554 shell pm trim-caches 9999999999
```

## Running on iOS (Simulator)

1. Install Xcode from the App Store and accept its license:
   ```
   sudo xcodebuild -license accept
   ```
2. Install the XCUITest driver and confirm WebDriverAgent builds against the simulator:
   ```
   appium driver install xcuitest
   ```
3. Boot a simulator (once per session is enough — Appium will reuse a booted simulator):
   ```
   xcrun simctl list devices
   xcrun simctl boot "iPhone 15"   # or any installed runtime
   ```
4. Build a Flutter Driver-enabled iOS **Simulator** build of the app (simulator builds are
   `.app` bundles, never `.ipa`), e.g.:
   ```
   flutter build ios --simulator --flavor staging
   ```
   Place the resulting `.app` at `src/test/resources/app-staging-release.app`.
5. Run the tests against iOS:
   ```
   mvn test -Dplatform=ios
   ```
   By default this targets a simulator named `iPhone 15`; override with `-Ddevice="<name>"`
   to match whichever simulator you booted.

## Project structure

`src/test/java/automation/` is split by role rather than kept flat:

- `base/` — `FlutterBaseTest`, the shared driver/session setup every test class extends.
- `steps/` — page-object-style step classes (`SignupSteps`, `JourneySteps`) that drive screens; not tests themselves.
- `tests/` — the actual `@RetryingTest` entry points (`Signup*UserTypeTest`, `JourneyHighschoolTest`).
- `config/` — `Config`, resolves settings from system properties, environment and `.env`.
- `data/` — `SignupTestData`, generators/constants for test input.
- `clients/` — `InvitationApiClient`, wraps calls to external services (the Postman-run invitation API) that a test needs but that aren't part of driving the app UI itself.
- `report/` — `ScenarioReport`/`StepExecutor`/`ScenarioReportExtension`, the per-step pass/fail reporting every scenario goes through (see "Step reporting, connection retries, and the red-banner check" below).

## Step reporting, connection retries, and the red-banner check

Every scenario method drives its steps through `SignupSteps`/`JourneySteps`, and every public
step on those classes is wrapped by `automation.report.StepExecutor`, so each scenario run
produces an explicit, ordered pass/fail trail rather than just a single pass/fail result:

- Each step logs `STEP PASSED - n. <name>` or `STEP FAILED - n. <name>: <reason>` as it runs.
  Scenarios stop at their first failing step (matching today's fail-fast behavior) — any step
  failing marks the whole scenario failed, exactly as before, just with an explicit trail of
  what ran up to that point.
- After each step, `StepExecutor` also asserts the app isn't showing a red validation-error
  banner (a failed input/tap should surface as a banner, not just silently not advance). This
  first pass is a **generic, unconfirmed check** — it looks for Flutter's built-in
  `SnackBar`/`MaterialBanner` markers in the render tree, not a locator confirmed against this
  app's actual banner widget. Tighten `StepExecutor.ERROR_BANNER_MARKERS` (or the check itself)
  once that widget is confirmed live, the same way every ValueKey in `SignupSteps` is only
  added once confirmed against the running app — see that class's javadoc.
- `ScenarioReportExtension` logs one final `SCENARIO PASSED - Class.method (N steps)` or
  `SCENARIO FAILED - Class.method at step k '<name>': <reason>` line once a scenario finishes.

`scripts/parse-surefire-results.sh` pulls these `STEP `/`SCENARIO ` lines back out of each
testcase's captured console output, so the Slack summary posted by
`.github/workflows/appium-tests.yml` shows every scenario's step trail, not just failures.
Console output itself is formatted by `src/test/resources/logback.xml` — colored log levels and
a short logger name, for easier local/CI reading; only the level/logger tokens are colored, so
the plain `STEP `/`SCENARIO ` marker text the parser greps for is unaffected.

`InvitationApiClient` logs the Postman CLI's full collection-run output between
`POSTMAN RESPONSE START`/`POSTMAN RESPONSE END` marker lines. When a `JourneyHighschoolTest`
scenario fails, `parse-surefire-results.sh` pulls that block out of the failing testcase and
includes it (indented, under "Postman response:") in the Slack summary — passing runs stay
quiet, since the response is only useful for debugging a failure.

In CI, the "Post results to Slack" step in `.github/workflows/appium-tests.yml` runs
`if: always()`, so this report is posted to whichever channel `SLACK_WEBHOOK_URL` targets on
every run, pass or fail — no separate step is needed to be notified, just being in that Slack
channel. Locally, `scripts/run-tests.sh` (see "Running tests" above) prints the same report to
your terminal as soon as a run finishes.

Every scenario method is a `@RetryingTest(maxAttempts = 3, onExceptions = {...})` — an in-repo
annotation (`automation.report.RetryingTest`/`RetryingTestExtension`), not junit-pioneer's — rather
than a plain `@Test`: each retry is a full fresh `@BeforeEach`/`@AfterEach` cycle (new Appium
server, new driver session), so a scenario is automatically re-run from scratch if it hits a
connection-level failure mid-run: `WebDriverException`/`IOException` from the Appium
server/session in `FlutterBaseTest`, plus `UncheckedIOException` from `InvitationApiClient`'s
Postman CLI call for `JourneyHighschoolTest`. A real assertion failure (a missing element, the
red-banner check, an unexpected app state) is **not** in `onExceptions`, so it fails the scenario
immediately instead of being retried and potentially masked.

The compiled-in `maxAttempts = 3` is what CI and any plain `mvn test` run use — nothing below
changes that unless you explicitly pass the flag yourself. Locally, pass
`-Dretry.maxAttempts=1` (or `-Dretry.skip=true`, equivalent) to `mvn test` / `scripts/run-tests.sh`
to disable retries for a single run — useful when iterating on a real failure you don't want
silently retried, e.g. `./scripts/run-tests.sh -Dretry.skip=true -Dtest=JourneyHighschoolTest`.
If both flags are set, `retry.skip=true` wins.

## Project status

### Signup

Split by education type into three tests (`SignupUniversityUserTypeTest`,
`SignupHighschoolUserTypeTest`, `SignupProfessionalUserTypeTest`), all extending
`FlutterBaseTest` and driving shared step methods in `SignupSteps`:

- **University** (`SignupUniversityUserTypeTest`) and **Highschool**
  (`SignupHighschoolUserTypeTest`) are complete paths — each walks all three screens end to
  end, including the education-details screen (University: university name search, degree,
  area of study; Highschool: school name search, curriculum, grade).
- **Professional** stops after screen 2 (name + date of birth). Its screen 3 form uses
  different fields than the university/highschool paths, and those fields' ValueKeys haven't
  been confirmed against the running app yet — see the class javadoc on `SignupSteps` for why
  keys are only added once confirmed, not guessed ahead of time.

### Journey (post-signup)

`JourneyHighschoolTest` signs up as an invited premium highschool student (via
`InvitationApiClient`, so the journey screens - gated behind premium accounts - are reachable)
and drives as far into the post-signup journey as currently possible, using `JourneySteps`:

- The screen right after signup completion is the personality-intro screen directly - there's
  no intervening "content" screen for this journey (confirmed via a live render-tree dump), so
  `JourneySteps.tapContentContinue()` (a generic key other journeys may still use) is deliberately
  **not** called from this test.
- **Blocked app-side**: neither of the personality-intro screen's two buttons
  (`personality_intro_start_quiz_button`, `personality_intro_maybe_later_button`) can currently
  be tapped. Both automation IDs exist as constants in the app's compiled code, but confirmed
  live (two independent probes) that neither is actually wired to its widget - `flutter:waitFor`
  times out for both regardless of timeout length, and a native page-source dump shows both
  buttons' `content-desc` is only their visible label ("Start Quiz" / "Maybe Later"), with no key
  at all. This needs an app-side fix (wiring the existing automation IDs to these widgets'
  `key:` parameter) - see `JourneySteps`'s class javadoc for the full investigation. Until then,
  `highschoolJourney()` fails at this step.
- `JourneyUniversityTest` (a university-path counterpart) is anticipated - see
  `InvitationApiClient`'s javadoc - but doesn't exist yet.

### Next steps

1. **App-side**: wire `personality_intro_start_quiz_button`/`personality_intro_maybe_later_button`
   to their widgets so the journey test can advance past the personality-intro screen.
2. Walk the app's Professional signup screen (screen recording or Appium Inspector) to
   confirm its field ValueKeys, then fill in `SignupSteps` methods and finish that test the
   same way `SignupUniversityUserTypeTest`/`SignupHighschoolUserTypeTest` finish their paths.
3. Decide whether to delete `SignupFlutterDriverTest` now that all three education-type
   variants exist, to avoid two versions of the same University walkthrough drifting apart.
4. Most steps can only confirm a tap/type didn't throw, not that it took effect — see
   `SignupSteps.enterPhone`'s javadoc for why (these widgets don't expose their state through
   the native accessibility tree). `enterPhone` works around this via the Continue button's
   enabled state; the same trick may be worth applying to other fields as new scenarios are
   added, wherever it's not already covered by `tapContinue`'s next-screen check.
5. Once the app-side fix above lands, confirm the rest of the post-signup journey's screens
   and add their ValueKeys/step methods to `JourneySteps` the same way - only once confirmed,
   never guessed ahead of time (see that class's javadoc).

## CI

`.github/workflows/appium-tests.yml` runs the suite on pushes/PRs to `main` and on-demand
via `workflow_dispatch` (one run at a time per ref - a new push cancels an in-progress run for
the same branch). It boots a real Android emulator on a GitHub-hosted Linux runner (KVM) (needed
because these tests use the Flutter Driver protocol, which cloud device farms like
BrowserStack don't support), installs Appium + the `uiautomator2`/`appium-flutter-driver`
drivers and the Postman CLI (logging in with the `POSTMAN_API_KEY` repo secret, for the journey
tests' `InvitationApiClient` calls), then runs `mvn test` exactly as described above.

Repo secrets used: `APP_APK_URL` (download URL of the build under test; without it only the
unit tests run), `POSTMAN_API_KEY`, `POSTMAN_COLLECTION_ID`, `POSTMAN_ENVIRONMENT_ID`,
`POSTMAN_INTEGRATION_ID`, `SLACK_WEBHOOK_URL`. Repo variables: `TEST_EMAIL_DOMAIN`,
`TEST_EMAIL_LOCAL_PART`.

After each run, `scripts/parse-surefire-results.sh` summarizes `target/surefire-reports/`
into pass/fail counts plus the class, method, and assertion message of any failing test, and
posts it to the `#appium-run-results` Slack channel (via the `SLACK_WEBHOOK_URL` repo secret)
— so a failure's scenario and field can be identified from Slack without re-running locally.
Full XML reports are also uploaded as a workflow artifact.
