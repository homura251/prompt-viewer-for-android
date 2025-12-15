# Prompt Reader for Android — HANDOFF

Last updated: 2025-12-16

Repo: `https://github.com/homura251/prompt-reader-for-android.git` (branch: `master`)

This file is written in English to avoid Windows console encoding issues.

## Goals
- Read embedded SD metadata from images on Android and present:
  - Positive / Negative prompts
  - Settings (structured key/value)
  - Raw metadata (JSON/text) with good performance
- Support common sources: A1111, ComfyUI, SwarmUI, Fooocus, NovelAI (legacy + stealth).

## Project layout
- `prompt-reader-android/`: Android app (Kotlin + Jetpack Compose).
- `prompt-reader-kotlin/`: future shared Kotlin core (currently not used by Android parsers).
- `comfyui-workflow-prompt-viewer/`: vendored Python reference implementation (used for alignment + fixtures).

## Build
Prereqs:
- Android SDK (Android Studio)
- JDK 17

Commands (run under `prompt-reader-android/`):
- Debug APK: `.\gradlew.bat :app:assembleDebug --no-daemon`
- Unit tests: `.\gradlew.bat test --no-daemon`

Outputs:
- `prompt-reader-android/app/build/outputs/apk/debug/app-debug.apk`

## Release & signing (required for upgrades)
Android upgrades require the SAME signing key across releases.

CI release workflow: `.github/workflows/android-release.yml` (trigger: tag `v*`)
- Required GitHub Secrets (names must match exactly):
  - `ANDROID_KEYSTORE_BASE64` (base64 of `prompt-reader-android/release.jks`)
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

Local signing:
- Create `prompt-reader-android/keystore.properties` from `prompt-reader-android/keystore.properties.example`.
- Never commit keystore/passwords; they are ignored by `.gitignore`.

Backup:
- Keep `prompt-reader-android/release.jks` + passwords in a secure backup.
- Use `prompt-reader-android/tools/export-keystore-secrets.cmd` (double-click) to generate base64 for Secrets.

Security note:
- Do not paste keystore/passwords publicly (chat/issue/README). If they were leaked, rotate to a new signing key for future distributions.

## Current UX / App entry
Entry: `prompt-reader-android/app/src/main/java/com/promptreader/android/MainActivity.kt`
- Tabs: Positive / Negative / Settings / Raw
- Raw rendering supports a chunked view to avoid jank on very large JSON
- Theme toggle (System/Light/Dark)
- “Detection path” shown and copyable (helps bug reports)
- Settings list is ordered to show `Model` first

## Parsing pipeline (high level)
Entry: `prompt-reader-android/app/src/main/java/com/promptreader/android/parser/PromptReader.kt`
- Detect file by magic bytes: PNG / JPEG / WEBP
- PNG: read `tEXt/iTXt` chunks (key: `parameters`, `prompt`, `workflow`, `Comment`, etc.)
- JPEG/WEBP: read EXIF fields (esp. `UserComment`)
- NovelAI stealth: decode from alpha LSB (if present)

## Parser coverage (important bits)
All major parsers output:
- `settingEntries`: list of key/value for UI
- `settingDetail`: JSON for “Normal” view/debug

### ComfyUI (recent improvements)
- SDXL prompt graph text extraction supports `CLIPTextEncodeSDXL` / `CLIPTextEncodeSDXLRefiner` via `text/text_g/text_l`.
- Workflow-only parsing supports:
  - `SDPromptReader` nodes (if present)
  - Workflow graph traversal via `nodes + links` to recover positive/negative even without `SDPromptReader`.
- Added fixtures:
  - `prompt-reader-android/app/src/test/resources/fixtures/comfyui_prompt_sdxl.json`
  - `prompt-reader-android/app/src/test/resources/fixtures/comfyui_workflow_cliptext.json`

## Roadmap
M1 (done): improve ComfyUI recognition rate with more robust text extraction + workflow traversal.
M2 (next): refactor data model to `Facts + Evidence + RawParts` so:
- Raw view becomes structured (separate prompt/workflow/parameters blocks)
- Every extracted field can show “why/how it was found” (evidence)
- Easier to add new ComfyUI custom-node handlers without breaking UI
