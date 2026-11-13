package dev.polymorph.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class PolyFiles extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    File file(Uri u) {
        return new File(new File(getContext().getCacheDir(), "cam"), new File(u.getLastPathSegment()).getName());
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = file(u);
        f.getParentFile().mkdirs();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri u) { return "image/jpeg"; }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        File f = file(u);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
