package com.ikram.apkbuild;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.ikram.apkbuild.adapter.LibraryAdapter;
import com.ikram.apkbuild.databinding.ActivityLibraryBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Menu "Unduh Library" (dari MainActivity). Menampilkan katalog library Maven umum;
 * ketuk untuk mengunduh via MavenResolver ke cache disk (filesDir/libraries) — di-cache,
 * dipakai lintas proyek. Item yang sudah ada ditandai \u2713 (tanpa perlu unduh ulang).
 */
public class LibraryActivity extends ThemedActivity {
    private ActivityLibraryBinding binding;
    private final List<String[]> rows = new ArrayList<>();
    private LibraryAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivityLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        rebuildRows();
        ListView list = binding.listLibraries;
        adapter = new LibraryAdapter(this, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> onTapLibrary(i));
        binding.btnBack.setOnClickListener(v -> finish());
    }

    /** Bangun ulang daftar (nama + status sudah-diunduh). */
    private void rebuildRows() {
        rows.clear();
        for (String[] lib : LibraryCatalog.LIBRARIES) {
            String coord = LibraryCatalog.coord(lib);
            boolean have = LibraryCatalog.downloaded(this, coord);
            String[] row = { lib[0], lib[1] + (have ? "  \u2713" : ""), lib[2] };
            rows.add(row);
        }
        if (adapter != null) adapter.update(rows);
    }

    private void onTapLibrary(int index) {
        final String[] lib = LibraryCatalog.LIBRARIES[index];
        final String coord = LibraryCatalog.coord(lib);
        if (LibraryCatalog.downloaded(this, coord)) { toast("Sudah diunduh: " + coord); return; }
        toast("Mengunduh " + coord + " ...");
        new Thread(() -> {
            final String res = downloadResult(coord);
            runOnUiThread(() -> {
                rebuildRows();
                toast("Unduh " + lib[1] + ": " + res);
            });
        }).start();
    }

    private String downloadResult(String coord) {
        try {
            int n = LibraryCatalog.downloadTransitive(this, coord, new Util.Log() {
                @Override public void log(String m) {}
                @Override public void problem(String m) {}
            });
            return n > 0 ? ("OK (" + n + " artifact)") : "GAGAL";
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); binding = null; }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
