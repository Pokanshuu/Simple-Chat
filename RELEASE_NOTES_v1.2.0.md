# SimpleChat v1.2.0

SimpleChat v1.2.0 introduces a first-run setup flow and a lower reasoning-effort option. This build is a GitHub pre-release for final user acceptance; it is not marked as the latest stable release.

## Highlights

- A four-step welcome flow introduces SimpleChat, validates an optional DeepSeek API key, offers model selection, imports an existing backup, and lets users choose an accent color.
- DeepSeek keys are checked against the official model-list endpoint before the flow accepts them. Accepted keys use the existing encrypted local storage.
- Existing installations with saved settings skip onboarding after upgrading. Fresh installs and cleared app data start with onboarding.
- Reasoning effort now includes `low`, alongside Off, `high`, and `max`, for both supported request protocols.
- Backup import in onboarding uses the existing streaming importer and its progress reporting.

## Validation Checklist

- [x] Debug and signed, minified release APKs build successfully.
- [x] Unit tests pass: 714 tests, 0 failures, 10 skipped.
- [x] Signed release APK verifies with Android APK Signature Scheme v2.
- [x] Existing v1.1.6 installation upgrades and cold-starts in the emulator.
- [ ] Fresh install shows the welcome page and completes all steps.
- [ ] Invalid and rejected API keys show an actionable error and block Next.
- [ ] A valid DeepSeek key enables Pro and Flash selection.
- [ ] SimpleChat, Chatbox, and DeepSeek ZIP backups import; skipping remains available.
- [ ] Theme selection is saved and the app opens to chat after completion.
- [ ] Relaunch skips onboarding; clearing app data shows it again.
- [ ] OpenAI-compatible and Anthropic-compatible requests send each reasoning level correctly.
- [ ] Verify layout on a narrow screen and with enlarged system text.

This checklist is for final acceptance of the pre-release. No live DeepSeek credential was available during automated validation, so key validation and model selection still need a real-account check. The emulator check used an existing v1.1.6 installation; a clean-data first-run walkthrough remains on the checklist.

## Build

Build number: 30. SHA-256: `BF7CD9461E7843E3F6BE4A321A30EE9171AAAECF90A92597E51645A2366E9DAF`.
