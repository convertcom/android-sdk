# Readiness Assessment — Story 4.4 Visitor Segmentation

**Date:** 2026-04-22
**Feature:** android-sdk-4-4-visitor-segmentation
**Mode:** Autonomous sprint

## Verdict

**Score:** 8.4 / 10 — PASS (initial round, with auto-delegated clarifications)

## Dimension Breakdown

| Dimension | Score | Notes |
|---|---|---|
| Outcome clarity | 9 | AC-1 through AC-7 are specific and testable. |
| API surface | 9 | Setter signatures exist (Story 3.1); new work is persistence + event-payload wiring. |
| Data model | 9 | `StoreData.segments`, `Visitor.segments` already defined with `Map<String, JsonElement>?`. |
| Test expectations | 9 | AC-7 enumerates five named unit tests. |
| Dependencies | 9 | Story 3.1 (setters), 3.4 (RuleManager reads storeData.segments), 4.2 (event enqueue). |
| Edge cases | 8 | Empty map semantics specified (AC-6); null vs empty storage ambiguous. |
| Cross-SDK parity | 7 | JS SDK parity implied but not cited for segment payload shape. |
| Risk | 8 | Existing enqueue stubs must be extended — signature change risks churn. |

## Auto-delegated Questions (Q&A Round 1)

In autonomous sprint mode, the following ambiguities were delegated to implementation agents with "your call":

**Q1. AC-6 — null vs empty map storage?**
"Setting empty map clears to empty map (NOT null)". On StoreData, should `segments` be `emptyMap()` or `null` when caller passes `emptyMap()`?
**Your call.** Recommendation: Store as the exact map the caller supplied — `emptyMap()` stays `emptyMap()`. Consistent with how `setDefaultSegments` already stores an empty map in the in-memory field.

**Q2. AC-3 — Should enqueueBucketingEvent/enqueueConversionEvent signatures be extended?**
The current stubs take `(visitorId, experienceId, variationId)` and `(visitorId, goalId, goalData)`. Story 5.1 will build the full Visitor payload. Should this story extend the signatures with `segments` now, or defer?
**Your call.** Recommendation: Extend now with a defaulted `segments: Map<String, JsonElement> = emptyMap()` parameter. Default keeps existing callers/tests compiling; new callers pass the merged map. This satisfies AC-3 verifiably.

**Q3. AC-3 — Merge strategy for segments parameter type?**
Default segments are `Map<String, String>`; custom segments are `Map<String, JsonElement>`. Merged type?
**Your call.** Recommendation: Coerce defaults to `JsonPrimitive(string)` and merge with custom; custom wins on collision (per Dev Notes Gotcha 1). Expose as `getMergedSegments(): Map<String, JsonElement>`.

**Q4. AC-4 — Setter should persist on EVERY call?**
Dev Notes say "keep in sync on every setter call". Should `setDefaultSegments`/`setCustomSegments` block on DataManager.setStoreData synchronously?
**Your call.** Recommendation: Synchronous write. `DataManager.setStoreData` is already `synchronized(visitorLock)` and fast (in-memory cache + SharedPreferences string write). No launch needed.

**Q5. Visitor payload segments field omission on empty?**
AC-6 says "subsequent events have `segments: {}` (or omit the field if that's how the backend expects it)".
**Your call.** Recommendation: Pass `emptyMap()` through. `Visitor.segments` is already `Map<String, JsonElement>?` — serialization with `explicitNulls = false` will emit `segments: {}` when non-null+empty. Story 5.1 can adjust if backend requires omission.

## Estimated Score After Delegation: 9.0 / 10

All ambiguities resolved; implementation agent has clear direction. Proceeding to planning.
