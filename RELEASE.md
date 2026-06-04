# Release Process

This document describes how releases of the Convert Android SDK are produced
and what needs to be configured before the release pipeline can run.

The short version: **every merge to `main` with a conventional `feat:` or
`fix:` commit triggers a new release**. The release workflow runs
`semantic-release`, which bumps the version in
`gradle/libs.versions.toml`, regenerates `CHANGELOG.md`, publishes signed
artifacts to Maven Central via the vanniktech plugin, and pushes a
`vX.Y.Z` git tag.

No manual version bumping. No manual publishing. Conventional commits drive
everything.

---

## One-Time Setup (Repo Admin)

These steps must be completed **before the first merge to `main` after this
story lands**, otherwise the release workflow will fail.

### 1. Claim the `com.convert` namespace in the Central Portal

Maven Central (via the Sonatype Central Portal) requires the
[groupId](https://central.sonatype.org/publish/requirements/coordinates/)
namespace to be verified against domain ownership:

1. Log in at <https://central.sonatype.com>.
2. Navigate to **Namespaces** → **Add Namespace**.
3. Enter `com.convert` and follow the domain-verification flow (typically a
   `TXT` DNS record on `convert.com` proving ownership).
4. Wait for Sonatype to approve (usually within a business day).

Until the namespace is verified, `./gradlew publishAllPublicationsToMavenCentralRepository`
will fail with a "401 Unauthorized" or "Namespace not found" error.

### 2. Generate the Central Portal user token

The release workflow uses a **user token** (not your login password) to
authenticate against the Central Portal.

1. Sign in at <https://central.sonatype.com>.
2. Click your username → **View Account** → **Generate User Token**.
3. Copy the generated **username** and **password** (the password will not
   be shown again — store it securely immediately).
4. Add them as GitHub repository secrets (see the table below):
   - `MAVEN_CENTRAL_USERNAME` ← the token username
   - `MAVEN_CENTRAL_PASSWORD` ← the token password

### 3. Generate and publish the GPG signing key

Maven Central rejects unsigned artifacts. We use a single, dedicated GPG
key for SDK releases. **Do NOT reuse your personal key** — store the
release key in a password manager shared by maintainers.

```bash
# Create a new key (4096-bit RSA, no expiration — rotate manually).
gpg --full-generate-key
# Choose: (1) RSA and RSA, 4096, 0 (no expiration), real-name "Convert Release Bot",
#         email "support@convert.com", passphrase of your choice.

# Find its key ID (the long form — the 16-hex-digit short id is fine too).
gpg --list-secret-keys --keyid-format=long
# Example: sec   rsa4096/ABCDEF0123456789 …

# Export the ASCII-armored private key for the GitHub secret.
# The output is multi-line — GitHub's secret UI accepts multi-line values verbatim.
gpg --armor --export-secret-keys ABCDEF0123456789

# Upload the corresponding PUBLIC key to the OpenPGP keyservers so Maven
# Central can verify signatures. Without this step, Central will reject
# uploads with a "Missing signature" error even if the signature file is present.
gpg --armor --export ABCDEF0123456789 | curl -T - https://keys.openpgp.org
# Then confirm the upload at the URL keys.openpgp.org returns.
```

Add three GitHub repository secrets:

| Secret | Value |
|---|---|
| `GPG_PRIVATE_KEY` | Full multi-line output of `gpg --armor --export-secret-keys …` (including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END …-----` lines). |
| `GPG_KEY_PASSWORD` | The passphrase you chose when creating the key. |

### 4. Run the R8 consumer-app verification (one-time, blocks v1.0.0)

AC-7 / AC-11 of Story 1.4 require a runtime check that the
`consumer-rules.pro` keep directives are sufficient: a Release-mode
Android app with R8 full mode must be able to construct `ConvertSDK`
and call its public surface without `ClassNotFoundException` /
`NoSuchMethodException` from over-aggressive R8 stripping.

This was **deferred during initial implementation** because the dev
sandbox lacked the Android SDK + emulator tooling. Before the first
`v1.0.0` Maven Central publish, run this check by hand:

1. `./gradlew :packages:sdk:publishToMavenLocal :packages:core:publishToMavenLocal`
   — publishes both artifacts at the placeholder version
   `0.0.0` to `~/.m2/repository/com/convert/`.
2. Create a throwaway Android app outside this repo (e.g. under
   `/tmp/consumer-test/`) with:
   - `repositories { mavenLocal(); google(); mavenCentral() }`
   - `implementation("com.convert:sdk-android:0.0.0")`
   - R8 enabled in `release` build type (`isMinifyEnabled = true`,
     default proguard files plus the consumer rules pulled in via the
     SDK AAR — no extra rules needed in the consumer's own
     `proguard-rules.pro`).
3. Add a one-shot call in `Application.onCreate()`:
   ```kotlin
   val sdk = ConvertSDK.builder(this).sdkKey("test-key").build()
   val ctx = sdk.createContext()
   ctx.runExperience("any")          // expect: null (no config loaded)
   ctx.trackConversion("any-goal")   // expect: no-op, no crash
   ```
4. Build the Release variant: `./gradlew :app:assembleRelease`. Confirm:
   - The build completes without R8 errors about missing
     `com.convert.sdk.*` types or kotlinx.serialization `$serializer`
     classes.
   - Installing and launching the APK does not crash with
     `ClassNotFoundException` for any `com.convert.sdk.*` symbol.
5. Record the four-point outcome (a) dependency resolves, (b) R8
   Release build succeeds, (c) `ConvertSDK.builder(...).build()` runs,
   (d) `runExperience(...)` returns null without crashing — in the
   story's **Dev Agent Record** section, then proceed with the first
   release.

If R8 strips a class, broaden the rules in
`packages/sdk/consumer-rules.pro` (NEVER ask consumer apps to add their
own keep rules — the SDK's keep rules are its R8 contract). Then re-run
the check from step 1.

A future Story 1.3 hardening task may codify this as a post-merge CI
job (build a consumer composite project against `mavenLocal()`); until
then the manual run is the only verification.

### 5. Repository secrets summary

The full set of secrets the release workflow reads:

| Secret | Required | Source |
|---|---|---|
| `GITHUB_TOKEN` | yes (auto) | Provided automatically by GitHub Actions for every workflow run — nothing to configure. |
| `MAVEN_CENTRAL_USERNAME` | yes | Central Portal user token username (step 2). |
| `MAVEN_CENTRAL_PASSWORD` | yes | Central Portal user token password (step 2). |
| `GPG_PRIVATE_KEY` | yes | ASCII-armored private signing key (step 3). |
| `GPG_KEY_PASSWORD` | yes | Passphrase for the signing key (step 3). |

Configure them in **GitHub → repo → Settings → Secrets and variables → Actions → New repository secret**.

---

## Triggering a Release

Releases are fully automatic. The process:

1. Open a PR that contains one or more conventional commits.
2. Merge the PR to `main`. GitHub Actions fires the **CI** workflow
   (`.github/workflows/ci.yml`).
3. On CI success, GitHub fires the **Release** workflow
   (`.github/workflows/release.yml`) via a `workflow_run` trigger.
4. semantic-release analyzes every commit on `main` since the last tag:
   - `feat: …` → **minor** bump
   - `fix: …` → **patch** bump
   - `BREAKING CHANGE: …` footer → **major** bump
   - Everything else (`chore`, `docs`, `ci`, `test`, `style`, `perf`,
     `refactor` without `!`) → no release. The workflow succeeds silently.
5. If there's a release-worthy commit:
   1. `scripts/bump-version.mjs` updates `gradle/libs.versions.toml`.
   2. `CHANGELOG.md` is regenerated with the new notes.
   3. `./gradlew publishAllPublicationsToMavenCentralRepository` uploads
      both `com.convert:sdk-android:X.Y.Z` and `com.convert:sdk-core:X.Y.Z`
      to the Central Portal as a **staged deployment** (validated, but
      NOT yet released to the public mirror — see step 6).
   4. `@semantic-release/git` commits the version bump + CHANGELOG and
      pushes the `vX.Y.Z` tag to `main` (commit message carries
      `[skip ci]` to prevent a feedback loop).
6. **Manual step (repo admin):** Sign in to
   <https://central.sonatype.com> → **Deployments**, find the new
   deployment, confirm the artifacts look correct (version, POM, both
   modules present), and click **Publish**. This releases the staged
   deployment to the public Maven Central mirror. We deliberately chose
   `publishAllPublicationsToMavenCentralRepository` (staged) over
   `publishAndReleaseToMavenCentral` (auto-release) as a safety gate —
   lets a human eyeball the contents before pushing a new version to
   consumers. Once we're confident in the pipeline (several releases
   in), we can switch to auto-release by changing the Gradle task in
   `release.config.mjs` plugin 5's `publishCmd`.
7. Maven Central's sync to the public mirror takes 10–30 minutes after
   you click **Publish** in step 6. Verify with:
   ```bash
   curl -I https://repo1.maven.org/maven2/com/convert/sdk-android/X.Y.Z/sdk-android-X.Y.Z.aar
   # → HTTP/2 200 when the artifact is live.
   ```

### Version Numbering

- The first release (no prior tag) is always **v1.0.0**. Every subsequent
  release is calculated from the conventional-commit log.
- `gradle/libs.versions.toml` ships with `sdk-version = "0.0.0"` as a
  placeholder — the first release overwrites it.

---

## Previewing a Release: Dry Run

`yarn release:dry-run` runs semantic-release in dry-run mode. It will:
- Analyze commits since the last tag.
- Decide the next version.
- Show the rendered release notes.
- **Not** bump `libs.versions.toml`, **not** publish, **not** tag.

semantic-release checks the current branch against the `branches` entry
in `release.config.mjs` (currently `['main']`). On `main`, the dry-run
prints the next-version plan. On any other branch, it exits with:

```
ERROR This test run was not triggered in a known release branch.
```

That message is **expected** — it confirms the config parses correctly.
To exercise a full dry-run on a feature branch, temporarily add the
branch name to `release.config.mjs`'s `branches` array, run the dry-run,
then revert the change before committing.

```bash
# On main:
git checkout main
yarn release:dry-run

# On a feature branch (full dry-run):
# 1. Edit release.config.mjs → branches: ['main', 'feature/my-branch']
# 2. yarn release:dry-run
# 3. git checkout release.config.mjs   # discard the temporary edit
```

Requires the branch to exist on `origin` (semantic-release needs `git
ls-remote` to work). Push first if the branch is local-only.

---

## Fork-PR Safeguard (DO NOT REMOVE)

The release workflow's `if:` guard includes a fork-PR safeguard:

```yaml
if: >
  github.event.workflow_run.conclusion == 'success' &&
  github.event.workflow_run.event == 'push'
```

The **second** condition — `github.event.workflow_run.event == 'push'` —
is critical. Without it, CI runs triggered by pull requests (including
forks' PRs, which have no secret access) would also fire the release
workflow. For fork PRs this would either:

1. Fail noisily (no secrets available), cluttering the PR with red
   cross-marks, or — worse —
2. Under certain misconfigurations, leak secrets into the PR's logs.

Always keep the `push` check. If you're ever tempted to remove it because
"release ran twice for one push", the answer is almost certainly a
different fix (e.g., commit-skip semantics, concurrency groups), not
weakening this guard.

---

## Rollback Procedure

**Maven Central artifacts are immutable.** Once a version is uploaded and
synced, it cannot be deleted or replaced. If a bad release slips through:

1. Do **not** attempt to delete the tag or artifact.
2. Push a conventional `fix:` commit that addresses the problem.
3. The next release workflow will publish a new patch version (e.g.
   if `v1.2.3` was bad, the fix ships as `v1.2.4`).
4. Mention the superseded version in the CHANGELOG entry of the fix.

If a release is catastrophically broken (e.g., shipped malicious code),
contact Sonatype support at <https://central.sonatype.org/> — they can
block a version from resolving, but the artifact remains in the archive.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Execution failed for task ':packages:core:signMavenPublication'. Cannot perform signing task … because it has no configured signatory` | `ORG_GRADLE_PROJECT_signingInMemoryKey` is empty or malformed. | Re-export the GPG key with `gpg --armor --export-secret-keys`, re-paste into the GitHub secret, preserving line breaks. |
| `401 Unauthorized` during publish | Central Portal token expired, or `MAVEN_CENTRAL_USERNAME` / `_PASSWORD` out of sync. | Regenerate the user token at <https://central.sonatype.com>, update both secrets. |
| `Namespace com.convert not found` | Namespace claim not approved yet. | Check the claim status at <https://central.sonatype.com> → Namespaces. |
| `Missing signature` from Central | Public key not uploaded to a keyserver. | Re-run `gpg --armor --export … \| curl -T - https://keys.openpgp.org`, confirm via the email link. |
| `release.yml` didn't run after a merge to main | CI failed, or the commit was a non-release type. Check the Actions tab: Release workflow only appears as "Queued" if CI succeeded. | If CI failed, fix the CI issue. If the commit was `chore:` or `docs:`, that's expected — no release was warranted. |
| `yarn release:dry-run` errors with "This test run was not triggered in a known release branch" | Expected on any branch except `main`. | To force a full dry-run on a feature branch, temporarily add the branch name to `release.config.mjs`'s `branches` array (revert before committing). On `main`, this error means the local branch is not pushed to `origin` — `git push --set-upstream origin main` first. |
