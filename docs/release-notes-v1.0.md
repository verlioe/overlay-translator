# v1.0 — first public build

Live translation overlay for Android chat apps. Side-load only; needs accessibility and
"display over other apps" permissions, plus your own OpenAI-compatible API key.

## What it does

- **Live mode** — tap the floating ball, and a dot appears next to every incoming message.
  Tap a dot for its translation.
- **Context-aware translation** — the last N messages of the conversation are sent with the
  message being translated, so pronouns and short replies make sense.
- **Both directions** — incoming text to your language, and your own draft to the other
  person's language, ready to write back into the chat input box.
- **3 relationship tones** — `熟人閒聊` (casual banter), `普通閒聊` (neutral), `正式禮貌`
  (polite/formal). Applied where it belongs: your outgoing messages and the diagnosis.
- **Diagnosis** — reviews your draft (politeness, wording, nuance) and shows a suggested
  rewrite with a preview of what it means back in your language.
- **Chat-noise filtering** — read receipts, accessibility hints ("Double tap it to see more"),
  date separators and toolbars are not treated as messages.

## Install

1. Download `app-debug.apk` below and side-load it (or `adb install -r app-debug.apk`).
2. Open the app → grant **overlay** and **accessibility** permissions → press **啟動懸浮窗**.
3. Settings → paste your API key, pick languages, gender and tone.
4. Switch to any chat app and tap the floating ball.

## Notes

- This is a **debug build** (no release signing), which is normal for a side-loaded personal app.
- Translation goes only to the endpoint you configure (DeepSeek by default). The API key is
  stored on-device, encrypted. No analytics, no telemetry.
- Extraction is heuristic — there are no per-app rules. Known limitations are listed in the README.
- Tested on realme UI / Android 16 and a Lenovo tablet / Android 16.

## Verification

- 26 JVM unit tests (`./gradlew test`) covering the extraction rules and language detection.
- CI runs the unit tests, builds this APK, and checks the prompt contract on every push.
- Demo chat inside the app (`DemoChatActivity`) lets you try the whole flow without touching
  real conversations.

**Full changelog:** see the commit history on `main`.
