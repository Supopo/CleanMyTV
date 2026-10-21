package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvContract;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A separate HOME page with the existing header and MiFlow-style app menu. */
public final class NewMainActivity extends Activity {
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, NewHomeApps.Entry> apps = new HashMap<>();
    private final Map<String, View> cards = new HashMap<>();
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
    private GridLayout grid;
    private ScrollView scroll;
    private TextView greeting;
    private TextView btnWifi;
    private TextView count;
    private TextView title;
    private TextView subtitle;
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
    private String focusedId;
    private String movingId;
    private boolean chooseMove;
    private int columns;
    private float density;

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
        grid.setColumnCount(columns);
        scroll = findViewById(R.id.newHomeScroll);
        greeting = findViewById(R.id.greeting);
        count = findViewById(R.id.newHomeCount);
        title = findViewById(R.id.newHomeTitle);
        subtitle = findViewById(R.id.newHomeSubtitle);
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
            View selected = cards.get(focusedId);
            if (selected == null && !order.isEmpty()) selected = cards.get(order.get(0));
            return selected != null && selected.requestFocus();
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
        scroll.smoothScrollTo(0, 0);
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
            View first = cards.get(focusedId);
            if (first != null) first.requestFocus();
            scroll.smoothScrollTo(0, 0);
        }
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
        Set<String> livePins = NewHomeOrder.livePins(store.pins(), installed);
        pinned = livePins;
        store.savePins(livePins);
        order = NewHomeOrder.reconcile(installed, store.order(), pinned);
        store.saveOrder(order);
        focusedId = restore;
        renderHome();
    }

    private void renderHome() {
        String restore = movingId != null ? movingId : focusedId;
        grid.removeAllViews();
        cards.clear();
        title.setText(movingId == null ? "你的应用" : "移动应用");
        count.setText(order.size() + " 个应用  /  " + pinned.size() + " 个置顶");
        subtitle.setText(movingId != null ? "方向键调整同组位置，确定保存，返回取消。"
                : chooseMove ? "选择一个应用并按确定，开始移动。" : "让喜爱的应用，触手可及。");
        hint.setText(movingId != null ? "移动模式 · 确定保存 · 返回取消"
                : chooseMove ? "方向键选择 · 确定选择要移动的应用 · 返回退出"
                : "方向键选择 · 确定打开 · 长按确定 / 菜单键编辑应用");
        int usable = getResources().getConfiguration().screenWidthDp - 112 - 18;
        int width = Math.max(68, usable / columns - 16);
        for (String id : order) {
            NewHomeApps.Entry app = apps.get(id);
            if (app == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(12), dp(8), dp(10));
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClickable(true);
            card.setContentDescription(app.label + (pinned.contains(id) ? "，已置顶" : ""));
            card.setBackground(glass(false));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
            TextView label = new TextView(this);
            label.setText((pinned.contains(id) ? "↑ " : "") + app.label);
            label.setTextColor(0xFFF5F7FF);
            label.setTextSize(13);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(10), 0, 0);
            card.addView(label, new LinearLayout.LayoutParams(-1, -2));
            card.setOnFocusChangeListener((view, focused) -> {
                if (focused) focusedId = id;
                view.setBackground(glass(focused));
                label.setTextColor(focused ? 0xFF17213B : 0xFFF5F7FF);
                view.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                        .translationZ(focused ? dp(12) : 0).setDuration(160).start();
            });
            card.setOnClickListener(view -> {
                if (movingId != null) finishMove();
                else if (chooseMove) startMove(id);
                else launch(app);
            });
            card.setOnLongClickListener(view -> {
                if (movingId == null) edit(app);
                return true;
            });
            card.setOnKeyListener((view, key, event) -> onCardKey(app, key, event));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(width);
            params.height = dp(116);
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
            grid.addView(card, params);
            cards.put(id, card);
        }
        View selected = cards.get(restore);
        if (selected == null && !order.isEmpty()) selected = cards.get(order.get(0));
        if (selected != null) selected.requestFocus();
        else btnSettings.requestFocus();
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
                .setItems(new String[]{pinned.contains(app.id) ? "取消置顶" : "置顶", "移动位置", "应用信息"},
                        (dialog, which) -> {
                            if (which == 0) {
                                if (!pinned.remove(app.id)) pinned.add(app.id);
                                store.savePins(pinned);
                                order = NewHomeOrder.reconcile(order, order, pinned);
                                store.saveOrder(order);
                                focusedId = app.id;
                                renderHome();
                            } else if (which == 1) {
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

    private GradientDrawable glass(boolean focused) {
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{0xFFF7FAFF, 0xFFDBE7FF}
                        : new int[]{0x38FFFFFF, 0x10FFFFFF});
        surface.setCornerRadius(dp(20));
        surface.setStroke(dp(1), focused ? 0xFFFFFFFF : 0x55FFFFFF);
        return surface;
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
