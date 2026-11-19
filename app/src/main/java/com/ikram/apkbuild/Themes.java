package com.ikram.apkbuild;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

/**
 * Pengaturan tema host. Dua dimensi:
 *  - PALET: SUNNY (tema sunny glow), BLUE (blue wave) atau MATERIAL (dinamis wallpaper, Android 12+).
 *  - MODE TERANG/GELAP: ikut perangkat (SYSTEM), paksa terang, atau paksa gelap.
 */
public final class Themes {
    // palet
    public static final int SUNNY = 0;
    public static final int BLUE = 1;
    public static final int MATERIAL = 2;
    // mode siang/malam
    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "appearance";
    private static final String KEY_PALET = "palette";
    private static final String KEY_NIGHT = "night_mode";

    private Themes() {}

    public static int palette(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PALET, SUNNY); }
    public static void setPalette(Context c, int v) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PALET, v).apply(); }
    public static int nightMode(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_NIGHT, SYSTEM); }
    public static void setNightMode(Context c, int v) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_NIGHT, v).apply(); }

    /** Bisa pakai Material You sungguhan (warna dari wallpaper) hanya Android 12+. */
    public static boolean supportsMaterial(Context c) { return android.os.Build.VERSION.SDK_INT >= 31; }

    /** Apakah saat ini mode gelap aktif (ikut sistem atau paksa gelap). */
    public static boolean isDark(Context c) {
        int m = nightMode(c);
        if (m == LIGHT) return false;
        if (m == DARK) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Terapkan tema activity: Wajib SEBELUM super.onCreate.
     * Pilih style resource sesuai palet + mode (sunny/blue + terang/gelap),
     * lalu setDefaultNightMode AppCompat utk konsistensi resource ?attr.
     */
    public static void applyTheme(Activity a) {
        int pal = palette(a);
        boolean dark = isDark(a);
        int style;
        if (pal == BLUE) {
            style = dark ? R.style.Theme_App_BlueWave_Dark : R.style.Theme_App_BlueWave;
        } else {
            // SUNNY (default) & MATERIAL keduanya pakai Theme.App; MATERIAL
            // mewarnai via DynamicColors sesudah super.onCreate.
            style = dark ? R.style.Theme_App_Dark : R.style.Theme_App;
        }
        a.setTheme(style);
        int mode = nightMode(a);
        int ac = mode == LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == DARK ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(ac);
    }

    /** Panggil SETELAH super.onCreate, sebelum/sekitar inflate: palet dinamis Material You. */
    public static void applyPalette(Activity a) {
        if (palette(a) == MATERIAL && supportsMaterial(a)) {
            DynamicColors.applyToActivityIfAvailable(a);
        }
    }

    /** Label palet aktif, utk subtitle baris Tema. */
    public static String paletteLabel(Context c) {
        int p = palette(c);
        if (p == BLUE) return "Blue Wave";
        if (p == MATERIAL) return supportsMaterial(c) ? "Material You" : "Material You (aktif di Android 12+)";
        return "Sunny Glow";
    }
}
