package io.zharp.android.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;
import timber.log.Timber;

/**
 * Timber's logs, as records in the trace they were written in. Planted when the app has Timber
 * ({@link TimberSupport}); in Java so that {@code Timber.plant} is the static method Timber 4 and
 * Timber 5 both have (Kotlin would call Timber 5's companion, which Timber 4 lacks).
 */
final class ZharpTimberTree extends Timber.Tree {
    static Object plantNew() {
        ZharpTimberTree tree = new ZharpTimberTree();
        Timber.plant(tree);
        return tree;
    }

    static void uproot(Object tree) {
        Timber.uproot((Timber.Tree) tree);
    }

    @Override
    protected boolean isLoggable(String tag, int priority) {
        return LogCapture.INSTANCE.accepts(LogCapture.INSTANCE.priority(priority)) && !LogCapture.INSTANCE.isSdkThread();
    }

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        try {
            Severity severity = LogCapture.INSTANCE.priority(priority);
            String body = message;
            if (t != null) {
                // Timber appends the stack to the message; it is sent as the error's own attributes.
                int stack = body.indexOf("\n" + t);
                if (stack > 0) body = body.substring(0, stack);
                else if (body.startsWith(t.toString())) body = t.toString();
            }
            AttributesBuilder attributes = Attributes.builder().put(LogCapture.SOURCE, "timber");
            if (tag != null && !tag.isEmpty()) attributes.put(LogCapture.LOGGER, tag);
            LogCapture.INSTANCE.emit(severity, body, attributes.build(), t, null, null, true);
        } catch (Throwable ignored) {
            // A line that could not be copied is not worth an exception in the caller.
        }
    }
}
