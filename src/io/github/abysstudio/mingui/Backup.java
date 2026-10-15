package io.github.abysstudio.mingui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Export / import of settings, habits, history, streaks and buddy as one JSON file. */
final class Backup {
    private static final int VERSION = 1;

    /**
     * Keys that describe this device or today's in-progress state rather than your data:
     * kept as they are on restore instead of being overwritten.
     */
    private static boolean deviceState(String key) {
        return key.startsWith("done_") || key.startsWith("tstart_") || key.startsWith("breaks_")
                || key.startsWith("prompts_") || key.equals("break_until") || key.equals("prompt_last")
                || key.equals("hidden_pkgs") || key.equals("hidden") || key.equals("ops_version")
                || key.equals("root_ok") || key.equals("buddy_hidden_until") || key.equals("buddy_y")
                || key.equals("celebrated_day") || key.equals("freeze_checked") || key.equals("freeze_notice");
    }

    private Backup() {}

    static String export(Context c) throws JSONException {
        return new JSONObject()
                .put("app", "mingui")
                .put("version", VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("routine", encode(Routine.prefs(c).getAll()))
                .put("history", encode(History.store(c).getAll()))
                .toString(2);
    }

    static void restore(Context c, String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        // "routine-gate" = backups made before the app was renamed.
        if (!"mingui".equals(o.optString("app")) && !"routine-gate".equals(o.optString("app"))) throw new JSONException(c.getString(R.string.not_a_backup));
        apply(Routine.prefs(c), o.getJSONObject("routine"), true);
        apply(History.store(c), o.getJSONObject("history"), false);
    }

    private static void apply(SharedPreferences prefs, JSONObject data, boolean keepDeviceState)
            throws JSONException {
        Map<String, ?> current = prefs.getAll();
        SharedPreferences.Editor e = prefs.edit().clear();
        if (keepDeviceState) {
            for (Map.Entry<String, ?> en : current.entrySet()) {
                if (deviceState(en.getKey())) put(e, en.getKey(), en.getValue());
            }
        }
        JSONArray names = data.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                if (keepDeviceState && deviceState(k)) continue;
                JSONObject v = data.getJSONObject(k);
                switch (v.getString("t")) {
                    case "s": e.putString(k, v.getString("v")); break;
                    case "i": e.putInt(k, v.getInt("v")); break;
                    case "l": e.putLong(k, v.getLong("v")); break;
                    case "f": e.putFloat(k, (float) v.getDouble("v")); break;
                    case "b": e.putBoolean(k, v.getBoolean("v")); break;
                    case "set": {
                        Set<String> set = new HashSet<>();
                        JSONArray a = v.getJSONArray("v");
                        for (int j = 0; j < a.length(); j++) set.add(a.getString(j));
                        e.putStringSet(k, set);
                        break;
                    }
                    default: break;
                }
            }
        }
        e.commit();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof String) e.putString(k, (String) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
    }

    private static JSONObject encode(Map<String, ?> map) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> en : map.entrySet()) {
            Object v = en.getValue();
            JSONObject x = new JSONObject();
            if (v instanceof String) x.put("t", "s").put("v", v);
            else if (v instanceof Integer) x.put("t", "i").put("v", v);
            else if (v instanceof Long) x.put("t", "l").put("v", v);
            else if (v instanceof Float) x.put("t", "f").put("v", (double) (Float) v);
            else if (v instanceof Boolean) x.put("t", "b").put("v", v);
            else if (v instanceof Set) x.put("t", "set").put("v", new JSONArray((Set<?>) v));
            else continue;
            out.put(en.getKey(), x);
        }
        return out;
    }
}
