/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("PackageDirectoryMismatch") // Old package for compatibility
package org.jetbrains.kotlin.gradle.tasks

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.NormalizeLineEndings
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.compilerRunner.*
import org.jetbrains.kotlin.compilerRunner.KotlinNativeCInteropRunner.Companion.run
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonToolOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.NativeCacheKind
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.gradle.internal.isInIdeaSync
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.cocoapods.asValidFrameworkName
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.KotlinNativeCompilationData
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.KotlinNativeFragmentMetadataCompilationData
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.isMainCompilationData
import org.jetbrains.kotlin.gradle.plugin.sources.DefaultLanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.targets.native.KonanPropertiesBuildService
import org.jetbrains.kotlin.gradle.targets.native.internal.isAllowCommonizer
import org.jetbrains.kotlin.gradle.targets.native.tasks.*
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.gradle.utils.listFilesOrEmpty
import org.jetbrains.kotlin.konan.CompilerVersion
import org.jetbrains.kotlin.konan.library.KLIB_INTEROP_IR_PROVIDER_IDENTIFIER
import org.jetbrains.kotlin.konan.properties.saveToFile
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.CompilerOutputKind.*
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.*
import org.jetbrains.kotlin.project.model.LanguageSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import org.jetbrains.kotlin.konan.file.File as KFile
import org.jetbrains.kotlin.utils.ResolvedDependencies as KResolvedDependencies
import org.jetbrains.kotlin.utils.ResolvedDependenciesSupport as KResolvedDependenciesSupport
import org.jetbrains.kotlin.utils.ResolvedDependency as KResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyArtifactPath as KResolvedDependencyArtifactPath
import org.jetbrains.kotlin.utils.ResolvedDependencyId as KResolvedDependencyId
import org.jetbrains.kotlin.utils.ResolvedDependencyVersion as KResolvedDependencyVersion

// TODO: It's just temporary tasks used while KN isn't integrated with Big Kotlin compilation infrastructure.
// region Useful extensions
internal fun MutableList<String>.addArg(parameter: String, value: String) {
    add(parameter)
    add(value)
}

internal fun MutableList<String>.addArgs(parameter: String, values: Iterable<String>) {
    values.forEach {
        addArg(parameter, it)
    }
}

internal fun MutableList<String>.addArgIfNotNull(parameter: String, value: String?) {
    if (value != null) {
        addArg(parameter, value)
    }
}

