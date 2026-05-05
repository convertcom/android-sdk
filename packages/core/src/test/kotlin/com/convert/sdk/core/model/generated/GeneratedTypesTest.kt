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
     * Unused — kept solely so the buildJsonObject import is referenced,
     * since the emptyRoundTrip test asserts against an empty JsonObject
     * that is built via [buildJsonObject] for clarity. The function body
     * is a no-op; the import-level reference is what matters.
     */
    @Suppress("unused")
    private fun buildJsonObjectFixture(): JsonElement = buildJsonObject { }
}
