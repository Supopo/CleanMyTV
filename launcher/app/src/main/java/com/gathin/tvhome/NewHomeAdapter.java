package com.gathin.tvhome;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
    }

    private final Host host;
    private List<String> order = Collections.emptyList();
    private int tileSize;
    private int iconSize;
    private int gap;

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
        ImageView icon = new ImageView(parent.getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAdjustViewBounds(false);
        icon.setCropToPadding(false);
        int pad = Math.round(6 * host.density());
        icon.setPadding(pad, pad, pad, pad);
        card.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
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
            view.setBackground(glass(focused, host.density()));
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? 10 * host.density() : 0).setDuration(160).start();
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
        FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) holder.icon.getLayoutParams();
        iconParams.width = iconPx;
        iconParams.height = iconPx;
        iconParams.gravity = Gravity.CENTER;
        holder.icon.setLayoutParams(iconParams);

        Drawable drawable;
        if (isMore(id)) {
            holder.itemView.setContentDescription("更多");
            drawable = holder.itemView.getResources().getDrawable(R.drawable.ic_apps, null);
        } else {
            NewHomeApps.Entry app = host.apps().get(id);
            if (app == null) return;
            boolean pinned = host.pinned().contains(id);
            holder.itemView.setContentDescription(app.label + (pinned ? "，已置顶" : ""));
            drawable = app.icon;
        }
        holder.icon.setImageDrawable(drawable);
        holder.itemView.setBackground(glass(holder.itemView.isFocused(), host.density()));
        holder.itemView.setScaleX(holder.itemView.isFocused() ? 1.08f : 1f);
        holder.itemView.setScaleY(holder.itemView.isFocused() ? 1.08f : 1f);
    }

    private static GradientDrawable glass(boolean focused, float density) {
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{0xFFF7FAFF, 0xFFDBE7FF}
                        : new int[]{0x38FFFFFF, 0x10FFFFFF});
        surface.setCornerRadius(20 * density);
        surface.setStroke(Math.max(1, Math.round(density)), focused ? 0xFFFFFFFF : 0x55FFFFFF);
        return surface;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        Holder(FrameLayout card, ImageView icon) {
            super(card);
            this.icon = icon;
        }
    }
}
