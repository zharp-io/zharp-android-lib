package io.zharp.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `io.zharp.android-gradle`: for every minified (R8/ProGuard) variant of an app,
 *
 * 1. `generateZharp<Variant>ProguardUuid` makes a new UUID for the build and writes it into the
 *    app as the asset `zharp-debug-meta.properties`, which the Zharp SDK puts on every error as
 *    `zharp.proguard_uuid`;
 * 2. `uploadZharp<Variant>Mapping` runs after `minify<Variant>WithR8` and uploads that build's
 *    `mapping.txt` to Zharp under the same UUID, so Zharp turns the obfuscated frames back into the
 *    original classes, methods and lines.
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
        extension.failOnUploadError.convention(true)

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
            if (!variant.isMinifyEnabled) return@onVariants
            val name = variant.name.replaceFirstChar { it.uppercase() }

            val generate = project.tasks.register("generateZharp${name}ProguardUuid", GenerateProguardUuidTask::class.java)
            variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateProguardUuidTask::outputDirectory)

            val output = variant.outputs.firstOrNull()
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
                if (output != null) {
                    // The SDK's default release name: "versionName (versionCode)".
                    task.release.set(
                        output.versionName.zip(output.versionCode) { versionName, versionCode -> "$versionName ($versionCode)" }
                            .orElse(output.versionName),
                    )
                }
            }

            val minify = "minify${name}WithR8"
            project.tasks.configureEach {
                if (this.name == minify) finalizedBy(upload)
            }
        }
    }

    internal companion object {
        const val DEFAULT_URL = "https://api.zharp.io"
    }
}
