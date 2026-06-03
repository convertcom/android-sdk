# Cross-SDK Bucketing Parity Workflow

This document defines the engineering protocol for keeping the Convert
Android SDK's MurmurHash3 bucketing output in lock-step with the JS SDK
(and, transitively, every other Convert SDK that consumes the same hash
vectors). It is referenced from Story 3.5 AC-6 / AC-7 and from the user
guide produced by Story 6.2.

> **Hard rule.** A visitor bucketed by one SDK MUST land in the same
> variation when bucketed by any other SDK. The vector file
> `packages/core/src/test/resources/hash-parity-vectors.json` is the
> shared truth source; the Kotlin `HashParityTest` is the gate that
> enforces it.

---

## Scope of this repo

The android-sdk owns the **Kotlin side** of parity:

- The vector file at `packages/core/src/test/resources/hash-parity-vectors.json`
  (committed; regenerated only from the JS SDK reference).
- The generation script at `tools/generate-parity-vectors.mjs`
  (committed; runs against the PUBLISHED `@convertcom/js-sdk-bucketing`
  npm package, pinned in `tools/package.json`). The script and its
  dependency live in an isolated `tools/package.json` — deliberately
  separate from the root release `package.json`, so the release pipeline
  never installs the parity dependency. Regeneration is run from `tools/`
  and needs no sibling javascript-sdk checkout (it resolves bucketing from
  npm), making it portable and reproducible against the exact version
  other Convert SDKs consume.
- The Kotlin parity gate `HashParityTest.kt`.

The JS side maintains its own equivalent parity suite. Other SDKs (PHP,
Python, …) consume the SAME vector file conceptually but mirror it into
their own test resources — coordinated via the backend channel.

---

## When a parity divergence is discovered

A "parity divergence" means: the Kotlin `BucketingManager` produces a
different `expectedValue` or `expectedVariationId` from the vector for
some input. This is **always** a Kotlin-side bug to fix — never a vector
to silently regenerate.

### Workflow

1. **Diagnose Kotlin first.** Read the failing vector's `description`,
   reproduce the input locally, and find why
   `BucketingManager.getValueVisitorBased` diverges from the recorded
   JS output. Common causes:
   - UTF-8 byte conversion mismatch (NFC/NFD, surrogate pairs, …)
   - Unsigned-vs-signed hash masking
   - Truncation rounding (`Double.toInt` vs JS `Math.floor`)
   - Seed plumbing (zero-seed treated as null fallback)

2. **Fix Kotlin to restore parity.** Change `BucketingManager` (or its
   `HashAlgorithm`) so the existing vector passes again **without
   regenerating the JSON**. Add a unit test in `BucketingManagerTest`
   or `HashAlgorithmTest` that pins the specific cause so a future
   refactor cannot reintroduce the bug.

3. **Add the divergent input as a permanent vector.** Once Kotlin is
   green, add a new entry to `tools/generate-parity-vectors.mjs`'s
   `INPUTS` array describing the previously-divergent case (with a
   precise `description`). Then regenerate locally:

   ```sh
   cd android-sdk/tools
   yarn install
   yarn generate:parity-vectors
   ```

   This rewrites `hash-parity-vectors.json`. Inspect the diff carefully:

   - **New entries appended** — expected.
   - **Existing `expectedValue`/`expectedVariationId` changed** — STOP.
     That means either (a) Kotlin still diverges from JS for an
     existing input, or (b) the JS SDK itself has changed semantics,
     in which case treat the regeneration as a cross-SDK breaking
     change (see "JS SDK changes hash logic" below).

4. **Mirror the new vector into the JS SDK parity suite.** Open a PR
   in the javascript-sdk repo against the equivalent parity test
   adding the same `(visitorId, experienceId, seed)` input. The two
   suites must remain symmetric so neither SDK drifts unnoticed.

5. **Coordinate via the backend channel.** Note the new vector and
   the Kotlin fix in the cross-SDK coordination thread so PHP, Python,
   and any other SDK maintainers can mirror the input + diagnostic in
   their own suites.

---

## When the JS SDK changes hash logic

If `@convertcom/js-sdk-bucketing` intentionally changes its hash output
(e.g. switches MurmurHash variant, changes seed semantics, adopts
normalization), regenerating `hash-parity-vectors.json` will produce
different `expectedValue`s for inputs that have NOT changed. That is a
**breaking change for sticky buckets** — every existing visitor would be
re-bucketed.

Treat as a major-version coordination event:

- Bump `MAJOR` synchronously across all SDKs (JS, Android, PHP, Python, …).
- Document the migration in each SDK's user guide.
- Land vectors and Kotlin changes in lock-step, never piecemeal.

A regeneration that mutates existing vectors **without** an explicit
cross-SDK coordination decision is a defect to revert — it would
silently split sticky assignments across SDK versions.

---

## Anti-patterns (never do these)

- ❌ **Regenerate vectors to "fix" a failing parity test.** That papers
  over the Kotlin bug and silently breaks every visitor already
  bucketed by the JS SDK.
- ❌ **Edit `hash-parity-vectors.json` by hand.** The file is generated
  output — manual edits lose JS provenance and can't be reproduced.
- ❌ **Add Node.js inside the Kotlin runtime to "match" JS hashing.**
  Kotlin must produce parity natively; Node-in-Kotlin is forbidden.
- ❌ **Land a vector change without a Kotlin code-side test pinning the
  cause.** A regression-pinning test in `BucketingManagerTest` /
  `HashAlgorithmTest` is mandatory so the fix doesn't silently
  regress.

---

## Ownership

- **Vector file** — owned jointly with javascript-sdk; maintainers must
  ratify any non-additive change.
- **Generation script** — owned by android-sdk; mirrors the JS SDK's
  current public bucketing API.
- **`HashParityTest`** — owned by android-sdk; is a HARD CI gate. Do
  not weaken or skip.
