package com.ikram.apkbuild;

import android.content.Context;

import com.ikram.apkbuild.deps.MavenResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Katalog library Android/Maven umum (meniru CodeAssist library picker).
 * Digunakan utk: (a) menu "Unduh Library" di MainActivity, (b) dialog checklist
 * "Tambah Library" di CreateProjectActivity -> disimpan ke module.toml proyek.
 */
public final class LibraryCatalog {
    private LibraryCatalog() {}

    /** Koordinat Maven umum + versi stabil. Nama ditampilkan = "group:artifact". */
    public static final String[][] LIBRARIES = {
        {"androidx.appcompat", "appcompat", "1.7.0"},
        {"com.google.android.material", "material", "1.12.0"},
        {"androidx.media3", "media3-exoplayer", "1.7.0"},
        {"androidx.media", "media", "1.7.0"},
        {"androidx.annotation", "annotation", "1.9.1"},
    };

    /** Coordinate penuh "group:artifact:version" utk tiap baris. */
    public static String coord(String[] lib) { return lib[0] + ":" + lib[1] + ":" + lib[2]; }

    /** Nama tampilan utk tiap baris. */
    public static String label(String[] lib) { return lib[1] + "\n" + lib[0] + ":" + lib[2]; }

    public static List<String> allCoords() {
        List<String> out = new ArrayList<>();
        for (String[] l : LIBRARIES) out.add(coord(l));
        return out;
    }

    /** Lokasi file terunduh utk koordinat (sama dgn MavenResolver). */
    public static File downloadedFile(Context ctx, String coord) {
        String[] p = coord.split(":");
        if (p.length < 3) return null;
        String group = p[0], artifact = p[1], version = p[2];
        File base = new File(new File(ctx.getFilesDir(), "libraries"),
                group.replace('.', '/') + "/" + artifact + "/" + version);
        File aar = new File(base, artifact + "-" + version + ".aar");
        if (aar.exists()) return aar;
        File jar = new File(base, artifact + "-" + version + ".jar");
        return jar.exists() ? jar : null;
    }

    /** Apakah koordinat sudah ada di cache disk. */
    public static boolean downloaded(Context ctx, String coord) {
        return downloadedFile(ctx, coord) != null;
    }

    /** Unduh koordinat via MavenResolver. Return null = gagal. */
    public static File download(Context ctx, String coord, Util.Log log) {
        MavenResolver r = new MavenResolver(new File(ctx.getFilesDir(), "libraries"), log);
        return r.download(coord);
    }

    /** Unduh koordinat + SEMUA dependensi transitif (baca .pom). Return jumlah berhasil. */
    public static int downloadTransitive(Context ctx, String coord, Util.Log log) {
        java.util.List<String> roots = new java.util.ArrayList<>();
        roots.add(coord);
        MavenResolver r = new MavenResolver(new File(ctx.getFilesDir(), "libraries"), log);
        java.util.List<File> files = r.resolve(roots);
        return files == null ? 0 : files.size();
    }
}
