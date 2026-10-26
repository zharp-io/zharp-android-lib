package io.zharp.logs;

/** How bad a log is. */
public enum LogLevel {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    FATAL("fatal");

    private final String wireName;

    LogLevel(String wireName) {
        this.wireName = wireName;
    }

    /** The name Zharp knows this level by: {@code "info"}, {@code "error"}, … */
    public String wireName() {
        return wireName;
    }

    /** Logcat's priority for this level, for {@link ZharpConfig.Builder#logcat(boolean)}. */
    int logcatPriority() {
        switch (this) {
            case DEBUG: return android.util.Log.DEBUG;
            case INFO: return android.util.Log.INFO;
            case WARN: return android.util.Log.WARN;
            default: return android.util.Log.ERROR;
        }
    }
}
