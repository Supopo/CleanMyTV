package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Grid of launchable apps for {@link NewMainActivity}. */
final class NewHomeAdapter extends RecyclerView.Adapter<NewHomeAdapter.Holder> {
    /** Sentinel id for the "More" tile that opens overflow apps. */
    static final String MORE_ID = "newhome:more";
    /** Focused icon grows a little so the selection reads without a tile frame. */
    private static final float FOCUS_SCALE = 1.18f;
    /** Corner radius as a fraction of the icon edge so every tile matches. */
    private static final float ICON_CORNER = 0.22f;
    /** Shared icon / More-plate opacity (~80%). */
    private static final int ICON_ALPHA = 0xCC;

    interface Host {
        Map<String, NewHomeApps.Entry> apps();
        Set<String> pinned();
        float density();
        void onAppFocused(NewHomeApps.Entry app);
        void onAppClick(NewHomeApps.Entry app);
        void onAppEdit(NewHomeApps.Entry app);
        boolean onAppKey(NewHomeApps.Entry app, int key, KeyEvent event);
        void onMoreFocused();
        void onMoreClick();
        /** D-pad within the shelf; return true if the press was handled (including blocked edges). */
        boolean onShelfKey(int position, int key, KeyEvent event);
    }

    private final Host host;
    private List<String> order = Collections.emptyList();
    private int tileSize;
    private int iconSize;
    private int gap;
    /** Package that resolves {@link Settings#ACTION_SETTINGS}, if any. */
    private static String settingsPackage;

    NewHomeAdapter(Host host) {
        this.host = host;
        setHasStableIds(true);
    }

    void submit(List<String> next) {
        order = new ArrayList<>(next);
        notifyDataSetChanged();
    }

    void setMetrics(int tileSize, int iconSize, int gap) {
        if (this.tileSize == tileSize && this.iconSize == iconSize && this.gap == gap) return;
        this.tileSize = tileSize;
        this.iconSize = iconSize;
        this.gap = gap;
        notifyDataSetChanged();
    }

    int indexOf(String id) {
        return order.indexOf(id);
    }

    String idAt(int position) {
        return position >= 0 && position < order.size() ? order.get(position) : null;
    }

    static boolean isMore(String id) {
        return MORE_ID.equals(id);
    }

    @Override public long getItemId(int position) {
        String id = idAt(position);
        return id == null ? RecyclerView.NO_ID : id.hashCode();
    }

