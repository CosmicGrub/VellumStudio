package com.vellum.studio.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [ProjectSchemaMigrator]'s generic engine end-to-end with a *synthetic* upgrade chain.
 * There is no real version 2 yet ([ProjectMeta.CURRENT_SCHEMA_VERSION] is still 1 -- see that file
 * and [ProjectSchemaMigrator]'s own doc for why) so this is what proves the mechanism itself works
 * -- version detection, step ordering, final stamping -- rather than leaving it unexercised until
 * the day a real format change needs it.
 *
 * Plain JUnit, no Robolectric: everything here is pure `kotlinx.serialization` JSON-tree
 * manipulation, no Android framework classes involved.
 */
class ProjectSchemaMigratorTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `a blob with no schemaVersion key at all is treated as version 1`() {
        val v1 = buildJsonObject { put("name", JsonPrimitive("Old Project")) }
        assertEquals(1, ProjectSchemaMigrator.versionOf(v1))
    }

    @Test
    fun `a blob that already declares schemaVersion reports that version`() {
        val v5 = buildJsonObject { put("schemaVersion", JsonPrimitive(5)) }
        assertEquals(5, ProjectSchemaMigrator.versionOf(v5))
    }

    @Test
    fun `a fake version-1 blob missing a field the target schema requires is upgraded and back-filled`() {
        // Simulates a hypothetical future version 2 that introduces "paperSizePreset" -- every real
        // version-1 file on disk today predates that field entirely, exactly like this blob.
        val v1 = buildJsonObject { put("id", JsonPrimitive("abc123")) }
        val addPaperSizePreset = ProjectSchemaMigrator.Step { current ->
            buildJsonObject {
                current.forEach { (key, value) -> put(key, value) }
                put("paperSizePreset", JsonPrimitive("unspecified"))
            }
        }

        val migrated = ProjectSchemaMigrator.migrate(v1, targetVersion = 2, steps = listOf(addPaperSizePreset))

        assertEquals(2, (migrated.getValue("schemaVersion") as JsonPrimitive).int)
        assertEquals("unspecified", (migrated.getValue("paperSizePreset") as JsonPrimitive).content)
        assertEquals("abc123", (migrated.getValue("id") as JsonPrimitive).content)
    }

    @Test
    fun `multi-step chain runs every intermediate step in order and stamps the final version`() {
        val v1 = buildJsonObject { put("id", JsonPrimitive("multi")) }
        val step1to2 = ProjectSchemaMigrator.Step { current ->
            buildJsonObject { current.forEach { (k, v) -> put(k, v) }; put("addedAtV2", JsonPrimitive(true)) }
        }
        val step2to3 = ProjectSchemaMigrator.Step { current ->
            buildJsonObject { current.forEach { (k, v) -> put(k, v) }; put("addedAtV3", JsonPrimitive(true)) }
        }

        val migrated = ProjectSchemaMigrator.migrate(v1, targetVersion = 3, steps = listOf(step1to2, step2to3))

        assertEquals(3, migrated.getValue("schemaVersion").let { (it as JsonPrimitive).int })
        assertEquals(true, (migrated.getValue("addedAtV2") as JsonPrimitive).boolean)
        assertEquals(true, (migrated.getValue("addedAtV3") as JsonPrimitive).boolean)
        assertEquals("multi", (migrated.getValue("id") as JsonPrimitive).content)
    }

    @Test
    fun `a blob already at the target version is stamped but otherwise untouched`() {
        val current = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("id", JsonPrimitive("xyz"))
        }

        val migrated = ProjectSchemaMigrator.migrate(current, targetVersion = 1, steps = emptyList())

        assertEquals(1, (migrated.getValue("schemaVersion") as JsonPrimitive).int)
        assertEquals("xyz", (migrated.getValue("id") as JsonPrimitive).content)
    }

    @Test(expected = IllegalStateException::class)
    fun `a blob newer than the target version is refused rather than silently misread`() {
        val fromTheFuture = buildJsonObject { put("schemaVersion", JsonPrimitive(99)) }
        ProjectSchemaMigrator.migrate(fromTheFuture, targetVersion = 1, steps = emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun `a gap in the registered steps fails loudly instead of silently under-migrating`() {
        val v1 = buildJsonObject { put("id", JsonPrimitive("gap")) }
        // targetVersion 3 but only one step registered (1->2) -- 2->3 is missing.
        val step1to2 = ProjectSchemaMigrator.Step { it }
        ProjectSchemaMigrator.migrate(v1, targetVersion = 3, steps = listOf(step1to2))
    }

    @Test
    fun `end to end -- a real legacy on-disk project blob upgrades and decodes into a valid current-schema ProjectMeta`() {
        // Exactly the shape of every project already saved on a real device before this task: no
        // schemaVersion key, no activeLayerIndex key, layers present.
        val legacyOnDiskJson = """
            {
                "id": "2b502b23-da85-43ad-95cb-53b5c3e9a4de",
                "name": "Lotus Mandala",
                "widthPx": 2048,
                "heightPx": 2048,
                "createdAt": 1786453387790,
                "updatedAt": 1786537537305,
                "layers": [
                    {"id": "e44b2c03-fe8c-4e72-9730-3afeda296f4c", "name": "Coloring", "opacity": 1.0, "visible": true, "blendMode": "Normal", "order": 0},
                    {"id": "7813fd60-0037-471b-af56-de5acbde43f4", "name": "Line Art", "opacity": 1.0, "visible": true, "blendMode": "Normal", "order": 1, "locked": true}
                ]
            }
        """.trimIndent()

        val root = json.parseToJsonElement(legacyOnDiskJson).jsonObject
        assertEquals(1, ProjectSchemaMigrator.versionOf(root))

        val migrated = ProjectSchemaMigrator.migrate(root) // production chain: empty steps, target = CURRENT_SCHEMA_VERSION (1)
        val meta = json.decodeFromJsonElement<ProjectMeta>(migrated)

        assertEquals(ProjectMeta.CURRENT_SCHEMA_VERSION, meta.schemaVersion)
        assertEquals("2b502b23-da85-43ad-95cb-53b5c3e9a4de", meta.id)
        assertEquals(0, meta.activeLayerIndex) // defaulted -- key was absent
        assertEquals(2, meta.layers.size)
        assertEquals("Line Art", meta.layers[1].name)
        assertEquals(true, meta.layers[1].locked)
        assertEquals(false, meta.layers[0].locked) // defaulted -- key was absent on this layer
    }
}
