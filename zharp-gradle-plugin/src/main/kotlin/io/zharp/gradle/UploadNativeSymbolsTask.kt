package io.zharp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Uploads the symbols of the app's native libraries (`.so`, built with the NDK or shipped in its
 * dependencies), so native crashes show function names, files and lines.
 *
 * The libraries are read before Android strips them (AGP's merged native libs), turned into
 * Breakpad `.sym` files by Mozilla's dump_syms (see [DumpSyms]), gzipped, and uploaded as
 * `breakpad-sym` artifacts under each library's debug ID: the one the `.sym`'s MODULE line names,
 * derived from the library's GNU build ID, which a crash's tombstone records for every frame.
 * Libraries with no symbols at all are skipped. With [includeSources], the app's own C and C++
 * files each library's symbols name go along as a `source-bundle`, so crashes show the code
 * of that build. With no token it's a dry run that lists the libraries.
 */
@DisableCachingByDefault(because = "Uploads to Zharp")
public abstract class UploadNativeSymbolsTask : DefaultTask() {
    /** AGP's merged native libraries of the variant, unstripped; missing when the app has none. */
    @get:Internal
    public abstract val nativeLibs: DirectoryProperty

    /** Only source files under here go into a bundle. */
    @get:Internal
    public abstract val rootDir: Property<File>

    /** Where a downloaded dump_syms is kept (Gradle's user home). */
    @get:Internal
    public abstract val cacheDir: Property<File>

    @get:Input
    @get:Optional
    public abstract val dumpSyms: Property<String>

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
    public abstract val includeSources: Property<Boolean>

    @get:Input
    public abstract val failOnError: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun upload() {
        val libs = libraries(nativeLibs.get().asFile)
        if (libs.isEmpty()) {
            logger.info("Zharp: no native libraries in this build; no native symbols to upload.")
            return
        }
        val appId = appId.orNull?.trim().orEmpty()
        val token = authToken.orNull?.trim().orEmpty()
        val url = url.get().trim().trimEnd('/')
        val release = release.orNull
        if (token.isEmpty()) {
            logger.lifecycle(
                "Zharp: dry run (no ZHARP_AUTH_TOKEN / zharp.authToken): would upload the native symbols of " +
                    "${libs.size} librar${if (libs.size == 1) "y" else "ies"} (${libs.joinToString { "${it.parentFile.name}/${it.name}" }}) as kind=breakpad-sym",
            )
            return
        }
        if (appId.isEmpty()) {
            throw GradleException("Zharp: set zharp { appId = \"…\" } (or ZHARP_APP_ID) to upload native symbols; a token is set but no application ID.")
        }

        try {
            val binary = DumpSyms.find(dumpSyms.orNull, cacheDir.get(), logger)
            val modules = collect(libs, binary)
            if (modules.isEmpty()) {
                logger.warn("Zharp: none of the ${libs.size} native libraries has symbols (stripped before the build?); native crashes will show addresses only.")
                return
            }
            val uploader = ArtifactUploader(url, token)
            for (module in modules) {
                val gz = File(temporaryDir, "${module.name}-${module.debugId}.sym.gz")
                GZIPOutputStream(gz.outputStream().buffered()).use { it.write(module.data) }
                try {
                    uploader.upload(
                        appId = appId,
                        fields = buildMap {
                            put("kind", "breakpad-sym")
                            put("debugId", module.debugId)
                            put("name", module.name)
                            put("arch", module.arch)
                            release?.let { put("release", it) }
                        },
                        file = gz,
                        fileName = "${module.name}.sym.gz",
                        contentType = "application/gzip",
                    )
                } finally {
                    gz.delete()
                }
                if (!module.functions) logger.warn("Zharp: ${module.name} (${module.arch}) has no function names, only its exports: it was stripped before the build.")
                var withSources = ""
                if (includeSources.get()) {
                    val sources = sourcesOf(module.data, rootDir.get())
                    if (sources.isNotEmpty()) {
                        val bundle = File(temporaryDir, "sources-${module.debugId}.json.gz")
                        GZIPOutputStream(bundle.outputStream().buffered()).use { it.write(UploadSourcesTask.bundleJson(sources).toByteArray(Charsets.UTF_8)) }
                        try {
                            uploader.upload(
                                appId = appId,
                                fields = buildMap {
                                    put("kind", "source-bundle")
                                    put("debugId", module.debugId)
                                    put("name", "sources.json")
                                    release?.let { put("release", it) }
                                },
                                file = bundle,
                                fileName = "sources.json.gz",
                                contentType = "application/gzip",
                            )
                        } finally {
                            bundle.delete()
                        }
                        withSources = ", with ${sources.size} source file(s)"
                    }
                }
                logger.lifecycle("Zharp: uploaded native symbols of ${module.name} (${module.arch}) ${module.debugId}$withSources")
            }
        } catch (error: Exception) {
            val message = "Zharp: couldn't upload native symbols: ${error.message}"
            if (failOnError.get()) throw GradleException(message, error)
            logger.warn("$message. Native crashes from this build will show addresses until they're uploaded (npx @zharp/cli debug-files upload).")
        }
    }

    /** One library's symbols, as dump_syms wrote them. */
    internal class Module(val name: String, val arch: String, val debugId: String, val data: ByteArray, val functions: Boolean)

    /** Every library's `.sym`, one per debug ID: the same library in two ABIs has two IDs. */
    private fun collect(libs: List<File>, binary: File): List<Module> {
        val work = temporaryDir
        val byId = LinkedHashMap<String, Module>()
        for (lib in libs) {
            val read = try {
                readSym(DumpSyms.dump(binary, lib, work))
            } catch (error: Exception) {
                logger.info("Zharp: skipped ${lib.parentFile.name}/${lib.name}: ${error.message}")
                null
            } ?: continue
            if (!read.hasRecords) {
                logger.info("Zharp: skipped ${lib.parentFile.name}/${lib.name}: no symbols")
                continue
            }
            byId.putIfAbsent(read.module.debugId, read.module)
        }
        return byId.values.toList()
    }

    internal companion object {
        /** `MODULE <os> <arch> <debug id> <name>`: the first line of every `.sym`. */
        private val MODULE = Regex("^MODULE (\\S+) (\\S+) ([0-9A-Fa-f]{8,}) (.+?)\\s*$")
        private val FILE_RECORD = Regex("^FILE \\d+ (.+?)\\r?$", RegexOption.MULTILINE)
        private val SOURCE_EXTENSIONS = Regex("\\.(c|cc|cpp|cxx|c\\+\\+|h|hh|hpp|hxx|h\\+\\+|inl|ipp|tpp|m|mm|rs)$", RegexOption.IGNORE_CASE)
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

        class Read(val module: Module, val hasRecords: Boolean)

        /** The `.so` files under AGP's merged native libs (`<abi>/libfoo.so`), in a stable order. */
        fun libraries(dir: File): List<File> =
            if (!dir.isDirectory) emptyList() else dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.sortedBy { it.path }.toList()

        fun readSym(data: ByteArray): Read? {
            val head = String(data, 0, minOf(data.size, 4096), Charsets.UTF_8).removePrefix("﻿")
            val m = MODULE.find(head.lineSequence().firstOrNull().orEmpty()) ?: return null
            val text = String(data, Charsets.UTF_8)
            val functions = text.contains("\nFUNC ")
            val publics = text.contains("\nPUBLIC ")
            return Read(Module(m.groupValues[4], m.groupValues[2], m.groupValues[3], data, functions), functions || publics)
        }

        /**
         * The app's own C and C++ files a `.sym` names, read from disk: those under [root] (the
         * project), never the NDK's headers or a dependency's. Keyed by the path as the `.sym`
         * gives it, which is how the crash's frames name them.
         */
        fun sourcesOf(sym: ByteArray, root: File): Map<String, String> {
            val rootPath = root.canonicalFile.invariantSeparatorsPath.trimEnd('/') + "/"
            val out = sortedMapOf<String, String>()
            var total = 0L
            for (match in FILE_RECORD.findAll(String(sym, Charsets.UTF_8))) {
                val path = match.groupValues[1]
                if (!SOURCE_EXTENSIONS.containsMatchIn(path) || path in out) continue
                val file = File(path)
                val canonical = try { file.canonicalFile } catch (e: Exception) { continue }
                val absolute = canonical.invariantSeparatorsPath
                // Generated code (build/) is not the app's own.
                if (!absolute.startsWith(rootPath) || ("/" + absolute.removePrefix(rootPath)).contains("/build/") || !canonical.isFile) continue
                val size = canonical.length()
                if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) continue
                out[path] = canonical.readText()
                total += size
            }
            return out
        }
    }
}
