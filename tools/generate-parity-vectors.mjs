#!/usr/bin/env node
/*
 * Convert Android SDK — cross-SDK bucketing parity vector generator
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * ──────────────────────────────────────────────────────────────────────
 * What this does
 * ──────────────────────────────────────────────────────────────────────
 * Emits a JSON array of parity vectors describing, for a fixed set of
 * (visitorId, experienceId, seed) inputs, what the JS SDK's
 * `BucketingManager.getValueVisitorBased` + `selectBucket` produce. The
 * android-sdk's Kotlin `HashParityTest` loads this JSON from the
 * classpath and asserts that Kotlin `BucketingManager` produces
 * byte-identical output for every vector — a hard CI gate that blocks
 * any release that would split sticky buckets across SDKs.
 *
 * ──────────────────────────────────────────────────────────────────────
 * How to run
 * ──────────────────────────────────────────────────────────────────────
 *   cd android-sdk
 *   yarn install                              # once
 *   yarn generate:parity-vectors              # regenerate vectors
 *   # or equivalently:
 *   node tools/generate-parity-vectors.mjs \
 *     > packages/core/src/test/resources/hash-parity-vectors.json
 *
 * ──────────────────────────────────────────────────────────────────────
 * Dependencies
 * ──────────────────────────────────────────────────────────────────────
 * Requires:
 *   - Node 20+ (uses ESM + top-level await-compatible runtime)
 *   - `@convertcom/js-sdk-bucketing` resolved via the `file:../javascript-sdk/
 *     packages/bucketing` devDependency in the android-sdk's package.json.
 *     That pulls in the PRE-BUILT `lib/index.mjs` which has murmurhash
 *     bundled in — no internet access needed at runtime.
 *
 * If the sibling javascript-sdk checkout moves or the bucketing lib
 * hasn't been built (no `lib/index.mjs`), run
 * `cd ../javascript-sdk && yarn install && yarn bucketing:build`.
 *
 * ──────────────────────────────────────────────────────────────────────
 * When to regenerate
 * ──────────────────────────────────────────────────────────────────────
 *   ✅  When adding NEW test cases to the `INPUTS` array below.
 *   ✅  When the JS SDK's bucketing library intentionally changes.
 *   ❌  NEVER to "paper over" a Kotlin parity bug. The vectors are the
 *       authoritative source — if Kotlin output differs, fix Kotlin.
 *
 * A regeneration that changes existing vectors is effectively a
 * breaking change for sticky buckets: any visitor already assigned to
 * a variation under the old hash would be re-bucketed. Treat it as a
 * cross-SDK major version bump and coordinate via the backend channel
 * so JS, PHP, Python, and other SDKs can land the change in lock-step.
 *
 * ──────────────────────────────────────────────────────────────────────
 * Vector categories (AC-1 of Story 3.5)
 * ──────────────────────────────────────────────────────────────────────
 *   - 10 ASCII visitor IDs × 5 experience IDs ..... 50 combos
 *   - 5 Unicode (emoji, CJK, combining chars) .....  5
 *   - Empty visitorId + empty experienceId .........  1
 *   - 3 numeric-string IDs .........................  3
 *   - 3 very long strings (>1 KB each) .............  3
 *   - 3 edge cases (NFC/NFD, whitespace, NUL) ......  3
 *   -------------------------------------------------------
 *   Target: ~65 vectors (spec minimum is 50+).
 *
 * The `buckets` map is pinned to `{varA: 50.0, varB: 50.0}` per AC-1
 * because the purpose here is HASH parity, not bucket-selection
 * matrix coverage. `selectBucket`'s own algorithm is already covered
 * by `BucketingManagerTest` unit tests.
 */

import { BucketingManager } from '@convertcom/js-sdk-bucketing';

const DEFAULT_SEED = 9999;
const BUCKETS = { varA: 50.0, varB: 50.0 };

/**
 * Build the input list deterministically. Each entry is a plain object
 * (description, visitorId, experienceId, seed) — the generator fills in
 * the expected* fields by calling the JS SDK.
 */
