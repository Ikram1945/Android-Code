package com.ikram.apkbuild.toolchain;

import com.ikram.apkbuild.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Fork worker Kotlin PERSISTEN (ala CodeAssist ForkedKotlinCompiler).
 *
 * Satu VM dalvikvm (KotlincWorkerMain) di-start SEKALI dan dipakai ulang antar build.
 * - Heap terpisah dari app (bukan cap 576MB) -> aman proyek besar.
 * - Environment K2 (kotlin.environment.keepalive) dibangun sekali di worker dan hangat:
 *   compile berikutnya tak bayar class-loading 68MB + init IntelliJ-core + baca jars.
 * - Protokol request/response file di workDir: req.pending -> resp.txt.
 * - Idle timeout 10 menit di worker; manager respawn bila process mati.
 *
 * Worker dex: asset "kotlin/kotlinc-worker.dex.zip" (lihat ToolchainManager).
 * Classpath VM: worker.dex.zip + kotlin-compiler.dex.zip + stdlib dex + stdlib jar (persis LoaderTest).
 */
public class KotlinForkWorker {
    private static final String[] LAUNCHERS = {
            "/apex/com.android.art/bin/dalvikvm64",
            "/apex/com.android.art/bin/dalvikvm32",
            "/apex/com.android.art/bin/dalvikvm",
            "/system/bin/dalvikvm",
    };
    private static final long START_WAIT_MS = 30_000L;
    private static final long RESP_WAIT_MS = 10 * 60_000L;
    private static final int RESP_POLL_MS = 100;

    private final Util.Log log;
    private final File workDir;
    private final File reqFile;
    private final File respFile;
    private final String launcher;
    private Process proc;
    /** Property -D yang diberikan saat start terakhir, utk deteksi perubahan plugin Compose:
     *  worker persisten mempertahankan plugin set saat start, jadi ganti plugin butuh restart. */
    private String startedPluginClasspaths = null;

    /** Singleton STATIS: worker di-share semua instance BuildPipeline (warm() dan build() pakai
     *  instance berbeda). Tanpa ini, build membuat BuildPipeline baru -> proc null -> start ulang
     *  -> cold 12s tiap build walau sudah di-hangatkan saat index. */
    private static volatile KotlinForkWorker INSTANCE = null;

    public static KotlinForkWorker get(Util.Log log, File workDir) {
        KotlinForkWorker w = INSTANCE;
        if (w == null) {
            synchronized (KotlinForkWorker.class) {
                if (INSTANCE == null) INSTANCE = new KotlinForkWorker(log, workDir);
                w = INSTANCE;
            }
        }
        return w;
    }

    private KotlinForkWorker(Util.Log log, File workDir) {
        this.log = log;
        this.workDir = workDir;
        this.reqFile = new File(workDir, "req.pending");
        this.respFile = new File(workDir, "resp.txt");
        String l = null;
        for (String s : LAUNCHERS) if (new File(s).exists()) { l = s; break; }
        this.launcher = l;
    }

    public boolean available() { return launcher != null; }

