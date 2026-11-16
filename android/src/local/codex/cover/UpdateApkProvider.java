package local.codex.cover;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only URI grant for the verified APK. No file path is accepted from callers. */
public final class UpdateApkProvider extends ContentProvider {
    public static final String AUTHORITY = "local.codex.cover.updates";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/apk");

    private File apk(Uri uri) throws FileNotFoundException {
        if (!URI.equals(uri)) throw new FileNotFoundException("Unsupported update URI");
        File file = new File(new File(getContext().getCacheDir(), "updates"), "codex-cover.apk");
        if (!file.isFile()) throw new FileNotFoundException("Verified update not found");
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return URI.equals(uri) ? "application/vnd.android.package-archive" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                                  String sortOrder) {
        try {
            File file = apk(uri);
            String[] columns = projection != null ? projection :
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor result = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "codex-cover.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
            return result;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read-only");
    }
}