    @Override public int getItemCount() { return order.size(); }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout card = new FrameLayout(parent.getContext());
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setBackground(null);
        ImageView icon = new ImageView(parent.getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setAdjustViewBounds(false);
        icon.setCropToPadding(false);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                if (w <= 0 || h <= 0) {
                    outline.setEmpty();
                    return;
                }
                float radius = Math.min(w, h) * ICON_CORNER;
                outline.setRoundRect(0, 0, w, h, radius);
            }
        });
        card.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        card.setLayoutParams(new RecyclerView.LayoutParams(
                Math.max(tileSize, 1), Math.max(tileSize, 1)));
        Holder holder = new Holder(card, icon);
        card.setOnFocusChangeListener((view, focused) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            String id = order.get(pos);
            if (focused) {
                if (isMore(id)) host.onMoreFocused();
                else {
                    NewHomeApps.Entry app = host.apps().get(id);
                    if (app != null) host.onAppFocused(app);
                }
            }
            animateIcon(holder.icon, focused);
        });
        card.setOnClickListener(view -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            String id = order.get(pos);
            if (isMore(id)) host.onMoreClick();
            else {
                NewHomeApps.Entry app = host.apps().get(id);
                if (app != null) host.onAppClick(app);
            }
        });
        card.setOnLongClickListener(view -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return true;
            String id = order.get(pos);
            if (isMore(id)) return true;
            NewHomeApps.Entry app = host.apps().get(id);
            if (app != null) host.onAppEdit(app);
            return true;
        });
        card.setOnKeyListener((view, key, event) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            if (host.onShelfKey(pos, key, event)) return true;
            String id = order.get(pos);
            if (isMore(id)) return false;
            NewHomeApps.Entry app = host.apps().get(id);
            return app != null && host.onAppKey(app, key, event);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        String id = order.get(position);
        int size = Math.max(tileSize, 1);
        int inset = Math.max(gap, 0);
        int iconPx = Math.max(iconSize, 1);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (params == null) {
            params = new RecyclerView.LayoutParams(size, size);
            holder.itemView.setLayoutParams(params);
        } else {
            params.width = size;
            params.height = size;
        }
        params.setMargins(inset, inset, inset, inset);
        holder.itemView.setBackground(null);
        FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) holder.icon.getLayoutParams();
        iconParams.width = iconPx;
        iconParams.height = iconPx;
        iconParams.gravity = Gravity.CENTER;
        holder.icon.setLayoutParams(iconParams);
        holder.icon.invalidateOutline();

        if (isMore(id)) {
            holder.itemView.setContentDescription("更多");
            // Opaque plate; overall fade is View alpha (setImageAlpha is flaky on some OEM icons).
            applySoftPlate(holder.icon, iconPx, Math.max(iconPx / 4, Math.round(10 * host.density())), true);
            holder.icon.setImageResource(R.drawable.ic_more);
        } else {
            NewHomeApps.Entry app = host.apps().get(id);
            if (app == null) {
                holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.icon.setPadding(0, 0, 0, 0);
                holder.icon.setBackground(null);
                holder.icon.setImageDrawable(null);
            } else {
                boolean pinned = host.pinned().contains(id);
                holder.itemView.setContentDescription(app.label + (pinned ? "，已置顶" : ""));
                // Middle-menu Settings app only (not the top-bar Settings pill).
                if (isSettingsApp(app, holder.itemView.getContext())) {
                    styleSettingsIcon(holder.icon, app, iconPx, host.density());
                } else {
                    holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    holder.icon.setPadding(0, 0, 0, 0);
                    holder.icon.setBackground(null);
                    holder.icon.setImageDrawable(app.icon);
                }
            }
        }
        // Every tile (More / Settings / apps, both rows) gets the same overall fade.
        applyIconAlpha(holder.icon);
        float scale = holder.itemView.isFocused() ? FOCUS_SCALE : 1f;
        holder.icon.setScaleX(scale);
        holder.icon.setScaleY(scale);
    }

    private static void applyIconAlpha(ImageView icon) {
        icon.setImageAlpha(0xFF);
        icon.setAlpha(ICON_ALPHA / 255f);
    }

    private static void applySoftPlate(ImageView icon, int size, int pad, boolean opaque) {
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(pad, pad, pad, pad);
        icon.setBackground(softPlate(size, opaque));
    }

    /** Frosted plate for More; no stroke. */
    private static GradientDrawable softPlate(int size, boolean opaque) {
        int soft = opaque ? 0xFFF2F5FA : (ICON_ALPHA << 24) | 0x00FFFFFF;
        int softer = opaque ? 0xFFDDE3EE : (0x28 << 24) | 0x00FFFFFF;
        GradientDrawable plate = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{soft, softer});
        plate.setCornerRadius(size * ICON_CORNER);
        return plate;
    }

    /** Solid rounded background for the Settings app icon; no stroke. */
    static GradientDrawable roundedIconBackground(int size) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(0xFFE8EEF6);
        bg.setCornerRadius(size * ICON_CORNER);
        return bg;
    }

    /** Rounded plate + inset glyph for Settings on the home grid and in More. */
    static void styleSettingsIcon(ImageView icon, NewHomeApps.Entry app, int iconPx, float density) {
        int pad = Math.max(iconPx / 5, Math.round(8 * density));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(pad, pad, pad, pad);
        icon.setBackground(roundedIconBackground(iconPx));
        icon.setImageDrawable(app.icon);
    }

    /** Clears Settings chrome when binding a normal app icon. */
    static void clearSettingsIconStyle(ImageView icon) {
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setPadding(0, 0, 0, 0);
        icon.setBackground(null);
    }

    /** True for the system Settings tile that appears in the app grid / More. */
    static boolean isSettingsApp(NewHomeApps.Entry app, Context context) {
        if (app == null) return false;
        String label = app.label == null ? "" : app.label.trim();
        if ("设置".equals(label) || "系统设置".equals(label) || "电视设置".equals(label)
                || "Settings".equalsIgnoreCase(label)
                || "TV settings".equalsIgnoreCase(label)
                || "System settings".equalsIgnoreCase(label)) {
            return true;
        }
        String pkg = app.packageName == null ? "" : app.packageName;
        String cls = app.component == null ? "" : app.component.getClassName();
        if (pkg.equals("com.android.settings")
                || pkg.equals("com.android.tv.settings")
                || pkg.equals("com.xiaomi.mitv.settings")
                || pkg.endsWith(".tv.settings")
                || pkg.endsWith(".tvsettings")
                || cls.contains("mitv.settings")) {
            return true;
        }
        if (settingsPackage == null) {
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
            settingsPackage = info != null && info.activityInfo != null
                    ? info.activityInfo.packageName : "";
        }
        return !settingsPackage.isEmpty() && settingsPackage.equals(pkg);
    }

    private static void animateIcon(View icon, boolean focused) {
        icon.animate().cancel();
        float scale = focused ? FOCUS_SCALE : 1f;
        icon.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        Holder(FrameLayout card, ImageView icon) {
            super(card);
            this.icon = icon;
        }
    }
}
