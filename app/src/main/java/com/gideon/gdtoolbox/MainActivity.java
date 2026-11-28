package com.gideon.gdtoolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {

    // ---- fixed paths / matchers for this phone (Termux + Magisk) ----
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String DC_MATCH   = "[d]esktop-commander/dist/index.js remote";
    private static final String MOCK_PKG   = "ru.gavrikov.mocklocations";

    private static final int REQ_PICK_AUDIO = 1001;

    private TextView status;
    private Button btnPtt, btnMock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.txtStatus);
        btnPtt = findViewById(R.id.btnPttToggle);
        btnMock = findViewById(R.id.btnMockToggle);

        findViewById(R.id.btnAudioToVoice).setOnClickListener(v -> pickAudio());
        btnPtt.setOnClickListener(v -> togglePtt());
        findViewById(R.id.btnDcStart).setOnClickListener(v -> dcStart());
        findViewById(R.id.btnDcStop).setOnClickListener(v -> dcStop());
        btnMock.setOnClickListener(v -> toggleMock());

        refreshPttLabel();
        refreshMockLabel();
        if (!RootShell.hasRoot()) log("⚠ Root not granted yet — tap a root button and allow the su prompt.");
    }

    // ===== 1. Audio -> Voice Note =====
    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_AUDIO && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri src = data.getData();
            log("Converting… (16 kHz mono Opus)");
            new Thread(() -> {
                try {
                    File out = new File(getCacheDir(), "PTT-" + System.currentTimeMillis() + ".opus");
                    AudioConverter.convert(this, src, out);
                    runOnUiThread(() -> { log("✔ Converted: " + out.getName() + "\nOpening WhatsApp…"); shareToWhatsApp(out); });
                } catch (Exception e) {
                    runOnUiThread(() -> log("✘ Convert failed: " + e.getMessage()));
                }
            }).start();
        }
    }

    private void shareToWhatsApp(File opus) {
        try {
            // Flag the hook to treat the next shared audio as a PTT voice note
            if (Prefs.isPtt(this)) {
                RootShell.run("echo " + opus.getAbsolutePath() +
                        " > /data/local/tmp/gdtoolbox_ptt; chmod 644 /data/local/tmp/gdtoolbox_ptt");
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fp", opus);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/ogg");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setPackage("com.whatsapp");
            startActivity(send);
        } catch (Exception e) {
            log("Share failed: " + e.getMessage() + "\nFile saved at: " + opus.getAbsolutePath());
        }
    }

    // ===== 2. Voice-note mode toggle =====
    private void togglePtt() {
        boolean now = !Prefs.isPtt(this);
        Prefs.setPtt(this, now);
        refreshPttLabel();
        log("Voice-note mode " + (now ? "ON — audio sends as a voice note" : "OFF — audio sends as a normal file"));
    }

    private void refreshPttLabel() {
        btnPtt.setText("🔁  Voice-note mode: " + (Prefs.isPtt(this) ? "ON" : "OFF"));
    }

    // ===== 3. Start Desktop Commander =====
    private void dcStart() {
        log("Starting Desktop Commander…");
        new Thread(() -> {
            String script =
                "IDX=$(ls $HOME/.npm/_npx/*/node_modules/@wonderwhy-er/desktop-commander/dist/index.js 2>/dev/null | head -1); " +
                "[ -z \"$IDX\" ] && { echo NO_DC; exit 1; }; " +
                "pgrep -f \"" + DC_MATCH + "\" >/dev/null && { echo ALREADY_RUNNING; exit 0; }; " +
                "mkdir -p $HOME/.claude-server-commander-logs; " +
                "setsid node \"$IDX\" remote >$HOME/.claude-server-commander-logs/gdtoolbox.log 2>&1 </dev/null & " +
                "echo STARTED";
            RootShell.Result r = RootShell.run(TERMUX_BASH + " -lc '" + script + "'");
            String msg;
            if (r.out.contains("STARTED")) msg = "✔ Desktop Commander started.";
            else if (r.out.contains("ALREADY_RUNNING")) msg = "• Already running.";
            else if (r.out.contains("NO_DC")) msg = "✘ DC install not found under ~/.npm/_npx.";
            else msg = "DC start output: " + r.out;
            runOnUiThread(() -> log(msg));
        }).start();
    }

    // ===== 4. Stop Desktop Commander =====
    private void dcStop() {
        log("Stopping Desktop Commander…");
        new Thread(() -> {
            RootShell.Result r = RootShell.run(TERMUX_BASH + " -lc 'pkill -f \"" + DC_MATCH +
                    "\" && echo STOPPED || echo NOT_RUNNING'");
            String msg = r.out.contains("STOPPED") ? "✔ Desktop Commander stopped."
                    : r.out.contains("NOT_RUNNING") ? "• It wasn't running."
                    : "DC stop output: " + r.out;
            runOnUiThread(() -> log(msg));
        }).start();
    }

    // ===== 5. Mock location toggle =====
    private void toggleMock() {
        new Thread(() -> {
            boolean on = RootShell.run("appops get " + MOCK_PKG + " android:mock_location").out.toLowerCase().contains("allow");
            boolean target = !on;
            RootShell.run("appops set " + MOCK_PKG + " android:mock_location " + (target ? "allow" : "deny"));
            if (target) {
                // launch the Fake GPS app so you can drop the pin
                RootShell.run("monkey -p " + MOCK_PKG + " -c android.intent.category.LAUNCHER 1");
            }
            runOnUiThread(() -> {
                refreshMockLabel();
                log("📍 Mock location " + (target ? "ON — Fake GPS selected & launched" : "OFF"));
            });
        }).start();
    }

    private void refreshMockLabel() {
        new Thread(() -> {
            boolean on = RootShell.run("appops get " + MOCK_PKG + " android:mock_location").out.toLowerCase().contains("allow");
            runOnUiThread(() -> btnMock.setText("📍  Mock location: " + (on ? "ON" : "OFF")));
        }).start();
    }

    private void log(String s) {
        status.setText(s);
    }
}
