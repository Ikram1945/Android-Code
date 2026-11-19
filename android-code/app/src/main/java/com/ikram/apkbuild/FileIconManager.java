package com.ikram.apkbuild;

import java.util.HashMap;
import java.util.Map;

/** Ikon drawer per ekstensi (mirip FileIconManager ACS). */
public final class FileIconManager {
    private static final Map<String, Integer> MAP = new HashMap<>();
    static {
        put("java", R.drawable.ic_file_java);
        put("kt", R.drawable.ic_file_kotlin);
        put("kts", R.drawable.ic_file_kts);
        put("xml", R.drawable.ic_file_xml);
        put("json", R.drawable.ic_file_json);
        put("md", R.drawable.ic_file_markdown);
        put("gradle", R.drawable.ic_file_gradle);
        put("properties", R.drawable.ic_file_properties);
        put("prop", R.drawable.ic_file_properties);
        put("py", R.drawable.ic_file_python);
        put("c", R.drawable.ic_file_c);
        put("cpp", R.drawable.ic_file_cpp);
        put("svg", R.drawable.ic_file_svg);
        put("png", R.drawable.ic_file_image);
        put("jpg", R.drawable.ic_file_image);
        put("jpeg", R.drawable.ic_file_image);
        put("gif", R.drawable.ic_file_image);
        put("webp", R.drawable.ic_file_image);
        put("mp4", R.drawable.ic_file_video);
        put("mkv", R.drawable.ic_file_video);
        put("mp3", R.drawable.ic_file_audio);
        put("wav", R.drawable.ic_file_audio);
        put("zip", R.drawable.ic_file_archive);
        put("apk", R.drawable.ic_file_apk);
        put("aab", R.drawable.ic_file_apk);
        put("dex", R.drawable.ic_file_dex);
    }
    private static void put(String ext, int res) { MAP.put(ext, res); }

    private FileIconManager() {}

    /** Ikon untuk nama file; fallback ic_file_default. */
    public static int forFile(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return R.drawable.ic_file_default;
        Integer r = MAP.get(name.substring(i + 1).toLowerCase());
        return r != null ? r : R.drawable.ic_file_default;
    }
}
