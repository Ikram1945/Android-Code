package com.ikram.apkbuild;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base activity host: setel tema (palet sunny/blue/material + mode terang/gelap)
 * SEBELUM super.onCreate biar style & ?attr benar saat inflate. Lalu terapkan
 * palet Material You sesudah super.onCreate. Semua activity host extends ini.
 */
public abstract class ThemedActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle b) {
        Themes.applyTheme(this);      // setTheme + night mode, sebelum super.onCreate
        super.onCreate(b);
        Themes.applyPalette(this);    // DynamicColors Material You (sesudah create)
    }
}
