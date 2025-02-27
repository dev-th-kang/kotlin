/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("DeprecatedCallableAddReplaceWith")

package org.jetbrains.kotlin.gradle.plugin.sources

import org.gradle.api.Action
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.tooling.core.closure
import org.jetbrains.kotlin.tooling.core.withClosure
import java.io.File
import java.util.*
import javax.inject.Inject

const val METADATA_CONFIGURATION_NAME_SUFFIX = "DependenciesMetadata"

abstract class DefaultKotlinSourceSet @Inject constructor(
    private val project: Project,
    val displayName: String
) : AbstractKotlinSourceSet() {

    override val apiConfigurationName: String
        get() = disambiguateName(API)

    override val implementationConfigurationName: String
        get() = disambiguateName(IMPLEMENTATION)

    override val compileOnlyConfigurationName: String
        get() = disambiguateName(COMPILE_ONLY)

    override val runtimeOnlyConfigurationName: String
        get() = disambiguateName(RUNTIME_ONLY)

    override val apiMetadataConfigurationName: String
        get() = lowerCamelCaseName(apiConfigurationName, METADATA_CONFIGURATION_NAME_SUFFIX)

    override val implementationMetadataConfigurationName: String
        get() = lowerCamelCaseName(implementationConfigurationName, METADATA_CONFIGURATION_NAME_SUFFIX)

    override val compileOnlyMetadataConfigurationName: String
        get() = lowerCamelCaseName(compileOnlyConfigurationName, METADATA_CONFIGURATION_NAME_SUFFIX)

    override val runtimeOnlyMetadataConfigurationName: String
        get() = lowerCamelCaseName(runtimeOnlyConfigurationName, METADATA_CONFIGURATION_NAME_SUFFIX)

    /**
     * Dependencies added to this configuration will not be exposed to any other source set.
     */
    val intransitiveMetadataConfigurationName: String
        get() = lowerCamelCaseName(disambiguateName(INTRANSITIVE), METADATA_CONFIGURATION_NAME_SUFFIX)

    override val kotlin: SourceDirectorySet = createDefaultSourceDirectorySet(project, "$name Kotlin source")

    override val languageSettings: LanguageSettingsBuilder = DefaultLanguageSettingsBuilder()

    override val resources: SourceDirectorySet = createDefaultSourceDirectorySet(project, "$name resources")

    override fun kotlin(configure: SourceDirectorySet.() -> Unit): SourceDirectorySet = kotlin.apply {
        configure(this)
    }

    override fun kotlin(configure: Action<SourceDirectorySet>): SourceDirectorySet =
        kotlin { configure.execute(this) }

    override fun languageSettings(configure: Action<LanguageSettingsBuilder>): LanguageSettingsBuilder = languageSettings {
        configure.execute(this)
    }

    override fun languageSettings(configure: LanguageSettingsBuilder.() -> Unit): LanguageSettingsBuilder =
        languageSettings.apply { configure(this) }

    override fun getName(): String = displayName

    override fun dependencies(configure: KotlinDependencyHandler.() -> Unit): Unit =
        DefaultKotlinDependencyHandler(this, project).run(configure)

    override fun dependencies(configure: Action<KotlinDependencyHandler>) =
        dependencies { configure.execute(this) }

    override fun afterDependsOnAdded(other: KotlinSourceSet) {
        project.runProjectConfigurationHealthCheckWhenEvaluated {
            defaultSourceSetLanguageSettingsChecker.runAllChecks(this@DefaultKotlinSourceSet, other)
        }
    }

    override fun toString(): String = "source set $name"

    private val explicitlyAddedCustomSourceFilesExtensions = ArrayList<String>()

    override val customSourceFilesExtensions: Iterable<String>
        get() = Iterable {
            val fromExplicitFilters = kotlin.filter.includes.mapNotNull { pattern ->
                pattern.substringAfterLast('.')
            }
            val merged = (fromExplicitFilters + explicitlyAddedCustomSourceFilesExtensions).filterNot { extension ->
                DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS.any { extension.equals(it, ignoreCase = true) }
                        || extension.any { it == '\\' || it == '/' }
            }.distinct()
            merged.iterator()
        }

    override fun addCustomSourceFilesExtensions(extensions: List<String>) {
        explicitlyAddedCustomSourceFilesExtensions.addAll(extensions)
    }

    internal val dependencyTransformations: MutableMap<KotlinDependencyScope, GranularMetadataTransformation> = mutableMapOf()

    private val _requiresVisibilityOf = mutableSetOf<KotlinSourceSet>()

    override val requiresVisibilityOf: MutableSet<KotlinSourceSet>
        get() = Collections.unmodifiableSet(_requiresVisibilityOf)

    override fun requiresVisibilityOf(other: KotlinSourceSet) {
        _requiresVisibilityOf += other
    }

    //region IDE import for Granular source sets metadata

    data class MetadataDependencyTransformation(
        val groupId: String?,
        val moduleName: String,
        val projectPath: String?,
        val projectStructureMetadata: KotlinProjectStructureMetadata?,
        val allVisibleSourceSets: Set<String>,
        /** If empty, then this source set does not see any 'new' source sets of the dependency, compared to its dependsOn parents, but it
         * still does see all what the dependsOn parents see. */
        val useFilesForSourceSets: Map<String, Iterable<File>>
    )

    @Suppress("unused") // Used in IDE import
    fun getDependenciesTransformation(configurationName: String): Iterable<MetadataDependencyTransformation> {
        val scope = KotlinDependencyScope.values().find {
            project.sourceSetMetadataConfigurationByScope(this, it).name == configurationName
        } ?: return emptyList()

        return getDependenciesTransformation(scope)
    }

    fun getAdditionalVisibleSourceSets(): List<KotlinSourceSet> = getVisibleSourceSetsFromAssociateCompilations(this)

    internal fun getDependenciesTransformation(scope: KotlinDependencyScope): Iterable<MetadataDependencyTransformation> {
        val metadataDependencyResolutionByModule =
            dependencyTransformations[scope]?.metadataDependencyResolutions
                ?.associateBy { ModuleIds.fromComponent(project, it.dependency) }
                ?: emptyMap()

        val baseDir = SourceSetMetadataStorageForIde.sourceSetStorageWithScope(project, this@DefaultKotlinSourceSet.name, scope)

        if (metadataDependencyResolutionByModule.values.any { it is MetadataDependencyResolution.ChooseVisibleSourceSets }) {
            if (baseDir.isDirectory) {
                baseDir.deleteRecursively()
            }
            baseDir.mkdirs()
        }

        return metadataDependencyResolutionByModule.mapNotNull { (groupAndName, resolution) ->
            val (group, name) = groupAndName
            val projectPath = resolution.projectDependency?.path
            when (resolution) {
                // No metadata transformation leads to original dependency being used during import
                is MetadataDependencyResolution.KeepOriginalDependency -> null

                // We should pass empty transformation for excluded dependencies.
                // No transformation at all will result in a composite metadata jar being used as a dependency.
                is MetadataDependencyResolution.Exclude ->
                    MetadataDependencyTransformation(group, name, projectPath, null, emptySet(), emptyMap())

                is MetadataDependencyResolution.ChooseVisibleSourceSets -> {
                    val filesBySourceSet = resolution.visibleSourceSetNamesExcludingDependsOn.associateWith { visibleSourceSetName ->
                        resolution.metadataProvider.getSourceSetCompiledMetadata(
                            project,
                            sourceSetName = visibleSourceSetName,
                            outputDirectoryWhenMaterialised = baseDir,
                            materializeFilesIfNecessary = true
                        )
                    }.filter { (_, files) -> files.any(File::exists) }

                    MetadataDependencyTransformation(
                        group, name, projectPath,
                        resolution.projectStructureMetadata,
                        resolution.allVisibleSourceSetNames,
                        filesBySourceSet
                    )
                }
            }
        }
    }

    //endregion
}


