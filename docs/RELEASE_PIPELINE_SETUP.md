# One-time setup for the Play release pipeline

`.github/workflows/release.yml` ships every push to `main` to Google Play. It needs five
repository secrets and one Play Console permission. Do this once, from a machine that holds
the release keystore.

## 1. Play service account (Google Cloud + Play Console)

1. Google Cloud console → project **arthax-d13b2** (the Firebase project) → IAM & Admin →
   Service accounts → Create: name `play-publisher`, no roles needed.
2. On the new account → Keys → Add key → JSON. Keep the downloaded file; it is a secret.
3. Play Console → Users and permissions → Invite new users → the service account's email
   (`play-publisher@arthax-d13b2.iam.gserviceaccount.com`) → App permissions → ArthaX →
   tick **Release to production, exclude devices, and use Play App Signing** and
   **Release apps to testing tracks** → Invite. (It shows as active immediately.)

## 2. Repository secrets

```bash
gh auth login                       # once
cd ~/tech/Arthax_App
gh secret set RELEASE_KEYSTORE_BASE64  --body "$(base64 -i ~/Documents/arthax-secrets/arthax-release.keystore)"
gh secret set RELEASE_KEYSTORE_PASSWORD  # paste the store password when prompted
gh secret set RELEASE_KEY_ALIAS          --body "arthax"
gh secret set RELEASE_KEY_PASSWORD       # paste the key password
gh secret set PLAY_SERVICE_ACCOUNT_JSON  < ~/Downloads/arthax-d13b2-xxxxxxxx.json
```

## 3. Production approval gate

GitHub → repository Settings → Environments → New environment `play-production` →
Required reviewers → add the people who may approve a production rollout. Without this the
production job runs unattended.

## 4. Version codes

The workflow uses `run_number + VERSION_CODE_OFFSET` (offset 100, set in `release.yml`) as
`versionCode`, so codes are strictly increasing across pushes. The first manual upload was
versionCode 33; the first pipeline build will be ≥ 101. If Play ever rejects a code as
"already used", raise the offset.

## 5. First run

The first bundle must be uploaded by hand (the API cannot create the listing). After that,
push to `main`: the internal track updates within minutes; production waits for an approver
and then rolls out to 20% (raise to 100% from Play Console → Production → Manage rollout).
