package com.os4.musiccover;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Small versioned disk cache for successful lyrics and translations. */
final class LyricDiskCache {
    private static final String VERSION = "v1";
    private static final long MAX_AGE_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final long MAX_BYTES = 4L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private LyricDiskCache() {}

    static JSONObject read(String namespace, String key) {
        if (!LockLyrics.sOfflineCache) return null;
        synchronized (LOCK) {
            File file = file(namespace, key);
            if (file == null || !file.isFile()) return null;
            if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
                file.delete();
                return null;
            }
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) Math.min(file.length(), MAX_BYTES)];
                int count = 0;
                while (count < bytes.length) {
                    int read = in.read(bytes, count, bytes.length - count);
                    if (read < 0) break;
                    count += read;
                }
                if (count <= 0) return null;
                file.setLastModified(System.currentTimeMillis());
                return new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                file.delete();
                return null;
            }
        }
    }

    static void write(String namespace, String key, JSONObject value) {
        if (!LockLyrics.sOfflineCache || value == null) return;
        synchronized (LOCK) {
            File file = file(namespace, key);
            if (file == null) return;
            File parent = file.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) return;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                file.delete();
            }
            trim(parent);
        }
    }

    /** Removes only this module's persisted lyric/provider/translation results. */
    static void clear() {
        synchronized (LOCK) {
            Context context;
            try {
                context = Main.appContext();
            } catch (Throwable unavailable) {
                return;
            }
            if (context == null) return;
            deleteTree(new File(context.getCacheDir(), "lyrics"));
        }
    }

    private static File file(String namespace, String key) {
        Context context;
        try {
            context = Main.appContext();
        } catch (Throwable unavailable) {
            // Cache access is optional when the host context has not been initialized.
            return null;
        }
        if (context == null || key == null || key.isEmpty()) return null;
        File dir = new File(new File(context.getCacheDir(), "lyrics"), VERSION + "_" + namespace);
        return new File(dir, digest(key) + ".json");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        while (total > MAX_BYTES) {
            File oldest = null;
            for (File file : files) {
                if (oldest == null || file.lastModified() < oldest.lastModified()) oldest = file;
            }
            if (oldest == null) return;
            total -= oldest.length();
            oldest.delete();
            files = dir.listFiles();
            if (files == null) return;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
