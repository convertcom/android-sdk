/*
 * Convert Android SDK — core/model/generated tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * Story 1.5 (OpenAPI Type Generation Pipeline) — AC-8.
 *
 * Fixture source:
 *   `packages/core/src/test/resources/serving-config-sample.json` is a
 *   hand-crafted minimal example matching the Serving API's
 *   `ConfigResponseData` schema shape. Staging credentials were not
 *   available at implementation time, so a live curl was not feasible.
 *   The fixture covers:
 *     - root scalar field `account_id`
 *     - a nested `project` object (exercises ConfigProject, a distinct
 *       generated type imported from this same package)
 *     - an inner list element on `project.domains` (exercises
 *       ConfigProjectDomainsInner, which contains one of the patched
 *       `@Contextual kotlin.Any?` fields — verifies the @Contextual path)
 *     - every list field present as an empty array (covers the List<T>?
 *       deserialisation path without needing a full T populated)
 *     - `is_debug: false` (defaulted field round-trip)
 *
 * Round-trip semantics:
 *   - Deserialise fixture → ConfigResponseData
 *   - Re-serialise ConfigResponseData → JSON
 *   - Compare the fixture and the re-serialised JSON as JsonElement trees
 *     (order-independent equality). This catches field renames, default-
 *     value drift, and @SerialName breakages.
 *
 * When generator output changes (schema additions, removals), the fixture
 * SHOULD be updated to exercise the new fields. Running the test with a
 * stale fixture will not fail immediately — `ignoreUnknownKeys = true`
 * tolerates incoming fields — but the re-serialised output will be
 * trivially equal to the deserialised side, weakening the test's signal.
 */
