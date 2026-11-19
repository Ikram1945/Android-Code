package com.ikram.apkbuild.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/** Adapter daftar library utk LibraryActivity: label + status (unduh/cache). */
public class LibraryAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private List<String[]> items;   // [group, artifact, version]

    public LibraryAdapter(Context ctx, List<String[]> items) {
        this.inflater = LayoutInflater.from(ctx);
        this.items = items;
    }

    public void update(List<String[]> newItems) { this.items = newItems; notifyDataSetChanged(); }

    @Override public int getCount() { return items.size(); }
    @Override public String[] getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
        }
        String[] lib = items.get(position);
        TextView t1 = convert.findViewById(android.R.id.text1);
        TextView t2 = convert.findViewById(android.R.id.text2);
        if (t1 != null) t1.setText(lib[1] + " : " + lib[2]);
        if (t2 != null) t2.setText(lib[0]);
        return convert;
    }
}
