package io.zharp.android.internal

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.opentelemetry.api.common.Attributes
import java.io.File
import java.util.Locale

/** What the device and the app say about themselves, read once at init. */
internal class AndroidPlatform(private val context: Context) {
    val store: KeyValueStore = PreferencesStore(context.getSharedPreferences("io.zharp.android", Context.MODE_PRIVATE))

    val packageName: String = context.packageName

    val versionName: String? = guarded(null, "Reading the app version") {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        info.versionName?.let { "$it ($code)" }
    }

    /** Kept out of backups: a restored outbox from another device would be sent as this one's. */
    val outboxDir: File = File(context.noBackupFilesDir ?: context.filesDir, "zharp/outbox")

    fun deviceAttributes(): Attributes {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return Attributes.builder()
            .put(Keys.OS_NAME, "Android")
            .put(Keys.OS_TYPE, "linux")
            .put(Keys.OS_VERSION, Build.VERSION.RELEASE.orEmpty())
            .put(Keys.ANDROID_API_LEVEL, Build.VERSION.SDK_INT.toLong())
            .put(Keys.DEVICE_ID, deviceId(store))
            .put(Keys.DEVICE_MODEL_NAME, modelName(manufacturer, model))
            .put(Keys.DEVICE_MODEL_IDENTIFIER, model)
            .put(Keys.DEVICE_MANUFACTURER, manufacturer)
            .put(Keys.APP_ID, packageName)
            .build()
    }

    companion object {
        /**
         * Random, made on first run and kept in the app's private storage: the same for every
         * session of this install, new after a reinstall, never shared with other apps, and not a
         * hardware identifier.
         */
        fun deviceId(store: KeyValueStore): String =
            store.get("device.id") ?: Ids.random128().also { store.put("device.id", it) }

        /** "Google Pixel 8", "Samsung SM-S911B" — the maker once, capitalised. */
        fun modelName(manufacturer: String, model: String): String {
            if (manufacturer.isEmpty()) return model
            if (model.lowercase(Locale.ROOT).startsWith(manufacturer.lowercase(Locale.ROOT))) {
                return model.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            return manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) } + " " + model
        }
    }
}

private class PreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = guarded(null, "Reading preferences") { preferences.getString(key, null) }
    override fun put(key: String, value: String) {
        guarded(Unit, "Writing preferences") { preferences.edit().putString(key, value).apply() }
    }
}
