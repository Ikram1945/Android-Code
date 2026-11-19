package com.ikram.apkbuild.toolchain;

import android.content.Context;
import android.system.Os;

import com.ikram.apkbuild.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Menyiapkan toolchain build dari aset yang DIBUNDLE di APK (salinan dari repo
 * CodeAssist + dex ECJ/R8 yang dibuat build-time).
 *
 * Aset (assets/ + jniLibs/):
 *   android.jar          platform SDK
 *   core-lambda-stubs    stubs desugar
 *   debug.keystore       keystore debug default
 *   ecj.dex.zip          ECJ ter-patch (Runtime$Version->shim) + shim, siap dalvikvm
 *   r8.dex.zip           D8/R8 dex, siap dalvikvm
 *   kotlin/compose-compiler-plugin.jar   plugin compiler Compose (jar asli, dibaca K2)
 *   kotlin/compose-compiler-plugin.dex.zip  class plugin di-dex (classpath VM, untuk ART)
 *   libaapt2.so (jniLibs) aapt2 native, diekstrak ART ke nativeLibraryDir otomatis
 */
public class ToolchainManager {
    private final Context ctx;
    private final File dir;          // filesDir/toolchain
    private final File libsDir;
    private final Util.Log log;

    public ToolchainManager(Context ctx, Util.Log log) {
        this.ctx = ctx;
        this.dir = new File(ctx.getFilesDir(), "toolchain");
        this.libsDir = new File(dir, "libs");
        this.log = log;
    }

    public File root() { return dir; }
    public File libsDir() { return libsDir; }