    /** Pastikan worker hidup; mulai bila perlu. Return false bila gagal start.
     *  [pluginDex] + [pluginClasspaths]: plugin compiler Compose (dex di classpath VM karena
     *  class jar tak bisa didefinisikan di ART; jar diberi ke K2 via -Xplugin). Worker persisten
     *  jadi plugin hanya di-load sekali (bukan per compile). */
    public synchronized boolean ensureAlive(String workerDexZip, String compilerZip, String stdlibJar, String stdlibDex, String reflectDex, String kotlinHome, int xmxMb,
                                            String pluginDex, String pluginClasspaths) {
        if (proc != null && proc.isAlive()) {
            // Plugin Compose diberi saat start VM (persisten). Bila berubah sejak start terakhir
            // (proyek non-Compose -> Compose atau sebaliknya), worker lama tak punya/tak butuh
            // plugin -> restart bawa konfigurasi baru.
            String want = pluginClasspaths == null ? "" : pluginClasspaths;
            if (!want.equals(startedPluginClasspaths == null ? "" : startedPluginClasspaths)) {
                log.log("  [kotlin-worker] plugin compiler berubah -> restart worker");
                stopQuiet();
            } else {
                log.log("  [kotlin-worker] reuse (masih hidup)");
                return true;
            }
        }
        log.log("  [kotlin-worker] start ulang (sebelumnya " + (proc == null ? "null" : "mati") + ")"
                + (INSTANCE != null ? ", INSTANCE sama" : ", INSTANCE null"));
        stopQuiet();
        deleteQuiet(reqFile);
        deleteQuiet(respFile);
        workDir.mkdirs();
        // Classpath HARUS diawali worker dex (KotlincWorkerMain ada di sana), lalu compiler + stdlib + reflect.
        StringBuilder cp = new StringBuilder(workerDexZip == null || !new File(workerDexZip).exists() ? (compilerZip == null ? "" : compilerZip) : workerDexZip);
        if (compilerZip != null && new File(compilerZip).exists()) cp.append(':').append(compilerZip);
        if (stdlibDex != null && new File(stdlibDex).exists()) cp.append(':').append(stdlibDex);
        if (stdlibJar != null && new File(stdlibJar).exists()) cp.append(':').append(stdlibJar);
        if (reflectDex != null && new File(reflectDex).exists()) cp.append(':').append(reflectDex);
        // Plugin Compose: class di-dex di classpath VM (service descriptor dibaca KotlincMain
        // dari kotlinc.pluginClasspaths; class-nya resolve via parent delegation ke VM loader).
        if (pluginDex != null && new File(pluginDex).exists()) cp.append(':').append(pluginDex);
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher);
        cmd.add("-Xmx" + (xmxMb > 0 ? xmxMb : 1024) + "m");
        if (kotlinHome != null) cmd.add("-Dkotlinc.art.home=" + kotlinHome);
        // WAJIB: ART tak punya JDK. Worker default noJdk=false (lihat dexdump KotlincWorker.run:
        // getProperty("kotlinc.noJdk","false")), jadi tanpa -D ini K2 membangun JDK class-root
        // yang salah -> resolusi tipe platform (android.jar) rusak -> supertype binary tak
        // terbaca -> "override fun onFling overrides nothing" padahal signature benar.
        // Jalur fork one-shot sudah mengirimnya (BuildPipeline.compileKotlin); worker lupa.
        cmd.add("-Dkotlinc.noJdk=true");
        cmd.add("-Dkotlinc.noStdlib=true");
        cmd.add("-Dkotlinc.noReflect=true");
        if (pluginClasspaths != null && new File(pluginClasspaths).exists())
            cmd.add("-Dkotlinc.pluginClasspaths=" + pluginClasspaths);
        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add("KotlincWorkerMain");
        cmd.add(workDir.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            consumeAsync(proc);
        } catch (IOException e) {
            log.problem("  [kotlin-worker] start gagal: " + e.getMessage());
            proc = null;
            return false;
        }
        long deadline = System.currentTimeMillis() + START_WAIT_MS;
        File started = new File(workDir, "started");
        while (System.currentTimeMillis() < deadline) {
            if (started.exists()) { startedPluginClasspaths = pluginClasspaths; return true; }
            if (!proc.isAlive()) { log.problem("  [kotlin-worker] mati saat start"); stopQuiet(); return false; }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        log.problem("  [kotlin-worker] timeout tunggu started");
        stopQuiet();
        return false;
    }

    /** Kirim satu compile. Return exit code; -1 = worker gagal (fallback ke fork one-shot / in-process). */
    public synchronized int compile(String outDir, String classpath, String jvmTarget, String friendPaths, List<String> sources) {
        try {
            StringBuilder sb = new StringBuilder(outDir).append('\n')
                    .append(classpath).append('\n')
                    .append(jvmTarget).append('\n')
                    .append(friendPaths == null ? "" : friendPaths).append('\n');
            for (String s : sources) sb.append(s).append('\n');
            Files.write(reqFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.problem("  [kotlin-worker] tulis request gagal: " + e.getMessage());
            return -1;
        }
        long deadline = System.currentTimeMillis() + RESP_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (respFile.isFile()) break;
            if (proc != null && !proc.isAlive()) { log.problem("  [kotlin-worker] mati saat compile"); return -1; }
            try { Thread.sleep(RESP_POLL_MS); } catch (InterruptedException e) { return -1; }
        }
        if (!respFile.isFile()) { log.problem("  [kotlin-worker] timeout tunggu resp"); return -1; }
        int code = -1;
        StringBuilder out = new StringBuilder();
        try {
            List<String> lines = Files.readAllLines(respFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.startsWith("code=")) {
                    try { code = Integer.parseInt(line.substring(5).trim()); } catch (Exception ignored) {}
                } else out.append(line).append('\n');
            }
        } catch (IOException e) {
            log.problem("  [kotlin-worker] baca resp gagal: " + e.getMessage());
            code = -1;
        } finally {
            deleteQuiet(respFile);
        }
        emit(out.toString());
        return code;
    }

    /** Hentikan worker (idle timeout / app tutup). */
    public synchronized void stop() {
        stopQuiet();
        deleteQuiet(reqFile);
        deleteQuiet(respFile);
    }

    private void stopQuiet() {
        if (proc != null) {
            try { proc.destroy(); } catch (Exception ignored) {}
            proc = null;
        }
    }

    private void deleteQuiet(File f) { try { f.delete(); } catch (Exception ignored) {} }

    private void consumeAsync(final Process p) {
        Thread t = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                StringBuilder line = new StringBuilder();
                while ((n = in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        char c = (char) (buf[i] & 0xFF);
                        if (c == '\n') { emit(line.toString()); line.setLength(0); } else line.append(c);
                    }
                }
                if (line.length() > 0) emit(line.toString());
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private void emit(String s0) {
        String s = s0.trim();
        if (s.isEmpty()) return;
        String l = s.toLowerCase();
        if (l.contains("error") || l.contains("exception") || l.contains("cannot")
                || l.contains("unresolved") || l.contains("failed") || s.startsWith("----------"))
            log.problem("  [kotlin-worker] " + s);
        else log.log("  [kotlin-worker] " + s);
    }
}
