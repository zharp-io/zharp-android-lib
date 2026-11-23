package io.zharp.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The parts of the native symbols upload that don't need dump_syms or an NDK build. */
class NativeSymbolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsTheModuleLine() {
        val sym = """
            MODULE Linux arm64 0CA51D8A6FBD3E1F87B1C35D19A4E6E70 libshop.so
            INFO CODE_ID 8A1DA50CBD6F1F3E87B1C35D19A4E6E7C0FFEE00
            FILE 0 /work/app/src/main/cpp/cart.cpp
            FUNC 1a2a0 40 0 Cart::total() const
            1a2a0 14 12 0
        """.trimIndent().toByteArray()
        val read = UploadNativeSymbolsTask.readSym(sym)!!
        assertEquals("libshop.so", read.module.name)
        assertEquals("arm64", read.module.arch)
        assertEquals("0CA51D8A6FBD3E1F87B1C35D19A4E6E70", read.module.debugId)
        assertTrue(read.module.functions)
        assertTrue(read.hasRecords)

        // A stripped library: its exports only, still worth sending.
        val exports = UploadNativeSymbolsTask.readSym("MODULE Linux x86_64 ABCDEF0123456789ABCDEF01234567890 libvendor.so\nPUBLIC 1000 0 vendor_init\n".toByteArray())!!
        assertFalse(exports.module.functions)
        assertTrue(exports.hasRecords)

        // Unwind data only: nothing to name a frame with.
        val cfiOnly = UploadNativeSymbolsTask.readSym("MODULE Linux x86_64 ABCDEF0123456789ABCDEF01234567890 libbare.so\nSTACK CFI INIT 1000 10 .cfa: \$rsp 8 +\n".toByteArray())!!
        assertFalse(cfiOnly.hasRecords)

        assertNull(UploadNativeSymbolsTask.readSym("not a sym file".toByteArray()))
    }

    @Test
    fun findsTheLibrariesOfEveryAbi() {
        val dir = folder.newFolder("merged_native_libs", "release", "mergeReleaseNativeLibs", "out", "lib")
        for (abi in listOf("arm64-v8a", "x86_64")) {
            File(dir, abi).mkdirs()
            File(dir, "$abi/libshop.so").writeText("elf")
            File(dir, "$abi/libc++_shared.so").writeText("elf")
        }
        File(dir, "x86_64/notes.txt").writeText("no")
        val libs = UploadNativeSymbolsTask.libraries(folder.root.resolve("merged_native_libs/release"))
        assertEquals(4, libs.size)
        assertTrue(libs.all { it.name.endsWith(".so") })
        assertTrue(UploadNativeSymbolsTask.libraries(File(folder.root, "missing")).isEmpty())
    }

    @Test
    fun bundlesOnlyTheAppsOwnSourcesTheSymbolsName() {
        val root = folder.newFolder("project")
        val cpp = File(root, "app/src/main/cpp").apply { mkdirs() }
        File(cpp, "cart.cpp").writeText("int Cart::total() const { return *items; }\n")
        File(cpp, "cart.h").writeText("struct Cart { int* items; int total() const; };\n")
        val generated = File(root, "app/build/generated").apply { mkdirs() }
        File(generated, "gen.cpp").writeText("// generated\n")
        val outside = folder.newFolder("ndk")
        File(outside, "vector").writeText("// a system header\n")
        File(outside, "string.h").writeText("// a system header\n")
        val sym = """
            MODULE Linux arm64 0CA51D8A6FBD3E1F87B1C35D19A4E6E70 libshop.so
            FILE 0 ${File(cpp, "cart.cpp").path}
            FILE 1 ${File(cpp, "cart.h").path}
            FILE 2 ${File(generated, "gen.cpp").path}
            FILE 3 ${File(outside, "string.h").path}
            FILE 4 ${File(outside, "vector").path}
            FILE 5 ${File(root, "app/src/main/cpp/deleted.cpp").path}
        """.trimIndent().toByteArray()
        val sources = UploadNativeSymbolsTask.sourcesOf(sym, root)
        assertEquals(setOf(File(cpp, "cart.cpp").path, File(cpp, "cart.h").path), sources.keys)
        assertEquals("int Cart::total() const { return *items; }\n", sources[File(cpp, "cart.cpp").path])
    }

    @Test
    fun aDumpSymsReleaseForEveryBuildMachine() {
        for (platform in listOf("linux-x64", "linux-arm64", "darwin-x64", "darwin-arm64", "windows-x64", "windows-arm64")) {
            val release = DumpSyms.RELEASES[platform]!!
            assertTrue(release.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        assertTrue(DumpSyms.platform() in DumpSyms.RELEASES)
    }
}
