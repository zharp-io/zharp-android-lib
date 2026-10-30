package io.zharp.android;

import org.junit.Test;

import java.util.Collections;

import io.opentelemetry.api.trace.Span;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** The public API as a Java app calls it: this compiling is most of the test. */
public class JavaApiTest {
    @Test
    public void readsNaturallyFromJava() {
        ZharpOptions options = new ZharpOptions();
        options.setKey("zpk_test");
        options.setService("shop-android");
        options.setEnvironment("production");
        options.setTracingOrigins(Collections.singletonList("api.example.com"));
        assertEquals("shop-android", options.getService());
        assertEquals(true, options.getTracing());
        options.setTracing(false); // logs only

        // Not started: every call is a quiet no-op.
        assertFalse(Zharp.isActive());
        Zharp.setUser("user-1");
        Zharp.setUser("user-1", "ada@example.com");
        Zharp.setTag("plan", "pro");
        Zharp.log.info("Checkout opened");
        Zharp.log.warn("Slow", Collections.singletonMap("ms", 3200));
        Zharp.log.error("Payment failed", new IllegalStateException("declined"));
        Zharp.captureError(new RuntimeException("x"));
        int result = Zharp.startSpan("work", (Span span) -> 7);
        assertEquals(7, result);
        Zharp.trackScreen("Home");
        Zharp.flush();
        Zharp.clearUser();
        Zharp.setTag("plan", null);
        new ZharpOkHttpInterceptor();
    }
}
