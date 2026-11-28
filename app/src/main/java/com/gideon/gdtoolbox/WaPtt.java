package com.gideon.gdtoolbox;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The WhatsApp-version-specific part. Everything obfuscation-sensitive lives here
 * so it's the only file you touch when WhatsApp updates.
 */
public final class WaPtt {

    public interface Gate { boolean enabled(); }

    // ====== PIN THESE from the finder output, then rebuild ======
    // Example once discovered:
    //   TARGET_CLASS  = "com.whatsapp.xyz.MediaSender";
    //   TARGET_METHOD = "sendAudio";
    //   PTT_FIELD     = "ptt";   // boolean field on the media/message object, or ""
    public static final String TARGET_CLASS  = "";
    public static final String TARGET_METHOD = "";
    public static final String PTT_FIELD     = "";
    // ============================================================

    private WaPtt() {}

    public static boolean isPinned() {
        return !TARGET_CLASS.isEmpty() && !TARGET_METHOD.isEmpty();
    }

    /** Only our converted files (PTT-*.opus) should be upgraded to voice notes. */
    private static boolean isOurOpus(String path) {
        if (path == null) return false;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.startsWith("PTT-") && name.endsWith(".opus");
    }

    // ---------- Finder: logs WhatsApp's own opus-handling call chain ----------
    public static void installFinder(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookConstructor(File.class, String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String arg = (String) p.args[0];
                    if (arg == null || !arg.endsWith(".opus")) return;
                    StringBuilder sb = new StringBuilder(HookEntry.TAG + ": .opus touched -> " + arg + "\n");
                    for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                        String c = e.getClassName();
                        if (c.startsWith("com.whatsapp")) {
                            sb.append("    @ ").append(c).append('.').append(e.getMethodName()).append('\n');
                        }
                    }
                    XposedBridge.log(sb.toString());
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": finder install failed: " + t);
        }
    }

    // ---------- Force PTT once the target is pinned ----------
    public static void installForcePttHook(ClassLoader cl, final Gate gate) {
        try {
            Class<?> target = XposedHelpers.findClass(TARGET_CLASS, cl);
            XposedBridge.hookAllMethods(target, TARGET_METHOD, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!gate.enabled()) return;

                    // 1) confirm this send is one of OUR converted files
                    boolean ours = false;
                    for (Object a : p.args) {
                        if (a instanceof String && isOurOpus((String) a)) { ours = true; break; }
                        if (a instanceof File && isOurOpus(((File) a).getAbsolutePath())) { ours = true; break; }
                    }
                    if (!ours) return;

                    // 2) flip the ptt signal. Two common shapes — adjust to what the finder shows:
                    //    (a) a boolean parameter on the method
                    for (int i = 0; i < p.args.length; i++) {
                        if (p.args[i] instanceof Boolean) { p.args[i] = Boolean.TRUE; }
                    }
                    //    (b) a boolean field on the message/media object
                    if (!PTT_FIELD.isEmpty() && p.thisObject != null) {
                        try { XposedHelpers.setBooleanField(p.thisObject, PTT_FIELD, true); }
                        catch (Throwable ignore) {}
                    }
                    XposedBridge.log(HookEntry.TAG + ": forced PTT on outgoing voice note");
                }
            });
            XposedBridge.log(HookEntry.TAG + ": force-PTT hook installed on "
                    + TARGET_CLASS + "." + TARGET_METHOD);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": force-PTT install failed: " + t);
        }
    }
}
