/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.sessionbackup;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * Exports / imports the Instagram login session so the app can be reinstalled
 * (or installed as a clone) without re-logging in.
 *
 * <p>IG 435 stores session credentials in an encrypted "cask" prefs file named
 * {@code AuthHeaderPrefs} (AES/GCM via AndroidKeyStore), keyed by user id, plus
 * the current-user JSON under the {@code current} key of the default
 * (PreferenceManager) shared prefs. The encrypted file can only be read with
 * the per-install keystore key, so export DECRYPTS through IG's own loader and
 * import re-writes through it (a fresh install generates a new key; writing the
 * plaintext through IG's writer re-encrypts with the new key).</p>
 *
 * <p>Access is reflective over the obfuscated classes (X.2xf static loader,
 * X.2wz cask prefs) so no stubs are needed. Both classes live in classes1.dex
 * on 435 and are stable there; every entry point logs on failure.</p>
 *
 * <p>Exported JSON shape:</p>
 * <pre>
 * {
 *   "version": 1,
 *   "users": [ {"userId": "...", "authHeader": "Bearer IGT:2:..."} ],
 *   "current": "..." | null,
 *   "userDataMap": { "<userId>": "<json text>" }   // optional
 * }
 * </pre>
 */
public final class SessionBackup {

    private static final String TAG = "SessionBackup";

    /** Obfuscated loader: X.2xf.A00(Context, String, long, boolean) -> X.2wz */
    private static final String CLASS_PREF_LOADER = "X.2xf";
    private static final String CLASS_CASK_PREFS = "X.2wz";
    private static final String AUTH_HEADER_PREFS = "AuthHeaderPrefs";

    private SessionBackup() {}

    // ------------------------------------------------------------------
    // Public API (called from BackupSessionActivity / RestoreSessionActivity)
    // ------------------------------------------------------------------

    /** Builds the session JSON from the current install. Returns null on failure. */
    public static String exportSessionJson(Context context) {
        try {
            Map<String, String> authHeaders = readAuthHeaderPrefs(context);
            if (authHeaders.isEmpty()) {
                Logger.printInfo(() -> "export: AuthHeaderPrefs empty or unreadable");
                return null;
            }

            JSONObject json = new JSONObject();
            json.put("version", 3);

            // Piko's own settings (all patch toggles), so a migration also
            // restores the user's piko configuration. Keys that don't map to
            // patches in the target build are simply ignored by it.
            JSONObject pikoSettings = new JSONObject();
            try {
                SharedPreferences piko =
                        context.getSharedPreferences(
                                app.morphe.extension.instagram.constants.Constants.PIKO_SETTINGS, 0);
                Map<String, ?> all = piko.getAll();
                if (all != null) {
                    for (Map.Entry<String, ?> e : all.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof String) {
                            pikoSettings.put(e.getKey(), (String) v);
                        } else if (v instanceof Boolean) {
                            pikoSettings.put(e.getKey(), (Boolean) v);
                        } else if (v instanceof Float) {
                            pikoSettings.put(e.getKey(), (Float) v);
                        } else if (v instanceof Integer) {
                            pikoSettings.put(e.getKey(), (Integer) v);
                        } else if (v instanceof Long) {
                            pikoSettings.put(e.getKey(), (Long) v);
                        }
                    }
                }
            } catch (Exception e) {
                Logger.printException(() -> "export: piko settings read failed", e);
            }
            json.put("pikoSettings", pikoSettings);

            // Everything from AuthHeaderPrefs: numeric-id entries are account
            // auth headers; DEVICE_HEADER_ID is the device identity the server
            // binds the token to — both must travel together.
            JSONObject headers = new JSONObject();
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                headers.put(entry.getKey(), entry.getValue());
            }
            json.put("authHeaderPrefs", headers);

            // RoutingHeaderPrefs (x-mid, region hint, SHBID/SHBTS...): the cask
            // name is suffixed with the mid; find it by prefix scan.
            JSONObject routing = new JSONObject();
            try {
                for (String name : caskNames(context)) {
                    if (name.startsWith("RoutingHeaderPrefs")) {
                        Object cask = caskByName(context, name);
                        if (cask != null) {
                            java.lang.reflect.Method getAll =
                                    cask.getClass().getMethod("getAll");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> all =
                                    (Map<String, Object>) getAll.invoke(cask);
                            if (all != null) {
                                JSONObject inner = new JSONObject();
                                for (Map.Entry<String, Object> e : all.entrySet()) {
                                    if (e.getValue() instanceof String) {
                                        inner.put(e.getKey(), (String) e.getValue());
                                    }
                                }
                                routing.put(name, inner);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logger.printException(() -> "export: routing prefs read failed", e);
            }
            json.put("routingHeaderPrefs", routing);

            SharedPreferences defaultPrefs = defaultSharedPreferences(context);
            String current = defaultPrefs != null ? defaultPrefs.getString("current", null) : null;
            json.put("current", current == null ? JSONObject.NULL : current);

            // "user_access_map" holds serialized user dicts (profile cache) that the
            // session bootstrap parses; export it too so a fresh install can restore
            // the account list without a server round-trip.
            String userAccessMap = defaultPrefs != null ? defaultPrefs.getString("user_access_map", null) : null;
            json.put("user_access_map", userAccessMap == null ? JSONObject.NULL : userAccessMap);

            return json.toString(2);
        } catch (Exception e) {
            Logger.printException(() -> "exportSessionJson failed", e);
            return null;
        }
    }

    /** Writes the exported JSON to a SAF uri-selected stream. */
    public static boolean writeExport(Context context, String jsonText, OutputStream stream) {
        try {
            BufferedOutputStream out = new BufferedOutputStream(stream, 8192);
            out.write(jsonText.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "writeExport failed", e);
            return false;
        }
    }

    /**
     * Restores a session from exported JSON. Writes the auth header(s) into
     * AuthHeaderPrefs via IG's encrypted-cask writer and seeds the default
     * prefs' "current"/"user_access_map" values. Caller should restart the
     * app afterwards so the session bootstrap picks everything up.
     */
    public static boolean importSessionJson(Context context, String jsonText) {
        try {
            JSONObject json = new JSONObject(jsonText);
            int version = json.optInt("version", -1);
            if (version < 1 || version > 3) {
                Logger.printInfo(() -> "import: unsupported version " + version);
                return false;
            }

            // v1: users[] of {userId, authHeader}. v2: full authHeaderPrefs map
            // including DEVICE_HEADER_ID.
            Map<String, String> authHeaders = new HashMap<>();
            if (version >= 2) {
                JSONObject headers = json.optJSONObject("authHeaderPrefs");
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String key = it.next();
                        String value = headers.optString(key, "");
                        if (!value.isEmpty()) {
                            authHeaders.put(key, value);
                        }
                    }
                }
            } else {
                JSONArray users = json.optJSONArray("users");
                if (users != null) {
                    for (int i = 0; i < users.length(); i++) {
                        JSONObject user = users.getJSONObject(i);
                        String userId = user.optString("userId", "");
                        String authHeader = user.optString("authHeader", "");
                        if (!userId.isEmpty() && !authHeader.isEmpty()) {
                            authHeaders.put(userId, authHeader);
                        }
                    }
                }
            }
            if (authHeaders.isEmpty()) {
                Logger.printInfo(() -> "import: no auth headers in JSON");
                return false;
            }

            if (!writeAuthHeaderPrefs(context, authHeaders)) {
                return false;
            }

            // RoutingHeaderPrefs: write each exported cask back under its own name.
            if (version >= 2) {
                JSONObject routing = json.optJSONObject("routingHeaderPrefs");
                if (routing != null) {
                    Iterator<String> casks = routing.keys();
                    while (casks.hasNext()) {
                        String caskName = casks.next();
                        JSONObject inner = routing.optJSONObject(caskName);
                        if (inner == null) {
                            continue;
                        }
                        Map<String, String> entries = new HashMap<>();
                        Iterator<String> keys = inner.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            String v = inner.optString(k, "");
                            if (!v.isEmpty()) {
                                entries.put(k, v);
                            }
                        }
                        if (!entries.isEmpty()) {
                            writeCask(context, caskName, entries);
                        }
                    }
                }
            }

            SharedPreferences.Editor editor = null;
            SharedPreferences defaultPrefs = defaultSharedPreferences(context);
            if (defaultPrefs != null) {
                editor = defaultPrefs.edit();
            }
            if (editor == null) {
                Logger.printInfo(() -> "import: default prefs unavailable for current/user_access_map");
                return false;
            }

            if (!json.isNull("current")) {
                editor.putString("current", json.getString("current"));
            }
            if (!json.isNull("user_access_map")) {
                editor.putString("user_access_map", json.getString("user_access_map"));
            }
            editor.apply();

            // v3: restore piko settings (patch toggles) on top of the session.
            if (version >= 3) {
                JSONObject pikoSettings = json.optJSONObject("pikoSettings");
                if (pikoSettings != null && pikoSettings.length() > 0) {
                    try {
                        SharedPreferences piko =
                                context.getSharedPreferences(
                                        app.morphe.extension.instagram.constants.Constants.PIKO_SETTINGS, 0);
                        SharedPreferences.Editor pikoEditor = piko.edit();
                        Iterator<String> it = pikoSettings.keys();
                        while (it.hasNext()) {
                            String key = it.next();
                            Object value = pikoSettings.get(key);
                            if (value instanceof Boolean) {
                                pikoEditor.putBoolean(key, (Boolean) value);
                            } else if (value instanceof String) {
                                pikoEditor.putString(key, (String) value);
                            } else if (value instanceof Number) {
                                // Piko's numeric settings (e.g. ring size) are
                                // StringSettings; round-trip as strings.
                                pikoEditor.putString(key, String.valueOf(value));
                            }
                        }
                        pikoEditor.apply();
                    } catch (Exception e) {
                        Logger.printException(() -> "import: piko settings write failed", e);
                    }
                }
            }

            return true;
        } catch (Exception e) {
            Logger.printException(() -> "importSessionJson failed", e);
            return false;
        }
    }

    /**
     * True when no account auth headers are stored (fresh install / logged
     * out). The store also holds non-account keys (e.g. DEVICE_HEADER_ID),
     * so emptiness is not a valid check: an account header is one whose key
     * is a numeric user id.
     */
    public static boolean isLoggedOut(Context context) {
        try {
            Map<String, String> headers = readAuthHeaderPrefs(context);
            boolean hasAccount = false;
            for (String key : headers.keySet()) {
                if (key != null && key.matches("\\d+")) {
                    hasAccount = true;
                    break;
                }
            }
            final boolean loggedOut = !hasAccount;
            Logger.printInfo(() -> "SessionBackup: isLoggedOut map size=" + headers.size()
                    + " keys=" + headers.keySet() + " loggedOut=" + loggedOut);
            return loggedOut;
        } catch (Exception e) {
            Logger.printException(() -> "isLoggedOut check failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Encrypted AuthHeaderPrefs access (reflective over X.2xf / X.2wz)
    // ------------------------------------------------------------------

    /**
     * Instantiates a cask via X.2xf.A00 (loader picks the right encrypted-store
     * transformer and memoizes it in X.2wz.A0A).
     */
    private static Object caskPrefs(Context context, String name) throws Exception {
        Class<?> loader = Class.forName(CLASS_PREF_LOADER);
        Object prefs = null;
        for (java.lang.reflect.Method m : loader.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4
                    && Context.class.isAssignableFrom(p[0])
                    && String.class.isAssignableFrom(p[1])
                    && (p[2] == long.class || p[2] == Long.class)
                    && (p[3] == boolean.class || p[3] == Boolean.class)) {
                m.setAccessible(true);
                prefs = m.invoke(null, context, name, 0L, false);
                break;
            }
        }
        if (prefs == null) {
            throw new IllegalStateException("X.2xf.A00(Context,String,long,boolean) not found");
        }
        return prefs;
    }

    private static Object caskPrefs(Context context) throws Exception {
        return caskPrefs(context, AUTH_HEADER_PREFS);
    }

    /**
     * Enumerates cask names by listing the encrypted-store directory
     * (files under <dataDir>/app_android_igapps_encryptedstore_single) plus
     * plain-prefs fallback names present in shared_prefs.
     */
    private static java.util.List<String> caskNames(Context context) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        try {
            java.io.File dataDir = new File(context.getApplicationInfo().dataDir);
            java.io.File store = new File(dataDir, "app_android_igapps_encryptedstore_single");
            java.io.File[] files = store.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    names.add(f.getName());
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "caskNames: store dir scan failed", e);
        }
        try {
            java.io.File sp = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            java.io.File[] files = sp.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (n.endsWith(".xml") && n.startsWith("RoutingHeaderPrefs")) {
                        names.add(n.substring(0, n.length() - 4));
                    }
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "caskNames: shared_prefs scan failed", e);
        }
        Logger.printInfo(() -> "caskNames: " + names);
        return names;
    }

    /** Opens the named cask (or null). */
    private static Object caskByName(Context context, String name) {
        try {
            return caskPrefs(context, name);
        } catch (Exception e) {
            Logger.printException(() -> "caskByName(" + name + ") failed", e);
            return null;
        }
    }

    /** Writes a whole map into the named cask through its editor. */
    private static boolean writeCask(Context context, String name, Map<String, String> entries) {
        try {
            Object prefs = caskPrefs(context, name);
            Object editor = prefs.getClass().getMethod("AuT").invoke(prefs);
            if (editor == null) {
                throw new IllegalStateException("cask AuT() returned null");
            }
            java.lang.reflect.Method putString = null;
            java.lang.reflect.Method apply = null;
            for (java.lang.reflect.Method m : editor.getClass().getMethods()) {
                if (apply == null && m.getName().equals("apply") && m.getParameterCount() == 0) {
                    apply = m;
                }
                Class<?>[] p = m.getParameterTypes();
                if (putString == null && m.getName().equals("G8l")
                        && p.length == 2
                        && String.class.isAssignableFrom(p[0])
                        && String.class.isAssignableFrom(p[1])) {
                    putString = m;
                }
            }
            if (putString == null || apply == null) {
                throw new IllegalStateException("cask editor G8l/apply not found");
            }
            for (Map.Entry<String, String> e : entries.entrySet()) {
                putString.invoke(editor, e.getKey(), e.getValue());
            }
            apply.invoke(editor);
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "writeCask(" + name + ") failed", e);
            return false;
        }
    }

    /** Reads all auth header entries by calling X.2wz.getAll(). */
    private static Map<String, String> readAuthHeaderPrefs(Context context) {
        Map<String, String> result = new HashMap<>();
        try {
            Object prefs = caskPrefs(context);
            java.lang.reflect.Method getAll = prefs.getClass().getMethod("getAll");
            @SuppressWarnings("unchecked")
            Map<String, Object> all = (Map<String, Object>) getAll.invoke(prefs);
            if (all != null) {
                for (Map.Entry<String, Object> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String) {
                        result.put(e.getKey(), (String) v);
                    }
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "readAuthHeaderPrefs failed (class layout drift?)", e);
        }
        return result;
    }

    /**
     * Writes auth headers through X.2wz's editor (AuT() -> GuM, G8l(key, value),
     * apply()) so values are encrypted with the CURRENT install's keystore key.
     */
    private static boolean writeAuthHeaderPrefs(Context context, Map<String, String> authHeaders) {
        try {
            Object prefs = caskPrefs(context);
            Object editor = prefs.getClass().getMethod("AuT").invoke(prefs);
            if (editor == null) {
                throw new IllegalStateException("cask AuT() returned null");
            }
            java.lang.reflect.Method putString = null;
            java.lang.reflect.Method apply = null;
            for (java.lang.reflect.Method m : editor.getClass().getMethods()) {
                if (apply == null && m.getName().equals("apply") && m.getParameterCount() == 0) {
                    apply = m;
                }
                Class<?>[] p = m.getParameterTypes();
                if (putString == null && m.getName().equals("G8l")
                        && p.length == 2
                        && String.class.isAssignableFrom(p[0])
                        && String.class.isAssignableFrom(p[1])) {
                    putString = m;
                }
            }
            if (putString == null || apply == null) {
                throw new IllegalStateException("cask editor G8l/apply not found");
            }
            for (Map.Entry<String, String> e : authHeaders.entrySet()) {
                putString.invoke(editor, e.getKey(), e.getValue());
            }
            apply.invoke(editor);
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "writeAuthHeaderPrefs failed (class layout drift?)", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Default (PreferenceManager) shared prefs
    // ------------------------------------------------------------------

    /**
     * The "current"/"user_access_map" keys live in PreferenceManager's default
     * shared prefs ("com.instagram.android_preferences", clone-renamed along
     * with the package). Resolved reflectively to avoid hardcoding the name.
     */
    private static SharedPreferences defaultSharedPreferences(Context context) {
        // 1) PreferenceManager.getDefaultSharedPreferences (androidx or platform)
        for (String cn : new String[] {
                "androidx.preference.PreferenceManager",
                "android.preference.PreferenceManager"}) {
            try {
                Class<?> pm = Class.forName(cn);
                java.lang.reflect.Method getDefault = pm.getDeclaredMethod(
                        "getDefaultSharedPreferences", Context.class);
                Object prefs = getDefault.invoke(null, context);
                if (prefs instanceof SharedPreferences) {
                    return (SharedPreferences) prefs;
                }
            } catch (Exception ignored) {
            }
        }
        // 2) Fall back to the conventional name
        try {
            return context.getSharedPreferences(context.getPackageName() + "_preferences", 0);
        } catch (Exception e) {
            Logger.printException(() -> "defaultSharedPreferences failed", e);
            return null;
        }
    }
}
