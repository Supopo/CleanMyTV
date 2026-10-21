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
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
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

/** A separate HOME page with the existing header and MiFlow-style app menu. */
public final class NewMainActivity extends Activity implements NewHomeAdapter.Host {
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** The new home only pins one favourite; a later pin replaces the previous one. */
    private static final int MAX_PINS = 1;
    /** Default home shows this many rows; the last cell becomes "更多" after an app is stashed. */
    private static final int HOME_ROWS = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, NewHomeApps.Entry> apps = new HashMap<>();
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_new_main);
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
        // Keep vertical scroll still while moving left/right on the same row. Edge rows
        // (e.g. 影视仓 / Moonlight / 电视管家) are often 1–2 px past the padding box; the
        // default focus scroll then jitters the whole grid up and down.
        grid.setLayoutManager(new GridLayoutManager(this, columns) {
            private int lastFocusPos = RecyclerView.NO_POSITION;

            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                    @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                int pos = getPosition(child);
                int cols = Math.max(1, getSpanCount());
                boolean sameRow = lastFocusPos != RecyclerView.NO_POSITION
                        && pos != RecyclerView.NO_POSITION
                        && pos / cols == lastFocusPos / cols;
                if (pos != RecyclerView.NO_POSITION) lastFocusPos = pos;

                int slop = dp(2);
                int parentLeft = parent.getPaddingLeft() - slop;
                int parentTop = parent.getPaddingTop() - slop;
                int parentRight = parent.getWidth() - parent.getPaddingRight() + slop;
                int parentBottom = parent.getHeight() - parent.getPaddingBottom() + slop;
                int childLeft = child.getLeft();
                int childTop = child.getTop();
                int childRight = child.getRight();
                int childBottom = child.getBottom();

                int dx = 0;
                if (childLeft < parentLeft) dx = childLeft - parentLeft;
                else if (childRight > parentRight) dx = childRight - parentRight;

                int dy = 0;
                if (!sameRow) {
                    if (childTop < parentTop) dy = childTop - parentTop;
                    else if (childBottom > parentBottom) dy = childBottom - parentBottom;
                }

                if (dx == 0 && dy == 0) return false;
                if (immediate) parent.scrollBy(dx, dy);
                else parent.smoothScrollBy(dx, dy);
                return true;
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        adapter = new NewHomeAdapter(this);
        grid.setAdapter(adapter);
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) updateTileMetrics();
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
        View.OnKeyListener topKeys = (view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            String id = focusedId != null ? focusedId : (order.isEmpty() ? null : order.get(0));
            return id != null && focusApp(id);
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
        handler.removeCallbacks(minuteTick);
        wallpaperPlayer.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(minuteTick);
        unregisterReceiver(packages);
        if (library != null) library.close();
        wallpaperPlayer.close();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        cancelMove();
        chooseMove = false;
        grid.scrollToPosition(0);
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
        String restore = focusedId;
        Set<String> hidden = LibraryApps.loadRemoved(this);
        List<String> installed = new ArrayList<>();
        apps.clear();
        for (NewHomeApps.Entry app : NewHomeApps.load(this)) {
            if (hidden.contains(app.packageName)) continue;
            apps.put(app.id, app);
            installed.add(app.id);
        }
        Set<String> livePins = NewHomeOrder.livePins(store.pins(), installed, MAX_PINS);
        pinned = livePins;
        store.savePins(livePins);
        order = NewHomeOrder.reconcile(installed, store.order(), pinned);
        store.saveOrder(order);
        Set<String> liveStash = new HashSet<>(store.stashed());
        liveStash.retainAll(new HashSet<>(installed));
        // A pinned favourite stays on the shelf, not in More.
        liveStash.removeAll(pinned);
        stashed = liveStash;
        store.saveStashed(stashed);
        focusedId = restore;
        renderHome();
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
        grid.post(() -> {
            if (!focusApp(target)) {
                showFocusedName(null);
                btnSettings.requestFocus();
            }
        });
    }

    /** Apps still on the two-row shelf (not put into More). */
    private List<String> shelfOrder() {
        List<String> shelf = new ArrayList<>();
        for (String id : order) if (!stashed.contains(id)) shelf.add(id);
        return shelf;
    }

    /** Two-row shelf; More appears only after the owner has stashed at least one app. */
    private List<String> visibleOrder() {
        if (movingId != null || chooseMove) return new ArrayList<>(order);
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
        List<String> labels = new ArrayList<>(more.size());
        for (String id : more) {
            NewHomeApps.Entry app = apps.get(id);
            labels.add(app == null ? id : app.label);
        }
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        moreDialog = new AlertDialog.Builder(this)
                .setTitle("更多应用")
                .setView(list)
                .create();
        list.setOnItemClickListener((parent, view, which, rowId) -> {
            NewHomeApps.Entry app = apps.get(more.get(which));
            if (app == null) return;
            moreDialog.dismiss();
            launch(app);
        });
        list.setOnItemLongClickListener((parent, view, which, rowId) -> {
            NewHomeApps.Entry app = apps.get(more.get(which));
            if (app != null) editMore(app);
            return true;
        });
        moreDialog.setOnDismissListener(d -> moreDialog = null);
        moreDialog.show();
        list.post(list::requestFocus);
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
        int width = Math.max(dp(54), available / columns - gap * 2);
        int iconSize = Math.max(dp(32), Math.round(width * 0.8f));
        adapter.setMetrics(width, iconSize, gap);
        // One row only: sit on the bottom row of the two-row shelf (pad one row above).
        int baseV = dp(16);
        int count = adapter.getItemCount();
        int top = (count > 0 && count <= columns) ? baseV + width + gap * 2 : baseV;
        if (grid.getPaddingTop() != top || grid.getPaddingBottom() != baseV) {
            grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), baseV);
        }
        // Line the title up with the first tile's left edge (list padding + cell margin).
        int edge = grid.getPaddingLeft() + gap;
        title.setPaddingRelative(edge, title.getPaddingTop(), edge, title.getPaddingBottom());
    }

    private boolean focusApp(String id) {
        int pos = adapter.indexOf(id);
        if (pos < 0) return false;
        grid.scrollToPosition(pos);
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pos);
        if (holder != null) return holder.itemView.requestFocus();
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
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            order = NewHomeOrder.move(order, movingId, delta, pinned);
            renderHome();
        }
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
