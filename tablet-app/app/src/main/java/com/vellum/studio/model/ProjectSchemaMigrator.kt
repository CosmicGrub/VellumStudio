package com.vellum.studio.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * Upgrades a raw, on-disk project [JsonObject] to the current [ProjectMeta] schema, one version
 * step at a time, *before* [ProjectRepository] ever hands it to `Json.decodeFromJsonElement`.
 *
 * Working on the raw JSON tree rather than an already-deserialized [ProjectMeta] is the whole
 * point: a real future migration (renaming a field, changing a field's shape, splitting one field
 * into two) needs to reshape the tree before the *current* data class can even parse it -- there
 * is no intermediate Kotlin type old enough to decode an old file directly into.
 *
 * There is nothing to migrate from yet -- [ProjectMeta.CURRENT_SCHEMA_VERSION] is still 1, the
 * very first version, so [steps] is empty and every on-disk file (versioned or not) is already
 * current. [ProjectSchemaMigratorTest] proves the engine itself (version detection, step ordering,
 * final stamping) with a synthetic chain, independent of that. The next real format change is one
 * entry appended to [steps], not a special case anywhere else in the codebase.
 */
object ProjectSchemaMigrator {

    /** One version-to-version upgrade: reshapes the raw JSON at [fromVersion] into [fromVersion] + 1's shape. */
    fun interface Step {
        fun upgrade(json: JsonObject): JsonObject
    }

    /** Ordered by fromVersion ascending: `steps[0]` upgrades 1 -> 2, `steps[1]` upgrades 2 -> 3, and so on. No gaps. */
    private val steps: List<Step> = emptyList()

    /** A project JSON blob saved before this field existed carries no `schemaVersion` key at all. */
    private const val IMPLICIT_VERSION = 1

    /** The schema version [json] declares, or [IMPLICIT_VERSION] if it predates the field entirely. */
    fun versionOf(json: JsonObject): Int =
        (json["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: IMPLICIT_VERSION

    /**
     * Runs [json] through [steps] from its own declared (or implicit) version up to [targetVersion]
     * and stamps the result with [targetVersion] explicitly, so the caller's subsequent decode
     * always sees a `schemaVersion` matching what it asked for.
     *
     * [steps] and [targetVersion] are parameters -- not hardcoded to the production chain and
     * [ProjectMeta.CURRENT_SCHEMA_VERSION] -- purely so this engine can be exercised by a unit test
     * with a synthetic chain, independent of whether a real migration exists yet.
     *
     * @throws IllegalStateException if [json]'s version is newer than [targetVersion] (a project
     *   saved by a newer build of the app being opened by an older one), or if [steps] has a gap.
     */
    fun migrate(
        json: JsonObject,
        targetVersion: Int = ProjectMeta.CURRENT_SCHEMA_VERSION,
        steps: List<Step> = this.steps,
    ): JsonObject {
        var current = json
        var version = versionOf(json)
        check(version <= targetVersion) {
            "Project metadata is schema version $version, newer than this app's version $targetVersion -- refusing to guess how to downgrade it."
        }
        while (version < targetVersion) {
            val step = steps.getOrNull(version - 1)
                ?: error("No migration registered to upgrade project schema from version $version to ${version + 1}")
            current = step.upgrade(current)
            version += 1
        }
        return buildJsonObject {
            current.forEach { (key, value) -> put(key, value) }
            put("schemaVersion", JsonPrimitive(version))
        }
    }
}
