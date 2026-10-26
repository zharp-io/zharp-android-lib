package io.zharp.logs;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Sends batches of log entries to {@code {endpoint}/agent/logs} as {@code {"logs": [...]}}. */
class Transport {
    static final String DEFAULT_ENDPOINT = "https://api.zharp.io/api/v1";

    /**
     * Largest body one request carries. The backend accepts 10 MB; staying far below it keeps one
     * slow upload from holding everything behind it. An entry larger than this on its own is
     * still sent alone rather than dropped.
     */
    static final int MAX_REQUEST_BYTES = 1_000_000;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int TIMEOUT_MS = 15_000;

    private final String apiKey;
    private final String endpoint;
    private final String sdkName;
    /** The first retry waits twice this, the second four times. */
    private final long retryDelayMillis;

    Transport(String apiKey, String endpoint, String sdkName, long retryDelayMillis) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.sdkName = sdkName;
        this.retryDelayMillis = retryDelayMillis;
    }

    /** Trim a configured endpoint so paths can be appended to it. */
    static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /**
     * Send entries, split into bodies by size. Returns whether every body was taken or refused
     * for good (a 4xx) — false when the network or the server failed throughout.
     */
    boolean send(List<JSONObject> entries) {
        boolean delivered = true;
        for (String body : splitBySize(entries, MAX_REQUEST_BYTES)) {
            if (!sendBody(body)) delivered = false;
        }
        return delivered;
    }

    /**
     * Three attempts in all. Only network errors and 5xx responses are retried — a 4xx won't be
     * fixed by sending the same thing again.
     */
    private boolean sendBody(String body) {
        byte[] bytes = body.getBytes(UTF8);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (post(bytes) < 500) return true;
            } catch (IOException | RuntimeException ignored) {
                // Network failure or timeout — fall through to retry.
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(retryDelayMillis * (2L << attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** One POST; returns the status code. */
    int post(byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/agent/logs").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            // The key goes in a header, never the URL: URLs end up in proxy logs.
            connection.setRequestProperty("X-API-KEY", apiKey);
            connection.setRequestProperty("X-Zharp-SDK", sdkName);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int status = connection.getResponseCode();
            drain(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return status;
        } finally {
            connection.disconnect();
        }
    }

    /** Read the response out so the connection can be reused. */
    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) {
                // discard
            }
        } catch (IOException ignored) {
            // Nothing to do: the status is what mattered.
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Already done with it.
            }
        }
    }

    /**
     * {@code {"logs": [...]}} bodies of at most {@code limit} bytes each. An entry too large to
     * fit even alone still gets a body of its own: whether it can be delivered is for the network
     * to decide.
     */
    static List<String> splitBySize(List<JSONObject> entries, int limit) {
        int overhead = "{\"logs\":[]}".length();
        List<String> bodies = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        int size = overhead;
        for (JSONObject entry : entries) {
            String json = entry.toString();
            int entrySize = json.getBytes(UTF8).length + 1; // +1 for the separating comma
            if (group.length() > 0 && size + entrySize > limit) {
                bodies.add("{\"logs\":[" + group + "]}");
                group.setLength(0);
                size = overhead;
            }
            if (group.length() > 0) group.append(',');
            group.append(json);
            size += entrySize;
        }
        if (group.length() > 0) bodies.add("{\"logs\":[" + group + "]}");
        return bodies;
    }
}
