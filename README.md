# Arthax call recorder (Android)

Links a sales rep's phone calls to leads in their employer's Arthax CRM: watches the
phone's call log, matches each call to a lead by number, posts the call, and uploads the
recording the phone's own dialler saved. Kotlin, Hilt, Retrofit + Moshi, Compose,
WorkManager. `applicationId` is `com.callrecorder.app` (the successor to the previous
call-recorder app, signed with the same key so it installs as an update); the source
package is `ai.arthax.app`.

## Setup

- JDK 21 and an Android SDK with `platforms;android-36`, `platforms;android-36.1` and
  `build-tools;36.0.0` (`sdkmanager "platforms;android-36.1" "build-tools;36.0.0"`).
- `local.properties` at the repo root with `sdk.dir=/path/to/android/sdk` (gitignored).
- The Gradle wrapper (9.4.1) downloads itself on first run.

## Signing

Release builds are signed from `keystore.properties` at the repo root (gitignored):

```
storeFile=keystore/arthax-release.jks
storePassword=...
keyAlias=arthax
keyPassword=...
```

Put the keystore at `keystore/arthax-release.jks` (also gitignored). Without the file the
release build still succeeds, just unsigned — that is what CI does. The key is the one every
installed copy was signed with; losing it means no update can ever install over them.

## Build and test

```
./gradlew testReleaseUnitTest assembleRelease --console=plain   # unit tests + signed release APK
./gradlew bundleRelease                                          # AAB for Play
```

Outputs land in `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/`.
CI (`.github/workflows/android.yml`) runs the first command on every push.

## How call matching works

1. A foreground service watches the call log; a hang-up, the app opening, a reboot and a
   periodic worker all trigger a reconcile pass (`call/CallLogReconciler.kt`).
2. Each pass re-reads a trailing window of the call log (72 h by default, from the server
   config) above a floor set when tracking began, so a late or re-dated row is never lost.
3. A row is identified by its id *and* timestamp; the pending queue, the unmatched list and
   the dismissed list stop anything being handled twice.
4. The rep's last CALL tap (`ClickToCallIntent`) claims the matching outgoing row and is
   reported as `match_source=click_to_call`.
5. Every other number is asked of `GET /api/leads/by-phone` — org-wide, live — and reported
   as `by_phone`; offline, the last known answer is used (`lead_cache`).
6. "Not a lead" parks the call in `UnmatchedCallStore`; every pass re-checks the offline
   list and, on a budget, the server, and delivers the call when a lead appears.
7. A connected call's recording is found in the rep-chosen folder by time window and file
   name ranking (`domain/model/CallWindow.kt`), then copied into app storage.
8. The audio length is checked against the call (`recording/RecordingSanity.kt`); a file
   that does not fit is held for the rep's decision rather than uploaded.
9. `POST /api/calls/` first, then the upload; each step is persisted, WorkManager retries.
10. An hourly heartbeat (`POST /api/mobile/sync-health`) reports the phone's health and
    picks up config changes (`GET /api/mobile/config`); all knobs fall back to built-in
    defaults.

## Play submission notes

- **Permissions declaration form.** `READ_CALL_LOG`, `READ_PHONE_STATE` and `CALL_PHONE`
  are declared under the *Enterprise CRM / call archiving* exception: the app is deployed by
  an employer to its own sales reps to archive business calls in the company CRM. It is not
  a dialler replacement — no `InCallService`, no default-dialler role. Use the in-app video
  of the onboarding disclosure and the call-log step for the form.
- **Prominent disclosure.** Onboarding shows what is collected (call-log metadata and
  recordings of business calls), where it goes (the employer's Arthax CRM), and offers
  Accept / Decline before any call permission is requested. Decline is persisted and leaves
  the app usable for viewing and dialling leads. Privacy policy: https://arthax.ai/privacy,
  linked from the disclosure and from Settings.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** is requested only from the "Keep Arthax
  running" step, after an explanation, via the system dialog.
- **Foreground service** type `specialUse` carries the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  description in the manifest; `dataSync` is used on Android 10-13.
- **Data safety answers.** Collected: phone number (of the other party), call log
  (direction, time, duration), audio (call recordings), device or other IDs (ANDROID_ID for
  the sync-health row), app activity diagnostics (sync-health counters). Purpose: app
  functionality (CRM call history) for the employer. Data is encrypted in transit (HTTPS
  only, `usesCleartextTraffic=false`); users can request deletion through their employer.
  Not shared with third parties. No data is collected until the disclosure is accepted.
