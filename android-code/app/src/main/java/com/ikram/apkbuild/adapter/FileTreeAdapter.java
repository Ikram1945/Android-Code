package com.ikram.apkbuild.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ikram.apkbuild.FileIconManager;
import com.ikram.apkbuild.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter drawer tree FLAT (ListView + depth per baris). Meniru drawer Android Code
 * Studio: folder bisa dibuka/tutup di kedalaman berapa pun (ExpandableListView cuma
 * 2 level, jadi tidak dipakai). Baris = folder atau file; klik folder = toggle lalu
 * rebuild daftar. Indent & chevron dari depth.
 */
public class FileTreeAdapter extends BaseAdapter {

    /** satu baris tampil. */
    public static class Row {
        public final File file;
        public final int depth;
        public final boolean dir;
        Row(File f, int depth, boolean dir) { this.file = f; this.depth = depth; this.dir = dir; }
    }

    public interface OnFileClick { void onOpen(File f); }
    public interface OnFileLongClick { void onMenu(File f, boolean isDir, int depth); }

    private final Context ctx;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();   // path absolut folder terbuka
    private File root = null;
    private OnFileClick onFileClick;
    private OnFileLongClick onLongClick;

    public FileTreeAdapter(Context ctx, OnFileClick onFileClick) {
        this.ctx = ctx;
        this.onFileClick = onFileClick;
    }

    public void setOnFileClick(OnFileClick l) { this.onFileClick = l; }
    public void setOnFileLongClick(OnFileLongClick l) { this.onLongClick = l; }
    public Set<String> getExpanded() { return expanded; }
    public void setExpanded(File dir, boolean open) { if (open) expanded.add(key(dir)); else expanded.remove(key(dir)); }
    private String key(File f) { return f.getAbsolutePath(); }

    /** set root baru (mis. app/src/main), reset state folder terbuka. */
    public void load(File base) {
        this.root = base;
        expanded.clear();
        rebuild();
    }

    /** bangun ulang daftar baris dari root + set folder terbuka. */
    public void rebuild() {
        rows.clear();
        if (root != null && root.isDirectory()) {
            int startDepth = 0;
            for (File f : sorted(root.listFiles())) addRow(f, startDepth);
        }
        notifyDataSetChanged();
    }

    private void addRow(File f, int depth) {
        boolean dir = f.isDirectory();
        rows.add(new Row(f, depth, dir));
        if (dir && expanded.contains(key(f))) {
            for (File c : sorted(f.listFiles())) addRow(c, depth + 1);
        }
    }

    private static List<File> sorted(File[] fs) {
        List<File> l = new ArrayList<>();
        if (fs == null) return l;
        Collections.addAll(l, fs);
        Collections.sort(l, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return l;
    }

    /** buka otomatis rantai folder tunggal (mirip ACS listNode). */
    public void autoExpandSingle() {
        if (root == null) return;
        File cur = null;
        for (File f : sorted(root.listFiles())) { cur = f; break; }
        int guard = 0;
        while (cur != null && cur.isDirectory() && guard++ < 64) {
            File[] fs = cur.listFiles();
            int dirs = 0; File only = null; boolean hasFile = false;
            for (File f : fs == null ? new File[0] : fs) {
                if (f.isDirectory()) { dirs++; only = f; } else hasFile = true;
            }
            if (dirs == 1 && !hasFile) { expanded.add(key(cur)); cur = only; }
            else break;
        }
        if (guard > 0) rebuild();
    }

    // ---- BaseAdapter ----
    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int i) { return rows.get(i); }
    @Override public long getItemId(int i) { return i; }
    @Override public boolean hasStableIds() { return false; }

    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int i) { return rows.get(i).dir ? 0 : 1; }

    @Override public View getView(int i, View v, ViewGroup parent) {
        Row r = rows.get(i);
        // view didaur-ulang: tipe beda (folder vs file) -> inflate ulang, jangan pakai bekas
        if (v != null && (!java.util.Objects.equals(v.getTag(), r.dir ? 0 : 1))) v = null;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(
                    r.dir ? R.layout.item_filetree_group : R.layout.item_filetree_child, parent, false);
            v.setTag(r.dir ? 0 : 1);
        }

        ((TextView) v.findViewById(R.id.treeName)).setText(r.file.getName());
        int indent = 12 * r.depth + 8;

        if (r.dir) {
            boolean open = expanded.contains(key(r.file));
            ImageView chev = v.findViewById(R.id.treeChevron);
            chev.setImageResource(open ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);
            ProgressBar spin = v.findViewById(R.id.treeLoading);
            if (spin != null) spin.setVisibility(View.GONE);
            ((ImageView) v.findViewById(R.id.treeIcon)).setImageResource(R.drawable.ic_folder);
            v.setOnClickListener(x -> { setExpanded(r.file, !open); rebuild(); });
        } else {
            ((ImageView) v.findViewById(R.id.treeIcon))
                    .setImageResource(FileIconManager.forFile(r.file.getName()));
            v.setOnClickListener(x -> { if (onFileClick != null) onFileClick.onOpen(r.file); });
        }
        v.setOnLongClickListener(x -> {
            if (onLongClick != null) onLongClick.onMenu(r.file, r.dir, r.depth);
            return true;
        });
        v.setPaddingRelative(dp(indent), dp(8), dp(8), dp(8));
        return v;
    }

    private int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density); }
}
