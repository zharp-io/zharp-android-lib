package io.zharp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.UUID

/**
 * A new UUID for each build, as the asset the SDK reads. New every time (never up to date): each
 * build's mapping is uploaded under its own UUID, so an app never points at another build's mapping.
 */
@DisableCachingByDefault(because = "Makes a new UUID for every build")
public abstract class GenerateProguardUuidTask : DefaultTask() {
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    init {
        group = "zharp"
        description = "Writes a new ProGuard/R8 UUID into the app for Zharp."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun generate() {
        val uuid = UUID.randomUUID().toString()
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve(ASSET).writeText(contents(uuid))
        logger.info("Zharp ProGuard UUID: $uuid")
    }

    internal companion object {
        /** Read by the SDK (io.zharp.android.internal.BuildIds); keep the two in step. */
        const val ASSET = "zharp-debug-meta.properties"
        const val PROPERTY = "io.zharp.proguard.uuid"

        fun contents(uuid: String) = "# Written by io.zharp.android-gradle: the mapping uploaded to Zharp for this build.\n$PROPERTY=$uuid\n"

        fun read(text: String): String? =
            text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$PROPERTY=") }
                ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    }
}
