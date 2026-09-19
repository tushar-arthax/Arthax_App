# Arthax call recorder (Android)

Links a sales rep's phone calls to leads in their employer's Arthax CRM: watches the
phone's call log, matches each call to a lead by number, posts the call, and uploads the
recording the phone's own dialler saved. Receives push from the CRM: a colleague's
click-to-call wakes the phone and dials the lead. Kotlin, Hilt, Retrofit + Moshi, Compose,
WorkManager, Firebase Cloud Messaging. `applicationId` is `com.callrecorder.app` (the successor to the previous
call-recorder app, signed with the same key so it installs as an update); the source
package is `ai.arthax.app`.

## Setup

- JDK 21 and an Android SDK with `platforms;android-36`, `platforms;android-36.1` and
  `build-tools;36.0.0` (`sdkmanager "platforms;android-36.1" "build-tools;36.0.0"`).
- `local.properties` at the repo root with `sdk.dir=/path/to/android/sdk` (gitignored).
- The Gradle wrapper (9.4.1) downloads itself on first run.
- `app/google-services.json` is committed: it holds the Firebase project ids and the
  Android API key, which are public client identifiers, not secrets (Firebase project
  `arthax-d13b2`, Android app registered under `com.callrecorder.app`). If the project is
  ever re-created, download the new file from Firebase console > Project settings > Your
  apps and replace it; the `com.google.gms.google-services` plugin turns it into resources
  at build time. The backend signs its sends with the *server* key
  (`firebase-credentials.json` on the backend, never in this repo).

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

## Push notifications

Firebase Cloud Messaging, **data messages only** (`push/PushMessage.kt` is the contract,
`notification/ArthaxMessagingService.kt` the entry point). Every payload carries a `type`;
anything the app cannot decode is logged and dropped, never thrown — the service runs on a
thread whose crash takes the call watcher with it. The old `action=DIAL_LEAD` /
`phone_number` payload is still accepted and treated as `dial`.

| `type` | Fields | On the phone |
| --- | --- | --- |
| `dial` | `lead_id`, `phone`, `lead_name`, `requested_by`, `request_id` | App on screen: confirm sheet (Call / Not now). Otherwise heads-up notification "Call *lead*" / "Requested by *name* from ArthaX" with **Call** and **Dismiss**. Call goes through `DialRequestActivity`, which records the request so the call is posted with `match_source=web`. De-duplicated on `request_id` (last 20). Always delivered; cannot be switched off. |
| `lead_assigned` | `lead_id`, `lead_name`, `phone`, `assigned_by` | "New lead assigned: *name*" with a **Call** action (`match_source=click_to_call`); tap opens Leads with the number searched and the lead outlined. The list refreshes. |
| `follow_up_due` | `lead_id`, `lead_name`, `phone`, `due_at` (ISO), `minutes_left` | Reminders channel: "Follow-up in N min: *name*", **Call** when a phone is present. Recorded in the reminded set so the on-device timer for the same follow-up stays quiet. |
| `meeting_reminder` | `meeting_id`, `title`, `scheduled_at`, `minutes_left`, `lead_id?`, `phone?` | "Meeting in N min: *title*", **Call** when a phone is present; tap opens Leads. |
| `notification` | `title`, `body`, `notification_type`, `entity_type?`, `entity_id?` | General channel, shown as given; tap opens the app. |
| `sync_now` | `reason` | Enqueues a call-log reconcile through WorkManager and sends a heartbeat. Nothing shown. |
| `config_updated` | `version` | Fetches the server config if this version differs. Nothing shown. |
| `device_alert` | `health` (`warning`/`critical`), `message` | "Call tracking needs attention" + message; tap opens Settings. |

Channels: `calls_from_crm` (high, sound + vibration), `leads` (default), `reminders`
(high), `general` (default), alongside the existing call-tracking and uploads channels.
Settings has switches for new leads, reminders and general (calls from the CRM are shown
locked on), the push registration state, and a link to the system notification settings.
No full-screen intents are used.

**Token registration.** The token is persisted as soon as Firebase issues it and sent to
`PATCH /api/users/me/fcm-token` at whichever of "token" and "sign-in" comes second
(`push/FcmTokenRegistrar.kt`); a failed send is retried at the next start. Signing out
deletes the Firebase token so pushes for the previous rep stop reaching a shared handset;
the next sign-in registers a fresh one. The heartbeat reports `permissions.push`. A phone
without Google Play services runs everything except push; Settings says so.

**On-device follow-up fallback.** Push is not delivered on every phone, so each time the
lead list loads, a WorkManager timer is armed ten minutes before every follow-up in the
next 48 hours (`push/FollowUpPlanner.kt`, unique name `followup:<lead_id>:<due_at>`,
KEEP), re-planned on every refresh and cancelled when the date moves. Push and timer share
the reminded set, so a follow-up is announced once.

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
