package com.ikram.apkbuild;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ListView;
import com.ikram.apkbuild.adapter.FileTreeAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.ikram.apkbuild.build.BuildPipeline;
import com.ikram.apkbuild.build.InProcessTools;
import com.ikram.apkbuild.databinding.ActivityEditorBinding;
import com.ikram.apkbuild.toolchain.ToolchainManager;
import androidx.core.content.FileProvider;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.langs.java.JavaLanguage;
import com.ikram.apkbuild.build.TextMateLanguages;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor proyek. Drawer kiri = daftar file, strip tab = file terbuka, tengah = sora-editor,
 * bawah = konsol build (LOG / PROBLEM). Pola UX ditiru CodeAssist (FileNavigator + TabsStrip +
 * EditorCenter), tapi View/XML + sora-editor, bukan Compose.
 */
public class EditorActivity extends ThemedActivity implements Util.Log {
    private File proj;
    private android.view.View buildBtn;
    private ToolchainManager tc;
    private ActivityEditorBinding binding;

    private final StringBuilder logBuf = new StringBuilder();
    private final StringBuilder errBuf = new StringBuilder();
    private int errorCount = 0;
    private boolean showingErr = false;
    /** panel konsol build bisa ditutup/buka (toggle). */
    private boolean consoleOpen = true;
    private volatile boolean indexing = false;
    private Thread indexingThread;
    /** BuildPipeline terakhir yang dibuat (warm/build), untuk menghentikan fork worker saat keluar. */
    private volatile BuildPipeline lastPipeline;

    // ---- state editor ----
    private CodeEditor editor;
    private LinearLayout tabStrip;
    private DrawerLayout drawer;
    /** daftar path relatif yang sedang terbuka, sejajar dengan anak tabStrip. */
    private final List<String> openPaths = new ArrayList<>();
    /** adapter tree drawer (folder expand/collapse). */
    private FileTreeAdapter fileAdapter;
    private int activeIndex = -1;
    /** tanda file punya perubahan belum disimpan (per index tab). */
    private final java.util.Set<Integer> dirtyTabs = new java.util.HashSet<>();
    /** cegah dirty-flag terpasang saat loadFile mengisi teks programatik. */
    private boolean loadingFile = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        binding = ActivityEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        proj = new File(getIntent().getStringExtra("path"));
        binding.txtProjName.setText(proj.getName());
        buildBtn = binding.btnBuild;

        drawer = binding.drawer;
        tabStrip = binding.tabStrip;
        editor = binding.editor;

        TextMateLanguages.ensure(this);   // grammar kotlin/xml/java (assets textmate)
        setupEditor();