internal fun MutableList<String>.addKey(key: String, enabled: Boolean) {
    if (enabled) {
        add(key)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: FileCollection) {
    values.files.forEach {
        addArg(parameter, it.canonicalPath)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: Collection<FileCollection>) {
    values.forEach {
        addFileArgs(parameter, it)
    }
}

private fun File.providedByCompiler(project: Project): Boolean =
    toPath().startsWith(project.file(project.konanHome).resolve("klib").toPath())

// We need to filter out interop duplicates because we create copy of them for IDE.
// TODO: Remove this after interop rework.
private fun FileCollection.filterOutPublishableInteropLibs(project: Project): FileCollection =
    filterOutPublishableInteropLibs(project.buildLibDirectories())

private fun Project.buildLibDirectories(): List<Path> =
    rootProject.allprojects.map { it.buildDir.resolve("libs").absoluteFile.toPath() }

private fun FileCollection.filterOutPublishableInteropLibs(libDirectories: List<Path>): FileCollection {
    return filter { file ->
        !(file.name.contains("-cinterop-") && libDirectories.any { file.toPath().startsWith(it) })
    }
}

/**
 * We pass to the compiler:
 *
 *    - Only *.klib files and directories (normally containing an unpacked klib).
 *      A dependency configuration may contain jar files
 *      (e.g. when a common artifact was directly added to commonMain source set).
 *      So, we need to filter out such artifacts.
 *
 *    - Only existing files. We don't compile a klib if there are no sources
 *      for it (NO-SOURCE check). So we need to take this case into account
 *      and skip libraries that were not compiled. See also: GH-2617 (K/N repo).
 */
private val File.canKlibBePassedToCompiler get() = (extension == "klib" || isDirectory) && exists()

private fun Collection<File>.filterKlibsPassedToCompiler(): List<File> = filter(File::canKlibBePassedToCompiler)

/* Returned FileCollection is lazy */
private fun FileCollection.filterKlibsPassedToCompiler(): FileCollection = filter(File::canKlibBePassedToCompiler)

// endregion
abstract class AbstractKotlinNativeCompile<
        T : KotlinCommonToolOptions,
        K : KotlinNativeCompilationData<*>,
        M : CommonToolArguments
        >
@Inject constructor(
    private val objectFactory: ObjectFactory
): AbstractKotlinCompileTool<M>(objectFactory) {

    @get:Inject
    protected abstract val projectLayout: ProjectLayout

    @get:Internal
    abstract val compilation: K

    // region inputs/outputs
    @get:Input
    abstract val outputKind: CompilerOutputKind

    @get:Input
    abstract val optimized: Boolean

    @get:Input
    abstract val debuggable: Boolean

    @get:Internal
    abstract val baseName: String

    @get:Internal
    protected val konanTarget by project.provider {
        compilation.konanTarget
    }

    @get:Classpath
    override val libraries: ConfigurableFileCollection = objectFactory.fileCollection().from({
        // Avoid resolving these dependencies during task graph construction when we can't build the target:
        if (konanTarget.enabledOnCurrentHost)
            objectFactory.fileCollection().from(
                compilation.compileDependencyFiles.filterOutPublishableInteropLibs(project)
            )
        else objectFactory.fileCollection()
    })

    @get:Classpath
    protected val friendModule: FileCollection = project.files({ compilation.friendPaths })

    @get:Input
    val target: String by project.provider { compilation.konanTarget.name }

    // region Compiler options.
    @get:Internal
    abstract val kotlinOptions: T
    abstract fun kotlinOptions(fn: T.() -> Unit)
    abstract fun kotlinOptions(fn: Closure<*>)

    @get:Input
    abstract val additionalCompilerOptions: Provider<Collection<String>>

    @get:Internal
    val languageSettings: LanguageSettings by project.provider {
        compilation.languageSettings
    }

    @get:Input
    val progressiveMode: Boolean
        get() = languageSettings.progressiveMode
    // endregion.

    @get:Input
    val enableEndorsedLibs: Boolean by project.provider { compilation.enableEndorsedLibs }

    @get:Input
    val kotlinNativeVersion: String
        get() = project.konanVersion.toString()

    @get:Input
    internal val useEmbeddableCompilerJar: Boolean
        get() = project.nativeUseEmbeddableCompilerJar

    @get:Internal
    open val outputFile: Provider<File>
        get() = destinationDirectory.flatMap {
            val prefix = outputKind.prefix(konanTarget)
            val suffix = outputKind.suffix(konanTarget)
            val filename = "$prefix${baseName}$suffix".let {
                when {
                    outputKind == FRAMEWORK ->
                        it.asValidFrameworkName()
                    outputKind in listOf(STATIC, DYNAMIC) || outputKind == PROGRAM && konanTarget == KonanTarget.WASM32 ->
                        it.replace('-', '_')
                    else -> it
                }
            }

            objectFactory.property(it.file(filename).asFile)
        }

    // endregion
    @Internal
    val compilerPluginOptions = CompilerPluginOptions()

    @get:Input
    val compilerPluginCommandLine
        get() = compilerPluginOptions.arguments

    @Optional
    @Classpath
    open var compilerPluginClasspath: FileCollection? = null

    /**
     * Plugin Data provided by [KpmCompilerPlugin]
     */
    @get:Optional
    @get:Nested
    var kotlinPluginData: Provider<KotlinCompilerPluginData>? = null

    // Used by IDE via reflection.
    @get:Internal
    override val serializedCompilerArguments: List<String>
        get() = buildCommonArgs()

    // Used by IDE via reflection.
    @get:Internal
    override val defaultSerializedCompilerArguments: List<String>
        get() = buildCommonArgs(true)

    private val languageSettingsBuilder by project.provider {
        compilation.languageSettings
    }

    // Args used by both the compiler and IDEA.
    private fun buildCommonArgs(defaultsOnly: Boolean = false): List<String> {
        val plugins = listOfNotNull(
            compilerPluginClasspath?.let { CompilerPluginData(it, compilerPluginOptions) },
            kotlinPluginData?.orNull?.let { CompilerPluginData(it.classpath, it.options) }
        )
        val opts = object : KotlinCommonToolOptions {
            override var allWarningsAsErrors = kotlinOptions.allWarningsAsErrors
            override var suppressWarnings = kotlinOptions.suppressWarnings
            override var verbose = kotlinOptions.verbose
            override var freeCompilerArgs = if (defaultsOnly) emptyList() else additionalCompilerOptions.get().toList()
        }

        return buildKotlinNativeCommonArgs(
            languageSettings,
            enableEndorsedLibs,
            opts,
            plugins
        )
    }

    @get:Input
    @get:Optional
    internal val konanTargetsForManifest: String by project.provider {
        @Suppress("CAST_NEVER_SUCCEEDS") // TODO: this warning looks very suspicious, as if the code never works as intended.
        (compilation as? KotlinSharedNativeCompilation)
            ?.konanTargets
            ?.joinToString(separator = " ") { it.visibleName }
            .orEmpty()
    }

    @get:Internal
    internal val manifestFile: Provider<RegularFile> get() = projectLayout.buildDirectory.file("tmp/$name/inputManifest")
}

// Remove it once actual K2NativeCompilerArguments will be available without 'kotlin.native.enabled = true' flag
class StubK2NativeCompilerArguments : CommonCompilerArguments()

/**
 * A task producing a klibrary from a compilation.
 */
@CacheableTask
abstract class KotlinNativeCompile
@Inject
constructor(
    @Internal
    @Transient  // can't be serialized for Gradle configuration cache
    final override val compilation: KotlinNativeCompilationData<*>,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val execOperations: ExecOperations
) : AbstractKotlinNativeCompile<KotlinCommonOptions, KotlinNativeCompilationData<*>, StubK2NativeCompilerArguments>(objectFactory),
    KotlinCompile<KotlinCommonOptions> {

    @get:Input
    override val outputKind = LIBRARY

    @get:Input
    override val optimized = false

    @get:Input
    override val debuggable = true

    @get:Internal
    override val baseName: String by lazy {
        if (compilation.isMainCompilationData())
            project.name
        else "${project.name}_${compilation.compilationPurpose}"
    }

    // Store as an explicit provider in order to allow Gradle Instant Execution to capture the state
//    private val allSourceProvider = compilation.map { project.files(it.allSources).asFileTree }

    @get:Input
    val moduleName: String by lazy {
        project.klibModuleName(baseName)
    }

    @get:OutputFile
    override val outputFile: Provider<File>
        get() = super.outputFile

    @get:Input
    val shortModuleName: String by providerFactory.provider { baseName }

    // Inputs and outputs.
    // region Sources.

    @get:Internal // these sources are normally a subset of `source` ones which are already tracked
    val commonSources: ConfigurableFileCollection = project.files()

//    private val commonSources: FileCollection by lazy {
//        // Already taken into account in getSources method.
//        project.files(compilation.map { it.commonSources }).asFileTree
//    }

    private val commonSourcesTree: FileTree
        get() = commonSources.asFileTree

    // endregion.

    // region Language settings imported from a SourceSet.
    val languageVersion: String?
        @Optional @Input get() = languageSettings.languageVersion

    val apiVersion: String?
        @Optional @Input get() = languageSettings.apiVersion

    val enabledLanguageFeatures: Set<String>
        @Input get() = languageSettings.enabledLanguageFeatures

    val optInAnnotationsInUse: Set<String>
        @Input get() = languageSettings.optInAnnotationsInUse
    // endregion.

    // region Kotlin options.
    override val kotlinOptions: KotlinCommonOptions by providerFactory.provider {
        compilation.kotlinOptions
    }

    @get:Input
    override val additionalCompilerOptions: Provider<Collection<String>> = providerFactory.provider {
        kotlinOptions.freeCompilerArgs + ((languageSettings as? DefaultLanguageSettingsBuilder)?.freeCompilerArgs ?: emptyList())
    }

    private val runnerSettings = KotlinNativeCompilerRunner.Settings.fromProject(project)
    private val isAllowCommonizer: Boolean by lazy { project.isAllowCommonizer() }

    override fun kotlinOptions(fn: KotlinCommonOptions.() -> Unit) {
        kotlinOptions.fn()
    }

    override fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }
    // endregion.

    override fun createCompilerArgs(): StubK2NativeCompilerArguments = StubK2NativeCompilerArguments()

    override fun setupCompilerArgs(
        args: StubK2NativeCompilerArguments,
        defaultsOnly: Boolean,
        ignoreClasspathResolutionErrors: Boolean
    ) = Unit

    @TaskAction
    fun compile() {
        val output = outputFile.get()
        output.parentFile.mkdirs()

        var sharedCompilationData: SharedCompilationData? = null
        if (compilation is KotlinNativeFragmentMetadataCompilationData) {
            val manifestFile: File = manifestFile.get().asFile
            manifestFile.ensureParentDirsCreated()
            val properties = java.util.Properties()
            properties[KLIB_PROPERTY_NATIVE_TARGETS] = konanTargetsForManifest
            properties.saveToFile(org.jetbrains.kotlin.konan.file.File(manifestFile.toPath()))

            sharedCompilationData = SharedCompilationData(
                manifestFile,
                isAllowCommonizer
            )
        }

        val localKotlinOptions = object : KotlinCommonToolOptions {
            override var allWarningsAsErrors = kotlinOptions.allWarningsAsErrors
            override var suppressWarnings = kotlinOptions.suppressWarnings
            override var verbose = kotlinOptions.verbose
            override var freeCompilerArgs = additionalCompilerOptions.get().toList()
        }

        val plugins = listOfNotNull(
            compilerPluginClasspath?.let { CompilerPluginData(it, compilerPluginOptions) },
            kotlinPluginData?.orNull?.let { CompilerPluginData(it.classpath, it.options) }
        )

        val buildArgs = buildKotlinNativeKlibCompilerArgs(
            output,
            optimized,
            debuggable,
            konanTarget,
            libraries.files.filterKlibsPassedToCompiler(),
            languageSettings,
            enableEndorsedLibs,
            localKotlinOptions,
            plugins,
            moduleName,
            shortModuleName,
            friendModule,
            sharedCompilationData,
            sources.asFileTree,
            commonSourcesTree
        )

        KotlinNativeCompilerRunner(
            settings = runnerSettings,
            executionContext = KotlinToolRunner.GradleExecutionContext.fromTaskContext(objectFactory, execOperations, logger)
        ).run(buildArgs)
    }
}

/**
 * A task producing a final binary from a compilation.
 */
@CacheableTask
abstract class KotlinNativeLink
@Inject
constructor(
    @Internal
    val binary: NativeBinary,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val execOperations: ExecOperations
) : AbstractKotlinNativeCompile<KotlinCommonToolOptions, KotlinNativeCompilation, StubK2NativeCompilerArguments>(objectFactory) {
    @get:Internal
    final override val compilation: KotlinNativeCompilation
        get() = binary.compilation

    private val runnerSettings = KotlinNativeCompilerRunner.Settings.fromProject(project)

    init {
        dependsOn(project.provider { compilation.compileKotlinTaskProvider })
        // Frameworks actively uses symlinks.
        // Gradle build cache transforms symlinks into regular files https://guides.gradle.org/using-build-cache/#symbolic_links
        outputs.cacheIf { outputKind != FRAMEWORK }

        this.setSource(compilation.compileKotlinTask.outputFile)
        includes.clear() // we need to include non '.kt' or '.kts' files
        disallowSourceChanges()
    }

    override val destinationDirectory: DirectoryProperty = binary.outputDirectoryProperty

    override val outputKind: CompilerOutputKind
        @Input get() = binary.outputKind.compilerOutputKind

    override val optimized: Boolean
        @Input get() = binary.optimized

    override val debuggable: Boolean
        @Input get() = binary.debuggable

    override val baseName: String
        @Input get() = binary.baseName

    @get:Input
    protected val konanCacheKind: NativeCacheKind by lazy {
        project.getKonanCacheKind(konanTarget)
    }

    inner class NativeLinkOptions : KotlinCommonToolOptions {
        override var allWarningsAsErrors: Boolean = false
        override var suppressWarnings: Boolean = false
        override var verbose: Boolean = false
        override var freeCompilerArgs: List<String> = listOf()
    }

    private val nativeLinkArgs = PropertiesProvider(project).nativeLinkArgs

    // We propagate compilation free args to the link task for now (see KT-33717).
    @get:Input
    override val additionalCompilerOptions: Provider<Collection<String>> = providerFactory.provider {
        kotlinOptions.freeCompilerArgs +
                compilation.kotlinOptions.freeCompilerArgs +
                ((languageSettings as? DefaultLanguageSettingsBuilder)?.freeCompilerArgs ?: emptyList()) +
                nativeLinkArgs
    }

    override val kotlinOptions: KotlinCommonToolOptions = NativeLinkOptions()

    override fun kotlinOptions(fn: KotlinCommonToolOptions.() -> Unit) {
        kotlinOptions.fn()
    }

    override fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }

    // Binary-specific options.
    val entryPoint: String?
        @Input
        @Optional
        get() = (binary as? Executable)?.entryPoint

    val linkerOpts: List<String>
        @Input get() = binary.linkerOpts

    @get:Input
    val binaryOptions: Map<String, String> by lazy { PropertiesProvider(project).nativeBinaryOptions + binary.binaryOptions }

    val processTests: Boolean
        @Input get() = binary is TestExecutable

    @get:Classpath
    val exportLibraries: FileCollection get() = exportLibrariesResolvedGraph?.files ?: objectFactory.fileCollection()

    private val exportLibrariesResolvedGraph = if (binary is AbstractNativeLibrary) {
        ResolvedDependencyGraph(project.configurations.getByName(binary.exportConfigurationName))
    } else {
        null
    }

    @get:Input
    val isStaticFramework: Boolean by project.provider {
        binary.let { it is Framework && it.isStatic }
    }

    @get:Input
    val embedBitcode: BitcodeEmbeddingMode by project.provider {
        (binary as? Framework)?.embedBitcode ?: BitcodeEmbeddingMode.DISABLE
    }

    @get:Internal
    val apiFiles = project.files(project.configurations.getByName(compilation.apiConfigurationName)).filterKlibsPassedToCompiler()

    private val localKotlinOptions get() =
        object : KotlinCommonToolOptions {
            override var allWarningsAsErrors = kotlinOptions.allWarningsAsErrors
            override var suppressWarnings = kotlinOptions.suppressWarnings
            override var verbose = kotlinOptions.verbose
            override var freeCompilerArgs = additionalCompilerOptions.get().toList()
        }

    private val externalDependenciesArgs by lazy { ExternalDependenciesBuilder(project, compilation).buildCompilerArgs() }

    private val cacheBuilderSettings by lazy {
        CacheBuilder.Settings.createWithProject(project, binary, konanTarget, localKotlinOptions, externalDependenciesArgs)
    }

    override fun createCompilerArgs(): StubK2NativeCompilerArguments = StubK2NativeCompilerArguments()

    override fun setupCompilerArgs(
        args: StubK2NativeCompilerArguments,
        defaultsOnly: Boolean,
        ignoreClasspathResolutionErrors: Boolean
    ) = Unit

    private fun validatedExportedLibraries() {
        if (exportLibrariesResolvedGraph == null) return

        val failed = mutableSetOf<ResolvedDependencyResult>()
        exportLibrariesResolvedGraph
            .allDependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .forEach {
                val dependencyFiles = exportLibrariesResolvedGraph.dependencyArtifacts(it).map { it.file }.filterKlibsPassedToCompiler()
                if (!apiFiles.files.containsAll(dependencyFiles)) {
                    failed.add(it)
                }
            }

        check(failed.isEmpty()) {
            val failedDependenciesList = failed.joinToString(separator = "\n") {
                val componentId = it.selected.id
                when (componentId) {
                    is ModuleComponentIdentifier -> "|Files: ${exportLibrariesResolvedGraph.dependencyArtifacts(it).map { it.file }}"
                    is ProjectComponentIdentifier -> "|Project ${componentId.projectPath}"
                    else -> "|${componentId.displayName}"
                }
            }

            """
                |Following dependencies exported in the ${binary.name} binary are not specified as API-dependencies of a corresponding source set:
                |
                $failedDependenciesList
                |
                |Please add them in the API-dependencies and rerun the build.
            """.trimMargin()
        }
    }

    @get:Internal
    internal abstract val konanPropertiesService: Property<KonanPropertiesBuildService>

    private val resolvedDependencyGraph = ResolvedDependencyGraph(
        project.configurations.getByName(compilation.compileDependencyConfigurationName)
    )

    @TaskAction
    fun compile() {
        validatedExportedLibraries()

        val output = outputFile.get()
        output.parentFile.mkdirs()

        val plugins = listOfNotNull(
            compilerPluginClasspath?.let { CompilerPluginData(it, compilerPluginOptions) },
            kotlinPluginData?.orNull?.let { CompilerPluginData(it.classpath, it.options) }
        )

        val executionContext = KotlinToolRunner.GradleExecutionContext.fromTaskContext(objectFactory, execOperations, logger)
        val cacheArgs = CacheBuilder(
            executionContext = executionContext,
            settings = cacheBuilderSettings,
            konanPropertiesService = konanPropertiesService.get()
        ).buildCompilerArgs(resolvedDependencyGraph)

        val buildArgs = buildKotlinNativeBinaryLinkerArgs(
            output,
            optimized,
            debuggable,
            konanTarget,
            outputKind,
            libraries.files.filterKlibsPassedToCompiler(),
            friendModule.files.toList(),
            enableEndorsedLibs,
            localKotlinOptions,
            plugins,
            processTests,
            entryPoint,
            embedBitcode,
            linkerOpts,
            binaryOptions,
            isStaticFramework,
            exportLibraries.files.filterKlibsPassedToCompiler(),
            sources.asFileTree.files.toList(),
            externalDependenciesArgs + cacheArgs
        )

        KotlinNativeCompilerRunner(
            settings = runnerSettings,
            executionContext = executionContext
        ).run(buildArgs)
    }
}

