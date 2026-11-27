package com.gideon.gdtoolbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App-side settings. Stored under the module package so the Xposed hook can read
 * them via XSharedPreferences (LSPosed makes this prefs file readable to the hook).
 */
public final class Prefs {
    public static final String FILE = "config";
    public static final String KEY_PTT = "ptt_mode"; // true = send our audio as a voice note

    private Prefs() {}

    public static SharedPreferences get(Context ctx) {
        // MODE_WORLD_READABLE is deprecated but LSPosed tolerates it for XSharedPreferences.
        try {
            //noinspection deprecation
            return ctx.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    public static boolean isPtt(Context ctx) {
        return get(ctx).getBoolean(KEY_PTT, true);
    }

    public static void setPtt(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_PTT, on).apply();
    }
}
