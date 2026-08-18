package com.vellum.studio.art

/** Full coloring-book library: every original line-art page the app ships with, grouped by category. */
object ColoringTemplates {
    val all: List<ColoringTemplate> = ColoringTemplatesGeometric.templates + ColoringTemplatesIllustrated.templates + ColoringTemplatesMasterworks.templates + ColoringTemplatesMasterworksReal.templates + ColoringTemplatesKids.templates

    val categories: List<String> = all.map { it.category }.distinct()

    fun byId(id: String): ColoringTemplate? = all.firstOrNull { it.id == id }
}