private class ExternalDependenciesBuilder(
    val project: Project,
    val compilation: KotlinCompilation<*>,
    intermediateLibraryName: String?
) {
    constructor(project: Project, compilation: KotlinNativeCompilation) : this(
        project, compilation, compilation.compileKotlinTask.moduleName
    )

    private val compileDependencyConfiguration: Configuration
        get() = project.configurations.getByName(compilation.compileDependencyConfigurationName)

    private val sourceCodeModuleId: KResolvedDependencyId =
        intermediateLibraryName?.let { KResolvedDependencyId(it) } ?: KResolvedDependencyId.DEFAULT_SOURCE_CODE_MODULE_ID

    private val konanPropertiesService: KonanPropertiesBuildService
        get() = KonanPropertiesBuildService.registerIfAbsent(project.gradle).get()

    fun buildCompilerArgs(): List<String> {
        val compilerVersion = Distribution.getCompilerVersion(konanPropertiesService.compilerVersion, project.konanHome)
        val konanVersion = compilerVersion?.let(CompilerVersion.Companion::fromString)
            ?: project.konanVersion

        if (konanVersion.isAtLeast(1, 6, 0)) {
            val dependenciesFile = writeDependenciesFile(buildDependencies(), deleteOnExit = true)
            if (dependenciesFile != null)
                return listOf("-Xexternal-dependencies=${dependenciesFile.path}")
        }

        return emptyList()
    }

    private fun buildDependencies(): Collection<KResolvedDependency> {
        // Collect all artifacts.
        val moduleNameToArtifactPaths: MutableMap</* unique name*/ String, MutableSet<KResolvedDependencyArtifactPath>> = mutableMapOf()
        compileDependencyConfiguration.incoming.artifacts.artifacts.mapNotNull { resolvedArtifact ->
            val uniqueName = (resolvedArtifact.id.componentIdentifier as? ModuleComponentIdentifier)?.uniqueName ?: return@mapNotNull null
            val artifactPath = resolvedArtifact.file.absolutePath

            moduleNameToArtifactPaths.getOrPut(uniqueName) { mutableSetOf() } += KResolvedDependencyArtifactPath(artifactPath)
        }

        // The build system may express the single module as two modules where the first one is a common
        // module without artifacts and the second one is a platform-specific module with mandatory artifact.
        // Example: "org.jetbrains.kotlinx:atomicfu" (common) and "org.jetbrains.kotlinx:atomicfu-macosx64" (platform-specific).
        // Both such modules should be merged into a single module with just two names:
        // "org.jetbrains.kotlinx:atomicfu (org.jetbrains.kotlinx:atomicfu-macosx64)".
        val moduleIdsToMerge: MutableMap</* platform-specific */ KResolvedDependencyId, /* common */ KResolvedDependencyId> = mutableMapOf()

        // Collect plain modules.
        val plainModules: MutableMap<KResolvedDependencyId, KResolvedDependency> = mutableMapOf()
        fun processModule(resolvedDependency: DependencyResult, incomingDependencyId: KResolvedDependencyId) {
            if (resolvedDependency !is ResolvedDependencyResult) return

            val requestedModule = resolvedDependency.requested as? ModuleComponentSelector ?: return
            val selectedModule = resolvedDependency.selected
            val selectedModuleId = selectedModule.id as? ModuleComponentIdentifier ?: return

            val moduleId = KResolvedDependencyId(selectedModuleId.uniqueName)
            val module = plainModules.getOrPut(moduleId) {
                val artifactPaths = moduleId.uniqueNames.asSequence()
                    .mapNotNull { uniqueName -> moduleNameToArtifactPaths[uniqueName] }
                    .firstOrNull()
                    .orEmpty()

                KResolvedDependency(
                    id = moduleId,
                    selectedVersion = KResolvedDependencyVersion(selectedModuleId.version),
                    requestedVersionsByIncomingDependencies = mutableMapOf(), // To be filled in just below.
                    artifactPaths = artifactPaths.toMutableSet()
                )
            }

            // Record the requested version of the module by the current incoming dependency.
            module.requestedVersionsByIncomingDependencies[incomingDependencyId] = KResolvedDependencyVersion(requestedModule.version)

            // TODO: Use [ResolvedDependencyResult.resolvedVariant.externalVariant] to find a connection between platform-specific
            //  and common modules when "resolvedVariant" and "externalVariant" graduate from incubating state.
            if (module.artifactPaths.isNotEmpty()) {
                val originModuleId = resolvedDependency.from.id as? ModuleComponentIdentifier
                if (originModuleId != null
                    && selectedModuleId.group == originModuleId.group
                    && selectedModuleId.module.startsWith(originModuleId.module)
                    && selectedModuleId.version == originModuleId.version
                ) {
                    // These two modules should be merged.
                    moduleIdsToMerge[moduleId] = KResolvedDependencyId(originModuleId.uniqueName)
                }
            }

            selectedModule.dependencies.forEach { processModule(it, incomingDependencyId = moduleId) }
        }

        compileDependencyConfiguration.incoming.resolutionResult.root.dependencies.forEach { dependencyResult ->
            processModule(dependencyResult, incomingDependencyId = sourceCodeModuleId)
        }

        if (moduleIdsToMerge.isEmpty())
            return plainModules.values

        // Do merge.
        val replacedModules: MutableMap</* old module ID */ KResolvedDependencyId, /* new module */ KResolvedDependency> = mutableMapOf()
        moduleIdsToMerge.forEach { (platformSpecificModuleId, commonModuleId) ->
            val platformSpecificModule = plainModules.getValue(platformSpecificModuleId)
            val commonModule = plainModules.getValue(commonModuleId)

            val replacementModuleId = KResolvedDependencyId(platformSpecificModuleId.uniqueNames + commonModuleId.uniqueNames)
            val replacementModule = KResolvedDependency(
                id = replacementModuleId,
                visibleAsFirstLevelDependency = commonModule.visibleAsFirstLevelDependency,
                selectedVersion = commonModule.selectedVersion,
                requestedVersionsByIncomingDependencies = mutableMapOf<KResolvedDependencyId, KResolvedDependencyVersion>().apply {
                    this += commonModule.requestedVersionsByIncomingDependencies
                    this += platformSpecificModule.requestedVersionsByIncomingDependencies - commonModuleId
                },
                artifactPaths = mutableSetOf<KResolvedDependencyArtifactPath>().apply {
                    this += commonModule.artifactPaths
                    this += platformSpecificModule.artifactPaths
                }
            )

            replacedModules[platformSpecificModuleId] = replacementModule
            replacedModules[commonModuleId] = replacementModule
        }

        // Assemble new modules together (without "replaced" and with "replacements").
        val mergedModules: MutableMap<KResolvedDependencyId, KResolvedDependency> = mutableMapOf()
        mergedModules += plainModules - replacedModules.keys
        replacedModules.values.forEach { replacementModule -> mergedModules[replacementModule.id] = replacementModule }

        // Fix references to point to "replacement" modules instead of "replaced" modules.
        mergedModules.values.forEach { module ->
            module.requestedVersionsByIncomingDependencies.mapNotNull { (replacedModuleId, requestedVersion) ->
                val replacementModuleId = replacedModules[replacedModuleId]?.id ?: return@mapNotNull null
                Triple(replacedModuleId, replacementModuleId, requestedVersion)
            }.forEach { (replacedModuleId, replacementModuleId, requestedVersion) ->
                module.requestedVersionsByIncomingDependencies.remove(replacedModuleId)
                module.requestedVersionsByIncomingDependencies[replacementModuleId] = requestedVersion
            }
        }

        return mergedModules.values
    }

    private fun writeDependenciesFile(dependencies: Collection<KResolvedDependency>, deleteOnExit: Boolean): File? {
        if (dependencies.isEmpty()) return null

        val dependenciesFile = Files.createTempFile("kotlin-native-external-dependencies", ".deps").toAbsolutePath().toFile()
        if (deleteOnExit) dependenciesFile.deleteOnExit()
        dependenciesFile.writeText(KResolvedDependenciesSupport.serialize(KResolvedDependencies(dependencies, sourceCodeModuleId)))
        return dependenciesFile
    }

    private val ModuleComponentIdentifier.uniqueName: String
        get() = "$group:$module"

    companion object {
        @Suppress("unused") // Used for tests only. Accessed via reflection.
        @JvmStatic
        fun buildExternalDependenciesFileForTests(project: Project): File? {
            val compilation = project.tasks.asSequence()
                .filterIsInstance<KotlinNativeLink>()
                .map { it.binary }
                .filterIsInstance<Executable>() // Not TestExecutable or any other kind of NativeBinary. Strictly Executable!
                .firstOrNull()
                ?.compilation
                ?: return null

            return with(ExternalDependenciesBuilder(project, compilation)) {
                val dependencies = buildDependencies().sortedBy { it.id.toString() }
                writeDependenciesFile(dependencies, deleteOnExit = false)
            }
        }
    }
}

