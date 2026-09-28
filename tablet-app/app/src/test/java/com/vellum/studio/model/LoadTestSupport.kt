package com.vellum.studio.model

/**
 * The happy-path half of [ProjectRepository.loadProject] for tests that only need the opened
 * project: fails the test loudly (with the reason) on anything but [LoadResult.Ok], instead of
 * every call site unpacking the sealed result.
 */
internal suspend fun ProjectRepository.loadOk(id: String): ProjectRepository.LoadedProject =
    when (val result = loadProject(id)) {
        is LoadResult.Ok -> result.project
        is LoadResult.Failed -> throw AssertionError("expected project $id to open, got ${result::class.simpleName}: ${result.message}")
    }
