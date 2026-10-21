package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Discovers actual launchable activities, preferring their TV entry points. */
final class NewHomeApps {
    static final class Entry {
        final String id;
        final String packageName;
        final String label;
        final ComponentName component;
        final Drawable icon;

        Entry(ResolveInfo info, PackageManager pm) {
            component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            id = component.flattenToString();
            packageName = component.getPackageName();
            label = String.valueOf(info.loadLabel(pm));
            Drawable loaded;
            try { loaded = info.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = pm.getDefaultActivityIcon(); }
            icon = loaded;
        }
    }

    private NewHomeApps() { }

    static List<Entry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null || !info.activityInfo.enabled
                        || !info.activityInfo.applicationInfo.enabled || !info.activityInfo.exported
                        || context.getPackageName().equals(info.activityInfo.packageName)) continue;
                Entry entry = new Entry(info, pm);
                unique.putIfAbsent(entry.id, entry);
            }
        }
        List<Entry> result = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort((left, right) -> collator.compare(left.label, right.label));
        return result;
    }
}
