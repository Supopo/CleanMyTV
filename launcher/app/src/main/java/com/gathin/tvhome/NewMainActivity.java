package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
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
    /** Last shelf cell size / margin from {@link #updateTileMetrics()}. */
    private int shelfTileSize;
    private int shelfGap;
    private AlertDialog moreDialog;
    /** Top-bar control that had focus when Home paused (Wi‑Fi / Settings / …). */
    private View topBarResumeFocus;
    /** Last top-bar pill focused; Up from the shelf returns here. */
    private View lastTopBarFocus;
    /** Dim overlay while PackageManager is still filling the shelf after boot. */
    private View shelfLoading;
    private View shelfLoadingSpin;
    private boolean shelfReady;
    private final Runnable hideLoadingTimeout = this::hideShelfLoading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_new_main);
        // Must finish before Config.load so an on-TV wallpaper pick can resolve after reboot.
        // Already-installed files are existence checks only; a cold copy is rare.
        BuiltinWallpapers.install(this);
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
        shelfLoading = findViewById(R.id.shelfLoading);
        shelfLoadingSpin = findViewById(R.id.shelfLoadingSpin);
        showShelfLoading();
        // Wallpaper pick calls recreate(); restore Settings focus that onPause cannot see.
        SharedPreferences launcherSettings =
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (launcherSettings.getBoolean(Config.RESUME_FOCUS_SETTINGS_KEY, false)) {
            launcherSettings.edit().remove(Config.RESUME_FOCUS_SETTINGS_KEY).apply();
            topBarResumeFocus = btnSettings;
        }
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
            lastTopBarFocus = view;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return wrapTopBarFocus(view, key == KeyEvent.KEYCODE_DPAD_RIGHT);
            }
            // Cycle: top bar → shelf row 0 → shelf bottom row → top bar.
            // Down enters the first shelf row; Up enters the bottom shelf row.
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusShelfRowFromTopBar(true);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                focusShelfRowFromTopBar(false);
                return true;
            }
            return false;
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
            shamsi.setText(Shamsi.today(getResources()));
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
        handler.removeCallbacks(hideLoadingTimeout);
        unregisterReceiver(packages);
        if (library != null) library.close();
        wallpaperPlayer.close();
        io.shutdownNow();
        super.onDestroy();
    }

    /** Semi-transparent “正在加载桌面” sheet until the shelf has real app tiles. */
    private void showShelfLoading() {
        if (shelfReady || shelfLoading == null) return;
        handler.removeCallbacks(hideLoadingTimeout);
        shelfLoading.animate().cancel();
        shelfLoading.setAlpha(0f);
        shelfLoading.setVisibility(View.VISIBLE);
        shelfLoading.animate().alpha(1f).setDuration(220).start();
        startShelfLoadingSpin();
        // Never block the remote forever if PackageManager stays empty.
        handler.postDelayed(hideLoadingTimeout, 15_000L);
    }

    private void hideShelfLoading() {
        handler.removeCallbacks(hideLoadingTimeout);
        boolean reveal = !shelfReady;
        shelfReady = true;
        // Paint apps + More together the moment the sheet starts to dismiss.
        if (reveal) renderHome();
        if (shelfLoading == null || shelfLoading.getVisibility() != View.VISIBLE) return;
        stopShelfLoadingSpin();
        shelfLoading.animate().cancel();
        shelfLoading.animate().alpha(0f).setDuration(280).withEndAction(() -> {
            shelfLoading.setVisibility(View.GONE);
            shelfLoading.setAlpha(1f);
        }).start();
    }

    /** One full turn every 2.4s — slower than the default ProgressBar spinner. */
    private void startShelfLoadingSpin() {
        if (shelfLoadingSpin == null) return;
        shelfLoadingSpin.clearAnimation();
        RotateAnimation spin = new RotateAnimation(0f, 360f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        spin.setDuration(2400L);
        spin.setRepeatCount(RotateAnimation.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        shelfLoadingSpin.startAnimation(spin);
    }

    private void stopShelfLoadingSpin() {
        if (shelfLoadingSpin != null) shelfLoadingSpin.clearAnimation();
    }

    /** True when the two-row shelf shows at least one real app (not only More / spacers). */
    private boolean shelfHasAppTiles() {
        for (String id : visibleOrder()) {
            if (!NewHomeAdapter.isEmpty(id) && !NewHomeAdapter.isMore(id)) return true;
        }
        return false;
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
     * Vertical cycle: top bar ↔ first shelf row ↔ bottom shelf row.
     * Left/Right wrap only within the current shelf row band / top bar.
     */
    @Override public boolean onShelfKey(int position, int key, KeyEvent event) {
        if (movingId != null || chooseMove) return false;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            default: return false;
        }
        List<Integer> focusable = focusablePositions();
        if (focusable.isEmpty()) return false;
        int at = focusable.indexOf(position);
        if (at < 0) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int delta = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            int next = focusable.get((at + delta + focusable.size()) % focusable.size());
            focusApp(adapter.idAt(next));
            return true;
        }
        int row = position / columns;
        int col = position % columns;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            int above = position - columns;
            if (above >= 0 && !NewHomeAdapter.isEmpty(adapter.idAt(above))) {
                focusApp(adapter.idAt(above));
                return true;
            }
            int best = nearestOnRow(focusable, row - 1, col);
            if (best >= 0) {
                focusApp(adapter.idAt(best));
                return true;
            }
            // First middle row → top bar.
            focusTopBarFromShelf();
            return true;
        }
        // Down → next shelf row, or from the bottom middle row → top bar.
        int below = position + columns;
        if (below < adapter.getItemCount() && !NewHomeAdapter.isEmpty(adapter.idAt(below))) {
            focusApp(adapter.idAt(below));
            return true;
        }
        int best = nearestOnRow(focusable, row + 1, col);
        if (best >= 0) {
            focusApp(adapter.idAt(best));
            return true;
        }
        focusTopBarFromShelf();
        return true;
    }

    /** Closest focusable on {@code row} to {@code col}, or -1 when that row has none. */
    private int nearestOnRow(List<Integer> focusable, int row, int col) {
        if (row < 0) return -1;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int pos : focusable) {
            if (pos / columns != row) continue;
            int dist = Math.abs(pos % columns - col);
            if (dist < bestDist || (dist == bestDist && pos > best)) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    private int firstShelfRow(List<Integer> focusable) {
        int min = Integer.MAX_VALUE;
        for (int pos : focusable) min = Math.min(min, pos / columns);
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private int lastShelfRow(List<Integer> focusable) {
        int max = 0;
        for (int pos : focusable) max = Math.max(max, pos / columns);
        return max;
    }

    private List<Integer> focusablePositions() {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (!NewHomeAdapter.isEmpty(adapter.idAt(i))) positions.add(i);
        }
        return positions;
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
                // Previous Intent, kept for reference: it did not tell the panel which Home opened it.
                // open(config.settingsKeyPanel
                //         ? new Intent(this, QuickSettingsActivity.class)
                //         : systemIntent(Settings.ACTION_SETTINGS));
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                    open(config.settingsKeyPanel
                            ? QuickSettingsActivity.fromHome(this)
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
        final int epochAtStart = homeStore.epoch();
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
            // Re-read after package scan so a concurrent owner edit is not overwritten.
            if (homeStore.epoch() != epochAtStart) return;
            List<String> savedOrder = homeStore.order();
            Set<String> savedStash = homeStore.stashed();
            Set<String> savedPins = homeStore.pins();
            // Boot scans often return only a handful of apps; never persist that over a full shelf.
            boolean incomplete = NewHomeOrder.looksIncomplete(savedOrder, installed)
                    || NewHomeOrder.looksIncomplete(new ArrayList<>(savedStash), installed);
            Set<String> livePins = NewHomeOrder.livePins(savedPins, installed, MAX_PINS);
            List<String> nextOrder = NewHomeOrder.reconcile(installed, savedOrder, livePins);
            Set<String> liveStash = NewHomeOrder.remapIds(savedStash, installed);
            // A pinned favourite stays on the shelf, not in More.
            liveStash.removeAll(livePins);
            if (homeStore.epoch() != epochAtStart) return;
            boolean changed = !nextOrder.equals(savedOrder)
                    || !liveStash.equals(savedStash)
                    || !livePins.equals(savedPins);
            // Incomplete PackageManager passes must not wipe new_home_shelf.json.
            if (changed && !incomplete) homeStore.saveAll(nextOrder, liveStash, livePins);
            final int epochAfter = homeStore.epoch();
            final boolean rescan = incomplete;
            handler.post(() -> {
                if (gen != appsLoadGen || isFinishing()) return;
                // Owner wrote while we were loading — discard this stale pass and reload.
                if (homeStore.epoch() != epochAfter) {
                    reloadApps();
                    return;
                }
                apps.clear();
                apps.putAll(nextApps);
                pinned = livePins;
                order = nextOrder;
                stashed = liveStash;
                focusedId = restore;
                renderHome();
                // Keep the sheet up through thin boot scans; dismiss only once order is restored.
                if (!shelfReady && !rescan && shelfHasAppTiles()) hideShelfLoading();
                // Try again shortly; the full launchable list usually appears after boot settles.
                if (rescan) handler.postDelayed(() -> {
                    if (movingId == null && !isFinishing()) reloadApps();
                }, 2_000L);
            });
        });
    }

    private void renderHome() {
        // While the boot sheet is up, keep the grid empty so "更多" does not appear alone.
        if (shelfReady) autoStashOverflow();
        String restore = movingId != null ? movingId : focusedId;
        hint.setText(movingId != null ? "移动模式 · 确定保存 · 返回取消"
                : chooseMove ? "方向键选择 · 确定选择要移动的应用 · 返回退出"
                : "方向键选择 · 确定打开 · 长按确定 / 菜单键编辑应用 · 长按顶部设置更换壁纸");
        List<String> visible = shelfReady ? visibleOrder() : Collections.emptyList();
        adapter.submit(visible);
        updateTileMetrics();
        updateTitleShelfOffset();
        if (!shelfReady) {
            showFocusedName(null);
            return;
        }
        if (visible.isEmpty()) {
            showFocusedName(null);
            btnSettings.requestFocus();
            return;
        }
        String target;
        if (restore != null && visible.contains(restore) && !NewHomeAdapter.isEmpty(restore))
            target = restore;
        else if (restore != null && moreOrder().contains(restore)
                && visible.contains(NewHomeAdapter.MORE_ID))
            target = NewHomeAdapter.MORE_ID;
        else target = firstFocusableId(visible);
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

    /** Visible top-bar pills in left→right order (hidden wifi / library / reorder skipped). */
    private List<View> visibleTopBarButtons() {
        List<View> buttons = new ArrayList<>();
        for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibrary, btnSettings}) {
            if (button != null && button.getVisibility() == View.VISIBLE) buttons.add(button);
        }
        return buttons;
    }

    /**
     * At the ends of the top bar, Left/Right wraps to the other end instead of leaving the row.
     * @return true when the key was handled (wrap); false lets focus move to the neighbour pill.
     */
    private boolean wrapTopBarFocus(View focused, boolean right) {
        List<View> buttons = visibleTopBarButtons();
        int at = buttons.indexOf(focused);
        if (at < 0 || buttons.size() < 2) return false;
        if (right && at == buttons.size() - 1) {
            buttons.get(0).requestFocus();
            return true;
        }
        if (!right && at == 0) {
            buttons.get(buttons.size() - 1).requestFocus();
            return true;
        }
        return false;
    }

    /** From the middle shelf: restore the last top-bar pill, else the rightmost visible one. */
    private void focusTopBarFromShelf() {
        List<View> buttons = visibleTopBarButtons();
        if (buttons.isEmpty()) return;
        View target = lastTopBarFocus;
        if (target == null || target.getVisibility() != View.VISIBLE || !buttons.contains(target)) {
            target = buttons.get(buttons.size() - 1);
        }
        target.requestFocus();
    }

    /**
     * From the top bar into the shelf.
     * @param firstRow true = Down → middle first row; false = Up → middle bottom row
     */
    private void focusShelfRowFromTopBar(boolean firstRow) {
        List<Integer> focusable = focusablePositions();
        if (focusable.isEmpty()) return;
        int col = 0;
        if (focusedId != null) {
            int prev = adapter.indexOf(focusedId);
            if (prev >= 0) col = prev % columns;
        }
        int row = firstRow ? firstShelfRow(focusable) : lastShelfRow(focusable);
        int best = nearestOnRow(focusable, row, col);
        if (best < 0) best = focusable.get(firstRow ? 0 : focusable.size() - 1);
        focusApp(adapter.idAt(best));
    }

    /** Apps still on the two-row shelf (not put into More). */
    private List<String> shelfOrder() {
        List<String> shelf = new ArrayList<>();
        for (String id : order) if (!stashed.contains(id)) shelf.add(id);
        return shelf;
    }

    /**
     * Apps beyond the two-row home capacity are moved into More automatically.
     * More occupies one home cell, so at most {@code slots - 1} apps stay outside — same cap as 展示.
     * A full two rows (exactly {@code slots} apps) therefore stashes one into More as well.
     */
    private void autoStashOverflow() {
        List<String> shelf = shelfOrder();
        int slots = columns * HOME_ROWS;
        int keep = Math.max(0, slots - 1);
        // Same limit as showOnHome: outside is full at keep, whether or not More already exists.
        if (shelf.size() <= keep) return;
        boolean changed = false;
        for (int i = shelf.size() - 1; i >= keep; i--) {
            String id = shelf.get(i);
            if (pinned.contains(id)) continue;
            stashed.add(id);
            changed = true;
        }
        shelf = shelfOrder();
        while (shelf.size() > keep) {
            String victim = null;
            for (int i = shelf.size() - 1; i >= 0; i--) {
                if (!pinned.contains(shelf.get(i))) {
                    victim = shelf.get(i);
                    break;
                }
            }
            if (victim == null) break;
            stashed.add(victim);
            changed = true;
            shelf = shelfOrder();
        }
        if (changed) store.saveStashed(stashed);
    }

    /**
     * Two-row shelf filled bottom-row-first (left→right), then top row (left→right).
     * Empty spacers hold the top row until the bottom row is full. More appears only after
     * at least one app is stashed: last on the bottom row while that row still has room,
     * otherwise first on the top row.
     */
    private List<String> visibleOrder() {
        List<String> shelf = shelfOrder();
        int slots = columns * HOME_ROWS;
        List<String> fill = new ArrayList<>();
        if (stashed.isEmpty()) {
            fill.addAll(shelf.size() <= slots ? shelf : shelf.subList(0, slots));
        } else {
            int keep = Math.max(0, slots - 1);
            List<String> apps = shelf.subList(0, Math.min(keep, shelf.size()));
            if (apps.size() < columns) {
                // Bottom row only: More sits at the end.
                fill.addAll(apps);
                fill.add(NewHomeAdapter.MORE_ID);
            } else {
                // Bottom row full of apps; More leads the top row.
                fill.addAll(apps.subList(0, columns));
                fill.add(NewHomeAdapter.MORE_ID);
                if (apps.size() > columns) {
                    fill.addAll(apps.subList(columns, apps.size()));
                }
            }
        }
        return bottomFirstLayout(fill, columns);
    }

    /** Maps fill-order apps onto a grid whose bottom row is occupied before the top row. */
    private static List<String> bottomFirstLayout(List<String> fill, int cols) {
        if (fill.isEmpty() || cols <= 0) return fill;
        if (fill.size() <= cols) {
            List<String> visible = new ArrayList<>(cols + fill.size());
            for (int i = 0; i < cols; i++) visible.add(NewHomeAdapter.emptyId(i));
            visible.addAll(fill);
            return visible;
        }
        List<String> bottom = fill.subList(0, cols);
        List<String> top = fill.subList(cols, fill.size());
        List<String> visible = new ArrayList<>(cols * 2);
        visible.addAll(top);
        for (int i = top.size(); i < cols; i++) visible.add(NewHomeAdapter.emptyId(i));
        visible.addAll(bottom);
        return visible;
    }

    private static String firstFocusableId(List<String> visible) {
        for (String id : visible) if (!NewHomeAdapter.isEmpty(id)) return id;
        return null;
    }

    /** Apps that were stashed into More (manually or by auto-overflow). */
    private List<String> moreOrder() {
        if (stashed.isEmpty()) return Collections.emptyList();
        List<String> more = new ArrayList<>();
        for (String id : order) {
            if (stashed.contains(id)) more.add(id);
        }
        for (String id : stashed) {
            if (!more.contains(id)) more.add(id);
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
        moreDialog.setOnDismissListener(d -> {
            moreDialog = null;
            // Dialog stole focus; put the shelf cursor back on More when it is still there.
            if (movingId != null) return;
            if (visibleOrder().contains(NewHomeAdapter.MORE_ID)) {
                focusedId = NewHomeAdapter.MORE_ID;
                grid.post(() -> focusApp(NewHomeAdapter.MORE_ID));
            }
        });
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
     * Puts a More-folder app back on the two-row shelf. While More is on the home grid it
     * occupies one cell, so at most {@code slots - 1} apps fit outside. If that capacity is
     * already reached, only tip — do not unstash or reorder (including the last More item).
     */
    private void showOnHome(NewHomeApps.Entry app) {
        if (!stashed.contains(app.id)) return;
        // More is visible whenever 展示 is offered; outside is full at slots - 1 apps.
        int homeAppSlots = Math.max(0, columns * HOME_ROWS - 1);
        if (shelfOrder().size() >= homeAppSlots) {
            Toast.makeText(this, "外面已经放不下啦", Toast.LENGTH_SHORT).show();
            return;
        }
        stashed.remove(app.id);
        store.saveStashed(stashed);
        moveToLastVisibleShelfSlot(app.id);
        if (moreDialog != null) moreDialog.dismiss();
        // Opened from More — keep the cursor on More after 展示, unless More itself went away.
        focusedId = visibleOrder().contains(NewHomeAdapter.MORE_ID)
                ? NewHomeAdapter.MORE_ID : app.id;
        renderHome();
    }

    /**
     * Places {@code id} as the last tile in the visible shelf window (bottom-first fill order)
     * without reshuffling the apps that were already on the shelf.
     */
    private void moveToLastVisibleShelfSlot(String id) {
        order.remove(id);
        int slots = columns * HOME_ROWS;
        int keep = stashed.isEmpty() ? slots : Math.max(0, slots - 1);
        int seen = 0;
        int insertAt = order.size();
        for (int i = 0; i < order.size(); i++) {
            if (stashed.contains(order.get(i))) continue;
            seen++;
            if (seen == keep) {
                insertAt = i;
                break;
            }
            insertAt = i + 1;
        }
        order.add(insertAt, id);
        store.saveOrder(order);
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
        shelfTileSize = width;
        shelfGap = gap;
        adapter.setMetrics(width, iconSize, gap);
        // Bottom-first layout uses empty spacers for the top row; keep even vertical padding.
        if (grid.getPaddingTop() != baseV || grid.getPaddingBottom() != baseV) {
            grid.setPadding(grid.getPaddingLeft(), baseV, grid.getPaddingRight(), baseV);
        }
        if (grid.computeVerticalScrollOffset() != 0) {
            ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
        updateTitleFirstTileAlign();
        updateTitleShelfOffset();
    }

    /**
     * Centers the shelf title over the leftmost tile (icon is centered in that cell).
     */
    private void updateTitleFirstTileAlign() {
        if (title == null || shelfTileSize <= 0) return;
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        int tileCenter = grid.getPaddingLeft() + shelfGap + shelfTileSize / 2;
        String text = title.getText() == null ? "" : title.getText().toString();
        float textW = title.getPaint().measureText(text);
        int padStart = Math.max(0, Math.round(tileCenter - textW / 2f));
        int padEnd = grid.getPaddingRight() + shelfGap;
        title.setPaddingRelative(padStart, title.getPaddingTop(), padEnd, title.getPaddingBottom());
    }

    /**
     * When only the bottom shelf row has apps, an empty top row would leave the name far above
     * the icons. Nudge the title down by one cell so spacing matches the two-row layout.
     */
    private void updateTitleShelfOffset() {
        if (title == null) return;
        float nudge = 0f;
        if (shelfReady && shelfTileSize > 0 && shelfUsesBottomRowOnly()) {
            nudge = shelfTileSize + shelfGap * 2f;
        }
        if (Math.abs(title.getTranslationY() - nudge) > 0.5f) title.setTranslationY(nudge);
    }

    /** True when bottom-first layout parked a full empty spacer row above the real tiles. */
    private boolean shelfUsesBottomRowOnly() {
        int count = adapter == null ? 0 : adapter.getItemCount();
        if (count < columns) return false;
        for (int i = 0; i < columns; i++) {
            if (!NewHomeAdapter.isEmpty(adapter.idAt(i))) return false;
        }
        for (int i = columns; i < count; i++) {
            if (!NewHomeAdapter.isEmpty(adapter.idAt(i))) return true;
        }
        return false;
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
        } else {
            title.setText(label == null || label.isEmpty() ? "你的应用" : label);
        }
        updateTitleFirstTileAlign();
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
                config.appLibrarySelection,
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
        if (config.shamsiDate) ((TextView) findViewById(R.id.shamsiDate)).setText(Shamsi.today(getResources()));
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
