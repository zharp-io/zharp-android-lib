package io.zharp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Uploads one build's `mapping.txt` as a `proguard` artifact keyed by its UUID. With no token it's
 * a dry run that says what it would send. Runs every build: the UUID is new each time.
 */
@DisableCachingByDefault(because = "Uploads to Zharp")
public abstract class UploadMappingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val uuidFile: RegularFileProperty

    @get:Input
    @get:Optional
    public abstract val appId: Property<String>

    /** A secret: kept out of task inputs (and so out of build caches and scans). */
    @get:Internal
    public abstract val authToken: Property<String>

    @get:Input
    public abstract val url: Property<String>

    @get:Input
    @get:Optional
    public abstract val release: Property<String>

    @get:Input
    public abstract val enabledUpload: Property<Boolean>

    @get:Input
    public abstract val failOnError: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun upload() {
        val uuid = GenerateProguardUuidTask.read(uuidFile.get().asFile.readText())
            ?: throw GradleException("Zharp: no ProGuard UUID in ${uuidFile.get().asFile}")
        val mapping = mappingFile.get().asFile
        val appId = appId.orNull?.trim().orEmpty()
        val token = authToken.orNull?.trim().orEmpty()
        val url = url.get().trim().trimEnd('/')
        val release = release.orNull

        if (!enabledUpload.get()) {
            logger.lifecycle("Zharp: mapping upload is off (uploadMappings = false). ProGuard UUID $uuid; upload with: npx @zharp/cli proguard upload \"$mapping\" --app <id> --uuid $uuid")
            return
        }
        if (token.isEmpty()) {
            logger.lifecycle(
                "Zharp: dry run (no ZHARP_AUTH_TOKEN / zharp.authToken): would upload $mapping (${mapping.length()} bytes) " +
                    "as kind=proguard debugId=$uuid${release?.let { " release=\"$it\"" } ?: ""} to " +
                    "$url/api/v1/applications/${appId.ifEmpty { "<appId>" }}/artifacts",
            )
            return
        }
        if (appId.isEmpty()) {
            throw GradleException("Zharp: set zharp { appId = \"…\" } (or ZHARP_APP_ID) to upload the mapping; a token is set but no application ID.")
        }

        try {
            val uploader = ArtifactUploader(url, token)
            uploader.upload(
                appId = appId,
                fields = buildMap {
                    put("kind", "proguard")
                    put("debugId", uuid)
                    put("name", "mapping.txt")
                    release?.let { put("release", it) }
                },
                file = mapping,
                fileName = "mapping.txt",
                contentType = "text/plain",
            )
            logger.lifecycle("Zharp: uploaded mapping.txt for ProGuard UUID $uuid (${mapping.length()} bytes)")
        } catch (error: Exception) {
            val message = "Zharp: couldn't upload the mapping for ProGuard UUID $uuid: ${error.message}"
            if (failOnError.get()) throw GradleException(message, error)
            logger.warn("$message. Stacks from this build will stay obfuscated until it's uploaded (zharp proguard upload).")
        }
    }
}
