package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Choices made on NewMainActivity; the old home's order.txt is untouched. */
final class NewHomeStore {
    private final SharedPreferences prefs;

    NewHomeStore(Context context) {
        prefs = context.getSharedPreferences("new_home", Context.MODE_PRIVATE);
    }

    List<String> order() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("order", "[]"));
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (JSONException ignored) { }
        return result;
    }

    void saveOrder(List<String> order) {
        prefs.edit().putString("order", new JSONArray(order).toString()).apply();
    }

    Set<String> pins() {
        return new HashSet<>(prefs.getStringSet("pins", Collections.emptySet()));
    }

    void savePins(Set<String> pins) {
        prefs.edit().putStringSet("pins", new HashSet<>(pins)).apply();
    }

    /** Apps the owner moved into the More folder. */
    Set<String> stashed() {
        return new HashSet<>(prefs.getStringSet("stashed", Collections.emptySet()));
    }

    void saveStashed(Set<String> stashed) {
        prefs.edit().putStringSet("stashed", new HashSet<>(stashed)).apply();
    }
}
