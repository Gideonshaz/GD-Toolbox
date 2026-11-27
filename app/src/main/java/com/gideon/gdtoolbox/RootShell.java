package com.gideon.gdtoolbox;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/** Minimal root helper. Runs commands through Magisk `su`. */
public final class RootShell {

    public static class Result {
        public final int code;
        public final String out;
        public Result(int code, String out) { this.code = code; this.out = out; }
    }

    private RootShell() {}

    /** Run a command as root and wait for it to finish. Good for quick, non-blocking commands. */
    public static Result run(String command) {
        StringBuilder sb = new StringBuilder();
        int code = -1;
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            while ((line = e.readLine()) != null) sb.append(line).append('\n');
            code = p.waitFor();
        } catch (Exception ex) {
            sb.append("ERROR: ").append(ex.getMessage());
        }
        return new Result(code, sb.toString().trim());
    }

    /** True if root is available (su grantable). */
    public static boolean hasRoot() {
        Result r = run("id -u");
        return r.out.contains("0");
    }
}
