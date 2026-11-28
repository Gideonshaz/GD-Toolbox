package com.gideon.gdtoolbox;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed entry. Loads inside WhatsApp and (when voice-note mode is ON) forces
 * outgoing files named "PTT-*.opus" — the ones GD Toolbox produces — to be sent
 * as push-to-talk voice notes instead of audio attachments.
 *
 * WhatsApp's media-send classes are obfuscated and rename every update, so the
 * exact target method must be pinned per WhatsApp version. This class ships with
 * a FINDER that logs strong candidates to logcat (tag: GDTOOLBOX). Run it once,
 * read the log, and fill in WaPtt.TARGET_CLASS / TARGET_METHOD.
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "GDTOOLBOX";
    private static final String WA   = "com.whatsapp";
    private static final String WAB  = "com.whatsapp.w4b";

    private XSharedPreferences prefs;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!WA.equals(lpparam.packageName) && !WAB.equals(lpparam.packageName)) return;

        prefs = new XSharedPreferences("com.gideon.gdtoolbox", Prefs.FILE);
        prefs.makeWorldReadable();
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);

        if (WaPtt.isPinned()) {
            WaPtt.installForcePttHook(lpparam.classLoader, this::pttEnabled);
        } else {
            WaPtt.installFinder(lpparam.classLoader);
            XposedBridge.log(TAG + ": PTT target not pinned yet — finder active. " +
                    "Send a normal voice note, then check `logcat -s GDTOOLBOX`.");
        }
    }

    private boolean pttEnabled() {
        prefs.reload();
        return prefs.getBoolean(Prefs.KEY_PTT, true);
    }
}
