package com.os4.musiccover;

import android.content.Context;

import com.atilika.kuromoji.unidic.Tokenizer;
import com.atilika.kuromoji.util.ResourceResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Optional UniDic data pack. Kuromoji classes remain in the APK; its large binary tables do not. */
final class JapaneseDictionary {
    private JapaneseDictionary() { }

    private static final String VERSION = "unidic-0.9.0";
    private static final String URL = "https://repo1.maven.org/maven2/com/atilika/kuromoji/"
            + "kuromoji-unidic/0.9.0/kuromoji-unidic-0.9.0.jar";
    private static final String SHA256 = "f4ed14657ac41752e658cef40f98cfd1ac46772df7d037d4ed698f066930871b";
    private static final String PREFIX = "com/atilika/kuromoji/unidic/";
    private static final String[] TABLES = {"characterDefinitions.bin", "connectionCosts.bin",
            "doubleArrayTrie.bin", "tokenInfoDictionary.bin", "tokenInfoFeaturesMap.bin",
            "tokenInfoPartOfSpeechMap.bin", "tokenInfoTargetMap.bin", "unknownDictionary.bin"};

    private static volatile boolean downloading;
    private static volatile Tokenizer tokenizer;
    private static volatile int phase;
    private static volatile int progress = -1;
    private static volatile String error = "";

    static final int NOT_DOWNLOADED = 0;
    static final int DOWNLOADING = 1;
    static final int READY = 2;
    static final int ERROR = 3;

    static final class Status {
        final int phase;
        final int progress;
        final String error;

        Status(int phase, int progress, String error) {
            this.phase = phase;
            this.progress = progress;
            this.error = error == null ? "" : error;
        }
    }

    static Status status(Context context) {
        if (ready(context)) return new Status(READY, 100, "");
        return new Status(phase, progress, error);
    }

    static boolean ready(Context context) {
        if (context == null) return false;
        File dir = directory(context);
        for (String name : TABLES) if (!new File(dir, name).isFile()) return false;
        return true;
    }

    static void ensure(final Context context) {
        if (context == null || ready(context) || downloading) return;
        synchronized (JapaneseDictionary.class) {
            if (downloading || ready(context)) return;
            downloading = true;
            phase = DOWNLOADING;
            progress = 0;
            error = "";
        }
        new Thread(new Runnable() {
            @Override public void run() {
                boolean installed = false;
                try { installed = download(context); }
                catch (Throwable t) {
                    error = friendlyError(t);
                    phase = ERROR;
                    Xp.w("Japanese dictionary download failed: " + t);
                } finally { downloading = false; }
                if (installed) {
                    phase = READY;
                    progress = 100;
                    Main.main().post(new Runnable() {
                        @Override public void run() { LockLyrics.localRomanizationReady(); }
                    });
                }
            }
        }, "mc-unidic-download").start();
    }

    /** Removes only this optional pack from SystemUI's private storage. */
    static void remove(Context context) {
        if (context == null || downloading) return;
        synchronized (JapaneseDictionary.class) {
            if (downloading) return;
            tokenizer = null;
            delete(directory(context));
            phase = NOT_DOWNLOADED;
            progress = -1;
            error = "";
        }
        JapaneseRomanizer.clearTokenizer();
    }

    static Tokenizer tokenizer(Context context) throws Exception {
        if (!ready(context)) return null;
        Tokenizer local = tokenizer;
        if (local != null) return local;
        synchronized (JapaneseDictionary.class) {
            if (tokenizer == null) tokenizer = new DiskBuilder(directory(context)).build();
            return tokenizer;
        }
    }

    private static boolean download(Context context) throws Exception {
        File parent = new File(context.getFilesDir(), "mc-japanese");
        if (!parent.exists() && !parent.mkdirs()) return false;
        File jar = new File(parent, VERSION + ".jar.part");
        File staging = new File(parent, VERSION + ".staging");
        delete(staging);
        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Server returned HTTP " + connection.getResponseCode());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = connection.getContentLengthLong();
        if (total <= 0L) progress = -1;
        long received = 0L;
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(jar)) {
            byte[] buffer = new byte[32768];
            for (int n; (n = in.read(buffer)) >= 0;) {
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
                received += n;
                if (total > 0L) progress = Math.min(99, (int) (received * 100L / total));
            }
        } finally { connection.disconnect(); }
        if (!SHA256.equals(hex(digest.digest()))) {
            jar.delete();
            throw new IOException("Downloaded file did not pass verification");
        }
        if (!staging.mkdirs()) {
            jar.delete();
            throw new IOException("Could not create dictionary storage");
        }
        int extracted = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                String name = e.getName();
                if (!name.startsWith(PREFIX) || !name.endsWith(".bin") || name.indexOf('/', PREFIX.length()) >= 0) continue;
                File out = new File(staging, name.substring(PREFIX.length()));
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[32768];
                    for (int n; (n = zip.read(buffer)) >= 0;) stream.write(buffer, 0, n);
                }
                extracted++;
            }
        } finally { jar.delete(); }
        if (extracted != TABLES.length || !readyDirectory(staging)) {
            delete(staging);
            throw new IOException("Downloaded package is incomplete");
        }
        File installed = directory(context);
        delete(installed);
        if (!staging.renameTo(installed)) {
            delete(staging);
            throw new IOException("Could not install dictionary");
        }
        return true;
    }

    private static File directory(Context context) {
        return new File(new File(context.getFilesDir(), "mc-japanese"), VERSION);
    }

    private static boolean readyDirectory(File dir) {
        for (String name : TABLES) if (!new File(dir, name).isFile()) return false;
        return true;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String friendlyError(Throwable failure) {
        if (failure instanceof java.net.SocketTimeoutException) return "Connection timed out";
        if (failure instanceof java.net.UnknownHostException) return "Could not reach download server";
        String message = failure == null ? "" : failure.getMessage();
        if (message != null && message.startsWith("Server returned HTTP")) return message;
        return "Download failed";
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }

    private static final class DiskBuilder extends Tokenizer.Builder {
        DiskBuilder(final File directory) {
            resolver = new ResourceResolver() {
                @Override public InputStream resolve(String resource) throws java.io.IOException {
                    int slash = resource.lastIndexOf('/');
                    return new FileInputStream(new File(directory,
                            slash < 0 ? resource : resource.substring(slash + 1)));
                }
            };
        }
    }
}
