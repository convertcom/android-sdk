/*
 * release.config.mjs — semantic-release configuration for the Convert Android SDK.
 *
 * Triggered by `.github/workflows/release.yml` on every successful CI run on
 * `main`. Analyzes the conventional commits since the last tag, decides the
 * next version, bumps `gradle/libs.versions.toml`, writes CHANGELOG.md,
 * publishes signed artifacts to Maven Central via the vanniktech plugin,
 * commits the bump + CHANGELOG, and pushes the `vX.Y.Z` tag.
 *
 * Plugin order is LOAD-BEARING — see Story 1.4 AC-3 and the Dev Notes
 * "Why @semantic-release/exec Twice" section:
 *
 *   1. commit-analyzer          → decide next version from feat/fix/BREAKING
 *   2. release-notes-generator  → render markdown release notes
 *   3. changelog                → prepend notes to CHANGELOG.md
 *   4. exec (bump-version)      → write nextRelease.version into libs.versions.toml
 *   5. exec (publish-maven)     → ./gradlew publishAllPublicationsToMavenCentralRepository
 *   6. git                      → commit CHANGELOG + libs.versions.toml, push vX.Y.Z tag
 *
 * If step 5 fails, semantic-release aborts BEFORE step 6 — so no orphan tag
 * gets pushed without a corresponding Maven Central upload. The repo stays
 * in its pre-release state; the next push retries.
 *
 * Adapted from the Convert PHP SDK's release.config.mjs (same PHP SDK
 * release plumbing, different build tool — Gradle vs Composer).
 */

export default {
  // Only publish from `main`. Developers can run `yarn release:dry-run` on
  // any branch to preview what would happen, but `yarn release` will refuse
  // to publish from anything else — preventing an accidental tag on a
  // feature branch.
  branches: ['main'],

  // All git tags use the `v` prefix (v1.0.0, v1.2.3). Matches the PHP SDK
  // and the Kotlin ecosystem convention.
  tagFormat: 'v${version}',

  plugins: [
    // 1. Map conventional commits to SemVer impact.
    //    feat: X              → minor bump
    //    fix: X               → patch bump
    //    BREAKING CHANGE: …   → major bump
    //    Anything else (chore/docs/ci/test/style/perf/refactor) → no release.
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'conventionalcommits',
      },
    ],

    // 2. Build the markdown release notes. Matches the PHP SDK's types
    //    mapping — only feat/fix/refactor are surfaced to users; maintenance
    //    commit types (chore/docs/ci/test/style/perf) are hidden.
    [
      '@semantic-release/release-notes-generator',
      {
        preset: 'conventionalcommits',
        presetConfig: {
          types: [
            { type: 'feat', section: 'Features' },
            { type: 'fix', section: 'Bug Fixes' },
            { type: 'refactor', section: 'Refactoring' },
            { type: 'chore', hidden: true },
            { type: 'docs', hidden: true },
            { type: 'ci', hidden: true },
            { type: 'test', hidden: true },
            { type: 'style', hidden: true },
            { type: 'perf', hidden: true },
          ],
        },
      },
    ],

    // 3. Prepend the release notes to CHANGELOG.md at repo root.
    '@semantic-release/changelog',

    // 4. Bump `sdk-version = "…"` in gradle/libs.versions.toml so the Gradle
    //    build knows which version to publish. Runs BEFORE the Maven Central
    //    publish, so the published AAR carries the new version.
    [
      '@semantic-release/exec',
      {
        prepareCmd: 'node scripts/bump-version.mjs ${nextRelease.version}',
      },
    ],

    // 5. Publish signed AAR + JAR to Maven Central Portal via the vanniktech
    //    plugin. Reads the in-memory GPG key + Central Portal credentials
    //    from the `ORG_GRADLE_PROJECT_*` and `MAVEN_CENTRAL_*` env vars
    //    wired in release.yml. Runs AFTER the version bump so the uploaded
    //    artifact name matches what the tag will say, and BEFORE the git
    //    commit so a publish failure means no orphan tag gets created.
    [
      '@semantic-release/exec',
      {
        publishCmd: './gradlew publishAllPublicationsToMavenCentralRepository --no-daemon',
      },
    ],

    // 6. Commit CHANGELOG.md + libs.versions.toml, push `vX.Y.Z` tag.
    //    `[skip ci]` prevents the release workflow from recursing on its
    //    own auto-commit — without it, CI would fire again on the release
    //    commit and (harmlessly) repeat the "no release-worthy changes"
    //    analysis.
    [
      '@semantic-release/git',
      {
        assets: ['CHANGELOG.md', 'gradle/libs.versions.toml'],
        message:
          'chore(release): v${nextRelease.version} [skip ci]\n\n${nextRelease.notes}',
      },
    ],
  ],
};
