package com.ikram.apkbuild;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.ikram.apkbuild.databinding.ActivityCreateProjectBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Form buat proyek (ViewBinding) + dialog checklist pilih library (meniru CodeAssist). */
public class CreateProjectActivity extends ThemedActivity {
    private ActivityCreateProjectBinding binding;
    private final List<String> selectedLibs = new ArrayList<>();   // koordinat Maven terpilih

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivityCreateProjectBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        EditText name = binding.inName;
        EditText pkg = binding.inPackage;
        EditText verName = binding.inVerName;
        EditText verCode = binding.inVerCode;
        EditText compile = binding.inCompileSdk;
        EditText min = binding.inMinSdk;
        EditText target = binding.inTargetSdk;
        RadioGroup javaG = binding.rgJava;
        RadioGroup rgTpl = binding.rgTemplate;
        RadioGroup jeG = binding.rgJavaExec;
        RadioGroup keG = binding.rgKotlinExec;
        RadioGroup keW = binding.rgKotlinWorker;
        android.widget.SeekBar sbHeap = binding.sbKotlinHeap;
        android.widget.SeekBar sbDex = binding.sbDexForks;
        android.widget.TextView txtHeap = binding.txtKotlinHeapVal;
        android.widget.TextView txtDex = binding.txtDexForksVal;

        binding.btnAddLib.setOnClickListener(v -> showLibraryDialog());
        refreshLibStatus();
        sbHeap.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean f) { txtHeap.setText(heapLabel(p)); }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        sbDex.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean f) { txtDex.setText("" + p + " fork" + (p == 0 ? " (auto, max 3)" : "")); }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        txtHeap.setText(heapLabel(0));
        txtDex.setText("0 (auto, max 3)");
        // Fork Worker ON -> sinkronkan radio Kotlin ke "Fork".
        keW.setOnCheckedChangeListener((grp, id) -> {
            if (id == R.id.radioKotlinWorker) keG.check(R.id.radioKotlinFork);
        });
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnCreate.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String p = pkg.getText().toString().trim();
            if (n.isEmpty() || p.isEmpty()) { toast("Isi nama & package"); return; }
            int javaL = javaG.getCheckedRadioButtonId() == R.id.radio21 ? 21 : 17;
            // Template: 0 = Activity (View/XML), 1 = Material You (M3 + FAB), 2 = Compose (Kotlin).
            // Compose butuh Kotlin -> javaLevel tak relevan, tapi KotlinExec harus ada (default).
            int tpl = rgTpl.getCheckedRadioButtonId() == R.id.radioTplCompose ? 2
                    : (rgTpl.getCheckedRadioButtonId() == R.id.radioTplMaterial ? 1 : 0);
            if (tpl == 1 && intval(min, 26) < 21) min.setText("21"); // Material butuh minSdk >= 21
            if (tpl == 2 && intval(min, 26) < 21) min.setText("21"); // Compose butuh minSdk >= 21
            File root = new File(new File(Environment.getExternalStorageDirectory(), "Ikram"), n);
            String je = jeG.getCheckedRadioButtonId() == R.id.radioJavaFork ? "fork" : "inprocess";
            String ke = keW.getCheckedRadioButtonId() == R.id.radioKotlinWorker ? "forkworker"
                    : (keG.getCheckedRadioButtonId() == R.id.radioKotlinFork ? "fork" : "inprocess");
            int kHeapMb = heapMbAt(sbHeap.getProgress());
            int dexForks = sbDex.getProgress();
            boolean ok = new ProjectCreator(this).create(root, n, p,
                    val(verName, "1.0"), intval(verCode, 1),
                    intval(compile, 36), intval(min, 26), intval(target, 34), javaL,
                    je, ke, kHeapMb, dexForks, selectedLibs, tpl);
            if (ok) {
                toast("Proyek dibuat, membuka editor...");
                Intent i = new Intent(this, EditorActivity.class);
                i.putExtra("path", root.getAbsolutePath());
                startActivity(i);
                finish();
            } else toast("Gagal buat proyek (folder mungkin sudah ada)");
        });
    }

    /** Dialog checklist pilih library (meniru CodeAssist library picker). */
    private void showLibraryDialog() {
        int n = LibraryCatalog.LIBRARIES.length;
        String[] labels = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            String coord = LibraryCatalog.coord(LibraryCatalog.LIBRARIES[i]);
            labels[i] = LibraryCatalog.label(LibraryCatalog.LIBRARIES[i]);
            checked[i] = selectedLibs.contains(coord);
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih Library")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    String coord = LibraryCatalog.coord(LibraryCatalog.LIBRARIES[which]);
                    if (isChecked) { if (!selectedLibs.contains(coord)) selectedLibs.add(coord); }
                    else selectedLibs.remove(coord);
                    refreshLibStatus();
                })
                .setPositiveButton("OK", (d, w) -> refreshLibStatus())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void refreshLibStatus() {
        if (binding == null) return;
        if (selectedLibs.isEmpty()) binding.txtLibStatus.setText("Belum ada library");
        else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < selectedLibs.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append("\u2022 ").append(selectedLibs.get(i));
            }
            binding.txtLibStatus.setText(sb.toString());
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); binding = null; }

    private String val(EditText e, String def) { String s = e.getText().toString().trim(); return s.isEmpty() ? def : s; }
    private int intval(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; } }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static int heapMbAt(int pos) { return pos == 0 ? 0 : (pos == 1 ? 768 : (pos == 2 ? 1024 : (pos == 3 ? 1536 : 2048))); }
    private static String heapLabel(int pos) { return pos == 0 ? "0 MB (auto)" : heapMbAt(pos) + " MB"; }
}
