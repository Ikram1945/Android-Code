package com.ikram.apkbuild;

import java.io.File;

/**
 * Konfigurasi proyek = isi module.toml (format CodeAssist versi 2).
 * Proyek berformat:
 *   <root>/
 *     app/
 *       module.toml
 *       src/main/java/<pkg>/...
 *       src/main/res/...
 *       src/main/AndroidManifest.xml
 * Output build -> <root>/app/build/output.apk (atau sesuai module.toml output).
 */
public class ProjectConfig {
    public String name = "app";
    public String packageName = "com.ikram.app";
    public int versionCode = 1;
    public String versionName = "1.0";
    public int minSdk = 26;
    public int targetSdk = 34;
    public int compileSdk = 36;
    public int javaLevel = 17;
    public String dexMode = "auto";
    public boolean parallelDex = false;
    /** Eksekusi compiler Java/Kotlin: "inprocess" (default, fallback fork) atau "fork" (paksa dalvikvm). */
    public String javaExec = "inprocess";
    public String kotlinExec = "inprocess";
    /** Heap worker Kotlin persist (MB). 0 = auto (ladder availMem). */
    public int kotlinWorkerHeapMb = 0;
    /** Maksimum fork D8 merge paralel. 0 = auto (budget availMem, cap 3). */
    public int dexMergeForks = 0;
    public java.util.List<String> implementation = new java.util.ArrayList<>();
    public File moduleToml;

    public static File appDir(File root) { return new File(root, "app"); }
    public static File moduleTomlFile(File root) { return new File(appDir(root), "module.toml"); }
    public static File manifestFile(File root) { return new File(appDir(root), "src/main/AndroidManifest.xml"); }
    public static File javaDir(File root) { return new File(appDir(root), "src/main/java"); }
    public static File kotlinDir(File root) { return new File(appDir(root), "src/main/kotlin"); }
    public static File resDir(File root) { return new File(appDir(root), "src/main/res"); }
    public static File outApk(File root) { return new File(appDir(root), "build/output.apk"); }

    /** Baca module.toml dari proyek. Return null jika file tidak ada / gagal. */
    public static ProjectConfig load(File root) {
        File f = moduleTomlFile(root);
        String t = Util.readText(f);
        if (t == null) return null;
        ProjectConfig c = new ProjectConfig();
        c.moduleToml = f;
        c.packageName = grab(t, "namespace", c.packageName);
        c.name = grab(t, "name", c.name);
        // [module] name
        // versi
        c.versionName = grab(t, "versionName", c.versionName);
        String vc = grab(t, "versionCode", null);
        if (vc != null) { try { c.versionCode = Integer.parseInt(vc.trim()); } catch (Exception ignored) {} }
        String cs = grab(t, "compileSdk", null);
        if (cs != null) { try { c.compileSdk = Integer.parseInt(cs.trim()); } catch (Exception ignored) {} }
        String ms = grab(t, "minSdk", null);
        if (ms != null) { try { c.minSdk = Integer.parseInt(ms.trim()); } catch (Exception ignored) {} }
        String ts = grab(t, "targetSdk", null);
        if (ts != null) { try { c.targetSdk = Integer.parseInt(ts.trim()); } catch (Exception ignored) {} }
        // languageLevel
        String ll = grab(t, "languageLevel", null);
        if (ll != null) {
            if (ll.contains("21")) c.javaLevel = 21;
            else if (ll.contains("17")) c.javaLevel = 17;
        }
        // dexMode (default auto)
        String dm = grab(t, "dexMode", "auto");
        c.dexMode = dm.trim().toLowerCase();
        c.parallelDex = "true".equalsIgnoreCase(grab(t, "parallelDex", "false").trim());
        // eksekusi compiler per bahasa (default inprocess)
        String je = grab(t, "javaExec", "inprocess");
        c.javaExec = "fork".equalsIgnoreCase(je.trim()) ? "fork" : "inprocess";
        String ke = grab(t, "kotlinExec", "inprocess");
        ke = ke.trim().toLowerCase();
        if (ke.equals("fork") || ke.equals("forkworker")) c.kotlinExec = ke;
        else c.kotlinExec = "inprocess";
        String kh = grab(t, "kotlinWorkerHeapMb", "0");
        try { c.kotlinWorkerHeapMb = Math.max(0, Integer.parseInt(kh.trim())); } catch (Exception ignored) {}
        String dmf = grab(t, "dexMergeForks", "0");
        try { c.dexMergeForks = Math.max(0, Integer.parseInt(dmf.trim())); } catch (Exception ignored) {}
        java.util.List<String> impl = parseList(t, "implementation");
        if (!impl.isEmpty()) c.implementation = impl;
        return c;
    }

    /** Ambil nilai key:key setelah = (nama key unik di file). */
    private static String grab(String text, String key, String def) {
        // cari "key = value" di baris
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith(key) && s.substring(key.length()).trim().startsWith("=")) {
                int eq = s.indexOf('=');
                String v = s.substring(eq + 1).trim();
                v = v.replace("\"", "").trim();
                return v.isEmpty() ? def : v;
            }
        }
        return def;
    }
    /** Parse "key = [ \"a:b:c\", \"x:y:z\" ]" -> list koordinat. */
    private static java.util.List<String> parseList(String text, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith(key) && s.substring(key.length()).trim().startsWith("=")) {
                int eq = s.indexOf('=');
                String body = s.substring(eq + 1).trim();
                // ambil isi dalam []
                int a = body.indexOf('['), b = body.lastIndexOf(']');
                if (a >= 0 && b > a) body = body.substring(a + 1, b);
                for (String item : body.split(",")) {
                    String it = item.trim().replace("\"", "").trim();
                    if (!it.isEmpty()) out.add(it);
                }
                break;
            }
        }
        return out;
    }


    /** Tulis module.toml ke file. */
    public void save() {
        if (moduleToml == null) return;
        String s = "version = 2\n\n"
            + "[module]\n"
            + "type = \"android-app\"\n"
            + "name = \"" + name + "\"\n"
            + "languageLevel = \"JAVA_" + javaLevel + "\"\n"
            + "output = \"build/classes\"\n\n"
            + "[sourceSets.main]\n"
            + "scope = \"IMPLEMENTATION\"\n"
            + "java = [\"src/main/java\"]\n"
            + "res = [\"src/main/res\"]\n\n"
            + "[android]\n"
            + "namespace = \"" + packageName + "\"\n"
            + "compileSdk = " + compileSdk + "\n"
            + "minSdk = " + minSdk + "\n"
            + "targetSdk = " + targetSdk + "\n"
            + "manifest = \"src/main/AndroidManifest.xml\"\n"
            + "versionCode = " + versionCode + "\n"
            + "versionName = \"" + versionName + "\"\n"
            + "isApplication = true\n\n"
            + "[build]\n"
            + "dexMode = \"" + dexMode + "\"\n"
            + "parallelDex = \"" + parallelDex + "\"\n"
            + "javaExec = \"" + javaExec + "\"\n"
            + "kotlinExec = \"" + kotlinExec + "\"\n"
            + "kotlinWorkerHeapMb = " + kotlinWorkerHeapMb + "\n\n"
            + "dexMergeForks = " + dexMergeForks + "\n\n"
            + "[dependencies]\n"
            + "implementation = " + formatList(implementation) + "\n";
        Util.writeText(moduleToml, s);
    }
    /** Format daftar koordinat -> [ "a:b:c", "d:e:f" ] */
    private static String formatList(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(list.get(i)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }
}
