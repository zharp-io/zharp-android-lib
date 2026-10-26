package io.zharp.logs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ZharpLoggerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A stand-in for Zharp: records each request; answers with {@link #status}. */
    private FakeZharp server;
    private final List<JSONObject> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(201);
    private String endpoint;

    @Before
    public void start() throws Exception {
        server = new FakeZharp((requestHeaders, body) -> {
            headers.add(requestHeaders);
            bodies.add(new JSONObject(body));
            return status.get();
        });
        endpoint = "http://127.0.0.1:" + server.port() + "/api/v1/";
        ZharpLogger.resetForTests();
    }

    @After
    public void stop() {
        ZharpLogger.close();
        server.stop();
    }

    private ZharpLogger.Setup setup(long flushMillis) throws Exception {
        Transport transport = new Transport("test-key", Transport.normalizeEndpoint(endpoint), "android-logs/test", 10);
        JSONObject device = new JSONObject();
        Json.put(device, "platform", "android");
        Json.put(device, "model", "Pixel 9");
        return new ZharpLogger.Setup("shop-app", "production", "2.4.0", null, false, device,
                new Pipeline(transport, flushMillis, 50));
    }

    private List<JSONObject> sentLogs() throws Exception {
        List<JSONObject> logs = new ArrayList<>();
        for (JSONObject body : bodies) {
            JSONArray array = body.getJSONArray("logs");
            for (int i = 0; i < array.length(); i++) logs.add(array.getJSONObject(i));
        }
        return logs;
    }

    private void waitFor(int count) throws Exception {
        long until = System.currentTimeMillis() + 5_000;
        while (sentLogs().size() < count && System.currentTimeMillis() < until) Thread.sleep(20);
    }

    @Test
    public void sends_the_shape_every_zharp_sdk_sends() throws Exception {
        ZharpLogger.startForTests(setup(50));
        ZharpLogger.setContext(Collections.singletonMap("userId", 42));
        ZharpLogger.info("User signed in", Collections.singletonMap("plan", "pro"));
        waitFor(1);

        JSONObject log = sentLogs().get(0);
        assertEquals("shop-app", log.getString("service"));
        assertEquals("info", log.getString("level"));
        assertEquals("User signed in", log.getString("message"));
        assertTrue(log.getString("timestamp").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertEquals("production", log.getString("environment"));
        assertEquals("2.4.0", log.getString("version"));
        assertFalse(log.has("source"));

        JSONObject meta = log.getJSONObject("metadata");
        assertEquals("pro", meta.getString("plan"));
        assertEquals(ZharpLogger.getSessionId(), meta.getString("sessionId"));
        JSONObject context = meta.getJSONObject("context");
        assertEquals("android", context.getString("platform"));
        assertEquals("Pixel 9", context.getString("model"));
        assertEquals(42, context.getInt("userId"));
        assertEquals("2.4.0", context.getString("appVersion"));

        assertEquals("test-key", headers.get(0).get("X-API-KEY"));
        assertEquals("android-logs/test", headers.get(0).get("X-Zharp-SDK"));
    }

    @Test
    public void a_caught_exception_brings_its_type_and_stack_without_being_asked() throws Exception {
        ZharpLogger.startForTests(setup(60_000));
        try {
            throw new IllegalStateException("card declined");
        } catch (IllegalStateException e) {
            ZharpLogger.error(e);
            ZharpLogger.error("Payment failed", e, Collections.singletonMap("orderId", 99));
            ZharpLogger.warn("Retrying", Collections.singletonMap("err", e));
        }
        // error goes at once, without waiting for the minute-long timer; warn waits for the flush.
        waitFor(2);
        ZharpLogger.flush();
        waitFor(3);

        List<JSONObject> logs = sentLogs();
        JSONObject plain = logs.get(0);
        assertEquals("IllegalStateException: card declined", plain.getString("message"));
        assertEquals("java.lang.IllegalStateException", plain.getJSONObject("metadata").getString("name"));
        assertTrue(plain.getJSONObject("metadata").getString("stack").contains("a_caught_exception_brings"));

        JSONObject withMessage = logs.get(1);
        assertEquals("Payment failed", withMessage.getString("message"));
        assertEquals(99, withMessage.getJSONObject("metadata").getInt("orderId"));
        assertTrue(withMessage.getJSONObject("metadata").has("stack"));

        JSONObject inMetadata = logs.get(2);
        assertEquals("warn", inMetadata.getString("level"));
        assertTrue(inMetadata.getJSONObject("metadata").getString("stack").contains("card declined"));
        assertEquals("card declined", inMetadata.getJSONObject("metadata").getJSONObject("err").getString("message").replace("IllegalStateException: ", ""));
    }

    @Test
    public void a_stack_the_caller_gave_wins() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stack", "my own stack");
        JSONObject entry = ZharpLogger.entry(setup(1000), LogLevel.ERROR, "boom", meta, new RuntimeException("x"), null, 0);
        assertEquals("my own stack", entry.getJSONObject("metadata").getString("stack"));
        assertEquals("1970-01-01T00:00:00.000Z", entry.getString("timestamp"));
    }

    @Test
    public void one_log_can_go_under_another_service() throws Exception {
        JSONObject entry = ZharpLogger.entry(setup(1000), LogLevel.INFO, "x", null, null, " payments ", 0);
        assertEquals("payments", entry.getString("service"));
    }

    @Test
    public void every_message_becomes_text() throws Exception {
        assertEquals("(empty message)", Json.toMessage(null));
        assertEquals("(empty message)", Json.toMessage("   "));
        assertEquals("42", Json.toMessage(42));
        assertEquals("{\"a\":1}", Json.toMessage(Collections.singletonMap("a", 1)));
        assertEquals("[1,2]", Json.toMessage(Arrays.asList(1, 2)));
        assertEquals("NullPointerException", Json.toMessage(new NullPointerException()));
    }

    @Test
    public void metadata_json_cannot_hold_becomes_text() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("nan", Double.NaN);
        meta.put("level", LogLevel.WARN);
        meta.put("nested", Collections.singletonMap("ids", new int[]{1, 2}));
        meta.put("missing", null);
        JSONObject json = Json.toJsonMap(meta);
        assertEquals("NaN", json.getString("nan"));
        assertEquals("WARN", json.getString("level"));
        assertEquals(2, json.getJSONObject("nested").getJSONArray("ids").getInt(1));
        assertTrue(json.isNull("missing"));
    }

    @Test
    public void a_map_that_contains_itself_does_not_hang() throws Exception {
        Map<String, Object> loop = new HashMap<>();
        loop.put("self", loop);
        assertNotNull(Json.toJsonMap(loop));
    }

    @Test
    public void bodies_are_cut_by_size_and_a_large_entry_goes_alone() throws Exception {
        List<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JSONObject e = new JSONObject();
            Json.put(e, "m", new String(new char[40]).replace('\0', 'x'));
            entries.add(e);
        }
        JSONObject big = new JSONObject();
        Json.put(big, "m", new String(new char[500]).replace('\0', 'y'));
        entries.add(big);

        List<String> bodies = Transport.splitBySize(entries, 120);
        for (String body : bodies) assertTrue(body.startsWith("{\"logs\":["));
        assertEquals(new JSONObject(bodies.get(bodies.size() - 1)).getJSONArray("logs").length(), 1);
        int total = 0;
        for (String body : bodies) total += new JSONObject(body).getJSONArray("logs").length();
        assertEquals(4, total);
    }

    @Test
    public void server_errors_are_retried_and_refusals_are_not() throws Exception {
        Transport transport = new Transport("k", Transport.normalizeEndpoint(endpoint), "t", 1);
        List<JSONObject> one = Collections.singletonList(new JSONObject());

        status.set(503);
        assertFalse(transport.send(one));
        assertEquals(3, bodies.size());

        bodies.clear();
        status.set(429);
        assertTrue(transport.send(one));
        assertEquals(1, bodies.size());
    }

    @Test
    public void nothing_is_sent_before_init() throws Exception {
        ZharpLogger.info("dropped");
        ZharpLogger.error("dropped too");
        Thread.sleep(100);
        assertTrue(bodies.isEmpty());
        assertFalse(ZharpLogger.isActive());
    }

    @Test
    public void the_queue_keeps_the_newest_500() throws Exception {
        Pipeline pipeline = new Pipeline(new Transport("k", Transport.normalizeEndpoint(endpoint), "t", 1), 60_000, 1000);
        for (int i = 0; i < 600; i++) {
            JSONObject e = new JSONObject();
            Json.put(e, "i", i);
            pipeline.push(e);
        }
        List<JSONObject> left = pipeline.drain();
        assertEquals(Pipeline.MAX_QUEUED, left.size());
        assertEquals(100, left.get(0).getInt("i"));
        pipeline.close();
    }

    @Test
    public void a_saved_crash_is_read_back_once() throws Exception {
        PendingStore store = new PendingStore(folder.getRoot());
        JSONObject crash = new JSONObject();
        Json.put(crash, "level", "fatal");
        store.save(Collections.singletonList(crash));

        List<JSONObject> back = store.take();
        assertEquals(1, back.size());
        assertEquals("fatal", back.get(0).getString("level"));
        assertTrue(store.take().isEmpty());
    }

    @Test
    public void saved_logs_go_out_before_new_ones() throws Exception {
        ZharpLogger.Setup s = setup(60_000);
        ZharpLogger.startForTests(s);
        JSONObject saved = new JSONObject();
        Json.put(saved, "message", "from last run");
        s.pipeline.pushFirst(Collections.singletonList(saved));
        ZharpLogger.info("now");
        s.pipeline.flushAll().get(5, TimeUnit.SECONDS);
        assertEquals("from last run", sentLogs().get(0).getString("message"));
        assertEquals("now", sentLogs().get(1).getString("message"));
    }

    @Test
    public void endpoints_lose_trailing_slashes_and_default_to_zharp() throws Exception {
        assertEquals("https://api.zharp.io/api/v1", Transport.normalizeEndpoint(null));
        assertEquals("https://example.com/api/v1", Transport.normalizeEndpoint(" https://example.com/api/v1// "));
    }
}
