package com.ikram.apkbuild.toolchain;

import com.ikram.apkbuild.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Menjalankan tool JVM (ECJ/D8/apksig) via FORK dalvikvm. */
public class JvmRunner {
    private static final String[] LAUNCHERS = {
        "/apex/com.android.art/bin/dalvikvm64",
        "/apex/com.android.art/bin/dalvikvm32",
        "/apex/com.android.art/bin/dalvikvm",
        "/system/bin/dalvikvm",
    };
    private final Util.Log log;
    private final String launcher;

    public JvmRunner(Util.Log log) {
        this.log = log;
        this.launcher = findLauncher();
    }
    private static String findLauncher() {
        for (String l : LAUNCHERS) if (new File(l).exists()) return l;
        return null;
    }
    public boolean available() { return launcher != null; }

    /** D8 batch. */
    public boolean d8(File r8DexZip, int minApi, File androidJar, List<String> inputs, File outDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher); cmd.add("-Xmx1g");
        cmd.add("-cp"); cmd.add(r8DexZip.getAbsolutePath());
        cmd.add("com.android.tools.r8.D8");
        cmd.add("--release"); cmd.add("--min-api"); cmd.add(String.valueOf(minApi));
        if (androidJar != null) { cmd.add("--lib"); cmd.add(androidJar.getAbsolutePath()); }
        for (String in : inputs) cmd.add(in);
        cmd.add("--output"); cmd.add(outDir.getAbsolutePath());
        return run(cmd, "d8");
    }

    /** D8 dgn desugaring classpath lengkap (android.jar + library + core-stubs). */
    public boolean d8WithClasspath(File r8DexZip, int minApi, List<String> classpath, List<String> inputs, File outDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher); cmd.add("-Xmx1g");
        cmd.add("-cp"); cmd.add(r8DexZip.getAbsolutePath());
        cmd.add("com.android.tools.r8.D8");
        cmd.add("--release"); cmd.add("--min-api"); cmd.add(String.valueOf(minApi));
        cmd.add("--lib"); cmd.add(classpath.get(0)); // android.jar pertama
        for (int i = 1; i < classpath.size(); i++) { cmd.add("--classpath"); cmd.add(classpath.get(i)); }
        for (String in : inputs) cmd.add(in);
        cmd.add("--output"); cmd.add(outDir.getAbsolutePath());
        return run(cmd, "d8");
    }

    /**
     * D8 MERGE bucket via FORK dalvikvm (proses terpisah -> aman utk merge paralel antar bucket).
     * Main D8Entry mode merge (r8DexZip). Argumen kecil lewat argv; argumen BESAR (banyak per-class
     * dex = proyek besar) lewat @argfile supaya tak kena "Argument list too long" (ART modern dukung
     * @file, dibuktikan CodeAssist ForkedD8Dexer). Bila fork gagal, caller fallback ke sequential.
     */
    public boolean d8EntryMerge(File r8DexZip, int minApi, File androidJar, List<String> classpath,
                                List<String> dexFiles, List<String> globalsFiles, String outDir, String xmx) {
        List<String> d8Args = new ArrayList<>();
        d8Args.add("--release"); d8Args.add("--min-api"); d8Args.add(String.valueOf(minApi));
        if (androidJar != null && androidJar.exists()) { d8Args.add("--lib"); d8Args.add(androidJar.getAbsolutePath()); }
        for (String c : classpath) { d8Args.add("--classpath"); d8Args.add(c); }
        d8Args.addAll(dexFiles);
        if (globalsFiles != null && !globalsFiles.isEmpty()) { d8Args.add("--merge-globals"); d8Args.addAll(globalsFiles); }
        d8Args.add("--output"); d8Args.add(outDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(launcher); cmd.add(xmx == null ? "-Xmx1g" : xmx);
        cmd.add("-cp"); cmd.add(r8DexZip.getAbsolutePath());
        cmd.add("D8Entry");

        long argLen = 0;
        for (String a : d8Args) argLen += a.length() + 1;
        if (argLen < 12000) {
            // argv pendek -> langsung
            cmd.addAll(d8Args);
            return run(cmd, "d8-merge-fork");
        }
        // argv panjang -> @argfile (nama unik per outDir, dibersihkan setelah)
        File outF = new File(outDir);
        File argFile = new File(outF.getParentFile(), "m" + outF.getName() + ".args");
        try {
            argFile.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(argFile);
            StringBuilder sb = new StringBuilder();
            for (String a : d8Args) {
                if (sb.length() > 0) sb.append(' ');
                boolean needQ = a.indexOf(' ') >= 0 || a.indexOf('"') >= 0;
                if (needQ) sb.append('"').append(a.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                else sb.append(a);
            }
            sb.append('\n');
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            log.problem("  [d8-merge-fork] tulis argfile gagal: " + e.getMessage());
            return false;
        }
        cmd.add("@" + argFile.getAbsolutePath());
        boolean ok = run(cmd, "d8-merge-fork");
        argFile.delete();
        return ok;
    }

    /**
     * D8 ARCHIVE bucket via FORK dalvikvm (per-class intermediate dex + globals output).
     * Dipakai sbg fallback saat in-process kehabisan heap: archiving butuh baca SELURUH
     * class nest + android.jar + library, puncak memori tertinggi dari semua tahap.
     * Argumen lewat @argfile bila panjang (banyak class per bucket).
     */
    public boolean d8EntryArchive(File r8DexZip, int minApi, File androidJar, List<String> classpath,
                                  List<String> inputs, String outDir, String xmx) {
        List<String> d8Args = new ArrayList<>();
        d8Args.add("--release"); d8Args.add("--min-api"); d8Args.add(String.valueOf(minApi));
        d8Args.add("--threads"); d8Args.add("1");
        d8Args.add("--intermediate"); d8Args.add("--per-class");
        d8Args.add("--globals-output"); d8Args.add(outDir);
        if (androidJar != null && androidJar.exists()) { d8Args.add("--lib"); d8Args.add(androidJar.getAbsolutePath()); }
        for (String c : classpath) { d8Args.add("--classpath"); d8Args.add(c); }
        d8Args.addAll(inputs);
        d8Args.add("--output"); d8Args.add(outDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(launcher); cmd.add(xmx == null ? "-Xmx1g" : xmx);
        cmd.add("-cp"); cmd.add(r8DexZip.getAbsolutePath());
        cmd.add("D8Entry");

        long argLen = 0;
        for (String a : d8Args) argLen += a.length() + 1;
        if (argLen < 12000) {
            cmd.addAll(d8Args);
            return run(cmd, "d8-archive-fork");
        }
        File outF = new File(outDir);
        File argFile = new File(outF.getParentFile(), "a" + outF.getName() + ".args");
        try {
            argFile.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(argFile);
            StringBuilder sb = new StringBuilder();
            for (String a : d8Args) {
                if (sb.length() > 0) sb.append(' ');
                boolean needQ = a.indexOf(' ') >= 0 || a.indexOf('"') >= 0;
                if (needQ) sb.append('"').append(a.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                else sb.append(a);
            }
            sb.append('\n');
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            log.problem("  [d8-archive-fork] tulis argfile gagal: " + e.getMessage());
            return false;
        }
        cmd.add("@" + argFile.getAbsolutePath());
        boolean ok = run(cmd, "d8-archive-fork");
        argFile.delete();
        return ok;
    }

    /** ECJ batch compiler. */
    public boolean ecj(File ecjDexZip, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher);
        cmd.add("-cp"); cmd.add(ecjDexZip.getAbsolutePath());
        cmd.add("EcjCompilerMain");
        cmd.addAll(args);
        return run(cmd, "ecj");
    }

    /** apksig sign via API ApkSignEntry (fork dalvikvm). debug.keystore: alias androiddebugkey / pass android. */
    public boolean apksig(File apksigDexZip, File inApk, File outApk, File debugKeystore) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher);
        cmd.add("-cp"); cmd.add(apksigDexZip.getAbsolutePath());
        cmd.add("ApkSignEntry");
        cmd.add(inApk.getAbsolutePath());
        cmd.add(outApk.getAbsolutePath());
        cmd.add(debugKeystore.getAbsolutePath());
        cmd.add("androiddebugkey");
        cmd.add("android");
        cmd.add("android");
        return run(cmd, "sign");
    }

    private boolean run(List<String> cmd, String tag) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p;
        try { p = pb.start(); }
        catch (IOException e) { log.problem("  [" + tag + "] " + e.getMessage()); return false; }
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192]; int n;
            StringBuilder line = new StringBuilder();
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) { char c = (char)(buf[i] & 0xFF); if (c == '\n') { emit(tag, line.toString()); line.setLength(0); } else line.append(c); }
            }
            if (line.length() > 0) emit(tag, line.toString());
        } catch (IOException ignored) {}
        int exit;
        try { exit = p.waitFor(); } catch (InterruptedException e) { exit = -1; }
        if (exit != 0) log.problem("  [" + tag + "] exit=" + exit);
        return exit == 0;
    }
    private void emit(String tag, String line) {
        String s = line.trim();
        if (s.isEmpty()) return;
        String l = s.toLowerCase();
        if (l.contains("error") || l.contains("exception") || l.contains("cannot")
                || l.contains("unresolved") || l.contains("failed") || s.startsWith("----------"))
            log.problem("  [" + tag + "] " + s);
        else log.log("  [" + tag + "] " + s);
    }
}