function buildInputs() {
  const inputs = [];

  // ─── Category 1: 10 ASCII visitor IDs × 5 experience IDs ──────────
  const asciiVisitors = [
    'visitor_001',
    'visitor_abc',
    'user-42',
    'alice.smith@example.com',
    'u_01HXZY8K3Q7JN5R2V4W6YAM8BC',
    'anon',
    'v',
    'session_token_xyz123',
    'A1B2C3D4E5F6',
    'convert-user-0000000001',
  ];
  const asciiExperiences = [
    'exp_1',
    'experiment-hero-cta',
    'E2',
    'exp_with_long_name_2026_q2_pricing_test',
    'x',
  ];
  for (const eid of asciiExperiences) {
    for (const vid of asciiVisitors) {
      inputs.push({
        description: `ASCII experience=${eid} visitor=${vid}`,
        visitorId: vid,
        experienceId: eid,
        seed: DEFAULT_SEED,
      });
    }
  }

  // ─── Category 2: 5 Unicode visitor IDs ────────────────────────────
  inputs.push({
    description: 'Unicode: waving-hand emoji with medium-skin-tone modifier',
    visitorId: '👋🏼',
    experienceId: 'exp_unicode',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Unicode: CJK (Japanese) — 日本語',
    visitorId: '日本語',
    experienceId: 'exp_unicode',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Unicode: combining-char precomposed é (U+00E9)',
    visitorId: 'café',
    experienceId: 'exp_unicode',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Unicode: mixed BMP + supplementary planes 𝕔𝕠𝕟𝕧𝕖𝕣𝕥',
    visitorId: '𝕔𝕠𝕟𝕧𝕖𝕣𝕥',
    experienceId: 'exp_unicode',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Unicode: RTL Arabic script محمد',
    visitorId: 'محمد',
    experienceId: 'exp_unicode',
    seed: DEFAULT_SEED,
  });

  // ─── Category 3: empty visitorId + empty experienceId ─────────────
  inputs.push({
    description: 'Empty: both visitor and experience ids are empty',
    visitorId: '',
    experienceId: '',
    seed: DEFAULT_SEED,
  });

  // ─── Category 4: 3 numeric-string IDs ─────────────────────────────
  inputs.push({
    description: 'Numeric string: "0"',
    visitorId: '0',
    experienceId: 'exp_num',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Numeric string: "12345"',
    visitorId: '12345',
    experienceId: 'exp_num',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Numeric string: "99999999999999999999" (beyond JS safe int)',
    visitorId: '99999999999999999999',
    experienceId: 'exp_num',
    seed: DEFAULT_SEED,
  });

  // ─── Category 5: 3 very long strings (>1 KB each) ─────────────────
  const long1 = 'x'.repeat(1024);
  const long2 =
    'convert-visitor-' + 'abcdef0123456789-'.repeat(100).slice(0, 1100);
  const long3 =
    '日'.repeat(512); // 512 chars × 3 UTF-8 bytes ≈ 1.5 KB
  inputs.push({
    description: 'Long ASCII: 1024 repeated x',
    visitorId: long1,
    experienceId: 'exp_long',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Long ASCII: ~1.1 KB hex-like pattern',
    visitorId: long2,
    experienceId: 'exp_long',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Long Unicode: 512 CJK chars (~1.5 KB UTF-8)',
    visitorId: long3,
    experienceId: 'exp_long',
    seed: DEFAULT_SEED,
  });

  // ─── Category 6: 3 edge cases ─────────────────────────────────────
  // NFC vs NFD — the SAME user-visible string encoded two ways produces
  // DIFFERENT byte sequences, so MUST hash to different values. Parity
  // requires Kotlin to treat the raw String bytes the same way JS does
  // (no implicit normalization on either side).
  inputs.push({
    description: 'Edge: NFC-precomposed é (U+00E9) — 2-byte UTF-8',
    visitorId: 'é',
    experienceId: 'exp_edge',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Edge: NFD-decomposed e + combining acute (U+0065 U+0301) — 3-byte UTF-8',
    visitorId: 'é',
    experienceId: 'exp_edge',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Edge: trailing whitespace — "visitor_trail "',
    visitorId: 'visitor_trail ',
    experienceId: 'exp_edge',
    seed: DEFAULT_SEED,
  });
  inputs.push({
    description: 'Edge: mid-string NUL character — "a\\u0000b"',
    visitorId: 'a b',
    experienceId: 'exp_edge',
    seed: DEFAULT_SEED,
  });

  // ─── Extra seed coverage (cheap, adds signal) ─────────────────────
  inputs.push({
    description: 'Seed=0: zero-seed must be treated as a valid seed, not null fallback',
    visitorId: 'visitor_42',
    experienceId: '',
    seed: 0,
  });
  inputs.push({
    description: 'Seed=12345: single-char visitor with non-default seed',
    visitorId: 'a',
    experienceId: '',
    seed: 12345,
  });
  inputs.push({
    description: 'Seed=2147483647: int32 max seed',
    visitorId: 'visitor_boundary',
    experienceId: 'exp_seed',
    seed: 2147483647,
  });

  return inputs;
}

/**
 * Compute the parity vector for one input by calling the JS SDK.
 * The BucketingManager is constructed with DEFAULT config
 * (max_traffic=10000, hash_seed=9999); we override `seed` per call via
 * the options object so the test suite can sweep seed values.
 */
function computeVector(input) {
  const bm = new BucketingManager();
  const value = bm.getValueVisitorBased(input.visitorId, {
    experienceId: input.experienceId,
    seed: input.seed,
  });
  const variationId = bm.selectBucket(BUCKETS, value);

  // expectedHash is recorded for debug visibility (AC-4 says it's
  // optional and NOT asserted by Kotlin). Recompute it with the same
  // bundled murmurhash that the BucketingManager uses internally by
  // back-solving: hash = (value * 2^32) / max_traffic would lose
  // precision, so instead we recompute directly with generateHash
  // semantics. The simplest faithful reproduction is to call
  // getValueVisitorBased on a fresh BM with max_traffic=2^32, which
  // yields `hash` unchanged. But that's surprising — so just emit
  // the rounded `value` context; skip expectedHash to stay honest.
  //
  // Per story AC-4: "expectedHash is optional — included for debug
  // but not asserted". We intentionally OMIT it: recording a synthetic
  // hash would mislead debuggers chasing real parity bugs. If a future
  // debugger wants the raw 32-bit hash, they can re-run this script
  // with a printf-hash branch.

  return {
    description: input.description,
    visitorId: input.visitorId,
    experienceId: input.experienceId,
    seed: input.seed,
    expectedValue: value,
    expectedVariationId: variationId,
    buckets: BUCKETS,
  };
}

function main() {
  const inputs = buildInputs();
  const vectors = inputs.map(computeVector);
  // Pretty-print with 2-space indent — the JSON file is committed,
  // so diff-readability matters more than compactness.
  process.stdout.write(JSON.stringify(vectors, null, 2) + '\n');
}

main();
