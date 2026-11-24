package com.runninghub.clearstudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Minimal ContentProvider serving processed PNG exports strictly from cache/outputs/.
 * Avoids external AndroidX dependencies while supporting system Share intents safely.
 */
public final class ShareFileProvider extends ContentProvider {

    public static Uri uriForOutput(String authority, String id, String filename) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("outputs")
                .appendPath(id)
                .appendPath(filename)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null) {
            throw new FileNotFoundException("Invalid context or URI");
        }
        List<String> segs = uri.getPathSegments();
        if (segs == null || segs.size() != 3 || !"outputs".equals(segs.get(0))) {
            throw new FileNotFoundException("Unsupported path");
        }
        String id = segs.get(1);
        String name = segs.get(2);
        if (id.isEmpty() || name.isEmpty() || id.contains("..") || name.contains("..")
                || id.contains("/") || name.contains("/") || id.contains("\\") || name.contains("\\")) {
            throw new FileNotFoundException("Unsafe path segment");
        }
        File base = new File(getContext().getCacheDir(), "outputs");
        File target = new File(new File(base, id), name);
        try {
            String baseCan = base.getCanonicalPath();
            String targetCan = target.getCanonicalPath();
            if (!targetCan.startsWith(baseCan + File.separator) || !target.isFile()) {
                throw new FileNotFoundException("File missing or out of bounds");
            }
            return target;
        } catch (IOException e) {
            throw new FileNotFoundException("Canonical resolution failed");
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveSafeFile(uri);
            String[] cols = (projection != null && projection.length > 0)
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
