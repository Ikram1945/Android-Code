package com.ikram.apkbuild;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.ikram.apkbuild.adapter.ProjectListAdapter;
import com.ikram.apkbuild.databinding.ActivityMainBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/** Home: daftar proyek + tombol Create (di atas). Pakai ViewBinding. */
public class MainActivity extends ThemedActivity {
    private File projectsRoot;
    private ProjectListAdapter adapter;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        projectsRoot = new File(Environment.getExternalStorageDirectory(), "Ikram");
        if (!projectsRoot.exists()) projectsRoot.mkdirs();

        ListView list = binding.listProjects;
        TextView empty = binding.txtEmpty;
        list.setEmptyView(empty);
        adapter = new ProjectListAdapter(this, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> {
            File proj = adapter.getItem(i);
            if (hasConfig(proj)) openEditor(proj);
            else toast(proj.getName() + " bukan proyek (tidak ada app/module.toml)");
        });
        list.setOnItemLongClickListener((p, v, i, id) -> {
            File proj = adapter.getItem(i);
            if (hasConfig(proj)) openConfig(proj); else toast("Bukan proyek");
            return true;
        });
        binding.btnCreateTop.setOnClickListener(v -> openCreate());
        binding.fabCreate.setOnClickListener(v -> openCreate());
        binding.btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        binding.btnLibrary.setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
        binding.btnAbout.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        requestStorageIfNeeded();
        refreshList();
    }

    @Override protected void onResume() { super.onResume(); refreshList(); binding.txtTagline.setText(Taglines.INSTANCE.random()); }

    @Override protected void onDestroy() { super.onDestroy(); binding = null; }

    private void refreshList() {
        if (adapter == null) return;
        adapter.clear();
        File[] fs = projectsRoot.listFiles();
        if (fs != null) { Arrays.sort(fs); for (File f : fs) if (f.isDirectory()) adapter.add(f); }
        adapter.notifyDataSetChanged();
    }
    private boolean hasConfig(File proj) { return ProjectConfig.moduleTomlFile(proj).exists(); }
    private void openEditor(File proj) { Intent i = new Intent(this, EditorActivity.class); i.putExtra("path", proj.getAbsolutePath()); startActivity(i); }
    private void openCreate() { startActivity(new Intent(this, CreateProjectActivity.class)); }
    private void openConfig(File proj) { Intent i = new Intent(this, ModuleConfigActivity.class); i.putExtra("path", proj.getAbsolutePath()); startActivity(i); }

    private void requestStorageIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
