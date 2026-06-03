# Auto-generated Kotlin Types (OpenAPI → kotlinx.serialization)

**Do not hand-edit any `.kt` file in this directory.**

Every file here is produced by `openapi-generator-cli` (Kotlin generator
with `serializationLibrary=kotlinx_serialization`) from the Convert Serving
OpenAPI specification. The backend repo is the **single authoritative
source** for the schema — all type changes happen there first, then are
regenerated and synced here.

The Android SDK CI lint job (see `.github/workflows/ci.yml` lint job)
enforces the auto-generated header on every file in this directory. Hand-
edited files will be caught at PR time.

---

## Regenerating the types (manual flow — MVP)

1. **Switch to the backend repo's Serving spec directory.**

   ```bash
   cd ../backend/apiDoc/serving
   ```

2. **Install deps if you haven't already.**

   ```bash
   yarn install
   ```

3. **Run the generator chain.**

   ```bash
   yarn buildKotlinTypes
   ```

   This performs three steps:
     - `buildOneFileCustom` — combines the multi-file spec into
       `build/serving.yaml`
     - `test` — `swagger-cli` validates the combined spec
     - `generateKotlinTypes` — runs `openapi-generator-cli`, then
       `patchKotlinGeneratorBugs.js` (fixes 5 known generator bugs —
       BigDecimal enum defaults, `@Serializable` on non-sealed interfaces,
       stray `override` without parent clause, `kotlin.Any` in generic
       type parameters, self-referential nested `Type` enum), then
       `prependKotlinHeader.js` (prepends the "do not edit" header to
       every `.kt` file).

   The output lands in `dist-kotlin/com/convert/sdk/core/model/generated/`.

4. **Copy the output into the Android SDK.**

   From the Android SDK repo root:

   ```bash
   rm -rf packages/core/src/main/kotlin/com/convert/sdk/core/model/generated/*.kt
   cp ../backend/apiDoc/serving/dist-kotlin/com/convert/sdk/core/model/generated/*.kt \
      packages/core/src/main/kotlin/com/convert/sdk/core/model/generated/
   ```

   Do NOT delete this `README.md` during the sync (it is not generated).

5. **Verify the build.**

   ```bash
   ./gradlew :packages:core:build :packages:sdk:build
   ```

6. **Commit with a conventional `feat(types)` message so Story 1.4's
   semantic-release picks it up as a minor release.**

   ```bash
   git add packages/core/src/main/kotlin/com/convert/sdk/core/model/generated/
   git commit -m "feat(types): regenerate from serving openapi $(date +%Y-%m-%d)"
   ```

   Note: these are TWO commits, on TWO repos:
     - The backend's spec change (actual OpenAPI edit) lands in the
       `backend` repo first.
     - The regenerated types land in this (`android-sdk`) repo second.

---

## Known limitations

- **Manual sync only.** An automated workflow (backend CI opens a PR in
  android-sdk with the regenerated types) is out of scope for MVP and is
  tracked as a future enhancement.
- **Generator version pinned.** `openapi-generator-cli` is pinned in
  `backend/apiDoc/serving/openapitools.json`. Per Story 1.5 AC-1
  (corrected), the target pin is `7.13.0` — the current version in the
  backend's `openapitools.json` on default branches. Verify the latest
  stable at `https://github.com/OpenAPITools/openapi-generator/releases`
  during implementation and update if a newer stable version is
  available. The generated `.kt` files currently committed to this
  directory were produced by `7.10.0` on the unmerged backend branch
  `api-docs/auttomate-android-config-types`; when the backend tooling
  re-lands on `main` aligned with the corrected story, regenerate from
  `7.13.0` and re-sync. Upgrading the generator may require revisiting
  `patchKotlinGeneratorBugs.js` — five `7.10.0`-era bugs are patched
  there; some may be fixed in `7.13.0` and others may newly appear.
- **Spec validation bypassed for generation.** `skipValidateSpec: true`
  is set in `openapitools.json` because the generator's strict validator
  produces false positives on Serving's `visitor-data` path parameters.
  `swagger-cli validate` (the `npm run test` step) still runs first and
  enforces structural validity. The stricter false positive fix belongs
  upstream in the Serving spec.
- **Some types contain `kotlin.Any` (scalar) wrapped in `@Contextual`.**
  Callers that deserialize these fields must supply a `SerializersModule`
  with a fallback serializer registered for `Any`. This affects
  `ExperienceChange*` types and is unused by `ConfigResponseData`-reachable
  code paths in the MVP SDK. Flagged as a follow-up for the Serving API
  team: prefer discriminated `oneOf` subtypes over bare `Any` where
  practical.

---

## Naming convention

- Package: `com.convert.sdk.core.model.generated` (for all files here).
- File name: `<ClassName>.kt`.
- Hand-written models live in `com.convert.sdk.core.model` (one level up).
  There is currently no class-name collision between the two packages.
  Fully qualify imports if a future schema change introduces one.

## Relationship to `packages/core`

- `packages/core/build.gradle.kts` excludes `com.convert.sdk.core.model.generated`
  from Kover coverage filters (generated types have no tests and would
  skew the 85% line-coverage threshold).
- The root `build.gradle.kts` excludes `**/generated/**` from detekt
  (style rules would produce thousands of spurious findings on generated
  files).
- Round-trip correctness is exercised by
  `packages/core/src/test/kotlin/com/convert/sdk/core/model/generated/GeneratedTypesTest.kt`
  (loads a fixture JSON, deserializes into `ConfigResponseData`,
  re-serializes, asserts JSON-tree equality).

## Reference

- Story 1.5 (OpenAPI Type Generation Pipeline):
  `/Users/abbas/Sites/convert/ai-driven-product-dev/_bmad-output/implementation-artifacts/2026-03-23-convert-android-sdk/1-5-openapi-type-generation-pipeline.md`
- Backend generator config:
  `/Users/abbas/Sites/convert/backend/apiDoc/serving/openapitools.json`
- Backend post-processors:
  `/Users/abbas/Sites/convert/backend/apiDoc/serving/src/scripts/patchKotlinGeneratorBugs.js`
  `/Users/abbas/Sites/convert/backend/apiDoc/serving/src/scripts/prependKotlinHeader.js`
