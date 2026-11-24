package emu.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Keeps imported ROMs in app-private storage and remembers what was played. */
public final class RomLibrary {

    private static final String PREFS = "milliemu";
    private static final String KEY_RECENT = "recent_roms";

    private final Context context;
    private final File romDir;
    private final File stateDir;

    public RomLibrary(Context context) {
        this.context = context.getApplicationContext();
        this.romDir = new File(this.context.getFilesDir(), "roms");
        this.stateDir = new File(this.context.getFilesDir(), "states");
        if (!romDir.exists()) {
            romDir.mkdirs();
        }
        if (!stateDir.exists()) {
            stateDir.mkdirs();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public File importRom(Uri uri, String displayName) throws IOException {
        String safeName = sanitize(displayName);
        File target = new File(romDir, safeName);
        ContentResolver resolver = context.getContentResolver();
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            throw new IOException("could not open the selected file");
        }
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        remember(target);
        return target;
    }

    public void remember(File rom) {
        List<String> paths = rawRecents();
        paths.remove(rom.getAbsolutePath());
        paths.add(0, rom.getAbsolutePath());
        while (paths.size() > 12) {
            paths.remove(paths.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(path);
        }
        prefs().edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    public void forget(File rom) {
        List<String> paths = rawRecents();
        paths.remove(rom.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(path);
        }
        prefs().edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    public List<File> recentRoms() {
        List<File> result = new ArrayList<File>();
        for (String path : rawRecents()) {
            File file = new File(path);
            if (file.exists()) {
                result.add(file);
            }
        }
        return result;
    }

    private List<String> rawRecents() {
        List<String> result = new ArrayList<String>();
        String stored = prefs().getString(KEY_RECENT, "");
        if (stored != null && stored.length() > 0) {
            for (String line : stored.split("\n")) {
                if (line.trim().length() > 0) {
                    result.add(line.trim());
                }
            }
        }
        return result;
    }

    public File stateFileFor(File rom) {
        return new File(stateDir, sanitize(rom.getName()) + ".state");
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && name.length() > 0) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall back to the path segment below
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "rom.nes" : last;
    }

    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() == 0) {
            cleaned = "rom";
        }
        return cleaned.length() > 96 ? cleaned.substring(cleaned.length() - 96) : cleaned;
    }
}
