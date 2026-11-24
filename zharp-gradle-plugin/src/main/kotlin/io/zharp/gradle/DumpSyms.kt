package io.zharp.gradle

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Mozilla's `dump_syms` (MIT / Apache-2.0): turns an unstripped `.so` (DWARF in ELF) into a
 * Breakpad `.sym` file, the symbol format Zharp's native crash processing reads.
 *
 * Found as `@zharp/cli debug-files upload` finds it: `zharp { dumpSyms = "…" }` /
 * `ZHARP_DUMP_SYMS`, then `dump_syms` on PATH, then Mozilla's prebuilt release, downloaded once
 * into Gradle's user home and checked against the SHA-256 pinned here. Nothing is installed
 * globally, and nothing is run that failed its checksum.
 */
internal object DumpSyms {
    const val VERSION = "2.3.9"

    internal class Release(val file: String, val sha256: String)

    /** From https://github.com/mozilla/dump_syms/releases/download/v2.3.9/sha256.sum (as @zharp/cli pins them). */
    internal val RELEASES = mapOf(
        "linux-x64" to Release("dump_syms-x86_64-unknown-linux-gnu.tar.xz", "0fc852a86b00337407d9d423cc388a24c3b489ccaaedcf92623cad57af5ca8ad"),
        "linux-arm64" to Release("dump_syms-aarch64-unknown-linux-gnu.tar.xz", "1b8d6ab436fbfeb9820d4a21413cc77c50c6a4221cff25a830f5d53ba0aaa732"),
        "darwin-x64" to Release("dump_syms-x86_64-apple-darwin.tar.xz", "e255caf99e403b31613c756babcead8463f6e600c1a9e1e5531fbe27a7e29cf2"),
        "darwin-arm64" to Release("dump_syms-aarch64-apple-darwin.tar.xz", "4945b7e5de0d7ad403822635f0fb912b69990fc412741ff2b7b512e168c40a32"),
        "windows-x64" to Release("dump_syms-x86_64-pc-windows-msvc.zip", "bdf48486220708808a3e00aec78856ee1cce096189d47a1e2cb1c635f93bacc7"),
        // Windows on Arm runs the x64 build: there is no arm64 one.
        "windows-arm64" to Release("dump_syms-x86_64-pc-windows-msvc.zip", "bdf48486220708808a3e00aec78856ee1cce096189d47a1e2cb1c635f93bacc7"),
    )

    private val windows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
    private val exe = if (windows) "dump_syms.exe" else "dump_syms"

    fun platform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val o = when {
            os.startsWith("windows") -> "windows"
            os.startsWith("mac") || os.contains("darwin") -> "darwin"
            else -> "linux"
        }
        val a = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
        return "$o-$a"
    }

    /** The dump_syms to run, downloading Mozilla's release into [cacheDir] the first time. */
    fun find(explicit: String?, cacheDir: File, logger: Logger): File {
        if (!explicit.isNullOrBlank()) {
            val file = File(explicit)
            if (!file.isFile) throw GradleException("Zharp: dump_syms not found at $explicit (zharp { dumpSyms } / ZHARP_DUMP_SYMS).")
            return file
        }
        onPath()?.let { return it }
        val release = RELEASES[platform()]
            ?: throw GradleException("Zharp: no dump_syms build for ${platform()}: install Mozilla's dump_syms (cargo install dump_syms) and set ZHARP_DUMP_SYMS.")
        val dir = File(cacheDir, "dump_syms-$VERSION")
        val binary = File(dir, exe)
        if (binary.isFile) return binary
        download(release, dir, logger)
        return binary
    }

    private fun onPath(): File? {
        for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)) {
            if (dir.isEmpty()) continue
            val candidate = File(dir, exe)
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }

    private fun download(release: Release, dir: File, logger: Logger) {
        val url = "https://github.com/mozilla/dump_syms/releases/download/v$VERSION/${release.file}"
        logger.lifecycle("Zharp: downloading dump_syms $VERSION (once, into $dir)")
        val archive = try {
            fetch(url)
        } catch (error: IOException) {
            throw GradleException("Zharp: couldn't download dump_syms from $url (${error.message}). Install it and set ZHARP_DUMP_SYMS, or zharp { uploadNativeSymbols = false }.", error)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        if (digest != release.sha256) {
            throw GradleException("Zharp: the dump_syms download failed its checksum (got $digest, expected ${release.sha256}); nothing was run.")
        }
        // Unpacked beside the final folder, then moved into place: a half-finished one is never used.
        val work = File(dir.parentFile, "${dir.name}.part-${ProcessHandle.current().pid()}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val file = File(work, release.file)
            file.writeBytes(archive)
            val out = File(work, "out").apply { mkdirs() }
            if (release.file.endsWith(".zip")) unzip(file, out) else untar(file, out)
            val binary = out.walkTopDown().firstOrNull { it.isFile && it.name == exe }
                ?: throw GradleException("Zharp: dump_syms is not in ${release.file}.")
            binary.setExecutable(true)
            val staged = File(work, "bin").apply { mkdirs() }
            binary.copyTo(File(staged, exe), overwrite = true).setExecutable(true)
            if (!staged.renameTo(dir) && !File(dir, exe).isFile) {
                // Another build got there first, or a rename across folders failed: copy.
                staged.copyRecursively(dir, overwrite = true)
                File(dir, exe).setExecutable(true)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun fetch(url: String): ByteArray {
        var target = url
        repeat(5) {
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.setRequestProperty("User-Agent", "zharp-android-gradle")
                val status = connection.responseCode
                if (status in 300..399) {
                    // GitHub sends release files from another host: followed by hand across hosts.
                    target = connection.getHeaderField("Location") ?: throw IOException("HTTP $status without a Location")
                    return@repeat
                }
                if (status !in 200..299) throw IOException("HTTP $status")
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun unzip(archive: File, into: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(into, entry.name).canonicalFile
                if (!target.path.startsWith(into.canonicalPath)) throw GradleException("Zharp: unexpected path in the dump_syms archive: ${entry.name}")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /** Linux and macOS tar read .tar.xz (with xz installed); the JDK can't. */
    private fun untar(archive: File, into: File) {
        val process = ProcessBuilder("tar", "-xJf", archive.path, "-C", into.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw GradleException("Zharp: couldn't unpack dump_syms with tar (${output.trim()}). It needs xz (apt install xz-utils), or install dump_syms and set ZHARP_DUMP_SYMS.")
        }
    }

    /** Runs dump_syms on one file: the `.sym` text. Throws with dump_syms's own words when it can't. */
    fun dump(binary: File, input: File, work: File): ByteArray {
        val out = File(work, "${input.name}-${System.nanoTime()}.sym")
        val log = File(work, "dump_syms.log")
        val process = ProcessBuilder(binary.path, "--inlines", "-o", out.path, input.path)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("dump_syms timed out on ${input.name}")
        }
        if (process.exitValue() != 0 || !out.isFile) {
            throw IOException(log.readText().trim().lines().takeLast(3).joinToString(" ").ifEmpty { "exit code ${process.exitValue()}" })
        }
        try {
            return out.readBytes()
        } finally {
            out.delete()
        }
    }
}
