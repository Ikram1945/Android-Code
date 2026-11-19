package com.ikram.apkbuild.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.ikram.apkbuild.R;

import java.io.File;
import java.util.List;

/** Adapter daftar proyek (folder di direktori proyek). */
public class ProjectListAdapter extends ArrayAdapter<File> {
    private final LayoutInflater inflater;

    public ProjectListAdapter(Context ctx, List<File> items) {
        super(ctx, 0, items);
        inflater = LayoutInflater.from(ctx);
    }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        if (cv == null) cv = inflater.inflate(R.layout.item_project, parent, false);
        TextView name = cv.findViewById(R.id.txtName);
        TextView path = cv.findViewById(R.id.txtPath);
        File f = getItem(pos);
        name.setText(f.getName());
        path.setText(f.getAbsolutePath());
        return cv;
    }
}
