# Zharp reaches nothing by reflection; its public API is kept by the app's own use.

# OpenTelemetry (1.67+) reaches the resource's entities by name, by reflection
# (io.opentelemetry.sdk.resources.internal.EntityUtil). Renamed by R8, every export fails —
# traces, logs and saved crashes alike: "Error calling getEntities on Resource".
-keepclassmembers class io.opentelemetry.sdk.resources.Resource { *** getEntities(); }
-keepclassmembers class * extends io.opentelemetry.sdk.resources.Resource { *** getEntities(); }
-keepclassmembers class io.opentelemetry.sdk.resources.ResourceBuilder { *** addEntity(...); }

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
-dontwarn timber.log.**
-keepnames class timber.log.Timber$Tree
