package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordering for the new home, independent of the old dock order. */
final class NewHomeOrder {
    private NewHomeOrder() { }

    /**
     * Rebuilds the shelf order from installed launchables, keeping the owner's saved sequence.
     * When a component id changes but the package is the same (Leanback vs phone launcher),
     * the live id is substituted so a reboot does not scramble the shelf.
     */
    static List<String> reconcile(List<String> installed, List<String> saved, Set<String> pinned) {
        Map<String, String> byPackage = packageIndex(installed);
        Set<String> remaining = new LinkedHashSet<>(installed);
        List<String> ordered = new ArrayList<>();
        for (String id : saved) {
            String live = resolve(id, remaining, byPackage);
            if (live != null) ordered.add(live);
        }
        ordered.addAll(remaining);
        List<String> result = new ArrayList<>();
        Set<String> livePins = remapIds(pinned, installed);
        for (String id : ordered) if (livePins.contains(id)) result.add(id);
        for (String id : ordered) if (!livePins.contains(id)) result.add(id);
        return result;
    }

    /** Maps saved ids onto today's installed component ids (exact match, else same package). */
    static Set<String> remapIds(Set<String> saved, List<String> installed) {
        if (saved == null || saved.isEmpty()) return new LinkedHashSet<>();
        Map<String, String> byPackage = packageIndex(installed);
        Set<String> remaining = new LinkedHashSet<>(installed);
        Set<String> result = new LinkedHashSet<>();
        for (String id : saved) {
            String live = resolve(id, remaining, byPackage);
            if (live != null) result.add(live);
        }
        return result;
    }

    /**
     * True when this PackageManager scan looks too thin to trust for wiping the saved shelf
     * (common for a few seconds after TV boot).
     */
    static boolean looksIncomplete(List<String> saved, List<String> installed) {
        if (saved == null || saved.isEmpty()) return false;
        if (installed == null) return true;
        if (installed.size() >= saved.size()) return false;
        Map<String, String> byPackage = packageIndex(installed);
        Set<String> remaining = new LinkedHashSet<>(installed);
        int matched = 0;
        for (String id : saved) {
            if (resolve(id, remaining, byPackage) != null) matched++;
        }
        // Most of the owner's shelf is missing from this scan — wait for a fuller pass.
        return matched < (saved.size() + 1) / 2;
    }

    static Set<String> livePins(Set<String> pinned, List<String> installed) {
        return livePins(pinned, installed, Integer.MAX_VALUE);
    }

    /** Keeps installed pins only, and at most {@code max} of them (first wins). */
    static Set<String> livePins(Set<String> pinned, List<String> installed, int max) {
        Set<String> remapped = remapIds(pinned, installed);
        Set<String> result = new LinkedHashSet<>();
        if (max <= 0) return result;
        for (String id : remapped) {
            result.add(id);
            if (result.size() >= max) break;
        }
        return result;
    }

    static List<String> move(List<String> order, String id, int delta, Set<String> pinned) {
        List<String> result = new ArrayList<>(order);
        int from = result.indexOf(id), to = from + delta;
        if (from >= 0 && to >= 0 && to < result.size()
                && pinned.contains(id) == pinned.contains(result.get(to))) {
            result.set(from, result.get(to));
            result.set(to, id);
        }
        return result;
    }

    /** {@code component.flattenToString()} package prefix, or the whole token if it has no slash. */
    static String packageOf(String componentId) {
        if (componentId == null) return "";
        int slash = componentId.indexOf('/');
        return slash > 0 ? componentId.substring(0, slash) : componentId;
    }

    private static Map<String, String> packageIndex(List<String> installed) {
        Map<String, String> byPackage = new LinkedHashMap<>();
        if (installed == null) return byPackage;
        for (String id : installed) {
            String pkg = packageOf(id);
            if (!pkg.isEmpty()) byPackage.putIfAbsent(pkg, id);
        }
        return byPackage;
    }

    private static String resolve(String savedId, Set<String> remaining,
                                 Map<String, String> byPackage) {
        if (remaining.remove(savedId)) return savedId;
        String live = byPackage.get(packageOf(savedId));
        if (live != null && remaining.remove(live)) return live;
        return null;
    }
}