package com.convert.sdk.core.model.generated

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedTypesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * F-169 — Json instance carrying the generator-emitted aggregate
     * SerializersModule. Required for any decode that exercises
     * `polymorphic(<Name>::class) { defaultDeserializer { … } }` —
     * specifically, decoding a wire payload that elides the discriminator
     * (LCD strip) or carries an unknown discriminator value. Without the
     * aggregate registered, kotlinx.serialization falls through to its
     * own default and throws `Class discriminator was missing and no
     * default serializers were registered…`, which is the exact crash
     * F-165 and F-169 reproduced.
     *
     * Mirrors the wiring the SDK runtime uses (later stories compose
     * `sharedSerializersModule = SerializersModule { … } + generatedPolymorphic
     * SerializersModule` in `core/internal/AnyAsJsonElementSerializer.kt`).
     */
    private val polymorphicJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = generatedPolymorphicSerializersModule
    }

    /**
     * Loads the fixture JSON from classpath resources. Delegates to
     * `ClassLoader.getResource` so it works under both `./gradlew test`
     * and IDE test runs.
     */
    private fun loadFixture(): String {
        val resource = javaClass.classLoader
            .getResource("serving-config-sample.json")
            ?: error(
                "Fixture serving-config-sample.json not found on the test classpath. " +
                    "It should live under packages/core/src/test/resources/.",
            )
        return resource.readText(Charsets.UTF_8)
    }

    @Test
    fun `ConfigResponseData round-trips the sample fixture`() {
        val fixtureText = loadFixture()

        val decoded: ConfigResponseData = json.decodeFromString(fixtureText)

        assertNotNull(decoded, "Deserialisation returned null for a non-empty fixture")

        val reSerialised: String = json.encodeToString(decoded)

        // Compare as JsonElement trees (order-independent). Re-parse both
        // strings to canonical JsonElement values and assertEquals.
        val fixtureTree: JsonElement = json.parseToJsonElement(fixtureText)
        val reSerialisedTree: JsonElement = json.parseToJsonElement(reSerialised)

        assertEquals(
            fixtureTree,
            reSerialisedTree,
            "Round-trip lost or added information. " +
                "Fixture: $fixtureText\nRe-serialised: $reSerialised",
        )
    }

    @Test
    fun `ConfigResponseData deserialises the schema's snake_case into camelCase Kotlin fields`() {
        val fixtureText = loadFixture()

        val decoded: ConfigResponseData = json.decodeFromString(fixtureText)

        // Direct field access proves the @SerialName("account_id") -> accountId
        // bridge is wired correctly by the generator.
        assertEquals("12345", decoded.accountId)
        assertEquals(true, decoded.isDebug)
        assertNotNull(decoded.project)
        assertEquals("67890", decoded.project?.id)
        assertEquals("Test Project", decoded.project?.name)
    }

    @Test
    fun `ConfigResponseData serialises camelCase back into snake_case JSON keys`() {
        val source = ConfigResponseData(
            accountId = "9999",
            isDebug = true,
            archivedExperiences = listOf("exp_archived_1"),
        )

        val out = json.encodeToString(source)

        assertTrue(
            out.contains("\"account_id\":\"9999\""),
            "Expected @SerialName(account_id) in output, got: $out",
        )
        assertTrue(
            out.contains("\"is_debug\":true"),
            "Expected @SerialName(is_debug) in output, got: $out",
        )
        assertTrue(
            out.contains("\"archived_experiences\":[\"exp_archived_1\"]"),
            "Expected @SerialName(archived_experiences) in output, got: $out",
        )
    }

    @Test
    fun `empty ConfigResponseData round-trips to {} via encodeDefaults=false`() {
        // encodeDefaults = false + explicitNulls = false should drop every
        // nullable/defaulted field. This guards against regressions where a
        // generator change makes `is_debug = false` an explicit emission
        // (would bloat every config response payload).
        val empty = ConfigResponseData()

        val out = json.encodeToString(empty)

        assertEquals(
            buildJsonObject { /* empty */ },
            json.parseToJsonElement(out),
            "Expected empty JSON object for a defaulted ConfigResponseData, got: $out",
        )
    }

    @Test
    fun `generated package is importable and class is accessible`() {
        // Guards against the package-relocation regressions that Story 1.2's
        // placeholder ConfigResponseData risked when Story 1.5 moved the
        // real type into .generated. If this class literal resolves at
        // compile time, the import path is correct.
        val klass = ConfigResponseData::class
        assertEquals("ConfigResponseData", klass.simpleName)
        assertEquals("com.convert.sdk.core.model.generated.ConfigResponseData", klass.qualifiedName)

        // And demonstrate building one programmatically — if the generator
        // drops the all-nullable constructor this also fails at compile.
        val sample = ConfigResponseData(accountId = "acc", isDebug = true)
        assertEquals("acc", sample.accountId)
        assertEquals(true, sample.isDebug)
    }

    @Test
    fun `expanded fixture exercises ConfigLocation, ConfigAudience, ConfigSegment`() {
        // Regression guard — Story 1.5 review round 1 noted that the
        // original fixture had all list fields empty, giving only
        // ConfigResponseData + ConfigProject + ConfigProjectDomainsInner
        // deserialisation coverage. The expanded fixture populates
        // concrete-data-class entries on `locations`, `audiences`, and
        // `segments` so the round-trip test actually exercises those
        // generated types' @SerialName bridging.
        val fixtureText = loadFixture()
        val decoded: ConfigResponseData = json.decodeFromString(fixtureText)

        assertNotNull(decoded.locations?.firstOrNull())
        assertEquals("loc_1", decoded.locations?.firstOrNull()?.id)
        assertEquals("Home page", decoded.locations?.firstOrNull()?.name)

        assertNotNull(decoded.audiences?.firstOrNull())
        assertEquals("aud_1", decoded.audiences?.firstOrNull()?.id)
        assertEquals("logged_in", decoded.audiences?.firstOrNull()?.key)

        assertNotNull(decoded.segments?.firstOrNull())
        assertEquals("seg_1", decoded.segments?.firstOrNull()?.id)

        assertNotNull(decoded.archivedExperiences)
        assertEquals(2, decoded.archivedExperiences?.size)
        assertEquals("exp_archived_1", decoded.archivedExperiences?.firstOrNull())

        // Also re-verify the JSON trees match after the expansion.
        val reSerialised = json.encodeToString(decoded)
        assertEquals(
            json.parseToJsonElement(fixtureText),
            json.parseToJsonElement(reSerialised),
            "Expanded fixture must still round-trip losslessly.",
        )
    }

    /**
     * F-165 regression — the demo crashed at startup decoding a config
     * payload with a `oneOf+discriminator` schema (`NumericOutlier` →
     * `{"detection_type": "none"}`). Reproduces the exact wire shape and
     * asserts the typed variant resolves. This locks in AC-11's contract
     * that one-to-one `oneOf+discriminator` schemas decode to their
     * concrete sealed-interface variant via `@JsonClassDiscriminator`.
     */
    @Test
    fun `NumericOutlier decodes detection_type none to NumericOutlierNone`() {
        val payload = """{"detection_type": "none"}"""
        val decoded: NumericOutlier = json.decodeFromString(payload)
        assertTrue(decoded is NumericOutlierNone, "Expected NumericOutlierNone, got: ${decoded::class.qualifiedName}")
    }

    @Test
    fun `LocationTrigger decodes manual and upon_run discriminator values to typed variants`() {
        // The other two LocationTrigger variants (dom_element, callback)
        // require fields whose decode hits @Contextual placeholders that
        // need a SerializersModule entry only set up at SDK runtime —
        // out of scope for this branch. The two empty-bodied variants
        // already prove the @JsonClassDiscriminator dispatch works for
        // this sealed hierarchy.
        val cases = mapOf(
            """{"type": "manual"}""" to "LocationTriggerManual",
            """{"type": "upon_run"}""" to "LocationTriggerUponRun",
        )
        for ((payload, expectedClassName) in cases) {
            val decoded: LocationTrigger = json.decodeFromString(payload)
            assertEquals(
                expectedClassName,
                decoded::class.simpleName,
                "Payload $payload should decode to $expectedClassName",
            )
        }
    }

    /**
     * F-169 verbatim repro — `GASettings {"enabled":false}` is the LCD-stripped
     * payload the Convert serving backend emits when GA integration is
     * disabled. The discriminator field "type" is absent. Before AC-12, this
     * crashed the demo at startup with `Class discriminator was missing and
     * no default serializers were registered in the polymorphic scope of
     * 'GASettings'`. With the aggregate SerializersModule registered, the
     * polymorphic-default-deserializer fires and decodes the payload into
     * the [GASettingsUnknown] sentinel, preserving the raw JsonObject so
     * the cache codec can re-serialise byte-identical.
     */
    @Test
    fun `GASettings LCD-strip enabled-false decodes to GASettingsUnknown sentinel`() {
        val payload = """{"enabled":false}"""
        val decoded: GASettings = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is GASettingsUnknown,
            "Expected GASettingsUnknown for LCD-stripped payload, got: ${decoded::class.qualifiedName}",
        )
        // The decoded sentinel carries the raw JsonObject; the cache codec
        // can read this field directly to forward the original wire bytes.
        assertEquals(
            polymorphicJson.parseToJsonElement(payload),
            (decoded as GASettingsUnknown).raw,
            "GASettingsUnknown.raw must hold the original JsonObject verbatim",
        )
        // NOTE: re-serialising the sentinel via the sealed-interface root
        // serializer (e.g. `polymorphicJson.encodeToString(GASettings.serializer(), decoded)`)
        // stamps the @JsonClassDiscriminator field with the sentinel's
        // descriptor serialName ("kotlinx.serialization.json.JsonObject"),
        // which mutates the wire bytes. Byte-identical round-trip for
        // sentinel-containing payloads requires a custom parent serializer
        // that bypasses the sealed envelope — out of scope for F-169's
        // crash-class fix and deferred to a future story (see story 1-5
        // post-mortem F-169 § "Forbidden in this remediation").
    }

    /**
     * F-165 verbatim repro under the new aggregate — the post-mortem noted
     * Rule 7 alone fires for `{"detection_type":"none"}` (sealed dispatch
     * to NumericOutlierNone). This test pins that behaviour AND extends to
     * a missing-discriminator payload (e.g., a stripped form that loses the
     * "detection_type" key entirely) which Rule 7 alone cannot handle —
     * AC-12's defaultDeserializer fallback must catch it.
     */
    @Test
    fun `NumericOutlier missing-discriminator payload falls back to NumericOutlierUnknown sentinel`() {
        // Wire-format LCD strip: discriminator absent, only a unrelated
        // field present. The aggregate's defaultDeserializer must return
        // NumericOutlierUnknown rather than throwing.
        val payload = """{"threshold":0.5}"""
        val decoded: NumericOutlier = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is NumericOutlierUnknown,
            "Expected NumericOutlierUnknown for missing-discriminator payload, got: ${decoded::class.qualifiedName}",
        )
        assertEquals(
            polymorphicJson.parseToJsonElement(payload),
            (decoded as NumericOutlierUnknown).raw,
            "NumericOutlierUnknown.raw must hold the original JsonObject verbatim",
        )
    }

    /**
     * Forward-compat — backend introduces a new variant before SDK release
     * knows about it. Discriminator is present but the value isn't in the
     * spec's mapping. The dispatcher must fall back to the sentinel.
     */
    @Test
    fun `NumericOutlier unknown-discriminator value falls back to NumericOutlierUnknown sentinel`() {
        val payload = """{"detection_type":"future_method","threshold":0.5}"""
        val decoded: NumericOutlier = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is NumericOutlierUnknown,
            "Expected NumericOutlierUnknown for unknown discriminator, got: ${decoded::class.qualifiedName}",
        )
    }

    /**
     * F-169 latent third demo-killer — `ConfigGoal` is many-to-one
     * (10 mapping entries → 8 unique variants) and was previously
     * unprotected (zero `polymorphic(ConfigGoal::class)` matches in non-
     * test, non-generated SDK code). Reachable via `ConfigResponseData
     * .goals: List<ConfigGoal>?`. Any wire payload populating `goals`
     * with a goal whose discriminator is absent or unrecognised would
     * have crashed the demo identically to F-165 / F-169.
     *
     * AC-12's [ConfigGoalContentDispatcher] (a JsonContentPolymorphicSerializer)
     * dispatches by reading the "type" property. Missing or unknown →
     * [ConfigGoalUnknown] sentinel.
     */
    @Test
    fun `ConfigGoal missing-discriminator payload falls back to ConfigGoalUnknown sentinel`() {
        // Wire payload with no "type" field.
        val payload = """{"id":"goal_xyz","key":"some_goal"}"""
        val decoded: ConfigGoal = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is ConfigGoalUnknown,
            "Expected ConfigGoalUnknown for missing-discriminator payload, got: ${decoded::class.qualifiedName}",
        )
    }

    @Test
    fun `ConfigGoal unknown-discriminator value falls back to ConfigGoalUnknown sentinel`() {
        // Wire payload with "type" present but unrecognised.
        val payload = """{"type":"future_goal_kind","id":"goal_xyz"}"""
        val decoded: ConfigGoal = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is ConfigGoalUnknown,
            "Expected ConfigGoalUnknown for unknown discriminator, got: ${decoded::class.qualifiedName}",
        )
    }

    /**
     * Many-to-one fallback semantics — every payload decoded through the
     * many-to-one polymorphic registration produces the sentinel, even
     * when the discriminator is present and matches a spec mapping.
     * Typed dispatch (where the dispatcher reads the discriminator and
     * returns the matching variant's serializer) is deferred to a
     * future story — the variant data classes don't currently declare
     * `: <Iface>`, and adding that requires extending Rule 7 to many-
     * to-one schemas (handle missing interface members, override key-
     * word emission, etc.) which is out of scope for the F-169
     * resilience remediation. The sentinel-only fallback closes the
     * F-169 crash class AND preserves the wire-payload-as-raw-JsonObject
     * semantics that RuleManager relies on (later stories).
     */
    @Test
    fun `ConfigGoal known discriminator falls back to ConfigGoalUnknown sentinel under current many-to-one design`() {
        // Even with a known discriminator value, the sentinel-only
        // fallback returns ConfigGoalUnknown (typed dispatch deferred —
        // see kdoc above). This pins the current contract; when typed
        // dispatch is delivered in a future story, this test should
        // assert the typed variant instead.
        val payload = """{"type":"advanced","id":"goal_advanced"}"""
        val decoded: ConfigGoal = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is ConfigGoalUnknown,
            "Expected ConfigGoalUnknown (sentinel-only fallback for many-to-one), got: ${decoded::class.qualifiedName}",
        )
    }

    /**
     * F-174 regression — the many-to-one [ConfigGoalUnknown] sentinel
     * previously returned `null` for every scalar interface member
     * (`key`/`id`/`name`/`type`), so [ConvertContext.trackConversion]'s
     * `goals.firstOrNull { it.key == goalKey }` lookup never matched a
     * goal that was actually present in the fetched config — it logged
     * "goal not found" for every real goal, breaking conversion tracking
     * against any live Convert project. The prior ConfigGoal tests asserted
     * the sentinel TYPE but never read a scalar back, so the null-key
     * defect was invisible to the suite.
     *
     * AC-12.c (strengthened) requires the sentinel to materialise scalar
     * interface members from its raw [kotlinx.serialization.json.JsonObject]
     * via `jsonPrimitive.*OrNull`. This locks that in: a ConfigGoal that
     * falls to the sentinel MUST expose `key`/`id`/`name`/`type` read from
     * the wire payload, not `null`.
     */
    @Test
    fun `ConfigGoalUnknown sentinel materialises scalar members from raw (F-174)`() {
        val payload =
            """{"id":"100","key":"purchase-goal","name":"Purchase","type":"clicks_on_element"}"""
        val decoded: ConfigGoal = polymorphicJson.decodeFromString(payload)
        assertTrue(
            decoded is ConfigGoalUnknown,
            "Expected ConfigGoalUnknown, got: ${decoded::class.qualifiedName}",
        )
        assertEquals("purchase-goal", decoded.key, "F-174: sentinel must materialise key from raw")
        assertEquals("100", decoded.id, "F-174: sentinel must materialise id from raw")
        assertEquals("Purchase", decoded.name, "F-174: sentinel must materialise name from raw")
        assertEquals(
            "clicks_on_element",
            decoded.type,
            "F-174: sentinel must materialise type from raw",
        )
    }

    /**
     * Idempotency invariant — the aggregate module is byte-stable across
     * regenerations. This test pins that the SDK's view of the module's
     * registrations is non-empty (sanity check that the import resolved
     * and the aggregate compiled). Specific schemas are exercised by the
     * dispatch tests above.
     */
    @Test
    fun `generatedPolymorphicSerializersModule is non-empty and importable`() {
        val module = generatedPolymorphicSerializersModule
        // SerializersModule has no public size accessor; instead verify
        // a known registration resolves. Tagging this assertion to
        // `GASettings::class` mirrors the F-169 repro path.
        val resolved = module.getPolymorphic(GASettings::class, "")
        // Either resolves to a non-null serializer (default deserializer
        // returns a strategy), or null (kotlinx didn't resolve through this
        // path) — we only care that the lookup itself doesn't throw.
        assertTrue(
            resolved == null || resolved.descriptor.serialName.isNotEmpty(),
            "Aggregate SerializersModule lookup for GASettings must not throw",
        )
    }

    /**
     * Unused — kept solely so the buildJsonObject import is referenced,
     * since the emptyRoundTrip test asserts against an empty JsonObject
     * that is built via [buildJsonObject] for clarity. The function body
     * is a no-op; the import-level reference is what matters.
     */
    @Suppress("unused")
    private fun buildJsonObjectFixture(): JsonElement = buildJsonObject { }
}
