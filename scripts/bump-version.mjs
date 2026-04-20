#!/usr/bin/env node
/*
 * scripts/bump-version.mjs
 *
 * Bumps the `sdk-version` entry under the `[versions]` block of
 * `gradle/libs.versions.toml` to the value passed as the first CLI argument.
 * Called by `@semantic-release/exec`'s `prepareCmd` in `release.config.mjs`
 * during the semantic-release prepare phase — before the changelog is
 * generated, before the Maven Central publish runs, and before the commit +
 * tag is created.
 *
 * Story 1.4 / AC-3.4.
 *
 * Behaviour:
 *   - Requires exactly one positional argument (the new version string).
 *   - Rewrites `sdk-version = "OLD"` to `sdk-version = "NEW"` using a
 *     line-anchored regex. Does not touch any other line.
 *   - Re-reads the file after the write and confirms the new version is
 *     present; exits non-zero on mismatch so a misbehaving filesystem or
 *     unexpected TOML shape fails loudly during `yarn release`.
 *
 * Why not `sed`:
 *   TOML editing via regex is fragile in shell (escaping, portability
 *   between BSD and GNU sed, etc.). A tiny Node script is portable on every
 *   platform semantic-release runs on, and easy to extend if the catalog
 *   layout ever changes.
 */

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const CATALOG_PATH = resolve(SCRIPT_DIR, '..', 'gradle', 'libs.versions.toml');
const VERSION_KEY = 'sdk-version';
// Line-anchored: only matches the key at the start of a line, under [versions].
// Does NOT match entries like `kotlin = "..."` or any commented-out copy.
const LINE_REGEX = new RegExp(`^(${VERSION_KEY}\\s*=\\s*)"[^"]*"`, 'm');

async function main() {
  const [, , newVersion] = process.argv;
  if (!newVersion) {
    console.error(
      'Usage: node scripts/bump-version.mjs <new-version>\n' +
        '  Example: node scripts/bump-version.mjs 1.2.3',
    );
    process.exit(2);
  }
  if (!/^\d+\.\d+\.\d+(-[\w.-]+)?(\+[\w.-]+)?$/.test(newVersion)) {
    console.error(
      `error: "${newVersion}" is not a valid SemVer string.\n` +
        '  semantic-release always passes a valid SemVer — refusing to write a bad value.',
    );
    process.exit(3);
  }

  const original = await readFile(CATALOG_PATH, 'utf8');
  if (!LINE_REGEX.test(original)) {
    console.error(
      `error: could not find "${VERSION_KEY}" in ${CATALOG_PATH}.\n` +
        '  The version catalog must declare `sdk-version = "…"` under [versions]. ' +
        'Was the catalog layout changed without updating this script?',
    );
    process.exit(4);
  }
  const updated = original.replace(LINE_REGEX, `$1"${newVersion}"`);
  await writeFile(CATALOG_PATH, updated, 'utf8');

  // Verify by re-reading — catches silent write failures and also proves
  // the regex did replace the target line.
  const verify = await readFile(CATALOG_PATH, 'utf8');
  const confirmRegex = new RegExp(`^${VERSION_KEY}\\s*=\\s*"${newVersion.replace(/[.+]/g, '\\$&')}"`, 'm');
  if (!confirmRegex.test(verify)) {
    console.error(
      `error: post-write verification failed — ${VERSION_KEY} is not "${newVersion}" in the catalog.`,
    );
    process.exit(5);
  }
  console.log(`bump-version: ${VERSION_KEY} → ${newVersion}`);
}

main().catch((err) => {
  console.error('bump-version: unexpected failure:', err);
  process.exit(1);
});
