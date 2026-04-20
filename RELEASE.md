# Release Process

This repo publishes two artifacts to Maven Central on every release:

- `com.convert:sdk-android:<version>` — the Android library (AAR)
- `com.convert:sdk-core:<version>` — the pure-Kotlin core (JAR)

Releases are fully automated — every push to `main` that passes CI triggers
[`semantic-release`](https://semantic-release.gitbook.io/), which:

1. Derives the next semver from the conventional commits since the last tag.
2. Writes `CHANGELOG.md` and generates release notes.
3. Bumps `sdk-version` in `gradle/libs.versions.toml` via `scripts/bump-version.mjs`.
4. Runs `./gradlew publishAllPublicationsToMavenCentralRepository` to upload
   signed artifacts to the Sonatype Central Portal.
5. Commits the changelog + version bump, creates the `v<version>` tag, and
   pushes.

You only need to write conventional commits — `feat:` for a minor bump,
`fix:` for a patch, `refactor:` for a patch, and a `BREAKING CHANGE:` footer
for a major bump. Everything else is automated.

---

## Required GitHub Repository Secrets

The `.github/workflows/release.yml` job will fail at publish time without
these five secrets. Configure them at **Settings → Secrets and variables →
Actions → New repository secret**. All are required for a successful release.

| Secret | Purpose | How to obtain |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token ID | [central.sonatype.com](https://central.sonatype.com/) → Account → Generate User Token → copy the first field |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password | Same UI as above → copy the second field (shown once only) |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private signing key | `gpg --armor --export-secret-keys <KEY_ID>` — paste the full multi-line output (including the `-----BEGIN/END PGP PRIVATE KEY BLOCK-----` lines) |
| `GPG_KEY_PASSWORD` | Passphrase for the signing key | Whatever was set when the GPG key was generated |
| `GPG_KEY_ID` | Short key ID (last 16 hex chars) | `gpg --list-secret-keys --keyid-format=long` — copy the trailing hex after `sec rsa4096/` |

`GITHUB_TOKEN` is supplied by GitHub Actions automatically — no action required.

### One-time prerequisites (not secrets)

- **Claim the `com.convert` namespace** in the Central Portal. This proves
  ownership of `convert.com` and is a manual step in the Central Portal UI —
  see [Sonatype's namespace docs](https://central.sonatype.org/register/namespace/).
  Until the namespace is verified, `publishAllPublicationsToMavenCentralRepository`
  will return 403.
- **Publish the GPG public key** to `keys.openpgp.org` (or another keyserver
  Maven Central trusts):

  ```bash
  gpg --send-keys --keyserver hkps://keys.openpgp.org <KEY_ID>
  ```

  The Central validator fetches your public key from the keyserver to verify
  the `.asc` signatures on each uploaded artifact. Uploading artifacts
  signed with a key the keyserver doesn't have will fail validation.

---

## Developer Workflow

### Local dry-run

Before pushing a commit that will trigger a release, you can preview what
`semantic-release` would do:

```bash
yarn install
yarn release:dry-run
```

This runs `semantic-release --dry-run --no-ci --branches=<current-branch>` —
it prints the inferred next version, the commits considered, and the generated
release notes, but touches nothing (no git push, no Maven upload).

Note: a dry-run does **not** exercise the Gradle publish step. To validate
the publish configuration end-to-end without hitting Central, run:

```bash
./gradlew publishToMavenLocal
ls -la ~/.m2/repository/com/convert/sdk-android/
ls -la ~/.m2/repository/com/convert/sdk-core/
```

Both artifacts should be present at the current `sdk-version` from
`gradle/libs.versions.toml`, with accompanying `.aar`/`.jar`, `-sources.jar`,
`-javadoc.jar`, `.module`, and `.pom` files.

### Conventional commit cheat-sheet

| Commit prefix | CHANGELOG section | Version bump |
|---|---|---|
| `feat: ...` | Features | minor |
| `fix: ...` | Bug Fixes | patch |
| `refactor: ...` | Refactoring | patch |
| `chore:`, `docs:`, `ci:`, `test:`, `style:`, `perf:` | hidden | none |
| Any commit body with `BREAKING CHANGE: ...` footer | (promoted) | major |

---

## Version-Bumping Flow

```
conventional commits on main
          │
          ▼
  semantic-release analyses commits since last tag
          │
          ▼
  scripts/bump-version.mjs rewrites sdk-version in gradle/libs.versions.toml
          │
          ▼
  ./gradlew publishAllPublicationsToMavenCentralRepository
   (uploads signed AAR + JAR + sources + javadoc + POM + module)
          │
          ▼
  @semantic-release/git commits CHANGELOG + libs.versions.toml, tags v<version>, pushes
          │
          ▼
  Central Portal validates (10–30 min), then artifacts sync to repo1.maven.org
```

Verify a successful release by fetching the AAR directly from Maven Central:

```bash
curl -I https://repo1.maven.org/maven2/com/convert/sdk-android/<version>/sdk-android-<version>.aar
# → HTTP/2 200
```

Central Portal sync typically takes 10–30 minutes after publish. A 404 during
that window is expected.

---

## `workflow_run` Fork-PR Safeguard (Do Not Remove)

The release workflow uses a `workflow_run` trigger gated by:

```yaml
if: >-
  github.event.workflow_run.conclusion == 'success' &&
  github.event.workflow_run.event == 'push'
```

The `github.event.workflow_run.event == 'push'` clause is the critical
safeguard: fork PRs run CI via the `pull_request` event, never `push`. This
means a fork-authored PR can never trigger the release workflow, and the
Maven Central + GPG secrets are never injected into a job that a fork
contributor can influence.

**Do not remove or weaken this condition.** If a future change adds another
trigger source (e.g., `schedule` or `workflow_dispatch`), extend the guard
explicitly — do not relax the push requirement silently.

---

## Rollback Procedure

**Maven Central artifacts are immutable.** Once a version is published and
synced to `repo1.maven.org`, it cannot be un-published or replaced.

If a release is broken:

1. Do NOT attempt to delete the tag or the published artifact.
2. Push a fix via a conventional `fix:` commit (or `feat:` if the correction
   requires a new API surface). semantic-release will assign the next patch
   (or minor) version, which consumers will resolve as the newer, preferred
   release.
3. Update `CHANGELOG.md` via the fix commit so the broken version's entry
   documents the known issue and points at the fixed version.
4. For security-sensitive issues, also publish an advisory via GitHub's
   Security → Advisories UI.

The Central Portal does allow deprecating a version (marking it as
"staged-for-deletion") for up to 24 hours after publish — but this is an
emergency-only path and requires manual intervention through the Portal UI.
Prefer the fix-forward approach.

---

## Troubleshooting

- **"signing failed" in publish logs.** Almost always a mismatch between
  `GPG_KEY_PASSWORD` and the private key. Run
  `./gradlew publishToMavenLocal --info` locally with the same inputs and
  confirm `.asc` signature files exist alongside every artifact. If they
  don't, the passphrase is wrong.
- **403 from Central Portal during upload.** The `com.convert` namespace is
  not yet verified for your Sonatype account — complete the one-time
  namespace claim in the Central Portal UI.
- **Publish succeeds but `curl repo1.maven.org` returns 404.** Normal for
  the first 10–30 min after publish while Central Portal syncs. Wait and
  retry.
- **Release commit created but tag not pushed.** Check the
  `persist-credentials: true` and `token:` inputs on `actions/checkout` —
  both are required so `@semantic-release/git` can push using the default
  `GITHUB_TOKEN`.
