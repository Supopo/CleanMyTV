package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordering for the new home, independent of the old dock order. */
final class NewHomeOrder {
    private NewHomeOrder() { }

    static List<String> reconcile(List<String> installed, List<String> saved, Set<String> pinned) {
        Set<String> remaining = new LinkedHashSet<>(installed);
        List<String> ordered = new ArrayList<>();
        for (String id : saved) if (remaining.remove(id)) ordered.add(id);
        ordered.addAll(remaining);
        List<String> result = new ArrayList<>();
        for (String id : ordered) if (pinned.contains(id)) result.add(id);
        for (String id : ordered) if (!pinned.contains(id)) result.add(id);
        return result;
    }

    static Set<String> livePins(Set<String> pinned, List<String> installed) {
        Set<String> result = new LinkedHashSet<>(pinned);
        result.retainAll(installed);
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
}
