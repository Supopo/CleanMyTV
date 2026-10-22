package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/** On-TV picker: tap a thumbnail to set that wallpaper. */
final class WallpaperPicker {
    private static final int COLUMNS = 3;

    private WallpaperPicker() {}

    static void show(Activity activity) {
        List<File> images = BuiltinWallpapers.images(activity);
        if (images.isEmpty()) {
            Toast.makeText(activity, R.string.wallpaper_pick_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        // Remember last pick only for where focus starts; the ring itself is focus-only.
        int focusAt = Math.max(0, indexOf(images, current));

        RecyclerView grid = (RecyclerView) LayoutInflater.from(activity)
                .inflate(R.layout.dialog_wallpaper_picker, null);
        float density = activity.getResources().getDisplayMetrics().density;
        int thumbW = Math.round(220 * density);
        int thumbH = Math.round(124 * density);
        int inset = Math.max(3, Math.round(4 * density));
        grid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.wallpaper_pick)
                .setView(grid)
                .create();

        grid.setAdapter(new RecyclerView.Adapter<Holder>() {
            @NonNull @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_wallpaper, parent, false);
                Holder holder = new Holder(row);
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) holder.thumb.getLayoutParams();
                if (lp != null) {
                    lp.setMargins(inset, inset, inset, inset);
                    holder.thumb.setLayoutParams(lp);
                }
                row.setOnFocusChangeListener((v, focused) -> applyRing(v, focused));
                row.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    // recreate() runs while dialog had focus, so onPause cannot see Settings —
                    // persist the resume target for the new Activity instance.
                    choices.edit()
                            .putString(Config.WALLPAPER_CHOICE_KEY, images.get(pos).getName())
                            .putBoolean(Config.RESUME_FOCUS_SETTINGS_KEY, true)
                            .commit();
                    dialog.dismiss();
                    activity.recreate();
                });
                return holder;
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                File file = images.get(position);
                Bitmap bitmap = decodeThumb(file, thumbW, thumbH);
                if (bitmap != null) holder.thumb.setImageBitmap(bitmap);
                else holder.thumb.setImageDrawable(placeholder());
                holder.itemView.setContentDescription(BuiltinWallpapers.label(file.getName()));
                applyRing(holder.itemView, holder.itemView.isFocused());
            }

            @Override public int getItemCount() { return images.size(); }
        });

        dialog.setOnShowListener(d -> {
            grid.scrollToPosition(focusAt);
            Runnable focus = () -> {
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(focusAt);
                if (holder != null) holder.itemView.requestFocus();
                else grid.requestFocus();
            };
            grid.post(focus);
            grid.postDelayed(focus, 80);
        });
        // Back / cancel: return focus to Settings (opened from its long-press).
        dialog.setOnDismissListener(d -> {
            if (activity.isFinishing()) return;
            View settings = activity.findViewById(R.id.btnSettings);
            if (settings != null) settings.post(settings::requestFocus);
        });
        dialog.show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null || "none".equals(name)) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private static Bitmap decodeThumb(File file, int targetW, int targetH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW
                    && bounds.outHeight / (sample * 2) >= targetH) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static GradientDrawable placeholder() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xFF1A1F2A);
        fill.setCornerRadius(0);
        return fill;
    }

    /** Focus ring only — larger than the inset thumbnail; nothing left behind when focus moves. */
    private static void applyRing(View item, boolean focused) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(0);
        ring.setColor(focused ? 0xFFFFFFFF : 0x00000000);
        item.setBackground(ring);
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        Holder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumbnail);
        }
    }
}
