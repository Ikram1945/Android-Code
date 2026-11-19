package com.ikram.apkbuild;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.ikram.apkbuild.databinding.ActivityModuleConfigBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Ubah module.toml proyek (ViewBinding): package, version, sdk, java, dexMode, dependency. */
public class ModuleConfigActivity extends ThemedActivity {
    private File proj;
    private ProjectConfig cfg;
    private ActivityModuleConfigBinding binding;
    private ArrayAdapter<String> depAdapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivityModuleConfigBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        proj = new File(getIntent().getStringExtra("path"));
        cfg = ProjectConfig.load(proj);
        if (cfg == null) { toast("Tidak ada module.toml"); finish(); return; }

        EditText pkg = binding.cfgPkg;
        EditText vn = binding.cfgVerName;
        EditText vc = binding.cfgVerCode;
        EditText cs = binding.cfgCompile;
        EditText mn = binding.cfgMin;
        EditText tg = binding.cfgTarget;
        RadioGroup jg = binding.cfgJava;
        RadioGroup dg = binding.cfgDex;
        RadioGroup jeG = binding.cfgJavaExec;
        RadioGroup keG = binding.cfgKotlinExec;
        RadioGroup keW = binding.cfgKotlinWorker;
        android.widget.SeekBar sbHeap = binding.sbCfgKotlinHeap;
        android.widget.SeekBar sbDex = binding.sbCfgDexForks;
        android.widget.TextView txtHeap = binding.txtCfgKotlinHeapVal;
        android.widget.TextView txtDex = binding.txtCfgDexForksVal;

