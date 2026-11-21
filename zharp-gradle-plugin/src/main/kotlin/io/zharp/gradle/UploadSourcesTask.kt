package io.zharp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Uploads the app's own Java and Kotlin sources as a `source-bundle` artifact under the build's
 * UUID, so its errors show the exact code of that build — no repository needed. On by default
 * (`zharp { includeSources = false }` to turn it off). With no token it's a dry run.
 *
 * The bundle is gzipped JSON, {"files": {"app/src/main/java/io/shop/Cart.kt": "…"}}, paths from
 * the root project.
 */
@DisableCachingByDefault(because = "Uploads to Zharp")
public abstract class UploadSourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceDirs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val uuidFile: RegularFileProperty

    /** Where paths in the bundle start from. */
    @get:Internal
    public abstract val rootDir: Property<File>

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
    public abstract val failOnError: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun upload() {
        val uuid = GenerateProguardUuidTask.read(uuidFile.get().asFile.readText())
            ?: throw GradleException("Zharp: no build UUID in ${uuidFile.get().asFile}")
        val files = collect(sourceDirs.files, rootDir.get())
        if (files.isEmpty()) {
            logger.lifecycle("Zharp: no Java or Kotlin sources found to upload.")
            return
        }
        val bytes = files.values.sumOf { it.length.toLong() }
        val appId = appId.orNull?.trim().orEmpty()
        val token = authToken.orNull?.trim().orEmpty()
        val url = url.get().trim().trimEnd('/')
        val release = release.orNull
        if (token.isEmpty()) {
            logger.lifecycle("Zharp: dry run (no ZHARP_AUTH_TOKEN / zharp.authToken): would upload ${files.size} source file(s) as kind=source-bundle debugId=$uuid")
            return
        }
        if (appId.isEmpty()) {
            throw GradleException("Zharp: set zharp { appId = \"…\" } (or ZHARP_APP_ID) to upload the sources; a token is set but no application ID.")
        }
        val bundle = File(temporaryDir, "sources.json.gz")
        GZIPOutputStream(bundle.outputStream().buffered()).use { it.write(bundleJson(files).toByteArray(Charsets.UTF_8)) }
        try {
            ArtifactUploader(url, token).upload(
                appId = appId,
                fields = buildMap {
                    put("kind", "source-bundle")
                    put("debugId", uuid)
                    put("name", "sources.json")
                    release?.let { put("release", it) }
                },
                file = bundle,
                fileName = "sources.json.gz",
                contentType = "application/gzip",
            )
            logger.lifecycle("Zharp: uploaded the app's sources (${files.size} files, ${bytes / 1024} kB) for build $uuid — errors show the code of this build. includeSources = false to leave them out.")
        } catch (error: Exception) {
            val message = "Zharp: couldn't upload the sources for build $uuid: ${error.message}"
            if (failOnError.get()) throw GradleException(message, error)
            logger.warn(message)
        } finally {
            bundle.delete()
        }
    }

    internal companion object {
        private val EXTENSIONS = setOf("kt", "java")
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
        private const val MAX_FILES = 20_000

        /** The Java/Kotlin files under these folders, by path from `root`, within the size caps. */
        fun collect(dirs: Collection<File>, root: File): Map<String, String> {
            val out = sortedMapOf<String, String>()
            var total = 0L
            for (dir in dirs.filter { it.isDirectory }.sortedBy { it.path }) {
                // Generated sources (build/) are not the app's own code.
                if (dir.invariantSeparatorsPath.contains("/build/")) continue
                for (file in dir.walkTopDown().filter { it.isFile && it.extension in EXTENSIONS }.sortedBy { it.path }) {
                    val size = file.length()
                    if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES || out.size >= MAX_FILES) continue
                    val path = file.relativeToOrSelf(root).invariantSeparatorsPath
                    if (out.putIfAbsent(path, file.readText()) == null) total += size
                }
            }
            return out
        }

        fun bundleJson(files: Map<String, String>): String = buildString {
            append("{\"files\":{")
            files.entries.forEachIndexed { i, (path, text) ->
                if (i > 0) append(',')
                appendJsonString(path)
                append(':')
                appendJsonString(text)
            }
            append("}}")
        }

        private fun StringBuilder.appendJsonString(value: String) {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
