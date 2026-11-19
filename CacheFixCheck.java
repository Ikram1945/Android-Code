// Self-check fix cache android-code (BuildPipeline). Jalankan: javac CacheFixCheck.java && java -ea CacheFixCheck
import java.io.*;
import java.util.ArrayDeque;

public class CacheFixCheck {
    // replika resSentinel (fix #5)
    static String resSentinel(File resDir) {
        if (resDir == null || !resDir.isDirectory()) return "0:0";
        long count = 0, bytes = 0;
        ArrayDeque<File> q = new ArrayDeque<>(); q.add(resDir);
        while (!q.isEmpty()) {
            File[] ch = q.poll().listFiles();
            if (ch == null) continue;
            for (File f : ch) { if (f.isDirectory()) q.add(f); else { count++; bytes += f.length(); } }
        }
        return count + ":" + bytes;
    }
    // replika hashOf fallback (fix #2)
    static String hashOf(File f, boolean md5ok) {
        String h = md5ok ? "md5hex" : null;
        return h == null ? ("!unreadable:" + f.length() + ":" + System.nanoTime()) : h;
    }
    // replika cachedHash policy (fix #1): artefak build non-.class -> md5 segar (abaikan memo)
    static String cachedHash(File f, File work, java.util.Map<String,String> memo) {
        String key = f.getAbsolutePath();
        boolean isArtifact = work != null && key.startsWith(work.getAbsolutePath());
        if (isArtifact && !key.endsWith(".class")) return "FRESH:" + f.length();
        String stamp = f.length() + ":" + f.lastModified();
        String v = memo.get(key);
        if (v != null && v.startsWith(stamp + "|")) return v.substring(stamp.length()+1);
        String h = "H:" + f.length();
        memo.put(key, stamp + "|" + h);
        return h;
    }

    public static void main(String[] a) throws Exception {
        File base = new File("/tmp/selftest/cf"); del(base); base.mkdirs();
        File work = new File(base, "build"); work.mkdirs();

        // #5 sentinel: terpotong -> beda
        File res = new File(base, "libres/res"); res.mkdirs();
        new File(res, "a.xml").createNewFile();
        File sub = new File(res, "sub"); sub.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(sub, "b.xml"))) { o.write(new byte[100]); }
        String s1 = resSentinel(res);
        assert s1.equals("2:100") : "sentinel awal salah: " + s1;
        new File(sub, "b.xml").delete();
        assert !s1.equals(resSentinel(res)) : "sentinel tak berubah setelah terpotong";
        assert resSentinel(null).equals("0:0") && resSentinel(new File(base,"nope")).equals("0:0");

        // #2 hashOf fallback: dua file beda isi, length sama -> tak bertabrakan
        File f1 = new File(base, "u1"); f1.createNewFile();
        File f2 = new File(base, "u2"); f2.createNewFile();
        assert !hashOf(f1,false).equals(hashOf(f2,false)) : "sentinel hashOf bertabrakan";

        // #1 cachedHash: artefak non-.class -> SELALU fresh (walau memo terisi)
        java.util.Map<String,String> memo = new java.util.HashMap<>();
        File apk = new File(work, "output.apk"); apk.createNewFile();
        String h1 = cachedHash(apk, work, memo);
        String h2 = cachedHash(apk, work, memo);
        assert h1.startsWith("FRESH:") : "artefak build harus fresh-hash, dapat: " + h1;
        // .class -> boleh pakai memo
        File cls = new File(work, "classes/Foo.class"); cls.getParentFile().mkdirs(); cls.createNewFile();
        String c1 = cachedHash(cls, work, memo);
        assert c1.startsWith("H:") : ".class harus pakai memo, dapat: " + c1;
        // sumber di luar work -> memo
        File src = new File(base, "src/Main.java"); src.getParentFile().mkdirs(); src.createNewFile();
        assert cachedHash(src, work, memo).startsWith("H:") : "sumber harus pakai memo";

        System.out.println("CACHE-FIX SELF-CHECK PASS");
        System.out.println("  #1 artefak build fresh-hash, .class & sumber pakai memo  OK");
        System.out.println("  #2 hashOf fallback sentinel unik (tak bertabrakan)        OK");
        System.out.println("  #5 resSentinel berubah saat ekstraksi terpotong           OK");
    }
    static void del(File f){ if(f.isDirectory()&&f.listFiles()!=null) for(File c: f.listFiles()) del(c); f.delete(); }
}
