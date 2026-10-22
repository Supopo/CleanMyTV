package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * New-home shelf choices. Kept in an AtomicFile under {@link Context#getFilesDir()} (same approach
 * as {@link Order}) so a TV reboot cannot lose them the way in-memory fields or a late
 * SharedPreferences flush can. Migrates once from the older {@code new_home} prefs if present.
 */
final class NewHomeStore {
    private static final String TAG = "TvHome.NewHome";
    private static final String FILE_NAME = "new_home_shelf.json";
    private static final String PREFS_NAME = "new_home";

    private final Context app;
    private final AtomicFile file;
    private final Object lock = new Object();

    /** Bumped on every successful owner write so an in-flight reload cannot overwrite newer data. */
    private int epoch;
    /** Last durable snapshot; avoids treating a failed read as an empty shelf. */
    private Snapshot cached = Snapshot.empty();

    NewHomeStore(Context context) {
        app = context.getApplicationContext();
        file = new AtomicFile(new File(app.getFilesDir(), FILE_NAME));
        Snapshot loaded = readFile();
        if (loaded != null) cached = loaded;
        else migrateFromPrefsIfNeeded();
    }

    int epoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    List<String> order() {
        synchronized (lock) {
            return new ArrayList<>(cached.order);
        }
    }

    void saveOrder(List<String> order) {
        synchronized (lock) {
            write(new Snapshot(order, cached.stashed, cached.pins));
        }
    }

    Set<String> pins() {
        synchronized (lock) {
            return new HashSet<>(cached.pins);
        }
    }

    void savePins(Set<String> pins) {
        synchronized (lock) {
            write(new Snapshot(cached.order, cached.stashed, pins));
        }
    }

    Set<String> stashed() {
        synchronized (lock) {
            return new HashSet<>(cached.stashed);
        }
    }

    void saveStashed(Set<String> stashed) {
        synchronized (lock) {
            write(new Snapshot(cached.order, stashed, cached.pins));
        }
    }

    /** Saves order + stashed + pins in one atomic write (used by reload reconcile). */
    void saveAll(List<String> order, Set<String> stashed, Set<String> pins) {
        synchronized (lock) {
            write(new Snapshot(order, stashed, pins));
        }
    }

    private Snapshot readFile() {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return null;
        }
        try {
            byte[] bytes = file.readFully();
            return Snapshot.decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "cannot read " + FILE_NAME + ": " + e.getMessage());
            return null;
        }
    }

    /** @return true when the shelf file was written and fsynced. */
    private boolean write(Snapshot next) {
        FileOutputStream out = null;
        try {
            byte[] bytes = next.encode().getBytes(StandardCharsets.UTF_8);
            out = file.startWrite();
            out.write(bytes);
            out.getFD().sync();
            file.finishWrite(out);
            cached = next;
            epoch++;
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cannot write " + FILE_NAME + ": " + e.getMessage());
            if (out != null) file.failWrite(out);
            return false;
        }
    }

    /** One-shot copy from SharedPreferences used by older builds. */
    private void migrateFromPrefsIfNeeded() {
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains("order") && !prefs.contains("stashed") && !prefs.contains("pins")) return;
        List<String> order = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("order", "[]"));
            for (int i = 0; i < array.length(); i++) order.add(array.getString(i));
        } catch (JSONException ignored) { }
        Set<String> stashed = new HashSet<>(prefs.getStringSet("stashed", Collections.emptySet()));
        Set<String> pins = new HashSet<>(prefs.getStringSet("pins", Collections.emptySet()));
        if (order.isEmpty() && stashed.isEmpty() && pins.isEmpty()) return;
        synchronized (lock) {
            if (write(new Snapshot(order, stashed, pins))) {
                prefs.edit().clear().commit();
            }
        }
    }

    private static final class Snapshot {
        final List<String> order;
        final Set<String> stashed;
        final Set<String> pins;

        Snapshot(List<String> order, Set<String> stashed, Set<String> pins) {
            this.order = Collections.unmodifiableList(new ArrayList<>(order == null
                    ? Collections.emptyList() : order));
            this.stashed = Collections.unmodifiableSet(new HashSet<>(stashed == null
                    ? Collections.emptySet() : stashed));
            this.pins = Collections.unmodifiableSet(new HashSet<>(pins == null
                    ? Collections.emptySet() : pins));
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptySet(), Collections.emptySet());
        }

        String encode() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("order", toArray(order));
            json.put("stashed", toArray(stashed));
            json.put("pins", toArray(pins));
            return json.toString();
        }

        private static JSONArray toArray(Iterable<String> values) {
            JSONArray array = new JSONArray();
            if (values == null) return array;
            for (String value : values) array.put(value);
            return array;
        }

        static Snapshot decode(String text) throws JSONException {
            JSONObject json = new JSONObject(text);
            List<String> order = new ArrayList<>();
            JSONArray orderArr = json.optJSONArray("order");
            if (orderArr != null) {
                for (int i = 0; i < orderArr.length(); i++) order.add(orderArr.getString(i));
            }
            Set<String> stashed = new HashSet<>();
            JSONArray stashArr = json.optJSONArray("stashed");
            if (stashArr != null) {
                for (int i = 0; i < stashArr.length(); i++) stashed.add(stashArr.getString(i));
            }
            Set<String> pins = new HashSet<>();
            JSONArray pinArr = json.optJSONArray("pins");
            if (pinArr != null) {
                for (int i = 0; i < pinArr.length(); i++) pins.add(pinArr.getString(i));
            }
            return new Snapshot(order, stashed, pins);
        }
    }
}
