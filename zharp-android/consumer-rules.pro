# Zharp reaches nothing by reflection; its public API is kept by the app's own use.

# OpenTelemetry's optional integrations, absent on Android and never called by Zharp.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn io.opentelemetry.sdk.autoconfigure.**
-dontwarn io.opentelemetry.api.incubator.**

# Optional in the app: only touched when it has them.
-dontwarn okhttp3.**
-dontwarn androidx.fragment.**
-keepnames class androidx.fragment.app.FragmentManager$FragmentLifecycleCallbacks
