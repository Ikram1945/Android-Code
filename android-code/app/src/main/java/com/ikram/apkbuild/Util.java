package com.ikram.apkbuild;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Util IO + hash + log callback. */
public final class Util {
    public interface Log { void log(String msg); void problem(String msg); }

    private Util() {}

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    public static void copyFile(File from, File to) throws IOException {
        to.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        }
    }

    public static String readText(File f) {
        try {
            byte[] b = readBytes(f);
            return new String(b, "UTF-8");
        } catch (Exception e) { return null; }
    }

    public static void writeText(File f, String s) {
        try {
            f.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(f)) { os.write(s.getBytes("UTF-8")); }
        } catch (IOException ignored) {}
    }

    public static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(in, bos);
            return bos.toByteArray();
        }
    }

    /** Buffer hash. 64 KB -> 256 KB: I/O /sdcard (FUSE) masih jadi bagian terbesar biaya hash
     *  fingerprint (11 MB classes/ = ~3.2s di device, ~1.0s di bench lokal), jadi baca lebih besar
     *  per syscall. Semantik hash TIDAK berubah (diuji: hash identik dgn buffer 64 KB). */
    private static final int HASH_BUF = 256 * 1024;
    /** Tabel hex, ganti String.format("%02x") per byte - format() mahal bila dipanggil jutaan kali. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String toHex(byte[] d) {
        char[] c = new char[d.length * 2];
        for (int i = 0, j = 0; i < d.length; i++) {
            int b = d[i] & 0xFF;
            c[j++] = HEX[b >>> 4];
            c[j++] = HEX[b & 0xF];
        }
        return new String(c);
    }

    public static String md5(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[HASH_BUF];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (Exception e) { return null; }
    }

    public static String hashString(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            return toHex(d).substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    /** Cek file adalah zip/jar yang UTUH (bisa dibaca daftar entri). File terpotong/0 B -> false. */
    public static boolean isValidZip(File f) {
        if (f == null || !f.isFile() || f.length() == 0) return false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            return zf.size() >= 0;   // membuka END header; gagal -> exception
        } catch (Exception e) { return false; }
    }

    public static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        f.delete();
    }

    /** Gabungkan path untuk dir output (safe). */
    public static File join(File base, String... parts) {
        File f = base;
        for (String p : parts) f = new File(f, p);
        return f;
    }
}
