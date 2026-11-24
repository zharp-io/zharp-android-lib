package io.zharp.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * `io.zharp.android-gradle`: for every minified (R8/ProGuard) variant of an app, and every
 * release variant,
 *
 * 1. `generateZharp<Variant>ProguardUuid` makes a new UUID for the build and writes it into the
 *    app as the asset `zharp-debug-meta.properties`, which the Zharp SDK puts on every error as
 *    `zharp.proguard_uuid`;
 * 2. `uploadZharp<Variant>Mapping` runs after `minify<Variant>WithR8` and uploads that build's
 *    `mapping.txt` to Zharp under the same UUID, so Zharp turns the obfuscated frames back into the
 *    original classes, methods and lines;
 * 3. `uploadZharp<Variant>Sources` runs after `assemble<Variant>` / `bundle<Variant>` and uploads
 *    the app's own Java and Kotlin sources under that UUID, so errors show the build's exact code
 *    (`includeSources = false` to keep them);
 * 4. `uploadZharp<Variant>NativeSymbols` runs after `merge<Variant>NativeLibs` in release builds of
 *    apps with native libraries and uploads each `.so`'s symbols as a Breakpad `.sym`, so native
 *    (NDK) crashes show function names, files and lines (`uploadNativeSymbols = false` to skip).
 */
public class ZharpAndroidPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("zharp", ZharpExtension::class.java)
        val providers = project.providers
        extension.appId.convention(providers.environmentVariable("ZHARP_APP_ID").orElse(providers.gradleProperty("zharp.appId")))
        extension.authToken.convention(providers.environmentVariable("ZHARP_AUTH_TOKEN").orElse(providers.gradleProperty("zharp.authToken")))
        extension.url.convention(
            providers.environmentVariable("ZHARP_URL").orElse(providers.gradleProperty("zharp.url")).orElse(DEFAULT_URL),
        )
        extension.uploadMappings.convention(true)
        extension.includeSources.convention(
            providers.environmentVariable("ZHARP_INCLUDE_SOURCES").orElse(providers.gradleProperty("zharp.includeSources")).map { it.trim() != "false" }.orElse(true),
        )
        extension.failOnUploadError.convention(true)
        extension.uploadNativeSymbols.convention(
            providers.environmentVariable("ZHARP_UPLOAD_NATIVE_SYMBOLS").orElse(providers.gradleProperty("zharp.uploadNativeSymbols")).map { it.trim() != "false" }.orElse(true),
        )
        extension.dumpSyms.convention(providers.environmentVariable("ZHARP_DUMP_SYMS").orElse(providers.gradleProperty("zharp.dumpSyms")))

        var applied = false
        project.pluginManager.withPlugin("com.android.application") {
            applied = true
            configure(project, extension)
        }
        project.afterEvaluate {
            if (!applied) {
                project.logger.warn("io.zharp.android-gradle: apply it to an app module (com.android.application); nothing to do in ${project.path}")
            }
        }
    }

    private fun configure(project: Project, extension: ZharpExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
            val minified = variant.isMinifyEnabled
            // Sources go with release builds — what users run — not with every debug build.
            val sources = extension.includeSources.getOrElse(true) && variant.buildType == "release"
            val name = variant.name.replaceFirstChar { it.uppercase() }
            if (extension.uploadNativeSymbols.getOrElse(true) && variant.buildType == "release") {
                registerNativeSymbols(project, extension, variant, name)
            }
            if (!minified && !sources) return@onVariants

            val generate = project.tasks.register("generateZharp${name}ProguardUuid", GenerateProguardUuidTask::class.java)
            variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateProguardUuidTask::outputDirectory)

            val output = variant.outputs.firstOrNull()
            // The SDK's default release name: "versionName (versionCode)".
            val releaseName = output?.let { o -> o.versionName.zip(o.versionCode) { versionName, versionCode -> "$versionName ($versionCode)" }.orElse(o.versionName) }

            if (sources) {
                val uploadSources = project.tasks.register("uploadZharp${name}Sources", UploadSourcesTask::class.java) {
                    val task = this
                    task.group = "zharp"
                    task.description = "Uploads the ${variant.name} build's Java and Kotlin sources to Zharp."
                    variant.sources.java?.let { task.sourceDirs.from(it.all) }
                    variant.sources.kotlin?.let { task.sourceDirs.from(it.all) }
                    task.uuidFile.set(generate.flatMap { it.outputDirectory.file(GenerateProguardUuidTask.ASSET) })
                    task.rootDir.set(project.rootDir)
                    task.appId.set(extension.appId)
                    task.authToken.set(extension.authToken)
                    task.url.set(extension.url)
                    task.failOnError.set(extension.failOnUploadError)
                    if (releaseName != null) task.release.set(releaseName)
                }
                val built = setOf("assemble$name", "bundle$name")
                project.tasks.configureEach {
                    if (this.name in built) finalizedBy(uploadSources)
                }
            }
            if (!minified) return@onVariants
            val upload = project.tasks.register("uploadZharp${name}Mapping", UploadMappingTask::class.java) {
                val task = this
                task.group = "zharp"
                task.description = "Uploads the ${variant.name} build's R8/ProGuard mapping.txt to Zharp."
                task.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                task.uuidFile.set(generate.flatMap { it.outputDirectory.file(GenerateProguardUuidTask.ASSET) })
                task.appId.set(extension.appId)
                task.authToken.set(extension.authToken)
                task.url.set(extension.url)
                task.enabledUpload.set(extension.uploadMappings)
                task.failOnError.set(extension.failOnUploadError)
                if (releaseName != null) task.release.set(releaseName)
            }

            val minify = "minify${name}WithR8"
            project.tasks.configureEach {
                if (this.name == minify) finalizedBy(upload)
            }
        }
    }

    /**
     * `uploadZharp<Variant>NativeSymbols`, after `merge<Variant>NativeLibs`: the libraries as they
     * are before Android strips them, so their symbols are complete.
     */
    private fun registerNativeSymbols(project: Project, extension: ZharpExtension, variant: ApplicationVariant, name: String) {
        val output = variant.outputs.firstOrNull()
        val releaseName = output?.let { o -> o.versionName.zip(o.versionCode) { versionName, versionCode -> "$versionName ($versionCode)" }.orElse(o.versionName) }
        val merge = "merge${name}NativeLibs"
        val upload = project.tasks.register("uploadZharp${name}NativeSymbols", UploadNativeSymbolsTask::class.java) {
            val task = this
            task.group = "zharp"
            task.description = "Uploads the ${variant.name} build's native library symbols (.so → Breakpad .sym) to Zharp."
            task.nativeLibs.set(project.layout.buildDirectory.dir("intermediates/merged_native_libs/${variant.name}"))
            task.rootDir.set(project.rootDir)
            task.cacheDir.set(File(project.gradle.gradleUserHomeDir, "caches/zharp"))
            task.dumpSyms.set(extension.dumpSyms)
            task.appId.set(extension.appId)
            task.authToken.set(extension.authToken)
            task.url.set(extension.url)
            task.includeSources.set(extension.includeSources)
            task.failOnError.set(extension.failOnUploadError)
            if (releaseName != null) task.release.set(releaseName)
            task.mustRunAfter(merge)
        }
        project.tasks.configureEach {
            if (this.name == merge) finalizedBy(upload)
        }
    }

    internal companion object {
        const val DEFAULT_URL = "https://api.zharp.io"
    }
}
