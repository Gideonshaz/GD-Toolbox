# GD Toolbox

One APK that is **both** a normal app (a button panel) **and** an LSPosed module
(a WhatsApp hook) — same dual nature as WaEnhancer. Built for Gideon's rooted
Galaxy S20+ (Magisk + LSPosed + Termux).

## Buttons

| Button | What it does | How |
|---|---|---|
| 🎙 Audio → Voice Note | Pick any audio → convert to WhatsApp voice-note spec (OGG/Opus, mono, 16 kHz) → share into WhatsApp | In-app (own process) |
| 🔁 Voice-note mode ON/OFF | When ON, the hook sends the converted file as a true PTT voice note; OFF = normal audio file | SharedPref read by the hook |
| ▶️ Start Desktop Commander | Launches the DC `remote` agent via your Termux node, detached (`setsid`) | `su` → Termux bash |
| ⏹ Stop Desktop Commander | `pkill -f '[d]esktop-commander/dist/index.js remote'` | `su` → Termux bash |
| 📍 Mock location ON/OFF | Toggles `ru.gavrikov.mocklocations` as the mock-location app via `appops`, and launches it | `su` → `appops set … allow/deny` |

## What's turnkey vs. what needs a one-time pin

**Turnkey (works on build):** DC start/stop, mock-location toggle, and the audio→Opus
**converter**. These are built against real values read off your phone, not guesses.

**Needs one-time pinning:** the *force-PTT* hook. WhatsApp's send classes are
obfuscated and renamed every update, so the exact method can't be hardcoded blind.
The module ships with a **finder**:

1. Install + enable the module in LSPosed, scope = WhatsApp, force-stop WhatsApp.
2. Record a normal voice note in any chat.
3. On a PC or via Termux: `logcat -s GDTOOLBOX` — you'll see the `com.whatsapp.*`
   classes/methods that touched the `.opus`. That's the send path.
4. Put the class + method into `WaPtt.TARGET_CLASS` / `TARGET_METHOD`
   (and `PTT_FIELD` if it's a field), rebuild. Done — it then auto-upgrades every
   `PTT-*.opus` the converter makes.

Until pinned, the converter still gives you a perfectly-encoded voice-note file;
it just rides in as an audio attachment rather than a PTT bubble.

## Build (from your phone, no PC)

1. Push this folder to a new GitHub repo.
2. The **Build APK** Action runs automatically → download `gd-toolbox-debug-apk`
   from the run's Artifacts. (Debug-signed = installable and LSPosed-loadable.)
3. Install the APK. Enable it in **LSPosed → Modules**, scope **WhatsApp** (+ Business),
   force-stop WhatsApp. Grant the app **root** when it first prompts.

## Notes

- Converter supports anything Android's MediaCodec can decode (mp3, m4a/aac, flac, ogg…).
- The DC start path is globbed (`~/.npm/_npx/*/…`) so it survives DC reinstalls.
- Nothing here touches your integrity/keybox stack — it's a separate utility.
