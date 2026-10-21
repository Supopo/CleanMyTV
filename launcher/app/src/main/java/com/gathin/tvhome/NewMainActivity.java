package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvContract;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A separate HOME page with the existing header and MiFlow-style app menu. */
public final class NewMainActivity extends Activity implements NewHomeAdapter.Host {
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** The new home only pins one favourite; a later pin replaces the previous one. */
    private static final int MAX_PINS = 1;
    /** Default home shows this many rows; the last cell becomes "更多" after an app is stashed. */
    private static final int HOME_ROWS = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Package / icon / wallpaper IO — keeps first focus on the shelf from stalling after install. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, NewHomeApps.Entry> apps = new HashMap<>();
    private int appsLoadGen;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (movingId == null) reloadApps();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            updateGreeting();
            updateNetworkLabel();
            SkyView sky = findViewById(R.id.sky);
            sky.update();
            handler.postDelayed(this, 60_000L);
        }
    };

    private Config config;
    private NewHomeStore store;
    private RecyclerView grid;
    private NewHomeAdapter adapter;
    private TextView greeting;
    private TextView btnWifi;
    private TextView title;
    private TextView hint;
    private View btnInputs;
    private View btnReorder;
    private View btnLibrary;
    private View btnSettings;
    private AppLibraryDialog library;
    private WallpaperPlayer wallpaperPlayer;
    private List<String> order = new ArrayList<>();
    private List<String> beforeMove;
    private Set<String> pinned = new HashSet<>();
    /** Apps the owner put into More; they leave the two-row shelf until shown again. */
    private Set<String> stashed = new HashSet<>();
    private String focusedId;
    private String movingId;
    private boolean chooseMove;
    private int columns;
    private float density;
    private boolean sizing;
    private AlertDialog moreDialog;
    /** Top-bar control that had focus when Home paused (Wi‑Fi / Settings / …). */
    private View topBarResumeFocus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_new_main);
        // First Studio install copies ~14 wallpapers; never block the first frame on that.
        io.execute(() -> BuiltinWallpapers.install(getApplicationContext()));
        config = Config.load(this);
        store = new NewHomeStore(this);
        density = getResources().getDisplayMetrics().density;
        columns = getResources().getConfiguration().screenWidthDp >= 800 ? 6 : 4;
        FrameLayout root = findViewById(R.id.root);
        root.setBackgroundColor(config.backgroundColor);
        wallpaperPlayer = new WallpaperPlayer(this, root, config);
        SkyView sky = findViewById(R.id.sky);
        sky.setIntensity(config.skyIntensity);
        grid = findViewById(R.id.grid);
        // The shelf is at most two rows and already fits. Default focus scrolling pulls a
        // bottom-aligned single row up into the spacer as soon as move mode refocuses it.
        grid.setLayoutManager(new GridLayoutManager(this, columns) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                    @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                return true;
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        adapter = new NewHomeAdapter(this);
        grid.setAdapter(adapter);
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updateTileMetrics();
        });
        greeting = findViewById(R.id.greeting);
        title = findViewById(R.id.newHomeTitle);
        hint = findViewById(R.id.newHomeHint);
        btnInputs = findViewById(R.id.btnInputs);
        btnWifi = findViewById(R.id.btnWifi);
        btnReorder = findViewById(R.id.btnReorder);
        btnLibrary = findViewById(R.id.btnLibraryTop);
        btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetwork());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnReorder.setOnClickListener(v -> {
            chooseMove = true;
            renderHome();
        });
        btnLibrary.setOnClickListener(v -> showLibrary());
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            WallpaperPicker.show(this);
            return true;
        });
        View.OnKeyListener topKeys = (view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            // While apps are still loading the shelf has no children; keep Down on the top bar
            // and retry focus once the grid is bound instead of dropping the press.
            if (adapter.getItemCount() == 0) return true;
            String id = focusedId != null ? focusedId : (order.isEmpty() ? null : order.get(0));
            if (id == null) id = adapter.idAt(0);
            if (id != null) focusApp(id);
            else grid.requestFocus();
            return true;
        };
        for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibrary, btnSettings})
            button.setOnKeyListener(topKeys);
        btnReorder.setVisibility(config.reorderButton ? View.VISIBLE : View.GONE);
        btnLibrary.setVisibility(config.libraryButton && config.appLibrary ? View.VISIBLE : View.GONE);
        btnWifi.setVisibility(config.showNetworkPill ? View.VISIBLE : View.GONE);
        if (config.iconOnlyButtons) {
            if (config.wifiLabelWidth > 0) btnWifi.setMaxWidth(dp(config.wifiLabelWidth));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (config.shamsiDate) {
            TextView shamsi = findViewById(R.id.shamsiDate);
            shamsi.setVisibility(View.VISIBLE);
            shamsi.setText(Shamsi.today());
        }
        updateGreeting();
        updateNetworkLabel();
        updateInputsButton();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);
        if (state == null) openLauncherSettingsIfAsked(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        wallpaperPlayer.start();
        if (movingId == null) reloadApps();
        updateInputsButton();
        handler.removeCallbacks(minuteTick);
        handler.post(minuteTick);
    }

    @Override protected void onPause() {
        View focus = getCurrentFocus();
        topBarResumeFocus = isTopBar(focus) ? focus : null;
        handler.removeCallbacks(minuteTick);
        wallpaperPlayer.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        appsLoadGen++;
        handler.removeCallbacks(minuteTick);
        unregisterReceiver(packages);
        if (library != null) library.close();
        wallpaperPlayer.close();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        cancelMove();
        chooseMove = false;
        renderHome();
    }

    @Override public void onBackPressed() {
        if (movingId != null) {
            cancelMove();
            renderHome();
        } else if (chooseMove) {
            chooseMove = false;
            renderHome();
        } else if (!order.isEmpty()) {
            focusedId = order.get(0);
            focusApp(focusedId);
        }
    }

    @Override public Map<String, NewHomeApps.Entry> apps() { return apps; }
    @Override public Set<String> pinned() { return pinned; }
    @Override public float density() { return density; }

    @Override public void onAppFocused(NewHomeApps.Entry app) {
        focusedId = app.id;
        showFocusedName(app.label);
    }

    @Override public void onAppClick(NewHomeApps.Entry app) {
        if (movingId != null) finishMove();
        else if (chooseMove) startMove(app.id);
        else launch(app);
    }

    @Override public void onAppEdit(NewHomeApps.Entry app) {
        if (movingId == null) edit(app);
    }

    @Override public boolean onAppKey(NewHomeApps.Entry app, int key, KeyEvent event) {
        return onCardKey(app, key, event);
    }

    /**
     * Keep Left/Right/Down inside the shelf. Right on the last cell of a row continues on the
     * next row instead of jumping into the top bar; Up from the first row still leaves to chrome.
     */
    @Override public boolean onShelfKey(int position, int key, KeyEvent event) {
        if (movingId != null || chooseMove) return false;
        int delta;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -columns; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = columns; break;
            default: return false;
        }
        int count = adapter.getItemCount();
        if (count <= 0) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP && position < columns) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int target;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Wrap across the whole shelf: last → first on Right, first → last on Left.
            target = (position + delta + count) % count;
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (position + columns < count) target = position + columns;
            else if (position / columns < (count - 1) / columns) target = count - 1;
            else return true;
        } else {
            target = position + delta;
            if (target < 0) return true;
        }
        String id = adapter.idAt(target);
        if (id != null) focusApp(id);
        return true;
    }

    @Override public void onMoreFocused() {
        focusedId = NewHomeAdapter.MORE_ID;
        showFocusedName("更多");
    }

    @Override public void onMoreClick() {
        if (movingId != null || chooseMove) return;
        showMoreApps();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (config != null) for (int key : config.settingsKeycodes) {
            if (event.getKeyCode() == key) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                    open(config.settingsKeyPanel
                            ? new Intent(this, QuickSettingsActivity.class)
                            : systemIntent(Settings.ACTION_SETTINGS));
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !MainActivity.ACTION_LAUNCHER_SETTINGS.equals(intent.getAction()))
            return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class),
                LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == LAUNCHER_SETTINGS_REQUEST && result == RESULT_OK) recreate();
    }

    private void reloadApps() {
        final String restore = focusedId;
        final int gen = ++appsLoadGen;
        final Context appContext = getApplicationContext();
        final NewHomeStore homeStore = store;
        io.execute(() -> {
            Set<String> hidden = LibraryApps.loadRemoved(appContext);
            List<NewHomeApps.Entry> loaded = NewHomeApps.load(appContext);
            List<String> installed = new ArrayList<>();
            Map<String, NewHomeApps.Entry> nextApps = new HashMap<>();
            for (NewHomeApps.Entry app : loaded) {
                if (hidden.contains(app.packageName)) continue;
                nextApps.put(app.id, app);
                installed.add(app.id);
            }
            Set<String> livePins = NewHomeOrder.livePins(homeStore.pins(), installed, MAX_PINS);
            homeStore.savePins(livePins);
            List<String> nextOrder = NewHomeOrder.reconcile(installed, homeStore.order(), livePins);
            homeStore.saveOrder(nextOrder);
            Set<String> liveStash = new HashSet<>(homeStore.stashed());
            liveStash.retainAll(new HashSet<>(installed));
            // A pinned favourite stays on the shelf, not in More.
            liveStash.removeAll(livePins);
            homeStore.saveStashed(liveStash);
            handler.post(() -> {
                if (gen != appsLoadGen || isFinishing()) return;
                apps.clear();
                apps.putAll(nextApps);
                pinned = livePins;
                order = nextOrder;
                stashed = liveStash;
                focusedId = restore;
                renderHome();
            });
        });
    }

    private void renderHome() {
        String restore = movingId != null ? movingId : focusedId;
        hint.setText(movingId != null ? "移动模式 · 确定保存 · 返回取消"
                : chooseMove ? "方向键选择 · 确定选择要移动的应用 · 返回退出"
                : "方向键选择 · 确定打开 · 长按确定 / 菜单键编辑应用");
        List<String> visible = visibleOrder();
        adapter.submit(visible);
        updateTileMetrics();
        if (visible.isEmpty()) {
            showFocusedName(null);
            btnSettings.requestFocus();
            return;
        }
        String target;
        if (restore != null && visible.contains(restore)) target = restore;
        else if (restore != null && moreOrder().contains(restore)
                && visible.contains(NewHomeAdapter.MORE_ID))
            target = NewHomeAdapter.MORE_ID;
        else target = visible.get(0);
        // Returning from Wi‑Fi / Settings must not yank focus back into the shelf.
        View chrome = topBarResumeFocus;
        topBarResumeFocus = null;
        grid.post(() -> {
            if (chrome != null && chrome.getVisibility() == View.VISIBLE && chrome.isShown()) {
                chrome.requestFocus();
                return;
            }
            if (!focusApp(target)) {
                showFocusedName(null);
                btnSettings.requestFocus();
            }
        });
    }

    private boolean isTopBar(View view) {
        return view == btnInputs || view == btnWifi || view == btnReorder
                || view == btnLibrary || view == btnSettings;
    }

    /** Apps still on the two-row shelf (not put into More). */
    private List<String> shelfOrder() {
        List<String> shelf = new ArrayList<>();
        for (String id : order) if (!stashed.contains(id)) shelf.add(id);
        return shelf;
    }

    /** Two-row shelf; More appears only after the owner has stashed at least one app. */
    private List<String> visibleOrder() {
        List<String> shelf = shelfOrder();
        int slots = columns * HOME_ROWS;
        if (stashed.isEmpty()) {
            // No stashed apps yet: fill both rows, do not show More.
            if (shelf.size() <= slots) return shelf;
            return new ArrayList<>(shelf.subList(0, slots));
        }
        int keep = Math.max(0, slots - 1);
        List<String> visible = new ArrayList<>(shelf.subList(0, Math.min(keep, shelf.size())));
        visible.add(NewHomeAdapter.MORE_ID);
        return visible;
    }

    /** Stashed apps, plus shelf apps that no longer fit once More takes a cell. */
    private List<String> moreOrder() {
        if (stashed.isEmpty()) return Collections.emptyList();
        List<String> shelf = shelfOrder();
        int keep = Math.max(0, columns * HOME_ROWS - 1);
        List<String> more = new ArrayList<>();
        if (shelf.size() > keep) more.addAll(shelf.subList(keep, shelf.size()));
        for (String id : order) {
            if (stashed.contains(id) && !more.contains(id)) more.add(id);
        }
        return more;
    }

    private void showMoreApps() {
        List<String> more = moreOrder();
        if (more.isEmpty()) return;
        if (moreDialog != null && moreDialog.isShowing()) moreDialog.dismiss();
        RecyclerView list = (RecyclerView) LayoutInflater.from(this)
                .inflate(R.layout.dialog_more_apps, null);
        int moreColumns = Math.max(3, Math.min(columns, 5));
        list.setLayoutManager(new GridLayoutManager(this, moreColumns));
        list.setHasFixedSize(true);
        list.setItemAnimator(null);
        moreDialog = new AlertDialog.Builder(this)
                .setTitle("更多应用")
                .setView(list)
                .create();
        final float focusScale = 1.18f;
        list.setAdapter(new RecyclerView.Adapter<MoreHolder>() {
            @NonNull @Override
            public MoreHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_more_app, parent, false);
                MoreHolder holder = new MoreHolder(row);
                float radius = 10 * density;
                holder.icon.setClipToOutline(true);
                holder.icon.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                row.setOnFocusChangeListener((v, focused) -> scaleMoreItem(holder, focused, focusScale));
                row.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    NewHomeApps.Entry app = apps.get(more.get(pos));
                    if (app == null) return;
                    moreDialog.dismiss();
                    launch(app);
                });
                row.setOnLongClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return true;
                    NewHomeApps.Entry app = apps.get(more.get(pos));
                    if (app != null) editMore(app);
                    return true;
                });
                return holder;
            }

            @Override
            public void onBindViewHolder(@NonNull MoreHolder holder, int position) {
                NewHomeApps.Entry app = apps.get(more.get(position));
                int iconPx = Math.round(64 * density);
                if (app == null) {
                    holder.label.setText(more.get(position));
                    NewHomeAdapter.clearSettingsIconStyle(holder.icon);
                    holder.icon.setImageDrawable(null);
                } else {
                    holder.label.setText(app.label);
                    holder.itemView.setContentDescription(app.label);
                    if (NewHomeAdapter.isSettingsApp(app, NewMainActivity.this)) {
                        NewHomeAdapter.styleSettingsIcon(holder.icon, app, iconPx, density);
                    } else {
                        NewHomeAdapter.clearSettingsIconStyle(holder.icon);
                        holder.icon.setImageDrawable(app.icon);
                    }
                }
                holder.icon.invalidateOutline();
                float scale = holder.itemView.isFocused() ? focusScale : 1f;
                holder.icon.setScaleX(scale);
                holder.icon.setScaleY(scale);
                holder.label.setScaleX(scale);
                holder.label.setScaleY(scale);
            }

            @Override public int getItemCount() { return more.size(); }
        });
        moreDialog.setOnDismissListener(d -> moreDialog = null);
        moreDialog.setOnShowListener(d -> list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
            else list.requestFocus();
        }));
        moreDialog.show();
    }

    private static void scaleMoreItem(MoreHolder holder, boolean focused, float focusScale) {
        float scale = focused ? focusScale : 1f;
        holder.icon.animate().cancel();
        holder.label.animate().cancel();
        holder.icon.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
        holder.label.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
    }

    private static final class MoreHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        MoreHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            label = itemView.findViewById(R.id.label);
        }
    }

    /** Long-press actions inside More: put back on the shelf, or open app info. */
    private void editMore(NewHomeApps.Entry app) {
        new AlertDialog.Builder(this).setTitle(app.label)
                .setItems(new String[]{"展示", "应用信息"}, (dialog, which) -> {
                    if (which == 0) showOnHome(app);
                    else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + app.packageName)));
                }).show();
    }

    /**
     * Puts a More-folder app back on the two-row shelf. If the shelf is already full,
     * ask the owner to stash something first instead of silently overflowing.
     */
    private void showOnHome(NewHomeApps.Entry app) {
        boolean wasStashed = stashed.contains(app.id);
        if (wasStashed) {
            int slots = columns * HOME_ROWS;
            int shelfAfter = shelfOrder().size() + 1;
            int stashedAfter = stashed.size() - 1;
            int homeAppSlots = stashedAfter > 0 ? slots - 1 : slots;
            if (shelfAfter > homeAppSlots) {
                new AlertDialog.Builder(this)
                        .setMessage("首页两行已满，请先收纳一个应用后再展示")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            stashed.remove(app.id);
            store.saveStashed(stashed);
        } else {
            // Capacity overflow: promote it to the front of the shelf without growing the count.
            order.remove(app.id);
            int insert = 0;
            while (insert < order.size() && pinned.contains(order.get(insert))) insert++;
            order.add(insert, app.id);
            store.saveOrder(order);
        }
        if (moreDialog != null) moreDialog.dismiss();
        focusedId = app.id;
        renderHome();
    }

    private void updateTileMetrics() {
        int available = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        if (available <= 0) {
            if (!sizing) {
                sizing = true;
                grid.post(() -> {
                    sizing = false;
                    updateTileMetrics();
                });
            }
            available = dp(getResources().getConfiguration().screenWidthDp - 112);
        }
        int gap = Math.max(dp(5), Math.round(available * 0.012f));
        int baseV = dp(16);
        int width = Math.max(dp(48), available / columns - gap * 2);
        int rowsBudget = grid.getHeight() - baseV * 2;
        if (rowsBudget > 0) width = Math.min(width, Math.max(dp(48), rowsBudget / HOME_ROWS - gap * 2));
        int iconSize = Math.max(dp(32), Math.round(width * 0.8f));
        adapter.setMetrics(width, iconSize, gap);
        // One row only: sit on the bottom row of the two-row shelf (pad one row above).
        int count = adapter.getItemCount();
        int top = (count > 0 && count <= columns) ? baseV + width + gap * 2 : baseV;
        if (grid.getPaddingTop() != top || grid.getPaddingBottom() != baseV) {
            grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), baseV);
        }
        if (grid.computeVerticalScrollOffset() != 0) {
            ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
        // Line the title up with the first tile's left edge (list padding + cell margin).
        int edge = grid.getPaddingLeft() + gap;
        title.setPaddingRelative(edge, title.getPaddingTop(), edge, title.getPaddingBottom());
    }

    private boolean focusApp(String id) {
        int pos = adapter.indexOf(id);
        if (pos < 0) return false;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pos);
        if (holder != null) return holder.itemView.requestFocus();
        // Do not scrollToPosition: on a bottom-aligned single row that pulls the icon
        // up into the spacer the moment move mode asks for focus again.
        grid.post(() -> {
            RecyclerView.ViewHolder again = grid.findViewHolderForAdapterPosition(pos);
            if (again != null) again.itemView.requestFocus();
        });
        return true;
    }

    /** The focused app's name replaces the static "你的应用" heading above the grid. */
    private void showFocusedName(String label) {
        if (movingId != null) {
            NewHomeApps.Entry moving = apps.get(movingId);
            title.setText(moving == null ? "移动应用" : moving.label);
            return;
        }
        title.setText(label == null || label.isEmpty() ? "你的应用" : label);
    }

    private boolean onCardKey(NewHomeApps.Entry app, int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP && movingId == null) edit(app);
            return true;
        }
        if (movingId == null) return false;
        int delta;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -columns; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = columns; break;
            default: return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) moveOnShelf(delta);
        return true;
    }

    private void launch(NewHomeApps.Entry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, "无法启动该应用", Toast.LENGTH_SHORT).show();
            reloadApps();
        }
    }

    private void edit(NewHomeApps.Entry app) {
        new AlertDialog.Builder(this).setTitle(app.label)
                .setItems(new String[]{
                                pinned.contains(app.id) ? "取消置顶" : "置顶",
                                "收纳",
                                "移动位置",
                                "应用信息"},
                        (dialog, which) -> {
                            if (which == 0) {
                                if (!pinned.remove(app.id)) {
                                    pinned.clear();
                                    pinned.add(app.id);
                                    stashed.remove(app.id);
                                    store.saveStashed(stashed);
                                }
                                store.savePins(pinned);
                                order = NewHomeOrder.reconcile(order, order, pinned);
                                store.saveOrder(order);
                                focusedId = app.id;
                                renderHome();
                            } else if (which == 1) {
                                stashed.add(app.id);
                                pinned.remove(app.id);
                                store.savePins(pinned);
                                store.saveStashed(stashed);
                                focusedId = NewHomeAdapter.MORE_ID;
                                renderHome();
                            } else if (which == 2) {
                                startMove(app.id);
                            } else {
                                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + app.packageName)));
                            }
                        }).show();
    }

    /** Swap the moving app with a shelf neighbour, leaving stashed apps where they are. */
    private void moveOnShelf(int delta) {
        List<String> shelf = shelfOrder();
        int from = shelf.indexOf(movingId);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= shelf.size()) return;
        String other = shelf.get(to);
        if (pinned.contains(movingId) != pinned.contains(other)) return;
        int a = order.indexOf(movingId);
        int b = order.indexOf(other);
        if (a < 0 || b < 0) return;
        order.set(a, other);
        order.set(b, movingId);
        renderHome();
    }

    private void startMove(String id) {
        chooseMove = false;
        beforeMove = new ArrayList<>(order);
        movingId = id;
        focusedId = id;
        renderHome();
    }

    private void finishMove() {
        store.saveOrder(order);
        movingId = null;
        beforeMove = null;
        renderHome();
    }

    private void cancelMove() {
        if (movingId == null) return;
        order = beforeMove;
        movingId = null;
        beforeMove = null;
    }

    private int dp(float value) { return Math.round(value * density); }

    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        pill.setPaddingRelative(dp(10), 0, dp(10), 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = dp(42);
    }

    private void showLibrary() {
        if (library != null) library.close();
        library = new AppLibraryDialog(this, config.animations, config.reducedMotion,
                pkg -> {
                    for (NewHomeApps.Entry app : apps.values())
                        if (app.packageName.equals(pkg)) return true;
                    return false;
                }, this::reloadApps, () -> {
                    library = null;
                    btnLibrary.requestFocus();
                });
        library.show();
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22
                ? R.string.good_evening : R.string.good_night;
        greeting.setText(config.greetingName.isEmpty() ? getString(res)
                : getString(R.string.greeting_with_name, getString(res), config.greetingName));
        if (config.shamsiDate) ((TextView) findViewById(R.id.shamsiDate)).setText(Shamsi.today());
    }

    private void updateNetworkLabel() {
        if (btnWifi.getVisibility() != View.VISIBLE || config.iconOnlyButtons
                && config.wifiLabelWidth <= 0) return;
        String label = getString(R.string.wifi);
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                boolean online = false, ethernet = false, wifi = false;
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        continue;
                    online = true;
                    ethernet |= caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }
                if (!online) label = getString(R.string.offline);
                else if (ethernet) label = getString(R.string.ethernet);
                else if (wifi) {
                    WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                    WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                    String ssid = info == null ? null : info.getSSID();
                    if (ssid != null && !ssid.toLowerCase().contains("unknown"))
                        label = ssid.replace("\"", "");
                }
            }
        } catch (RuntimeException ignored) { }
        btnWifi.setText(label);
    }

    private void openNetwork() {
        Intent vpn = config.vpnApp.isEmpty() ? null
                : getPackageManager().getLeanbackLaunchIntentForPackage(config.vpnApp);
        if (vpn == null && !config.vpnApp.isEmpty())
            vpn = getPackageManager().getLaunchIntentForPackage(config.vpnApp);
        open(vpn == null ? systemIntent(Settings.ACTION_WIFI_SETTINGS) : vpn);
    }

    private Intent systemIntent(String action) {
        Intent standard = new Intent(action);
        if (standard.resolveActivity(getPackageManager()) != null) return standard;
        Intent xiaomi = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        return xiaomi.resolveActivity(getPackageManager()) != null ? xiaomi : standard;
    }

    private void open(Intent intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateInputsButton() {
        TvInputManager manager = getSystemService(TvInputManager.class);
        boolean available = !config.sourcePanelService.isEmpty() || !config.liveTvPackage.isEmpty();
        if (manager != null) for (TvInputInfo input : manager.getTvInputList())
            if (input.isPassthroughInput() && !input.isHidden(this)) available = true;
        btnInputs.setVisibility(available ? View.VISIBLE : View.GONE);
    }

    private void showInputs() {
        TvInputManager manager = getSystemService(TvInputManager.class);
        List<String> labels = new ArrayList<>();
        List<Intent> targets = new ArrayList<>();
        if (!config.sourcePanelService.isEmpty() && config.liveTvPackage.isEmpty()) {
            openSourcePanel();
            return;
        }
        if (!config.liveTvPackage.isEmpty()) {
            Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(config.liveTvPackage);
            if (live == null) live = getPackageManager().getLaunchIntentForPackage(config.liveTvPackage);
            if (live == null && !config.liveTvAction.isEmpty())
                live = new Intent(config.liveTvAction).setPackage(config.liveTvPackage);
            if (live != null) {
                labels.add(getString(R.string.live_tv));
                targets.add(live);
            }
        }
        if (manager != null) for (TvInputInfo input : manager.getTvInputList()) {
            if (!input.isPassthroughInput() || input.isHidden(this)) continue;
            labels.add(String.valueOf(input.loadLabel(this)));
            Uri uri = TvContract.buildChannelUriForPassthroughInput(input.getId());
            targets.add(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "vnd.android.cursor.item/channel"));
        }
        if (labels.isEmpty()) {
            if (!config.sourcePanelService.isEmpty()) openSourcePanel();
            else Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.inputs)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    Intent selected = targets.get(which);
                    if (Intent.ACTION_VIEW.equals(selected.getAction())) openInput(selected.getData());
                    else open(selected);
                })
                .show();
    }

    private void openInput(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(view); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            if (config.inputAction.isEmpty())
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            else open(new Intent(config.inputAction)
                    .setDataAndType(uri, "vnd.android.cursor.item/channel"));
        }
    }

    private void openSourcePanel() {
        Intent intent = new Intent(config.sourcePanelAction);
        ComponentName component = ComponentName.unflattenFromString(config.sourcePanelService);
        if (component != null) intent.setComponent(component);
        try {
            if (getPackageManager().resolveActivity(intent, 0) != null) open(intent);
            else startService(intent);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
