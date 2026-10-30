package io.zharp.gradle

import org.gradle.api.provider.Property

/**
 * ```kotlin
 * zharp {
 *     appId = "123"                 // the application's ID in Zharp
 *     // authToken: a workspace API key with the artifacts:write scope. Leave it out of build
 *     // files: set ZHARP_AUTH_TOKEN in CI, or zharp.authToken in ~/.gradle/gradle.properties.
 * }
 * ```
 *
 * Every option also reads from the environment (`ZHARP_APP_ID`, `ZHARP_AUTH_TOKEN`, `ZHARP_URL`)
 * and from Gradle properties (`zharp.appId`, `zharp.authToken`, `zharp.url`). Without a token the
 * upload is a dry run: it says what it would send and the build carries on.
 */
public abstract class ZharpExtension {
    /** The application's ID in Zharp (Application settings). */
    public abstract val appId: Property<String>

    /** A workspace API key (`zh_…`) with the `artifacts:write` scope. A secret: keep it out of the repo. */
    public abstract val authToken: Property<String>

    /** Zharp's API. Defaults to `https://api.zharp.io`; set it for a self-hosted or local Zharp. */
    public abstract val url: Property<String>

    /** Upload each minified build's mapping.txt. `false` still tags builds with a UUID (printed), for uploading later with `zharp proguard upload`. */
    public abstract val uploadMappings: Property<Boolean>

    /** Fail the build when the upload fails (the default), so a release never ships with unreadable stacks unnoticed. */
    public abstract val failOnUploadError: Property<Boolean>
}
