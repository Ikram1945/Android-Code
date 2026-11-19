package com.ikram.apkbuild.deps;

import com.ikram.apkbuild.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unduh library Maven (jar/aar) dari Google Maven + Maven Central.
 * Versi simpel: resolve koordinat -> unduh artifact, newest-wins sederhana per
 * koordinat (versi tertinggi menang). AAR diekstrak classes.jar + res/.
 * Disimpan di filesDir/libraries/<group>/<artifact>/<version>/.
 */
public class MavenResolver {
    private static final String GOOGLE = "https://dl.google.com/dl/android/maven2";
    private static final String CENTRAL = "https://repo1.maven.org/maven2";

    private final File libRoot;
    private final Util.Log log;

    public MavenResolver(File libRoot, Util.Log log) {
        this.libRoot = libRoot;
        this.log = log;
    }

    public File root() { return libRoot; }

    /** Apakah koordinat di-hosting Google Maven (androidx/com.google). */
    private static boolean googleHosted(String group) {
        return group.startsWith("androidx.") || group.startsWith("com.android")
                || group.startsWith("com.google.android") || group.startsWith("com.google.firebase");
    }

    /** Unduh artifact tunggal. Return path file (jar/aar) atau null. */
    /** Unduh artifact. Android lib (androidx/com.google.android) berupa .aar; lain .jar. */
    public File download(String coord) {
        String[] p = coord.split(":");
        if (p.length < 2) return null;
        String group = p[0], artifact = p[1];
        String version = p.length > 2 ? p[2] : "latest";
        String relBase = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        File destDir = new File(libRoot, group.replace('.', '/') + "/" + artifact + "/" + version);
        boolean androidLib = googleHosted(group);
        // coba aar dulu (Android library), lalu jar
        String[] exts = androidLib ? new String[]{".aar", ".jar"} : new String[]{".jar", ".aar"};
        String base = googleHosted(group) ? GOOGLE : CENTRAL;
        for (String ext : exts) {
            File dest = new File(destDir, artifact + "-" + version + ext);
            // Terima hanya unduhan yang UTUH: file terpotong ber-length>0 dulu lolos dan
            // meledak jauh kemudian sebagai "zip END header not found" di D8.
            if (Util.isValidZip(dest)) return dest;
            Util.deleteRecursive(dest);
            byte[] data = httpGet(base + "/" + relBase + ext, 180000);
            if (data != null) {
                try {
                    destDir.mkdirs();
                    try (OutputStream os = new FileOutputStream(dest)) { os.write(data); }
                    if (!Util.isValidZip(dest)) {
                        log.problem("  [deps] unduhan terpotong " + dest.getName() + " (" + data.length + " B) - dibuang");
                        Util.deleteRecursive(dest);
                        continue;
                    }
                    log.log("  [deps] unduh " + artifact + "-" + version + ext);
                    return dest;
                } catch (Exception e) { log.problem("  [deps] tulis gagal " + e.getMessage()); return null; }
            }
        }
        log.problem("  [deps] gagal unduh " + coord);
        return null;
    }