internal class CacheBuilder(
    private val executionContext: KotlinToolRunner.GradleExecutionContext,
    private val settings: Settings,
    private val konanPropertiesService: KonanPropertiesBuildService,
) {
    class Settings(
        val runnerSettings: KotlinNativeCompilerRunner.Settings,
        val konanCacheKind: NativeCacheKind,
        val libraries: FileCollection,
        val gradleUserHomeDir: File,
        val binary: NativeBinary,
        val konanTarget: KonanTarget,
        val kotlinOptions: KotlinCommonToolOptions,
        val externalDependenciesArgs: List<String>
    ) {
        val rootCacheDirectory get() = getRootCacheDirectory(
            File(runnerSettings.parent.konanHome),
            konanTarget,
            binary.debuggable,
            konanCacheKind
        )

        companion object {
            fun createWithProject(
                project: Project,
                binary: NativeBinary,
                konanTarget: KonanTarget,
                kotlinOptions: KotlinCommonToolOptions,
                externalDependenciesArgs: List<String>
            ): Settings {
                val konanCacheKind = project.getKonanCacheKind(konanTarget)
                return Settings(
                    runnerSettings = KotlinNativeCompilerRunner.Settings.fromProject(project),
                    konanCacheKind = konanCacheKind,
                    libraries = binary.compilation.compileDependencyFiles.filterOutPublishableInteropLibs(project),
                    gradleUserHomeDir = project.gradle.gradleUserHomeDir,
                    binary, konanTarget, kotlinOptions, externalDependenciesArgs
                )
            }
        }
    }


    private val nativeSingleFileResolveStrategy: SingleFileKlibResolveStrategy
        get() = CompilerSingleFileKlibResolveAllowingIrProvidersStrategy(
            listOf(KLIB_INTEROP_IR_PROVIDER_IDENTIFIER)
        )

    private val binary: NativeBinary
        get() = settings.binary

    private val konanTarget: KonanTarget
        get() = settings.konanTarget

    private val optimized: Boolean
        get() = binary.optimized

    private val debuggable: Boolean
        get() = binary.debuggable

    private val konanCacheKind: NativeCacheKind
        get() = settings.konanCacheKind

    // Inputs and outputs
    private val libraries: FileCollection
        get() = settings.libraries

    private val target: String
        get() = konanTarget.name

    private val rootCacheDirectory: File
        get() = settings.rootCacheDirectory

    private val partialLinkage: Boolean
        get() = PARTIAL_LINKAGE in settings.kotlinOptions.freeCompilerArgs

    private fun getCacheDirectory(
        resolvedDependencyGraph: ResolvedDependencyGraph,
        dependency: ResolvedDependencyResult
    ): File = getCacheDirectory(
        rootCacheDirectory = rootCacheDirectory,
        dependency = dependency,
        artifact = null,
        resolvedDependencyGraph = resolvedDependencyGraph,
        partialLinkage = partialLinkage
    )

    private fun needCache(libraryPath: String) =
        libraryPath.startsWith(settings.gradleUserHomeDir.absolutePath) && libraryPath.endsWith(".klib")

    private fun ResolvedDependencyGraph.ensureDependencyPrecached(
        dependency: ResolvedDependencyResult,
        visitedDependencies: MutableSet<ResolvedDependencyResult>
    ) {
        if (dependency in visitedDependencies)
            return

        visitedDependencies += dependency
        dependency
            .selected
            .dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .forEach { ensureDependencyPrecached(it, visitedDependencies) }

        val artifactsToAddToCache = dependencyArtifacts(dependency).filter { needCache(it.file.absolutePath) }

        if (artifactsToAddToCache.isEmpty()) return

        val dependenciesCacheDirectories = getDependenciesCacheDirectories(
            rootCacheDirectory = rootCacheDirectory,
            dependency = dependency,
            considerArtifact = false,
            resolvedDependencyGraph = this,
            partialLinkage = partialLinkage
        ) ?: return

        val cacheDirectory = getCacheDirectory(this, dependency)
        cacheDirectory.mkdirs()

        val artifactsLibraries = artifactsToAddToCache
            .map {
                resolveSingleFileKlib(
                    KFile(it.file.absolutePath),
                    logger = GradleLoggerAdapter(executionContext.logger),
                    strategy = nativeSingleFileResolveStrategy
                )
            }
            .associateBy { it.uniqueName }

        // Top sort artifacts.
        val sortedLibraries = mutableListOf<KotlinLibrary>()
        val visitedLibraries = mutableSetOf<KotlinLibrary>()

        fun dfs(library: KotlinLibrary) {
            visitedLibraries += library
            library.unresolvedDependencies
                .map { artifactsLibraries[it.path] }
                .forEach {
                    if (it != null && it !in visitedLibraries)
                        dfs(it)
                }
            sortedLibraries += library
        }

        for (library in artifactsLibraries.values)
            if (library !in visitedLibraries)
                dfs(library)

        for (library in sortedLibraries) {
            if (File(cacheDirectory, library.uniqueName.cachedName).listFilesOrEmpty().isNotEmpty())
                continue
            executionContext.logger.info("Compiling ${library.uniqueName} to cache")
            val args = mutableListOf(
                "-p", konanCacheKind.produce!!,
                "-target", target
            )
            if (debuggable) args += "-g"
            args += konanPropertiesService.additionalCacheFlags(konanTarget)
            args += settings.externalDependenciesArgs
            if (partialLinkage) args += PARTIAL_LINKAGE
            args += "-Xadd-cache=${library.libraryFile.absolutePath}"
            args += "-Xcache-directory=${cacheDirectory.absolutePath}"
            args += "-Xcache-directory=${rootCacheDirectory.absolutePath}"

            dependenciesCacheDirectories.forEach {
                args += "-Xcache-directory=${it.absolutePath}"
            }
            getAllDependencies(dependency)
                .flatMap { dependencyArtifacts(it) }
                .map { it.file }
                .filterKlibsPassedToCompiler()
                .forEach {
                    args += "-l"
                    args += it.absolutePath
                }
            library.unresolvedDependencies
                .mapNotNull { artifactsLibraries[it.path] }
                .forEach {
                    args += "-l"
                    args += it.libraryFile.absolutePath
                }
            KotlinNativeCompilerRunner(settings.runnerSettings, executionContext).run(args)
        }
    }

    private val String.cachedName
        get() = getCacheFileName(this, konanCacheKind)

    private fun ensureCompilerProvidedLibPrecached(
        platformLibName: String,
        platformLibs: Map<String, File>,
        visitedLibs: MutableSet<String>
    ) {
        if (platformLibName in visitedLibs)
            return
        visitedLibs += platformLibName
        val platformLib = platformLibs[platformLibName] ?: error("$platformLibName is not found in platform libs")
        if (File(rootCacheDirectory, platformLibName.cachedName).listFilesOrEmpty().isNotEmpty())
            return
        val unresolvedDependencies = resolveSingleFileKlib(
            KFile(platformLib.absolutePath),
            logger = GradleLoggerAdapter(executionContext.logger),
            strategy = nativeSingleFileResolveStrategy
        ).unresolvedDependencies
        for (dependency in unresolvedDependencies)
            ensureCompilerProvidedLibPrecached(dependency.path, platformLibs, visitedLibs)
        executionContext.logger.info("Compiling $platformLibName (${visitedLibs.size}/${platformLibs.size}) to cache")
        val args = mutableListOf(
            "-p", konanCacheKind.produce!!,
            "-target", target
        )
        if (debuggable)
            args += "-g"
        // It's a dirty workaround, but we need a Gradle Build Service for a proper solution,
        // which is too big to put in 1.6.0, so let's use ad-hoc solution for now.
        // TODO: https://youtrack.jetbrains.com/issue/KT-48553.
        if (konanTarget == KonanTarget.IOS_ARM64) {
            // See https://youtrack.jetbrains.com/issue/KT-48552
            args += "-Xembed-bitcode-marker"
        }
        args += "-Xadd-cache=${platformLib.absolutePath}"
        args += "-Xcache-directory=${rootCacheDirectory.absolutePath}"
        KotlinNativeCompilerRunner(settings.runnerSettings, executionContext).run(args)
    }

    private fun ensureCompilerProvidedLibsPrecached() {
        val distribution = Distribution(settings.runnerSettings.parent.konanHome)
        val platformLibs = mutableListOf<File>().apply {
            this += File(distribution.stdlib)
            this += File(distribution.platformLibs(konanTarget)).listFiles().orEmpty()
        }.associateBy { it.name }
        val visitedLibs = mutableSetOf<String>()
        for (platformLibName in platformLibs.keys)
            ensureCompilerProvidedLibPrecached(platformLibName, platformLibs, visitedLibs)
    }

    fun buildCompilerArgs(resolvedDependencyGraph: ResolvedDependencyGraph): List<String> = mutableListOf<String>().apply {
        if (konanCacheKind != NativeCacheKind.NONE && !optimized && konanPropertiesService.cacheWorksFor(konanTarget)) {
            rootCacheDirectory.mkdirs()
            ensureCompilerProvidedLibsPrecached()
            add("-Xcache-directory=${rootCacheDirectory.absolutePath}")
            val visitedDependencies = mutableSetOf<ResolvedDependencyResult>()
            val allCacheDirectories = mutableSetOf<String>()
            for (root in resolvedDependencyGraph.root.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
                resolvedDependencyGraph.ensureDependencyPrecached(root, visitedDependencies)
                for (dependency in listOf(root) + getAllDependencies(root)) {
                    val cacheDirectory = getCacheDirectory(resolvedDependencyGraph, dependency)
                    if (cacheDirectory.exists())
                        allCacheDirectories += cacheDirectory.absolutePath
                }
            }
            for (cacheDirectory in allCacheDirectories)
                add("-Xcache-directory=$cacheDirectory")
        }
    }

    companion object {
        internal fun getRootCacheDirectory(konanHome: File, target: KonanTarget, debuggable: Boolean, cacheKind: NativeCacheKind): File {
            require(cacheKind != NativeCacheKind.NONE) { "Unsupported cache kind: ${NativeCacheKind.NONE}" }
            val optionsAwareCacheName = "$target${if (debuggable) "-g" else ""}$cacheKind"
            return konanHome.resolve("klib/cache/$optionsAwareCacheName")
        }

        internal fun getCacheFileName(baseName: String, cacheKind: NativeCacheKind): String =
            cacheKind.outputKind?.let {
                "${baseName}-cache"
            } ?: error("No output for kind $cacheKind")

        private const val PARTIAL_LINKAGE = "-Xpartial-linkage"
    }
}

