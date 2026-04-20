/**
 * semantic-release configuration for the Convert Android SDK.
 *
 * Single-version project (unlike the PHP SDK monorepo, which uses a custom
 * rollover-version plugin to sync 12 packages). The Android SDK publishes
 * two artifacts — `com.convert:sdk-android` and `com.convert:sdk-core` —
 * that both read their version from `gradle/libs.versions.toml` under the
 * `sdk-version` key, so bumping a single value keeps both in lockstep.
 *
 * Plugin ordering (strict):
 *   1. commit-analyzer         — derives the next version from conventional commits
 *   2. release-notes-generator — formats CHANGELOG + release notes (feat/fix/refactor visible)
 *   3. changelog               — writes CHANGELOG.md at repo root
 *   4. exec(prepareCmd)        — bumps sdk-version in gradle/libs.versions.toml
 *   5. exec(publishCmd)        — runs Gradle publish to Maven Central AFTER bump
 *                                but BEFORE git commit, so a failed publish aborts
 *                                without leaving a half-released tag
 *   6. git                     — commits CHANGELOG.md + libs.versions.toml,
 *                                creates and pushes the v${version} tag
 */
export default {
  branches: ['main'],
  tagFormat: 'v${version}',
  plugins: [
    // 1. Analyse conventional commits → next semver bump.
    // Stock commit-analyzer is sufficient here (no monorepo rollover needed).
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'conventionalcommits',
      },
    ],

    // 2. Generate release notes — matches PHP SDK types config so both SDKs
    //    produce identically-shaped CHANGELOGs and release notes.
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

    // 3. Write CHANGELOG.md at repo root.
    '@semantic-release/changelog',

    // 4. Bump sdk-version in the Gradle version catalog BEFORE publish
    //    so the Gradle build picks up the new version.
    [
      '@semantic-release/exec',
      {
        prepareCmd: 'node scripts/bump-version.mjs ${nextRelease.version}',
      },
    ],

    // 5. Publish AAR + JAR artifacts to Maven Central via vanniktech plugin.
    //    Runs AFTER the version bump so the publish carries the new version;
    //    runs BEFORE the git commit/tag so a failed publish aborts cleanly.
    //    `--no-daemon` matches PHP SDK / CI convention — the daemon offers
    //    no benefit in one-shot release jobs and can cause intermittent
    //    "daemon disappeared" failures on ephemeral runners.
    [
      '@semantic-release/exec',
      {
        publishCmd:
          './gradlew publishAllPublicationsToMavenCentralRepository --no-daemon',
      },
    ],

    // 6. Commit CHANGELOG.md + libs.versions.toml, tag the release, push.
    //    [skip ci] in the message prevents the release commit from
    //    re-triggering CI → release → ... feedback loops.
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
