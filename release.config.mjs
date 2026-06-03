/*
 * release.config.mjs — semantic-release configuration for the Convert Android SDK.
 *
 * Triggered by `.github/workflows/release.yml` on every successful CI run on
 * `main`. Analyzes the conventional commits since the last tag, decides the
 * next version, bumps `gradle/libs.versions.toml` for the build, publishes
 * signed artifacts to Maven Central via the vanniktech plugin, then creates
 * the `vX.Y.Z` tag + a GitHub Release carrying the generated notes.
 *
 * IMPORTANT: this release NEVER pushes a commit to `main`. The `main`
 * repository ruleset (pull_request + code_scanning) rejects direct branch
 * pushes, so any commit-back step would fail with GH013. semantic-release
 * core pushes only the *tag* (a `refs/tags/*` ref — not gated by the branch
 * ruleset) and `@semantic-release/github` creates the Release via the API.
 * The version bump in `libs.versions.toml` is a build-time, working-tree
 * write that is consumed by the Gradle publish and intentionally not
 * committed; the next release derives its version from this run's git tag.
 *
 * Plugin order is LOAD-BEARING — see Story 1.4 AC-3, qs-03, and the Dev
 * Notes "Why @semantic-release/exec Twice" section:
 *
 *   1. commit-analyzer          → decide next version from feat/fix/BREAKING
 *   2. release-notes-generator  → render markdown release notes
 *   3. exec (bump-version)      → write nextRelease.version into libs.versions.toml
 *   4. exec (publish-maven)     → ./gradlew publishAllPublicationsToMavenCentralRepository
 *   5. github                   → create vX.Y.Z tag + GitHub Release (via API)
 *
 * If step 4 (Maven Central publish) fails, semantic-release aborts BEFORE
 * step 5 — so no GitHub Release/tag is finalized without a corresponding
 * Maven Central upload. The repo stays in its pre-release state; the next
 * push retries.
 *
 * Adapted from the Convert PHP SDK's release.config.mjs (same release
 * plumbing — tag + GitHub Release, no branch commit — different build tool:
 * Gradle vs Composer).
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

    // 3. Bump `sdk-version = "…"` in gradle/libs.versions.toml so the Gradle
    //    build knows which version to publish. Runs BEFORE the Maven Central
    //    publish, so the published AAR carries the new version.
    [
      '@semantic-release/exec',
      {
        prepareCmd: 'node scripts/bump-version.mjs ${nextRelease.version}',
      },
    ],

    // 4. Publish signed AAR + JAR to Maven Central Portal via the vanniktech
    //    plugin. Reads the in-memory GPG key + Central Portal credentials
    //    from the `ORG_GRADLE_PROJECT_*` and `MAVEN_CENTRAL_*` env vars
    //    wired in release.yml. Runs AFTER the version bump so the uploaded
    //    artifact name matches what the tag will say, and BEFORE the GitHub
    //    Release so a publish failure means no Release/tag is finalized.
    [
      '@semantic-release/exec',
      {
        publishCmd: './gradlew publishAllPublicationsToMavenCentralRepository --no-daemon',
      },
    ],

    // 5. Create the `vX.Y.Z` tag + a GitHub Release with the generated
    //    notes. semantic-release core pushes the tag (a `refs/tags/*` ref —
    //    NOT gated by the `main` branch ruleset); this plugin creates the
    //    Release via the GitHub API. There is NO commit-back to `main` —
    //    that would hit the ruleset (GH013). Mirrors the PHP SDK, where the
    //    GitHub Release surfaces the changelog and Packagist/Maven consumes
    //    the git tag directly.
    [
      '@semantic-release/github',
      {
        // release.yml grants `contents: write` only; default PR/issue
        // success comments would need issues:write + pull-requests:write
        // and could 403. Disable them — the Release + tag (contents:write)
        // are all we need. (qs-03 / TD-2.)
        successComment: false,
        failComment: false,
        failTitle: false,
      },
    ],
  ],
};
