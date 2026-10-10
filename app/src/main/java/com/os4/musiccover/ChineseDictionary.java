package com.os4.musiccover;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Optional pinyin table.  Only the Unicode-to-Hanyu reading data is retained on-device. */
final class ChineseDictionary {
    private ChineseDictionary() { }

    private static final String URL = "https://repo1.maven.org/maven2/com/belerweb/pinyin4j/2.5.1/pinyin4j-2.5.1.jar";
    private static final String SHA256 = "b8f9cf0578f6ad01acee51f48cb1e1027d3a016ad946ae298ab6e9331ac49273";
    private static final String ENTRY = "pinyindb/unicode_to_hanyu_pinyin.txt";
    private static volatile boolean downloading;
    private static volatile int phase;
    private static volatile int progress = -1;
    private static volatile String error = "";
    private static volatile HashMap<Integer, String> readings;

    static final int NOT_DOWNLOADED = 0, DOWNLOADING = 1, READY = 2, ERROR = 3;
    static final class Status { final int phase, progress; final String error;
        Status(int phase, int progress, String error) { this.phase = phase; this.progress = progress; this.error = error == null ? "" : error; }
    }
    static Status status(Context c) { return ready(c) ? new Status(READY, 100, "") : new Status(phase, progress, error); }
    static boolean ready(Context c) { return c != null && file(c).isFile(); }

    static void ensure(final Context c) {
        if (c == null || ready(c) || downloading) return;
        synchronized (ChineseDictionary.class) {
            if (ready(c) || downloading) return;
            downloading = true; phase = DOWNLOADING; progress = 0; error = "";
        }
        new Thread(new Runnable() { @Override public void run() {
            boolean installed = false;
            try { installed = download(c); } catch (Throwable t) { error = friendlyError(t); phase = ERROR; Xp.w("Chinese dictionary download failed: " + t); }
            finally { downloading = false; }
            if (installed) { phase = READY; progress = 100; readings = null; Main.main().post(new Runnable() { @Override public void run() { LockLyrics.localRomanizationReady(); } }); }
        }}, "mc-pinyin-download").start();
    }

    static void remove(Context c) { if (c == null || downloading) return; readings = null; file(c).delete(); phase = NOT_DOWNLOADED; progress = -1; error = ""; }

    static String reading(Context c, int codePoint) {
        if (!ready(c)) return null;
        HashMap<Integer, String> map = readings;
        if (map == null) synchronized (ChineseDictionary.class) {
            if ((map = readings) == null) readings = map = load(c);
        }
        return map.get(codePoint);
    }

    private static HashMap<Integer, String> load(Context c) {
        HashMap<Integer, String> out = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file(c)), "UTF-8"))) {
            for (String line; (line = in.readLine()) != null;) {
                int space = line.indexOf(' '), open = line.indexOf('('), comma = line.indexOf(',', open), close = line.indexOf(')', open);
                if (space < 1 || open < 0 || close < 0) continue;
                String first = line.substring(open + 1, comma < 0 ? close : comma).replaceAll("[0-9]", "");
                if (!first.isEmpty()) out.put(Integer.parseInt(line.substring(0, space), 16), first);
            }
        } catch (Throwable t) { Xp.w("Could not read Chinese dictionary: " + t); }
        return out;
    }

    private static boolean download(Context c) throws Exception {
        File parent = new File(c.getFilesDir(), "mc-chinese"); if (!parent.exists() && !parent.mkdirs()) return false;
        File jar = new File(parent, "pinyin4j.jar.part"), staged = new File(parent, "pinyin.txt.part");
        HttpURLConnection con = (HttpURLConnection) new URL(URL).openConnection(); con.setConnectTimeout(15000); con.setReadTimeout(30000);
        if (con.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Server returned HTTP " + con.getResponseCode());
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = con.getContentLengthLong(), got = 0;
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(jar)) { byte[] b = new byte[32768]; for (int n; (n = in.read(b)) >= 0;) { digest.update(b, 0, n); out.write(b, 0, n); got += n; if (total > 0) progress = Math.min(99, (int) (got * 100 / total)); } } finally { con.disconnect(); }
        if (!SHA256.equals(hex(digest.digest()))) { jar.delete(); throw new IOException("Downloaded file did not pass verification"); }
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(jar))) { for (ZipEntry e; (e = zip.getNextEntry()) != null;) if (ENTRY.equals(e.getName())) { try (FileOutputStream out = new FileOutputStream(staged)) { byte[] b = new byte[32768]; for (int n; (n = zip.read(b)) >= 0;) out.write(b, 0, n); } found = true; break; } } finally { jar.delete(); }
        if (!found || staged.length() == 0) { staged.delete(); throw new IOException("Downloaded package is incomplete"); }
        File installed = file(c); installed.delete(); if (!staged.renameTo(installed)) { staged.delete(); throw new IOException("Could not install dictionary"); } return true;
    }
    private static File file(Context c) { return new File(new File(c.getFilesDir(), "mc-chinese"), "pinyin4j-2.5.1.txt"); }
    private static String hex(byte[] b) { StringBuilder out = new StringBuilder(b.length * 2); for (byte x : b) out.append(String.format(java.util.Locale.ROOT, "%02x", x & 255)); return out.toString(); }
    private static String friendlyError(Throwable t) { if (t instanceof java.net.SocketTimeoutException) return "Connection timed out"; if (t instanceof java.net.UnknownHostException) return "Could not reach download server"; String m = t == null ? "" : t.getMessage(); return m != null && m.startsWith("Server returned HTTP") ? m : "Download failed"; }
}
