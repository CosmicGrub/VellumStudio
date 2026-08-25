package com.vellum.studio.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in a real bug found while verifying schema versioning on-device (R52X101MB6W): the
 * `Json` instance used everywhere in this codebase leaves `encodeDefaults` at the library's own
 * default of `false`, which omits any field left at its declared default value. [ProjectMeta]'s
 * `schemaVersion` default is *defined* as "whatever [ProjectMeta.CURRENT_SCHEMA_VERSION] currently
 * is" -- so without `@EncodeDefault`, a freshly-saved, genuinely-current project would always
 * encode a value equal to its own default and the key would silently never be written at all,
 * indistinguishable on disk from a project saved before the field existed -- no matter how many
 * times [ProjectMeta.CURRENT_SCHEMA_VERSION] is bumped in the future. This is what would catch
 * that regression coming back.
 */
class ProjectMetaSerializationTest {

    // Mirrors ProjectRepository's actual Json config exactly -- this bug is specifically about how
    // *that* configuration (encodeDefaults left at its library default) interacts with the model.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    @Test
    fun `a freshly-built current-schema ProjectMeta always serializes an explicit schemaVersion key`() {
        val meta = ProjectMeta(
            id = "id-1",
            name = "Test",
            widthPx = 100,
            heightPx = 100,
            createdAt = 0L,
            updatedAt = 0L,
            layers = emptyList(),
        )

        val encoded = json.encodeToString(meta)

        assertTrue("expected an explicit \"schemaVersion\" key in the encoded JSON:\n$encoded", encoded.contains("\"schemaVersion\""))
    }
}