@CacheableTask
open class CInteropProcess
@Inject constructor(
    @Internal
    val settings: DefaultCInteropSettings,
    private val objectFactory: ObjectFactory,
    private val execOperations: ExecOperations
) : DefaultTask() {

    @Internal // Taken into account in the outputFileProvider property
    lateinit var destinationDir: Provider<File>

    @get:Input
    val konanTarget: KonanTarget = settings.compilation.konanTarget

    @get:Input
    val konanVersion: CompilerVersion = project.konanVersion

    @get:Input
    val interopName: String = settings.name

    @get:Input
    val baseKlibName: String = run {
        val compilationPrefix = settings.compilation.let {
            if (it.isMainCompilationData()) project.name else it.compilationPurpose
        }
        "$compilationPrefix-cinterop-$interopName"
    }

    @get:Internal
    val outputFileName: String = with(CompilerOutputKind.LIBRARY) {
        "$baseKlibName${suffix(konanTarget)}"
    }

    @get:Input
    val moduleName: String = project.klibModuleName(baseKlibName)

    @get:Internal
    val outputFile: File
        get() = outputFileProvider.get()

    private val runnerSettings = KotlinNativeToolRunner.Settings.fromProject(project)

    // Inputs and outputs.

    @OutputFile
    val outputFileProvider: Provider<File> = project.provider { destinationDir.get().resolve(outputFileName) }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:NormalizeLineEndings
    val defFile: File get() = settings.defFileProperty.get()

    @get:Optional
    @get:Input
    val packageName: String? get() = settings.packageName

    @get:Input
    val compilerOpts: List<String> get() = settings.compilerOpts

    @get:Input
    val linkerOpts: List<String> get() = settings.linkerOpts

    @get:IgnoreEmptyDirectories
    @get:InputFiles
    @get:NormalizeLineEndings
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val headers: FileCollection get() = settings.headers

    @get:Input
    val allHeadersDirs: Set<File> get() = settings.includeDirs.allHeadersDirs.files

    @get:Input
    val headerFilterDirs: Set<File> get() = settings.includeDirs.headerFilterDirs.files

    private val libDirectories = project.buildLibDirectories()

    @get:IgnoreEmptyDirectories
    @get:InputFiles
    @get:NormalizeLineEndings
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val libraries: FileCollection get() = settings.dependencyFiles.filterOutPublishableInteropLibs(libDirectories)

    @get:Input
    val extraOpts: List<String> get() = settings.extraOpts

    private val isInIdeaSync = project.isInIdeaSync

    // Task action.
    @TaskAction
    fun processInterop() {
        val args = mutableListOf<String>().apply {
            addArg("-o", outputFile.absolutePath)

            addArgIfNotNull("-target", konanTarget.visibleName)
            addArgIfNotNull("-def", defFile.canonicalPath)
            addArgIfNotNull("-pkg", packageName)

            addFileArgs("-header", headers)

            compilerOpts.forEach {
                addArg("-compiler-option", it)
            }

            linkerOpts.forEach {
                addArg("-linker-option", it)
            }

            libraries.files.filterKlibsPassedToCompiler().forEach { library ->
                addArg("-library", library.absolutePath)
            }

            addArgs("-compiler-option", allHeadersDirs.map { "-I${it.absolutePath}" })
            addArgs("-headerFilterAdditionalSearchPrefix", headerFilterDirs.map { it.absolutePath })

            if (konanVersion.isAtLeast(1, 4, 0)) {
                addArg("-Xmodule-name", moduleName)
            }

            addAll(extraOpts)
        }

        outputFile.parentFile.mkdirs()
        KotlinNativeCInteropRunner.createExecutionContext(
            task = this,
            isInIdeaSync = isInIdeaSync,
            runnerSettings = runnerSettings,
            gradleExecutionContext = KotlinToolRunner.GradleExecutionContext.fromTaskContext(objectFactory, execOperations, logger)
        ).run(args)
    }
}
