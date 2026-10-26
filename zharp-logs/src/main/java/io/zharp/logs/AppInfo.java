package io.zharp.logs;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/** What's known about the app and the device it runs on. Read once at init. */
final class AppInfo {
    final String packageName;
    final String versionName;
    final long versionCode;
    final boolean debuggable;
    private final Bundle metaData;

    private AppInfo(String packageName, String versionName, long versionCode, boolean debuggable, Bundle metaData) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.debuggable = debuggable;
        this.metaData = metaData;
    }

    @SuppressWarnings("deprecation")
    static AppInfo of(Context context) {
        String packageName = context.getPackageName();
        String versionName = null;
        long versionCode = 0;
        Bundle metaData = null;
        ApplicationInfo application = context.getApplicationInfo();
        boolean debuggable = application != null && (application.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            versionName = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            // Not installed as a package (a test): no version.
        }
        try {
            metaData = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
        } catch (Exception ignored) {
            // No manifest settings.
        }
        return new AppInfo(packageName, versionName, versionCode, debuggable, metaData);
    }

    /** A {@code <meta-data android:name="io.zharp.NAME">} value from the manifest, or null. */
    String manifest(String name) {
        if (metaData == null) return null;
        Object value = metaData.get("io.zharp." + name);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** The device and app fields every log's {@code context} carries. */
    Map<String, Object> context() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("platform", "android");
        context.put("osVersion", Build.VERSION.RELEASE);
        context.put("sdkInt", Build.VERSION.SDK_INT);
        context.put("manufacturer", Build.MANUFACTURER);
        context.put("model", Build.MODEL);
        context.put("packageName", packageName);
        if (versionCode != 0) context.put("versionCode", versionCode);
        context.put("debuggable", debuggable);
        return context;
    }
}