        binding.tabLog.setOnClickListener(v -> selectTab(false));
        binding.tabProblem.setOnClickListener(v -> selectTab(true));
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnMenu.setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));

        fileAdapter = new FileTreeAdapter(this, f -> {
            openFile(rel(ProjectConfig.appDir(proj), f));
            drawer.closeDrawer(GravityCompat.START);
        });
        binding.listFiles.setAdapter(fileAdapter);
        // ExpandableListView tidak dipakai lagi: folder boleh di kedalaman berapa pun.
        // Klik folder = toggle di adapter (rebuild), klik file = buka.
        fileAdapter.setOnFileLongClick((f, isDir, depth) -> promptFileMenu(f, isDir));
        refreshFileList();

        binding.btnFolder.setOnClickListener(v -> openFolderBrowser());
        binding.btnProjSettings.setOnClickListener(v -> openProjectSettings());
        binding.btnConfig.setOnClickListener(v -> openEditorSettingsMenu());
        binding.btnLibs.setOnClickListener(v -> promptLibraries());
        // (btnFont dihapus; ukuran font kini lewat menu Pengaturan editor & APK)
        binding.btnSave.setOnClickListener(v -> saveTab());
        binding.btnCopy.setOnClickListener(v -> copyLog());
        subscribeDirty();
        binding.btnToggleConsole.setOnClickListener(v -> toggleConsole());
        buildBtn.setOnClickListener(v -> startBuild());

        showEmpty(true);
        restoreTabs();

        // pulihkan status konsol (tertutup/terbuka) antar sesi
        consoleOpen = prefs().getBoolean(consoleKey(), true);
        binding.consolePanel.setVisibility(consoleOpen ? View.VISIBLE : View.GONE);
        binding.btnToggleConsole.setImageResource(consoleOpen ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);

        tc = new ToolchainManager(this, this);
        new Thread(() -> {
            tc.ensure();
            runOnUiThread(() -> {
                appendLog("[status] toolchain " + (tc.ready() ? "SIAP" : "BELUM siap"));
                if (tc.ready()) startIndexing();
                else buildBtn.setEnabled(false);
            });
        }).start();
    }

    /** Konfigurasi awal sora-editor: font mono, skema warna, wordwrap, undo, nomor baris. */
    private void setupEditor() {
        try {
            editor.setTypefaceText(Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf"));
        } catch (Exception e) {
            editor.setTypefaceText(Typeface.MONOSPACE);
        }
        editor.setLineSpacing(2f, 1.1f);
        // AKAR FIX: urutan wajib ini. TextMateLanguages.ensure() (panggil SEBELUM setupEditor
        // di onCreate) sudah menjamin theme ter-load. Skema TextMate punya warna id token
        // (foreground+255); pakai skema biasa (SchemeDarcula/Eclipse) -> getColor(id>255)=0
        // -> semua teks TRANSPARAN. TextMateColorScheme fallback otomatis ke TEXT_NORMAL.
        EditorColorScheme scheme = chooseScheme();
        // TextMate menulis span warna berdasar theme model (TextMateColorScheme), bukan
        // SchemeDarcula/Eclipse. Kalau grammar TextMate aktif (ada theme), pakai itu supaya
        // warna token terbaca; kalau tidak, skema biasa (fallback aman).
        try {
            ThemeModel tm = TextMateLanguages.currentTheme();
            if (tm != null) scheme = TextMateColorScheme.create(ThemeRegistry.getInstance(), tm);
        } catch (Throwable ignored) {}
        editor.setColorScheme(scheme);
        // Pola ACS (IDEEditor.initEditor): warna latar solid dari theme, bukan transparan -
        // tanpa ini CodeEditor tembus warna activity & teks ikut pudar.
        editor.setBackgroundColor(0xFF1E1B19);
        editor.setWordwrap(true);
        editor.setUndoEnabled(true);
        editor.setEditable(true);
        editor.setTextSizePx(dp(fontSizeSp()));
    }

    /** Skema warna editor. Pakai SchemeDarcula pada mode gelap, SchemeEclipse saat terang. */
    private EditorColorScheme chooseScheme() {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return night
                ? new SchemeDarcula()
                : new io.github.rosemoe.sora.widget.schemes.SchemeEclipse();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private int fontSizeSp() { return prefs().getInt("editor_font_size_sp", 12); }

    /** dialog atur ukuran font editor, tersimpan permanen. */
    private void promptFontSize() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) dp(20), (int) dp(12), (int) dp(20), (int) dp(4));
        SeekBar sb = new SeekBar(this);
        sb.setMax(40);
        sb.setProgress(fontSizeSp() - 6);
        TextView label = new TextView(this);
        label.setText("Ukuran font: " + fontSizeSp() + " sp");
        label.setTextColor(getColor(android.R.color.darker_gray));
        label.setPadding(0, 8, 0, 4);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { label.setText("Ukuran font: " + (p + 6) + " sp"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(label);
        box.addView(sb);
        new AlertDialog.Builder(this)
                .setTitle("Ukuran font editor")
                .setView(box)
                .setPositiveButton("Simpan", (d, w) -> {
                    int sp = sb.getProgress() + 6;
                    prefs().edit().putInt("editor_font_size_sp", sp).apply();
                    editor.setTextSizePx(dp(sp));
                    toast("Ukuran font: " + sp + " sp");
                })
                .setNegativeButton("Batal", null)
                .show();
    }


    private void showEmpty(boolean empty) {
        binding.txtEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        editor.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // =========================================================== tab + buka file

    /** Buka file (path relatif ke app/src/main). Kalau sudah terbuka, fokus tab yang ada. */
    private void openFile(String rel) {
        int existing = openPaths.indexOf(rel);
        if (existing >= 0) { selectTabIndex(existing); return; }
        File f = new File(ProjectConfig.appDir(proj), rel);
        if (!f.isFile()) { toast("Bukan file: " + rel); return; }
        openPaths.add(rel);
        addTabView(rel);
        selectTabIndex(openPaths.size() - 1);
    }

    /** Tambah tombol tab baru ke strip (nama file + tombol tutup). */
    private void addTabView(String rel) {
        String name = new File(rel).getName();
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(FileIconManager.forFile(name));
        icon.setPadding((int) dp(6), (int) dp(6), (int) dp(2), (int) dp(6));
        tab.addView(icon);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(12);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding((int) dp(6), (int) dp(6), (int) dp(4), (int) dp(6));
        tab.addView(title);

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(11);
        close.setPadding((int) dp(4), (int) dp(6), (int) dp(10), (int) dp(6));
        tab.addView(close);

        tab.setOnClickListener(v -> selectTabIndex(tabStrip.indexOfChild(tab)));
        close.setOnClickListener(v -> closeTab(tabStrip.indexOfChild(tab)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) dp(2));
        tabStrip.addView(tab, lp);
    }

    /** Fokus tab index: simpan buffer tab lama, muat file tab baru ke sora, sorot tab aktif. */
    private void selectTabIndex(int idx) {
        if (idx < 0 || idx >= openPaths.size()) return;
        if (idx == activeIndex) return;
        saveActiveFile();  // simpan perubahan buffer tab lama sebelum pindah
        activeIndex = idx;
        String rel = openPaths.get(idx);
        File f = new File(ProjectConfig.appDir(proj), rel);
        loadFile(f, rel);
        highlightActiveTab();
        scrollTabIntoView(idx);
    }

    private void highlightActiveTab() {
        int bg = getColor(com.ikram.apkbuild.R.color.surface_container);
        int bgMain = getColor(com.ikram.apkbuild.R.color.background);
        int onActive = getColor(com.ikram.apkbuild.R.color.primary);
        int onIdle = getColor(com.ikram.apkbuild.R.color.on_surface_variant);
        for (int i = 0; i < tabStrip.getChildCount(); i++) {
            LinearLayout tab = (LinearLayout) tabStrip.getChildAt(i);
            boolean active = i == activeIndex;
            tab.setBackgroundColor(active ? bgMain : bg);
            for (int j = 0; j < tab.getChildCount(); j++) {
                android.view.View child = tab.getChildAt(j);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(active ? onActive : onIdle);
                }
            }
        }
    }

    private void scrollTabIntoView(int idx) {
        if (idx < 0 || idx >= tabStrip.getChildCount()) return;
        final View child = tabStrip.getChildAt(idx);
        binding.tabScroll.post(() -> binding.tabScroll.smoothScrollTo(child.getLeft(), 0));
    }

    /** Tutup tab. Kalau yang ditutup aktif, pindah ke tab di kirinya (atau berikutnya). */
    private void closeTab(int idx) {
        if (idx < 0 || idx >= openPaths.size()) return;
        openPaths.remove(idx);
        tabStrip.removeViewAt(idx);
        if (openPaths.isEmpty()) {
            activeIndex = -1;
            editor.setText("");
            editor.setEditorLanguage(new EmptyLanguage());
            showEmpty(true);
            return;
        }
        if (idx < activeIndex) selectTabIndex(activeIndex - 1);
        else if (idx == activeIndex) selectTabIndex(Math.min(idx, openPaths.size() - 1));
        else selectTabIndex(activeIndex);
    }

    /** Muat isi file ke editor + pilih bahasa dari ekstensi. */
    private void loadFile(File f, String rel) {
        String text;
        try { text = Util.readText(f); }
        catch (Exception e) { toast("Gagal baca: " + e.getMessage()); return; }
        loadingFile = true;
        editor.setEditorLanguage(languageFor(rel));
        editor.setText(text);
        editor.setSelection(0, 0);
        loadingFile = false;
        showEmpty(false);
        binding.txtProjName.setText(new File(rel).getName());
    }

    /** Bahasa highlight dari ekstensi. .kt/.kts/.xml/.java -> TextMate (language-textmate);
     *  fallback JavaLanguage utk .java, plain EmptyLanguage utk lainnya. */
    private Language languageFor(String path) {
        String lower = path.toLowerCase();
        Language tm = TextMateLanguages.languageFor(path);
        if (tm != null) return tm;
        if (lower.endsWith(".java")) return new JavaLanguage();
        return new EmptyLanguage();
    }

    @Override
    protected void onDestroy() {
        // simpan perubahan file aktif sebelum keluar
        saveActiveFile();
        saveTabs();
        super.onDestroy();
        binding = null;
        // Matikan worker kotlin fork (dalvikvm) supaya tidak ada proses jalan yang
        // menggantung di background saat keluar proyek. Singleton statis tetap hidup
        // antar instance BuildPipeline, jadi stop() di sini menutup VM worker sekali.
        try {
            BuildPipeline pl = lastPipeline;
            if (pl != null) pl.stopWorker();
        } catch (Throwable ignored) {}
        // clearCache dihapus: CACHED static tetap dipegang proses sampai app mati, mengosongkannya
        // tak menghemat memori tapi memaksa reload 68MB+ kotlin/r8 saat buka proyek berikutnya.
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        saveActiveFile();
        super.onBackPressed();
    }

    /** Simpan isi editor ke file tab aktif (dipanggil saat pindah tab / keluar). */
    /** tombol Save: simpan tab aktif + bersihkan tanda dirty. */
    private void saveTab() {
        if (activeIndex < 0 || activeIndex >= openPaths.size()) { toast("Tidak ada file terbuka"); return; }
        String rel = openPaths.get(activeIndex);
        File f = new File(ProjectConfig.appDir(proj), rel);
        try {
            Util.writeText(f, editor.getText().toString());
            dirtyTabs.remove(activeIndex);
            refreshTabLabel(activeIndex);
            toast("Tersimpan: " + new File(rel).getName());
        } catch (Exception e) { problem("simpan gagal " + rel + ": " + e.getMessage()); }
    }

    /** pasang listener perubahan konten -> tandai tab aktif dirty. */
    private void subscribeDirty() {
        try {
            editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class, (event, unsubscribe) -> {
                if (loadingFile) return;
                if (activeIndex >= 0 && dirtyTabs.add(activeIndex)) refreshTabLabel(activeIndex);
            });
        } catch (Throwable ignored) {}
    }

    /** perbarui label tab (tambah/lepas tanda * kalau dirty). */
    private void refreshTabLabel(int idx) {
        if (idx < 0 || idx >= tabStrip.getChildCount()) return;
        LinearLayout tab = (LinearLayout) tabStrip.getChildAt(idx);
        for (int j = 0; j < tab.getChildCount(); j++) {
            android.view.View child = tab.getChildAt(j);
            if (child instanceof TextView && !((TextView) child).getText().toString().equals("\u2715")) {
                String name = new File(openPaths.get(idx)).getName();
                ((TextView) child).setText(dirtyTabs.contains(idx) ? name + " *" : name);
                break;
            }
        }
    }
    private void saveActiveFile() {
        if (editor == null || activeIndex < 0 || activeIndex >= openPaths.size()) return;
        String rel = openPaths.get(activeIndex);
        File f = new File(ProjectConfig.appDir(proj), rel);
        try { Util.writeText(f, editor.getText().toString()); }
        catch (Exception e) { problem("simpan gagal " + rel + ": " + e.getMessage()); }
    }

    // ---- drawer: daftar file ----

    private void refreshFileList() {
        fileAdapter.load(ProjectConfig.appDir(proj));
        binding.drawerTitle.setText(proj.getName());
        restoreTreeState();
    }

    // =========================================================== konsol + build

    /** Indexing saat buka proyek: tombol Run ditiadakan sementara, hangatkan cache agar build cepat. */
    private void startIndexing() {
        indexing = true;
        buildBtn.setEnabled(false);
        ((android.widget.ImageButton) buildBtn).setImageResource(R.drawable.ic_build_spinner);
        binding.txtProjName.setText(proj.getName() + "  (mengindeks...)");
        appendLog("\n[index] menyiapkan cache (ECJ/D8/sign classloader + library)...");
        Thread t = new Thread(() -> {
            boolean ok;
            try {
                BuildPipeline pl = new BuildPipeline(this, tc, this);
                lastPipeline = pl;
                ok = pl.warm(proj);
            } catch (Throwable th) {
                ok = false;
                problem("[index] " + th);
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (binding == null) return; // activity sudah ditutup
                indexing = false;
                ((android.widget.ImageButton) buildBtn).setImageResource(R.drawable.ic_build_play);
                binding.txtProjName.setText(proj.getName());
                buildBtn.setEnabled(true);
                appendLog("[index] selesai " + (success ? "" : "(gagal, fallback build)") + " -> tombol Build aktif.");
            });
        });
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        indexingThread = t;
    }

    private void selectTab(boolean err) {
        showingErr = err;
        int activeBg = getColor(com.ikram.apkbuild.R.color.surface_container);
        if (err) {
            binding.tabLog.setTextColor(getColor(com.ikram.apkbuild.R.color.on_surface_variant));
            binding.tabLog.setBackgroundColor(0x00000000);
            binding.tabProblem.setTextColor(getColor(com.ikram.apkbuild.R.color.error));
            binding.tabProblem.setBackgroundColor(activeBg);
            binding.txtConsole.setText(errBuf.toString());
        } else {
            binding.tabProblem.setTextColor(getColor(com.ikram.apkbuild.R.color.on_surface_variant));
            binding.tabProblem.setBackgroundColor(0x00000000);
            binding.tabLog.setTextColor(getColor(com.ikram.apkbuild.R.color.primary));
            binding.tabLog.setBackgroundColor(activeBg);
            binding.txtConsole.setText(logBuf.toString());
        }
        autoScroll();
    }


    /** Toggle seluruh panel konsol: sembunyikan (editor penuh) atau buka lagi. */
    private String consoleKey() { return "console_open_" + proj.getName(); }

    private void toggleConsole() {
        consoleOpen = !consoleOpen;
        prefs().edit().putBoolean(consoleKey(), consoleOpen).apply();
        binding.consolePanel.setVisibility(consoleOpen ? View.VISIBLE : View.GONE);
        binding.btnToggleConsole.setImageResource(consoleOpen ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);
    }

    /** Tampilkan/sembunyikan progress bar build. */
    private void setBuildProgress(boolean on) {
        binding.buildProgress.setVisibility(on ? View.VISIBLE : View.GONE);
        ((android.widget.ImageButton) buildBtn).setImageResource(on ? R.drawable.ic_build_spinner : R.drawable.ic_build_play);
        buildBtn.setEnabled(!on);
        buildBtn.clearAnimation();
        if (on) {
            android.view.animation.RotateAnimation rot = new android.view.animation.RotateAnimation(
                    0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            rot.setDuration(1200);
            rot.setRepeatCount(android.view.animation.Animation.INFINITE);
            rot.setInterpolator(new android.view.animation.LinearInterpolator());
            buildBtn.startAnimation(rot);
        }
    }

    // Util.Log
    @Override public void log(String msg) { appendLog(msg); }
    @Override public void problem(String msg) {
        // Tulis ke KEDUA buffer: sebelumnya hanya errBuf, jadi tab log (logBuf) berhenti mendadak
        // tanpa pesan - urutan kejadian hilang dan penyebab error tak terlihat di console.
        logBuf.append("[!] ").append(msg).append(0x0A);
        final boolean flushLog = !showingErr;
        errorCount++;
        errBuf.append("[!] ").append(msg).append('\n');
        runOnUiThread(() -> {
            if (flushLog) binding.txtConsole.setText(logBuf.toString());
            binding.tabProblem.setText("PROBLEM (" + errorCount + ")");
            if (showingErr) binding.txtConsole.setText(errBuf.toString());
            autoScroll();
        });
    }

    private long lastLogUi = 0L;

    private void appendLog(final String s) {
        logBuf.append(s).append('\n');
        final boolean throttle = indexing && (System.currentTimeMillis() - lastLogUi) < 400L;
        lastLogUi = System.currentTimeMillis();
        if (throttle) return; // saat indexing, jangan spam UI: buffer tetap penuh, UI refresh tiap 400ms
        runOnUiThread(() -> {
            if (binding == null) return;
            if (!showingErr) binding.txtConsole.setText(logBuf.toString());
            autoScroll();
        });
    }
    private void autoScroll() {
        binding.scrollConsole.post(() -> binding.scrollConsole.fullScroll(View.FOCUS_DOWN));
    }

    // ---- file list ----
    private String rel(File base, File f) { return f.getPath().substring(base.getPath().length() + 1); }

    private void openConfig() { Intent i = new Intent(this, ModuleConfigActivity.class); i.putExtra("path", proj.getAbsolutePath()); startActivity(i); }

    /** menu Pengaturan: editor (ukuran font) vs APK (module config). */
    private void openEditorSettingsMenu() {
        String[] items = {"Editor - ukuran font", "APK - pengaturan proyek"};
        new AlertDialog.Builder(this)
                .setTitle("Pengaturan")
                .setItems(items, (d, which) -> {
                    if (which == 0) promptFontSize();
                    else openConfig();
                })
                .show();
    }

    // =========================================================== tree persist

    /** simpan daftar folder terbuka (path absolut) ke SharedPreferences proyek. */
    private void saveTreeState() {
        StringBuilder sb = new StringBuilder();
        for (String k : fileAdapter.getExpanded()) sb.append(k).append('\n');
        prefs().edit().putString(treeStateKey(), sb.toString()).apply();
    }

    /** buka kembali folder yg tersimpan, lalu auto-expand rantai folder tunggal. */
    private void restoreTreeState() {
        String raw = prefs().getString(treeStateKey(), "");
        fileAdapter.getExpanded().clear();
        if (!raw.isEmpty()) for (String line : raw.split("\n")) if (!line.isEmpty())
            fileAdapter.getExpanded().add(line);
        fileAdapter.rebuild();
        // ponytail: auto-expand hanya rantai folder tunggal; upgrade jk perlu expand semua
        fileAdapter.autoExpandSingle();
    }

    private String treeStateKey() { return "tree_" + proj.getName(); }
    private android.content.SharedPreferences prefs() { return getSharedPreferences("apkbuild_editor", MODE_PRIVATE); }

    // =========================================================== tab persist

    /** Simpan daftar tab terbuka + tab aktif ke SharedPreferences proyek (baris: path, header "*"). */
    private void saveTabs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < openPaths.size(); i++) {
            sb.append(openPaths.get(i)).append('\n');
        }
        sb.append(activeIndex);
        prefs().edit().putString(tabsStateKey(), sb.toString()).apply();
    }

    /** Buka kembali tab tersimpan sesuai posisi terakhir. */
    private void restoreTabs() {
        String raw = prefs().getString(tabsStateKey(), "");
        if (raw.isEmpty()) return;
        String[] lines = raw.split("\n");
        int active = -1;
        try { active = Integer.parseInt(lines[lines.length - 1]); } catch (Exception ignored) {}
        for (int i = 0; i < lines.length - 1; i++) {
            String rel = lines[i];
            if (rel.isEmpty()) continue;
            File f = new File(ProjectConfig.appDir(proj), rel);
            if (!f.isFile()) continue;
            openPaths.add(rel);
            addTabView(rel);
        }
        if (!openPaths.isEmpty()) {
            if (active < 0 || active >= openPaths.size()) active = openPaths.size() - 1;
            selectTabIndex(active);
        }
    }

    private String tabsStateKey() { return "tabs_" + proj.getName(); }

    // =========================================================== long-press menu

    /** menu long-press node: file/folder baru, rename, hapus, salin path. */
    private void promptFileMenu(File f, boolean isDir) {
        String[] items = isDir
                ? new String[]{"File baru", "Folder baru", "Rename", "Hapus", "Salin path"}
                : new String[]{"Rename", "Hapus", "Salin path"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (sel.equals("File baru")) promptNew(f, false);
                    else if (sel.equals("Folder baru")) promptNew(f, true);
                    else if (sel.equals("Rename")) promptRename(f);
                    else if (sel.equals("Hapus")) confirmDelete(f);
                    else copyPath(f);
                })
                .show();
    }

    /** dialog nama utk file/folder baru di dalam dir. */
    private void promptNew(File dir, boolean folder) {
        EditText et = new EditText(this);
        et.setHint(folder ? "nama_folder" : "Nama.java");
        new AlertDialog.Builder(this)
                .setTitle((folder ? "Folder baru di " : "File baru di ") + dir.getName())
                .setView(et)
                .setPositiveButton("Buat", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) { toast("Nama kosong"); return; }
                    File target = new File(dir, name);
                    try {
                        if (folder) { if (!target.mkdirs()) throw new Exception("gagal mkdir"); }
                        else { target.getParentFile().mkdirs(); Util.writeText(target, ""); }
                        refreshTreeKeepState();
                    } catch (Exception e) { toast("Gagal: " + e.getMessage()); }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void promptRename(File f) {
        EditText et = new EditText(this);
        et.setText(f.getName());
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(et)
                .setPositiveButton("Simpan", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/")) { toast("Nama tidak valid"); return; }
                    File target = new File(f.getParentFile(), name);
                    if (target.exists()) { toast("Sudah ada: " + name); return; }
                    if (f.renameTo(target)) refreshTreeKeepState();
                    else toast("Gagal rename");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus")
                .setMessage("Hapus \"" + f.getName() + "\"" + (f.isDirectory() ? " beserta isinya?" : "?"))
                .setPositiveButton("Hapus", (d, w) -> {
                    try { Util.deleteRecursive(f); closeTabsUnder(f); refreshTreeKeepState(); }
                    catch (Exception e) { toast("Gagal hapus: " + e.getMessage()); }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** tutup tab yg menunjuk file di dalam f (kalau f dihapus/rename). */
    private void closeTabsUnder(File f) {
        String base = ProjectConfig.appDir(proj).getAbsolutePath();
        for (int i = openPaths.size() - 1; i >= 0; i--) {
            File tf = new File(ProjectConfig.appDir(proj), openPaths.get(i));
            if (tf.getAbsolutePath().startsWith(f.getAbsolutePath())) closeTab(i);
        }
    }

    private void copyPath(File f) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("path", f.getAbsolutePath()));
        toast("Path disalin");
    }

    /** refresh tree tanpa menutup folder yang sedang terbuka. */
    private void refreshTreeKeepState() {
        java.util.Set<String> keep = new java.util.HashSet<>(fileAdapter.getExpanded());
        fileAdapter.rebuild();
        fileAdapter.getExpanded().clear();
        fileAdapter.getExpanded().addAll(keep);
        fileAdapter.rebuild();
        saveTreeState();
    }

    // =========================================================== folder browser + settings

    /** telusuri seluruh file proyek (di luar app/src/main) dalam dialog datar. */
    private void openFolderBrowser() {
        final File root = proj;
        final List<String> rels = new ArrayList<>();
        collectAll(root, root, rels);
        new AlertDialog.Builder(this)
                .setTitle("File proyek")
                .setItems(rels.toArray(new String[0]), (d, which) -> {
                    String r = rels.get(which);
                    openFile(r.startsWith("app/src/main/") ? r.substring("app/src/main/".length()) : r);
                    drawer.closeDrawer(GravityCompat.START);
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void collectAll(File root, File dir, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, java.util.Comparator.comparing(File::getName));
        for (File f : fs) {
            if (f.isDirectory()) collectAll(root, f, out);
            else out.add(f.getPath().substring(root.getPath().length() + 1));
        }
    }

    /** drag singkat pengaturan proyek: nama paket, version, sdk, versi java. */
    private void openProjectSettings() { openConfig(); }


    /** Tombol Library: dialog centang library terunduh; langsung simpan ke module.toml. */
    private void promptLibraries() {
        ProjectConfig cfg = ProjectConfig.load(proj);
        if (cfg == null) { toast("Tidak ada module.toml"); return; }
        ModuleConfigActivity.promptLibraries(this, cfg, null, true);
    }
    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String all = (showingErr ? errBuf : logBuf).toString();
        cm.setPrimaryClip(ClipData.newPlainText("log", all));
        // tulis juga ke file supaya mudah dibuka/dibaca utuh (konsol TextView tak selalu render semua baris)
        try {
            File app = ProjectConfig.appDir(proj);
            File f = new File(app, "build/console.log");
            f.getParentFile().mkdirs();
            Util.writeText(f, all);
            toast("Disalin + " + f.getAbsolutePath());
        } catch (Exception e) {
            toast("Disalin");
        }
    }

    private void startBuild() {
        if (indexing) { toast("Menunggu indexing selesai"); return; }
        setBuildProgress(true);
        saveActiveFile();
        buildBtn.setEnabled(false);
        errBuf.setLength(0); errorCount = 0;
        selectTab(false);
        appendLog("\n============= BUILD " + proj.getName() + " =============");
        appendLog("[mulai] " + java.text.SimpleDateFormat.getDateTimeInstance().format(new java.util.Date()));
        new Thread(() -> {
            try {
                BuildPipeline pl = new BuildPipeline(this, tc, this);
                lastPipeline = pl;
                boolean ok = pl.build(proj);
                if (ok) {
                    appendLog("\n[selesai] SUKSES");
                    final File apk = ProjectConfig.outApk(proj);
                    appendLog("-> " + apk.getAbsolutePath());
                    if (apk.exists()) runOnUiThread(() -> installApk(apk));
                } else {
                    appendLog("\n[selesai] GAGAL - APK dibatalkan (ada error).");
                }
            } catch (Exception ex) {
                problem("build exception: " + ex);
            } finally {
                runOnUiThread(() -> { setBuildProgress(false); selectTab(errorCount > 0); });
            }
        }).start();
    }

    /**
     * Pasang APK hasil build. Dua jalur, dan URUTANNYA PENTING:
     *
     * 1) `pm install -r` lewat shell. Ini yang benar: "-r" = REPLACE paket yang sudah ada, jadi
     *    APK baru benar-benar dipakai walau versionCode-nya sama (module.toml proyek user umumnya
     *    tetap "versionCode = 1"). Lewat installer sistem (jalur 2), versionCode yang sama sering
     *    dibiarkan "sudah terpasang" sehingga APK LAMA tetap jalan - gejalanya: build sukses, tapi
     *    aplikasi tetap crash karena kode lama mencari id/view yang baru saja ditambahkan.
     * 2) Bila shell gagal (pm tak ada / izin), baru buka installer sistem seperti semula.
     */
    private void installApk(File apk) {
        if (installViaPm(apk)) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.ikram.apkbuild.fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            appendLog("[install] pm gagal -> membuka installer...");
            startActivity(i);
        } catch (Exception e) {
            appendLog("[install] gagal buka installer: " + e.getMessage());
            // fallback: minta izin install unknown apps
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    appendLog("[install] beri izin 'install aplikasi tidak dikenal' lalu build ulang.");
                } catch (Exception ex2) {
                    problem("[install] " + ex2.getMessage());
                }
            }
        }
    }

    /** Salin file (APK) ke /data/local/tmp agar bisa dibaca system_server. */
    private void copyTo(File src, File dst) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream os = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** `pm install -r <apk>`: penggantian paket eksplisit. Return true bila exit code 0.
     *
     *  APK di /storage/emulated/0 TIDAK bisa dibaca system_server: itu mount FUSE
     *  (u:object_r:fuse:s0), jadi `pm` gagal dengan "Can't open file". Salin dulu ke
     *  /data/local/tmp (world-readable, konteks shell_data_file) lalu install dari sana. */
    private boolean installViaPm(File apk) {
        // SELinux: system_server (yang menjalankan `pm`) HANYA boleh membaca file berkonteks
        // shell_data_file / tmpfs. Dua lokasi ini TERBUKTI GAGAL dan tak usah dicoba:
        //   - /storage/emulated/0 : FUSE  (u:object_r:fuse:s0)
        //   - getCacheDir()/getFilesDir() : u:object_r:app_data_file  ->  "Unknown class
        //     fileSystem server has no access to read file context ... app_data_file"
        // Jadi HANYA /data/local/tmp yang layak dicoba lewat path.
        File tmp = new File("/data/local/tmp/apkbuild-install.apk");
        boolean copied = false;
        try {
            copyTo(apk, tmp);
            // Beri izin baca (0644) supaya system_server bisa membukanya.
            try { tmp.setReadable(true, false); } catch (Throwable ignored) {}
            copied = true;
            appendLog("[install] salin ke " + tmp.getAbsolutePath());
        } catch (Exception e) {
            appendLog("[install] gagal salin ke " + tmp.getAbsolutePath() + ": " + e.getMessage());
        }
        // Jalur 1: `cmd package install` dari /data/local/tmp (konteks shell_data_file).
        if (copied && runPm(java.util.Arrays.asList("/system/bin/cmd", "package", "install", "-r", "-d", tmp.getAbsolutePath()))) {
            return true;
        }
        // Jalur 2 (paling andal): `pm install -S <size>` + APK dialirkan lewat stdin. `pm` tak
        // membuka path sama sekali, jadi kebal pembatasan SELinux di atas.
        appendLog("[install] coba pm install -S (stream stdin)...");
        return runPmStream(apk);
    }

    /** Jalankan `pm`, catat output, return true bila exit code 0. */
    private boolean runPm(java.util.List<String> cmd) {
        try {
            java.lang.Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            int code = pr.waitFor();
            appendLog("[install] pm exit=" + code + " " + out.toString().trim());
            return code == 0;
        } catch (Exception e) {
            appendLog("[install] pm gagal: " + e.getMessage());
            return false;
        }
    }

    /** `cmd package install -S <size>` lalu salin byte APK ke stdin. Bekerja walau file APK
     *  tak bisa dibuka system_server (tak ada akses path).
     *
     *  PENTING: pakai /system/bin/cmd, BUKAN /system/bin/pm. `pm` hanyalah shell script tipis
     *  yang memanggil `cmd package ...`; script itu TIDAK meneruskan stdin ke `cmd`, sehingga
     *  jalur -S gagal dengan EPIPE (Broken pipe) - persis yang terlihat di console.log. */
    private boolean runPmStream(File apk) {
        java.lang.Process pr = null;
        try {
            java.util.List<String> cmd = java.util.Arrays.asList(
                    "/system/bin/cmd", "package", "install", "-r", "-d",
                    "-S", String.valueOf(apk.length()));
            pr = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            try (java.io.InputStream in = new java.io.FileInputStream(apk);
                 java.io.OutputStream os = pr.getOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } catch (Exception e) {
                appendLog("[install] stream gagal: " + e.getMessage());
                if (pr != null) pr.destroy();
                return false;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            int code = pr.waitFor();
            appendLog("[install] pm -S exit=" + code + " " + out.toString().trim());
            return code == 0;
        } catch (Exception e) {
            appendLog("[install] pm -S gagal: " + e.getMessage());
            if (pr != null) pr.destroy();
            return false;
        }
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
}
