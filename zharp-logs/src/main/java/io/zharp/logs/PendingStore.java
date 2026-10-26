package io.zharp.logs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs saved when the app crashes, sent on the next launch. A crashing process often ends before
 * a request can finish; the crash is the one log that must not be lost, so it goes to disk first.
 */
final class PendingStore {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** A file larger than this is not read back: something went wrong writing it. */
    private static final long MAX_FILE_BYTES = 5_000_000;

    private final File file;

    PendingStore(File directory) {
        this.file = new File(directory, "zharp-pending-logs.json");
    }

    /** Save entries, replacing what was saved before. Written whole, then moved into place. */
    void save(List<JSONObject> entries) {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) array.put(entry);
        File temp = new File(file.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(array.toString().getBytes(UTF8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (IOException ignored) {
            temp.delete();
        }
    }

    /** What an earlier run saved, removed from disk as it's read. */
    List<JSONObject> take() {
        List<JSONObject> entries = new ArrayList<>();
        if (!file.exists()) return entries;
        try {
            if (file.length() <= MAX_FILE_BYTES) {
                JSONArray array = new JSONArray(new String(read(file), UTF8));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.optJSONObject(i);
                    if (entry != null) entries.add(entry);
                }
            }
        } catch (Exception unreadable) {
            // A file cut short by the crash: what can't be read is dropped.
        } finally {
            clear();
        }
        return entries;
    }

    void clear() {
        file.delete();
    }

    private static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
