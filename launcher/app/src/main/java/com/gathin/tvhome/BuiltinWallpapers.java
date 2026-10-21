package com.gathin.tvhome;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Ships the scenes collection ({@code themes/scenes/wallpapers}) inside the APK and
 * installs missing files into the theme wallpapers folder so Home can pick them on-TV.
 */
final class BuiltinWallpapers {
    private static final String ASSET_DIR = "wallpapers";

    private BuiltinWallpapers() {}

    /** Copies any missing built-in images into {@code Config.dir/wallpapers}. */
    static void install(Context context) {
        File dir = Config.dir(context);
        if (dir == null) return;
        File out = new File(dir, ThemeFiles.WALLPAPERS);
        if (!out.isDirectory() && !out.mkdirs()) return;
        AssetManager assets = context.getAssets();
        String[] names;
        try {
            names = assets.list(ASSET_DIR);
        } catch (IOException ignored) {
            return;
        }
        if (names == null) return;
        byte[] buffer = new byte[16 * 1024];
        for (String name : names) {
            if (!ThemeFiles.collectionImage(name)) continue;
            File dest = new File(out, name);
            if (dest.isFile() && dest.length() > 0) continue;
            try (InputStream in = assets.open(ASSET_DIR + "/" + name);
                 OutputStream stream = new FileOutputStream(dest)) {
                int read;
                while ((read = in.read(buffer)) >= 0) stream.write(buffer, 0, read);
            } catch (IOException failed) {
                // Leave a partial file out of the picker.
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
    }

    /** Built-in (and any theme-pushed) images available for an on-TV wallpaper pick. */
    static List<File> images(Context context) {
        install(context);
        File dir = Config.dir(context);
        return WallpaperRotation.images(dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS));
    }

    /** Drop the {@code 01-} prefix and file extension for the picker row. */
    static String label(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 3 && Character.isDigit(base.charAt(0))
                && Character.isDigit(base.charAt(1)) && base.charAt(2) == '-') {
            base = base.substring(3);
        }
        return base.replace('-', ' ');
    }
}
