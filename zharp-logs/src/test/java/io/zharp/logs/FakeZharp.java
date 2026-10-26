package io.zharp.logs;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/** Just enough HTTP to stand in for Zharp's ingest endpoint in tests. */
final class FakeZharp {
    interface Handler {
        /** Returns the status to answer with. */
        int handle(Map<String, String> headers, String body) throws Exception;
    }

    private final ServerSocket socket;
    private final Thread thread;

    FakeZharp(final Handler handler) throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    serve(client, handler);
                } catch (Exception ignored) {
                    // closed, or a client that went away
                }
            }
        }, "fake-zharp");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    void stop() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static void serve(Socket client, Handler handler) throws Exception {
        InputStream in = new BufferedInputStream(client.getInputStream());
        readLine(in); // request line
        Map<String, String> headers = new HashMap<>();
        int length = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.put(canonical(name), value);
            if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        int status = handler.handle(headers, new String(body, 0, read, "UTF-8"));
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.flush();
    }

    /** Header names as the tests ask for them, whatever case the client sent. */
    private static String canonical(String name) {
        if (name.equalsIgnoreCase("X-API-KEY")) return "X-API-KEY";
        if (name.equalsIgnoreCase("X-Zharp-SDK")) return "X-Zharp-SDK";
        return name;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') line.write(c);
        }
        return line.toString("UTF-8");
    }
}
