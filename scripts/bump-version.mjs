#!/usr/bin/env node
/**
 * bump-version.mjs — single-purpose script invoked by @semantic-release/exec
 * during the prepare phase to rewrite the `sdk-version` entry in
 * `gradle/libs.versions.toml`.
 *
 * Why a dedicated script instead of `sed`:
 *   - TOML is whitespace-tolerant and permits comments on the same line.
 *     A naive `sed` pattern can silently corrupt the catalog (e.g., drop
 *     trailing comments, or match unrelated keys whose name is a prefix).
 *   - We want verifiable round-trip: write the new value, re-read the file,
 *     and confirm the new value is actually present. If anything goes wrong
 *     (regex didn't match, file system quirks, stale cache), we exit non-zero
 *     and semantic-release aborts BEFORE the publish step runs.
 *
 * The script matches the `sdk-version` key on its own line under the
 * `[versions]` table via a line-anchored regex:
 *     /^(sdk-version\s*=\s*)"[^"]*"/m
 * This tolerates any surrounding whitespace and preserves the rest of the
 * line (including any trailing comment). It intentionally does NOT handle
 * multi-line TOML strings or arrays — `sdk-version` is a single quoted
 * scalar and will remain so.
 *
 * Usage: `node scripts/bump-version.mjs <new-version>`
 */
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const CATALOG_PATH = resolve(__dirname, '..', 'gradle', 'libs.versions.toml');
const KEY = 'sdk-version';
const PATTERN = new RegExp(`^(${KEY}\\s*=\\s*)"[^"]*"`, 'm');

async function main() {
  const [, , newVersion] = process.argv;

  if (!newVersion) {
    console.error('bump-version.mjs: missing required argument <new-version>');
    console.error('usage: node scripts/bump-version.mjs <new-version>');
    process.exit(1);
  }

  // Reject anything that's not a plausible semver. semantic-release always
  // passes a real version string, but defensive validation keeps a stray
  // manual invocation from writing nonsense into the catalog.
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(newVersion)) {
    console.error(`bump-version.mjs: '${newVersion}' is not a valid semver`);
    process.exit(1);
  }

  const original = await readFile(CATALOG_PATH, 'utf8');

  if (!PATTERN.test(original)) {
    console.error(
      `bump-version.mjs: could not find '${KEY}' entry in ${CATALOG_PATH}`,
    );
    console.error(
      'Expected a line matching:  sdk-version = "<current-version>"',
    );
    process.exit(1);
  }

  const updated = original.replace(PATTERN, `$1"${newVersion}"`);

  if (updated === original) {
    // Pattern matched but replacement produced no change (new version == old).
    // Not a hard error — semantic-release should not schedule a release when
    // nothing changed, but this keeps the script idempotent.
    console.log(
      `bump-version.mjs: ${KEY} is already ${newVersion}; nothing to do.`,
    );
    return;
  }

  await writeFile(CATALOG_PATH, updated, 'utf8');

  // Round-trip verification — guard against file-system or encoding glitches.
  const reread = await readFile(CATALOG_PATH, 'utf8');
  const verifyPattern = new RegExp(
    `^${KEY}\\s*=\\s*"${newVersion.replace(/[.+]/g, '\\$&')}"`,
    'm',
  );
  if (!verifyPattern.test(reread)) {
    console.error(
      `bump-version.mjs: verification failed — '${KEY}' is not '${newVersion}' after write`,
    );
    process.exit(1);
  }

  console.log(`bump-version.mjs: ${KEY} -> ${newVersion}`);
}

main().catch((error) => {
  console.error('bump-version.mjs: unexpected failure:', error);
  process.exit(1);
});