internal val defaultSourceSetLanguageSettingsChecker =
    FragmentConsistencyChecker<KotlinSourceSet>(
        unitsName = "source sets",
        name = { name },
        checks = FragmentConsistencyChecks<KotlinSourceSet>(
            unitName = "source set",
            languageSettings = { languageSettings }
        ).allChecks
    )


internal fun KotlinSourceSet.disambiguateName(simpleName: String): String {
    val nameParts = listOfNotNull(this.name.takeIf { it != "main" }, simpleName)
    return lowerCamelCaseName(*nameParts.toTypedArray())
}

internal fun createDefaultSourceDirectorySet(project: Project, name: String?): SourceDirectorySet =
    project.objects.sourceDirectorySet(name!!, name)

@Suppress("Unused") // Still part of public API
@Deprecated("Use InternalKotlinSourceSet.dependsOnClosure instead. Will be removed in Kotlin 1.9")
val KotlinSourceSet.dependsOnClosure: Set<KotlinSourceSet> get() = this.internal.dependsOnClosure

@Suppress("Unused") // Still part of public API
@Deprecated("Use InternalKotlinSourceSet.withDependsOnClosure instead. Will be removed in Kotlin 1.9")
val KotlinSourceSet.withDependsOnClosure: Set<KotlinSourceSet> get() = this.internal.withDependsOnClosure

@Suppress("Unused") // Still part of public API
@Deprecated("Use InternalKotlinSourceSet.withDependsOnClosure instead. Will be removed in Kotlin 1.9")
val Iterable<KotlinSourceSet>.dependsOnClosure: Set<KotlinSourceSet>
    get() = flatMap { it.internal.dependsOnClosure }.toSet() - this.toSet()

val Iterable<KotlinSourceSet>.withDependsOnClosure: Set<KotlinSourceSet>
    get() = flatMap { it.internal.withDependsOnClosure }.toSet()

fun KotlinMultiplatformExtension.findSourceSetsDependingOn(sourceSet: KotlinSourceSet): Set<KotlinSourceSet> {
    return sourceSet.closure { seedSourceSet -> sourceSets.filter { otherSourceSet -> seedSourceSet in otherSourceSet.dependsOn } }
}