    private static byte[] httpGet(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "ApkBuild");
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            long want = c.getContentLengthLong();   // -1 bila chunked; hanya diperiksa bila diketahui
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) bos.write(b, 0, n);
                if (want >= 0 && bos.size() != want) return null;   // unduhan terpotong: tolak, jangan
                return bos.toByteArray();
            } finally { c.disconnect(); }
        } catch (Exception e) { return null; }
    }

    // ================= RESOLVE TRANSITIF (ala Gradle/CodeAssist) =================

    /**

     * Resolve daftar koordinat + SEMUA dependensi transitif (baca .pom).
     * Penjaga anti-bentrok: satu versi per group:artifact (newest-wins), dedup,
     * anti-loop via visited. Return file artifact (jar/aar) hasil unduh, urut stabil.
     */
    public List<File> resolve(List<String> coords) {
        // map group:artifact -> version (newest wins) ; pertahankan urutan kemunculan pertama
        LinkedHashMap<String, String> chosen = new LinkedHashMap<>(); // ga -> version
        Map<String, String> gaOfCoord = new LinkedHashMap<>();        // coord -> ga (cara tahu group asli utk host)
        for (String c : coords) {
            String[] p = c.split(":");
            if (p.length < 2) continue;
            String ga = p[0] + ":" + p[1];
            String ver = p.length > 2 ? p[2] : "latest";
            gaOfCoord.put(ga, ver);
            String prev = chosen.get(ga);
            if (prev == null || compareVersion(ver, prev) > 0) chosen.put(ga, ver);
        }

        // BFS expand transitif
        List<String> queue = new ArrayList<>();
        Set<String> expanded = new LinkedHashSet<>();  // coord sudah di-expand pom
        queue.addAll(coordsOf(chosen));
        while (!queue.isEmpty()) {
            String coord = queue.remove(0);
            if (expanded.contains(coord)) continue;
            expanded.add(coord);
            File pom = downloadPom(coord);
            if (pom == null) continue;
            for (Dep d : parseDeps(pom)) {
                if (d.skip) continue;
                String prev = chosen.get(d.ga);
                if (prev == null || compareVersion(d.version, prev) > 0) {
                    boolean added = (prev == null);
                    chosen.put(d.ga, d.version);
                    gaOfCoord.put(d.ga, d.version);
                    if (added || compareVersion(d.version, prev) > 0) {
                        queue.add(d.ga + ":" + d.version);
                    }
                }
            }
        }

        // version-alignment: artifact alias yang isinya sudah digabung ke pivot (kotlin jdk7/jdk8 ->
        // stdlib sejak 1.8) dibuang sblm unduh; kalau tak, class duplikat saat dex digabung.
        chosen = applyVersionAlignment(chosen);

        List<File> out = new ArrayList<>();
        for (Map.Entry<String, String> e : chosen.entrySet()) {
            File a = download(e.getKey() + ":" + e.getValue());
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Satu aturan alignment: bila pivot hadir pada versi >= [minPivotVersion], semua [aliases] yg juga
     *  hadir di-buang (isi/class-nya sdh digabung ke pivot sejak versi itu). Tambah entri di [ALIGNMENTS]
     *  utk family lain yg dikenal tumpang tindih. */
    private static final class Alignment {
        final String pivot; final String[] aliases; final String minPivotVersion;
        Alignment(String pivot, String[] aliases, String minPivotVersion) {
            this.pivot = pivot; this.aliases = aliases; this.minPivotVersion = minPivotVersion;
        }
    }
    /** Kotlin: sejak 1.8, isi stdlib-jdk7/jdk8 digabung ke stdlib (jdk7/jdk8 jadi shim kosong). */
    private static final Alignment[] ALIGNMENTS = {
        new Alignment("org.jetbrains.kotlin:kotlin-stdlib",
                new String[]{"org.jetbrains.kotlin:kotlin-stdlib-jdk7", "org.jetbrains.kotlin:kotlin-stdlib-jdk8"},
                "1.8.0"),
        // androidx.collection (KMP): root "collection" + "collection-ktx" hanyalah alias/variant
        // ke "collection-jvm" (variant jvm modul KMP). Resolver POM-only tak punya GMM variant
        // selection (spt CodeAssist), jadi ketiganya terunduh dan D8 abort:
        // "Classpath type already present: androidx.collection.ArraySet". Buang alias, sisakan pivot.
        new Alignment("androidx.collection:collection-jvm",
                new String[]{"androidx.collection:collection", "androidx.collection:collection-ktx"},
                "1.4.0"),
    };

    /**
     * Buang modul "root" KMP bila variant platformnya ("-jvm" / "-android") juga hadir di graf.
     * Modul KMP (mis. androidx.collection:collection) menerbitkan: artifact root (biasanya metadata
     * / POM-only, tanpa bytecode JVM) + artifact platform terpisah (collection-jvm) yg bawa bytecode.
     * Resolver POM-only tak tahu bedanya -> mengunduh keduanya -> tipe didefinisikan dua kali -> D8
     * abort "Classpath type already present: <T>". Aturan ini umum (tak perlu daftar manual):
     * "collection" buang krn "collection-jvm" hadir, "annotation" buang krn "annotation-jvm" hadir.
     * Aman utk kasus nyata: root KMP yg benar-benar bawa bytecode JVM (jadi kelas) tak menerbitkan
     * variant "-jvm" terpisah, sehingga tak tersentuh aturan ini.
     */
    private static LinkedHashMap<String, String> dropKmpRoots(LinkedHashMap<String, String> chosen) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : chosen.entrySet()) {
            String ga = e.getKey();
            int c = ga.indexOf(':');
            if (c <= 0) { out.put(ga, e.getValue()); continue; }
            String group = ga.substring(0, c), art = ga.substring(c + 1);
            // varian platform hanya relevan utk artifact yg BUKAN sudah bersuffix platform
            boolean isPlatformSuffixed = art.endsWith("-jvm") || art.endsWith("-android")
                    || art.endsWith("-jvm-stubs") || art.endsWith("-desktop");
            if (!isPlatformSuffixed && (chosen.containsKey(group + ":" + art + "-jvm")
                    || chosen.containsKey(group + ":" + art + "-android"))) {
                continue;
            }
            out.put(ga, e.getValue());
        }
        return out;
    }

    /**
     * Fitur 2-lite (meniru ALIGNMENT_GROUPS CodeAssist): seret sekeluarga artifact yg isinya
     * tumpang tindih ke satu versi supaya tak ada class didefinisikan dua kali saat dex digabung
     * (D8 abort "defined multiple times"). Di sini: bila pivot hadir >= ambang, alias redundant
     * di-buang. Return chosen final.
     */
    private static LinkedHashMap<String, String> applyVersionAlignment(LinkedHashMap<String, String> chosen) {
        LinkedHashMap<String, String> out = chosen;
        // Aturan UMUM (sebelum daftar manual): modul KMP menerbitkan "<art>" (root, metadata saja)
        // + "<art>-jvm"/"<art>-android" (variant platform yg bawa bytecode). Resolver POM-only
        // mengunduh keduanya -> tipe dobel -> D8 abort. Bila variant platform hadir, buang root.
        // Menangkap collection|collection-jvm, annotation|annotation-jvm, dst tanpa daftar manual.
        out = dropKmpRoots(out);
        for (Alignment al : ALIGNMENTS) {
            String pivotVer = null;
            for (Map.Entry<String, String> e : out.entrySet())
                if (e.getKey().equals(al.pivot)) { pivotVer = e.getValue(); break; }
            if (pivotVer == null || compareVersion(pivotVer, al.minPivotVersion) < 0) continue;
            LinkedHashMap<String, String> next = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<String, String> e : out.entrySet()) {
                boolean alias = false;
                for (String a : al.aliases) if (e.getKey().equals(a)) { alias = true; break; }
                if (alias) changed = true;        // buang alias; class-nya sdh di pivot
                else next.put(e.getKey(), e.getValue());
            }
            if (changed) out = next;
        }
        return out;
    }

    private static List<String> coordsOf(LinkedHashMap<String, String> chosen) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : chosen.entrySet()) l.add(e.getKey() + ":" + e.getValue());
        return l;
    }

    /** Unduh file .pom utk koordinat (dari google atau central). Return File pom atau null. */
    public File downloadPom(String coord) {
        String[] p = coord.split(":");
        if (p.length < 2) return null;
        String group = p[0], artifact = p[1];
        String version = p.length > 2 ? p[2] : "latest";
        File pom = new File(libRoot, group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom");
        if (pom.exists() && pom.length() > 0) return pom;
        String base = googleHosted(group) ? GOOGLE : CENTRAL;
        byte[] data = httpGet(base + "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom", 60000);
        if (data == null) return null;
        try {
            pom.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(pom)) { os.write(data); }
            return pom;
        } catch (Exception e) { return null; }
    }

    /** Dep hasil parse POM. */
    public static class Dep {
        public String ga; public String version; public boolean skip;
    }

    /** Parse <dependencies> dari file pom, abaikan scope provided/test/system & optional.
     *  Blok <dependencyManagement> (bukan dependensi nyata) DIBUANG dulu. */
    public List<Dep> parseDeps(File pom) {
        List<Dep> deps = new ArrayList<>();
        String xml;
        try { xml = new String(Util.readBytes(pom), "UTF-8"); } catch (Exception e) { return deps; }
        // buang dependencyManagement: hanya manajemen versi, bukan dependensi aktif
        xml = stripBlock(xml, "<dependencyManagement>", "</dependencyManagement>");
        int idx = 0;
        while (true) {
            int s = xml.indexOf("<dependency>", idx);
            if (s < 0) break;
            int e = xml.indexOf("</dependency>", s);
            if (e < 0) break;
            String blk = xml.substring(s + 13, e);
            idx = e + 14;
            Dep d = new Dep();
            String g = tag(blk, "groupId");
            String a = tag(blk, "artifactId");
            String v = tag(blk, "version");
            String scope = tag(blk, "scope");
            String opt = tag(blk, "optional");
            if (g.isEmpty() || a.isEmpty()) continue;
            d.ga = g + ":" + a;
            // version boleh ${prop} (BOM) -> tak bisa langsung, tandai skip kecuali ada literal
            v = cleanVersion(v);
            if (v.isEmpty() || v.startsWith("${")) { d.skip = true; continue; }
            d.version = v;
            if (scope.equals("test") || scope.equals("provided") || scope.equals("system")) d.skip = true;
            if (opt.equals("true")) d.skip = true;
            deps.add(d);
        }
        return deps;
    }

    /** Hapus SEMUA blok <open>...</open> (utk buang dependencyManagement). */
    private static String stripBlock(String xml, String open, String close) {
        while (true) {
            int s = xml.indexOf(open);
            if (s < 0) return xml;
            int e = xml.indexOf(close, s + open.length());
            if (e < 0) return xml;
            xml = xml.substring(0, s) + xml.substring(e + close.length());
        }
    }

    /** Ambil teks antar <name>..</name> (nama tag lokal di blok dependency). */
    private static String tag(String blk, String name) {
        int s = blk.indexOf("<" + name + ">");
        if (s < 0) return "";
        s += name.length() + 2;
        int e = blk.indexOf("</" + name + ">", s);
        if (e < 0) return "";
        String val = blk.substring(s, e).trim();
        return val.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** Normalisasi versi Maven: buang bracket range "[1.7.0]" -> "1.7.0", "[1.0,2.0)" -> "1.0". */
    public static String cleanVersion(String v) {
        v = v.replace("[", "").replace("]", "").replace("(", "").replace(")", "").replace(" ", "");
        int c = v.indexOf(',');
        if (c >= 0) v = v.substring(0, c);
        return v;
    }

    private static int compareVersion(String a, String b) {
        List<String> ta = tokens(a), tb = tokens(b);
        int n = Math.max(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            String x = i < ta.size() ? ta.get(i) : "0";
            String y = i < tb.size() ? tb.get(i) : "0";
            int cx = cmp(x, y);
            if (cx != 0) return cx;
        }
        return 0;
    }
    private static List<String> tokens(String v) {
        List<String> o = new ArrayList<>();
        StringBuilder sb = new StringBuilder(); boolean num = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i); boolean d = c >= '0' && c <= '9';
            if (c == '.' || c == '-' || c == '_' || c == '+') { if (sb.length() > 0) { o.add(sb.toString()); sb.setLength(0); } num = false; continue; }
            if (d != num && sb.length() > 0) { o.add(sb.toString()); sb.setLength(0); }
            sb.append(c); num = d;
        }
        if (sb.length() > 0) o.add(sb.toString());
        return o;
    }
    private static int cmp(String a, String b) {
        boolean an = isNum(a), bn = isNum(b);
        if (an && bn) return Long.compare(Long.parseLong(a), Long.parseLong(b));
        if (an) return 1;
        if (bn) return -1;
        return a.compareToIgnoreCase(b);
    }
    private static boolean isNum(String s) { if (s.isEmpty()) return false; for (char c : s.toCharArray()) if (c < '0' || c > '9') return false; return true; }


    /** Ekstrak classes.jar dari .aar (untuk classpath). */
    public File classesJarFromAar(File aar) {
        // Simpan per-AAR, bukan di folder versi bersama: satu folder versi bisa memuat >1 AAR
        // (varian platform AndroidX, mis. lifecycle-runtime-android + lifecycle-runtime).
        // classes.jar di folder bersama saling menimpa -> classpath = isi varian terakhir diekstrak.
        File dir = new File(aar.getParentFile(), "cj-" + aar.getName());
        dir.mkdirs();
        File classesJar = new File(dir, "classes.jar");
        // Terima hanya classes.jar yang benar-benar bisa dibuka: ekstraksi yang terputus
        // (disk penuh / AAR terpotong) meninggalkan file sebagian, dan dulu `exists()`
        // menerimanya bulat-bulat -> D8 lalu mati "zip END header not found".
        if (Util.isValidZip(classesJar)) return classesJar;
        Util.deleteRecursive(classesJar);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(aar)) {
            java.util.zip.ZipEntry e = zf.getEntry("classes.jar");
            if (e != null) {
                try (InputStream in = zf.getInputStream(e); OutputStream os = new FileOutputStream(classesJar)) {
                    Util.copy(in, os);
                }
            }
        } catch (Exception ignored) {}
        if (!Util.isValidZip(classesJar)) {
            Util.deleteRecursive(classesJar);   // jangan tinggalkan sampah yg diterima build berikutnya
            return null;
        }
        return classesJar;
    }
}
