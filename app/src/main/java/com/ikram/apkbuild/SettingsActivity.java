package com.ikram.apkbuild;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.ikram.apkbuild.databinding.ActivitySettingsBinding;

/** Menu Pengaturan host: daftar sub-menu (sekarang: Tema). Baris lain ditambah di sini nanti. */
public class SettingsActivity extends ThemedActivity {
    private ActivitySettingsBinding binding;

    // id baris visual di layout
    private static final int ROW_SUNNY = 1, ROW_MATERIAL = 2, ROW_SYSTEM = 3, ROW_LIGHT = 4, ROW_DARK = 5, ROW_BLUE = 6;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());

        binding.rowSunny.setOnClickListener(v -> choose(ROW_SUNNY));
        binding.rowBlue.setOnClickListener(v -> choose(ROW_BLUE));
        binding.rowMaterial.setOnClickListener(v -> choose(ROW_MATERIAL));
        binding.rowSystem.setOnClickListener(v -> choose(ROW_SYSTEM));
        binding.rowLight.setOnClickListener(v -> choose(ROW_LIGHT));
        binding.rowDark.setOnClickListener(v -> choose(ROW_DARK));

        refresh();
    }

    /** Highlight baris sesuai palet+mode aktif. */
    private void refresh() {
        binding.txtThemeSub.setText("Palet: " + Themes.paletteLabel(this)
                + "  •  Mode: " + nightLabel());
        setActive(binding.rowSunny, Themes.palette(this) == Themes.SUNNY);
        setActive(binding.rowBlue, Themes.palette(this) == Themes.BLUE);
        setActive(binding.rowMaterial, Themes.palette(this) == Themes.MATERIAL);
        setActive(binding.rowSystem, Themes.nightMode(this) == Themes.SYSTEM);
        setActive(binding.rowLight, Themes.nightMode(this) == Themes.LIGHT);
        setActive(binding.rowDark, Themes.nightMode(this) == Themes.DARK);
    }

    private String nightLabel() {
        switch (Themes.nightMode(this)) {
            case Themes.LIGHT: return "Terang";
            case Themes.DARK: return "Gelap";
            default: return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES ? "Sistem (gelap)" : "Sistem (terang)";
        }
    }

    private void setActive(View row, boolean active) {
        row.setAlpha(active ? 1f : 0.62f);
    }

    private void choose(int which) {
        switch (which) {
            case ROW_SUNNY:
                Themes.setPalette(this, Themes.SUNNY);
                if (!nightWasForced()) Themes.setNightMode(this, Themes.SYSTEM);
                break;
            case ROW_BLUE:
                Themes.setPalette(this, Themes.BLUE);
                if (!nightWasForced()) Themes.setNightMode(this, Themes.SYSTEM);
                break;
            case ROW_MATERIAL:
                if (!Themes.supportsMaterial(this)) {
                    toast("Material You butuh Android 12+");
                    return;
                }
                Themes.setPalette(this, Themes.MATERIAL);
                if (!nightWasForced()) Themes.setNightMode(this, Themes.SYSTEM);
                break;
            case ROW_SYSTEM:
                Themes.setNightMode(this, Themes.SYSTEM);
                break;
            case ROW_LIGHT:
                Themes.setNightMode(this, Themes.LIGHT);
                break;
            case ROW_DARK:
                Themes.setNightMode(this, Themes.DARK);
                break;
        }
        refresh();
        recreate();   // terapkan seketika
    }

    /** Mode gelap/terang dipaksa user (bukan sekadar default ikut sistem)? */
    private boolean nightWasForced() {
        int m = Themes.nightMode(this);
        return m == Themes.LIGHT || m == Themes.DARK;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