        pkg.setText(cfg.packageName);
        vn.setText(cfg.versionName);
        vc.setText(String.valueOf(cfg.versionCode));
        cs.setText(String.valueOf(cfg.compileSdk));
        mn.setText(String.valueOf(cfg.minSdk));
        tg.setText(String.valueOf(cfg.targetSdk));
        if (cfg.javaLevel == 21) jg.check(R.id.cfgRadio21); else jg.check(R.id.cfgRadio17);
        if ("full".equals(cfg.dexMode)) dg.check(R.id.dexFull);
        else if ("incremental".equals(cfg.dexMode)) dg.check(R.id.dexInc);
        else dg.check(R.id.dexAuto);
        binding.chkParallel.setChecked(cfg.parallelDex);
        if ("fork".equals(cfg.javaExec)) jeG.check(R.id.cfgJavaFork); else jeG.check(R.id.cfgJavaInproc);
        // Daftarkan listener SEBELUM check: saat Fork Worker On -> sinkronkan radio Kotlin ke "Fork".
        keW.setOnCheckedChangeListener((grp, id) -> {
            if (id == R.id.cfgKotlinWorkerOn) keG.check(R.id.cfgKotlinFork);
        });
        if ("forkworker".equals(cfg.kotlinExec)) keW.check(R.id.cfgKotlinWorkerOn);
        else if ("fork".equals(cfg.kotlinExec)) keG.check(R.id.cfgKotlinFork);
        else keG.check(R.id.cfgKotlinInproc);
        sbHeap.setProgress(heapPosAt(cfg.kotlinWorkerHeapMb));
        txtHeap.setText(heapLabel(sbHeap.getProgress()));
        sbDex.setProgress(Math.min(8, cfg.dexMergeForks));
        txtDex.setText(sbDex.getProgress() == 0 ? "0 (auto, max 3)" : sbDex.getProgress() + " fork");
        sbHeap.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean f) { txtHeap.setText(heapLabel(p)); }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        sbDex.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean f) { txtDex.setText(p == 0 ? "0 (auto, max 3)" : p + " fork"); }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        binding.btnBack.setOnClickListener(v -> finish());

        ListView depList = binding.depList;
        depAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(cfg.implementation));
        depList.setAdapter(depAdapter);
        binding.depPick.setOnClickListener(v -> promptLibraries(this, cfg, depAdapter, false));
        depList.setOnItemLongClickListener((p, v, i, id) -> { cfg.implementation.remove(i); depAdapter.notifyDataSetChanged(); return true; });

        binding.btnSaveConfig.setOnClickListener(v -> {
            cfg.packageName = pkg.getText().toString().trim();
            cfg.versionName = vn.getText().toString().trim();
            cfg.versionCode = intv(vc, cfg.versionCode);
            cfg.compileSdk = intv(cs, cfg.compileSdk);
            cfg.minSdk = intv(mn, cfg.minSdk);
            cfg.targetSdk = intv(tg, cfg.targetSdk);
            cfg.javaLevel = jg.getCheckedRadioButtonId() == R.id.cfgRadio21 ? 21 : 17;
            int d = dg.getCheckedRadioButtonId();
            cfg.dexMode = d == R.id.dexFull ? "full" : (d == R.id.dexInc ? "incremental" : "auto");
            cfg.parallelDex = binding.chkParallel.isChecked();
            cfg.javaExec = jeG.getCheckedRadioButtonId() == R.id.cfgJavaFork ? "fork" : "inprocess";
            cfg.kotlinExec = keW.getCheckedRadioButtonId() == R.id.cfgKotlinWorkerOn ? "forkworker"
                    : (keG.getCheckedRadioButtonId() == R.id.cfgKotlinFork ? "fork" : "inprocess");
            cfg.kotlinWorkerHeapMb = heapMbAt(sbHeap.getProgress());
            cfg.dexMergeForks = sbDex.getProgress();
            cfg.save();
            toast("module.toml disimpan");
            finish();
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); binding = null; }
    private int intv(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; } }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static int heapMbAt(int pos) { return pos == 0 ? 0 : (pos == 1 ? 768 : (pos == 2 ? 1024 : (pos == 3 ? 1536 : 2048))); }
    private static int heapPosAt(int mb) { return mb >= 2048 ? 4 : (mb >= 1536 ? 3 : (mb >= 1024 ? 2 : (mb >= 768 ? 1 : 0))); }
    private static String heapLabel(int pos) { return pos == 0 ? "0 MB (auto)" : heapMbAt(pos) + " MB"; }

    /** Dialog centang library yg SUDAH diunduh; centang = pasang ke proyek.
     *  adapter != null -> update daftar tampilan; saveNow -> tulis module.toml langsung. */
    public static void promptLibraries(Activity act, ProjectConfig cfg, ArrayAdapter<String> adapter, boolean saveNow) {
        String[][] libs = LibraryCatalog.LIBRARIES;
        List<String> labels = new ArrayList<>();
        List<String> coords = new ArrayList<>();
        for (String[] l : libs) {
            String coord = LibraryCatalog.coord(l);
            if (LibraryCatalog.downloaded(act, coord)) { coords.add(coord); labels.add(LibraryCatalog.label(l)); }
        }
        if (coords.isEmpty()) { toast(act, "Belum ada library diunduh. Buka menu Unduh Library dulu."); return; }

        final String[] labArr = labels.toArray(new String[0]);
        final String[] coordArr = coords.toArray(new String[0]);
        boolean[] checked = new boolean[coords.size()];
        for (int i = 0; i < coords.size(); i++) checked[i] = cfg.implementation.contains(coordArr[i]);

        new AlertDialog.Builder(act)
                .setTitle("Library terpasang (sudah diunduh)")
                .setMultiChoiceItems(labArr, checked, (d, i, isChecked) -> {})
                .setPositiveButton("Terapkan", (d, w) -> {
                    ListView lv = ((AlertDialog) d).getListView();
                    List<String> next = new ArrayList<>();
                    for (int i = 0; i < coords.size(); i++) if (lv.isItemChecked(i)) next.add(coordArr[i]);
                    cfg.implementation.clear();
                    cfg.implementation.addAll(next);
                    if (adapter != null) { adapter.clear(); adapter.addAll(next); adapter.notifyDataSetChanged(); }
                    if (saveNow) cfg.save();
                    toast(act, "Library diterapkan: " + next.size());
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static void toast(Activity act, String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }
}