    /** Path aapt2 executable (dari nativeLibraryDir, sudah diekstrak ART). */
    public File aapt2() {
        File a = new File(ctx.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        if (a.exists()) return a;
        return new File(dir, "aapt2"); // fallback hasil ekstrak manual
    }

    public File androidJar() { return new File(libsDir, "android.jar"); }
    public File coreStubsJar() { return new File(libsDir, "core-lambda-stubs.jar"); }
    public File debugKeystore() { return new File(libsDir, "debug.keystore"); }
    public File debugP12() { return new File(libsDir, "debug.p12"); }
    public File ecjDexZip() { return new File(libsDir, "ecj.dex.zip"); }
    public File r8DexZip() { return new File(libsDir, "r8.dex.zip"); }
    public File apksigDexZip() { return new File(libsDir, "apksig.dex.zip"); }
    public File viewbindingJar() { return new File(libsDir, "viewbinding.jar"); }
    public File kotlinCompilerDexZip() { return new File(libsDir, "kotlin-compiler.dex.zip"); }
    public File kotlinStdlibJar() { return new File(libsDir, "kotlin-stdlib.jar"); }
    public File kotlinStdlibDexZip() { return new File(libsDir, "kotlin-stdlib.dex.zip"); }
    public File kotlinReflectDexZip() { return new File(libsDir, "kotlin-reflect.dex.zip"); }
    public File kotlincWorkerDexZip() { return new File(libsDir, "kotlinc-worker.dex.zip"); }
    /** Plugin compiler Compose (jar asli: K2 baca META-INF/services dari sini). */
    public File composeCompilerPluginJar() { return new File(libsDir, "compose-compiler-plugin.jar"); }
    /** Class plugin Compose yang sudah di-dex: di classpath VM karena class jar tak bisa
     *  didefinisikan saat runtime di ART (ServiceLoader resolve via parent delegation). */
    public File composeCompilerPluginDexZip() { return new File(libsDir, "compose-compiler-plugin.dex.zip"); }
    /** Ekstrak aset ke filesDir (sekali; reuse kalau sudah ada + ukuran sama). */
    public void ensure() {
        File verFile = new File(dir, ".asset-ver");
        // v17: paksa ekstrak ulang. Sebelumnya extractAsset menerima file panjang-berapapun
        // yang sudah ada, jadi android.jar yang terpotong di device lama tak pernah diperbaiki.
        String wantVer = "v17-asset-integrity";
        if (!wantVer.equals(Util.readText(verFile))) {
            deleteChildren(libsDir);
            Util.writeText(verFile, wantVer);
        }
        dir.mkdirs();
        libsDir.mkdirs();
        extractAsset("android.jar", androidJar());
        extractAsset("core-lambda-stubs.jar", coreStubsJar());
        extractAsset("debug.keystore", debugKeystore());
        extractAsset("debug.p12", debugP12());
        extractAsset("ecj.dex.zip", ecjDexZip());
        extractAsset("r8.dex.zip", r8DexZip());
        extractAsset("apksig.dex.zip", apksigDexZip());
        extractAsset("viewbinding/viewbinding.jar", viewbindingJar());
        extractAsset("kotlin/kotlin-compiler.dex.zip", kotlinCompilerDexZip());
        extractAsset("kotlin/kotlin-stdlib.jar", kotlinStdlibJar());
        extractAsset("kotlin/kotlin-stdlib.dex.zip", kotlinStdlibDexZip());
        extractAsset("kotlin/kotlin-reflect.dex.zip", kotlinReflectDexZip());
        extractAsset("kotlin/kotlinc-worker.dex.zip", kotlincWorkerDexZip());
        extractAsset("kotlin/compose-compiler-plugin.jar", composeCompilerPluginJar());
        extractAsset("kotlin/compose-compiler-plugin.dex.zip", composeCompilerPluginDexZip());
        makeReadOnly(ecjDexZip());
        makeReadOnly(r8DexZip());
        makeReadOnly(apksigDexZip());
        makeReadOnly(kotlinCompilerDexZip());
        makeReadOnly(kotlinStdlibJar());
        makeReadOnly(kotlinStdlibDexZip());
        makeReadOnly(kotlinReflectDexZip());
        makeReadOnly(kotlincWorkerDexZip());
        makeReadOnly(composeCompilerPluginJar());
        makeReadOnly(composeCompilerPluginDexZip());
    }
    private void deleteChildren(File d) {
        File[] c = d.listFiles();
        if (c != null) for (File f : c) Util.deleteRecursive(f);
    }
    private void extractAsset(String assetName, File dest) {
        long srcLen = -1;
        try {
            srcLen = ctx.getAssets().openFd(assetName).getLength();
        } catch (Exception ignored) {
            // Aset terkompresi di APK -> openFd() tak bisa; panjang asli tak diketahui.
        }
        // Panjang asli diketahui -> file lokal WAJIB sama panjang. Sebelumnya hanya
        // `length() > 0` diperiksa, jadi android.jar yang ekstraknya TERPOTONG diterima
        // bulat-bulat; K2 lalu membaca class separuh dan melaporkan "overrides nothing"
        // untuk override yang sebenarnya benar.
        if (srcLen >= 0 && dest.exists()) {
            if (dest.length() == srcLen) return;
            Util.deleteRecursive(dest);
        } else if (srcLen < 0 && dest.exists() && dest.length() > 0) {
            // Aset terkompresi di APK: panjang asli tak diketahui (openFd gagal), jadi tak
            // bisa dibandingkan. Sebelumnya jalur ini selalu membuka ulang dest untuk ditulis
            // ulang -> (a) ekstrak ulang tiap buka proyek, (b) EACCES saat dest sudah 0444
            // dari makeReadOnly(). Ponytail: percaya file lokal yang ada; lengkap/tidaknya
            // dijaga oleh versi .asset-ver (naikkan bila perlu paksa ekstrak ulang).
            return;
        }
        try (InputStream in = ctx.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            Util.copy(in, out);
            if (srcLen >= 0 && dest.length() != srcLen) {
                log.problem("[toolchain] ekstrak " + assetName + " TAK LENGKAP: " + dest.length()
                        + " dari " + srcLen + " B - file dibuang");
                Util.deleteRecursive(dest);
                return;
            }
            log.log("[toolchain] ekstrak " + assetName + " -> " + dest.length() + " B");
        } catch (Exception e) {
            log.problem("[toolchain] gagal ekstrak " + assetName + ": " + e.getMessage());
        }
    }

    /** Set read-only (W^X). */
    public void makeReadOnly(File f) {
        try { Os.chmod(f.getAbsolutePath(), 0444); } catch (Exception ignored) {}
    }

    /** Apakah toolchain build sudah siap? */
    public boolean ready() {
        return aapt2().exists() && androidJar().exists()
                && ecjDexZip().exists() && r8DexZip().exists();
    }
}
