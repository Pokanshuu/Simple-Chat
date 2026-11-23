# SimpleChat

> An Android AI chat client built with Jetpack Compose, with long conversations, local history, and content export.

**[中文](README.md) | English**

[![Version](https://img.shields.io/badge/version-1.2.5-blue)](https://github.com/Pokanshuu/Simple-Chat/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

See the [v1.2.5 release notes (Chinese)](release-notes/RELEASE_NOTES_v1.2.5.md) for themes, wallpapers, third-party speech, the Recycle Bin, and chat tools.

## Introduction

An Android AI chat client aimed at long-form, continuous creative writing. It talks to any OpenAI-compatible endpoint, stores everything locally, and has no accounts and no cloud sync.

Good for:

- long-form writing, world building, role-play
- long multi-turn conversations that need context tracking and compaction
- self-hosted or local OpenAI-compatible services

## Contents

- [Introduction](#introduction)
- [Features](#features)
- [Not included](#not-included)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Configuration](#configuration)
- [Development](#development)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Privacy & security](#privacy--security)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Features

- Independent Search / Fetch and Files API settings, with custom URLs, paths and encrypted keys or chat-key reuse. Supports DeepSeek-compatible protocols.
- Long user inputs and text attachments can be uploaded once and reused by file ID. Original messages stay local. This reduces transfer size; file content still uses model context and input tokens. See the [tool API guide](docs/23-独立工具API与长文本文件传输.md).
- DeepSeek v3 compatible export includes a native extension to retain existing backup data. Previous JSON and ZIP formats remain supported.
- Inline LaTeX flows with Markdown text, quotes and lists. Double-tap an AI reply to select text; the long-press menu remains available.

- Multiple providers: any OpenAI-compatible endpoint, with built-in presets for DeepSeek and OpenCode Go.
- **Two protocol channels**: the official DeepSeek endpoint can use the **Anthropic-compatible** extended channel (web search / link reading / citations / server-side titles / time-and-place reminder), while every other endpoint stays on OpenAI-compatible. Both coexist and can be pinned manually.
- **Smart search**: `deepseek_web_search`. The process renders as a timeline (search / browse pages / find in page / read links), the answer carries `[ⁿ]` citation marks, and the source strip and source sheet share one numbering.
- Streaming output: SSE token by token, live Markdown rendering, pixel-identical before and after finalisation.
- **Loading phrases**: while waiting for the first token, the app picks one phrase per reply and animates the trailing dots. Under Settings → Conversation behavior → Display → Loading phrases, add, edit, or delete phrases and optionally apply the theme accent to the text, spinner, and dots. An empty custom list uses the eight built-in phrases.
- Thinking panel: `reasoning_content` shown collapsed, timing counts real thinking time only, folds as soon as the answer starts (can be turned off).
- **Continue generating**: a reply you stopped, or one cut off by the output limit, can be continued in place (trailing-assistant prefix).
- **Math**: `$…$` and `\(…\)` are inline; standalone `$$…$$` and `\[…\]` are display math. Same-line `$$…$$` and `\[…\]` stay inline. Compose-native rendering, no WebView.
- **Mermaid diagrams**: ```mermaid fences render as diagrams with a switch back to source; the script ships in the APK, so it works offline.
- **Hold to speak**: press and hold the empty composer to record. Release to transcribe and send, or swipe up and release to cancel. Official DeepSeek connections use DeepSeek ASR for recordings up to 120 seconds. Other connections use the Android speech recognizer when available.
- **Read aloud**: replies are spoken by the system TTS engine — no backend, no permission. Pick a voice (with preview) in Settings; code blocks and tables are stripped before reading.
- **Automatic reading**: the speaker switch next to New chat reads newly completed replies using the selected voice. Turning it off stops playback immediately.
- **API profiles**: save independent URLs, models, protocols and encrypted keys; create, copy, rename, delete or switch profiles. Changes save automatically and existing settings are preserved.
- **First-run onboarding**: validate a DeepSeek API key, choose a model, import a backup, and set an accent color. Existing configured installs skip onboarding after upgrading.
- Reasoning effort includes Off, `low`, `high`, and `max` for both request protocols.
- Message tree organised by `parentId`. Regenerating creates an in-conversation branch; switching versions carries the downstream along instead of deleting history; long-press forks into a new conversation.
- **Context management**: Estimate usage and check the budget before sending. Compaction keeps recent turns intact
  and leaves local messages unchanged. Compaction stops on timeout or truncated output and saves completed batches. It uses one request
  for all eligible history when the budget allows, with summary guidance for creative, technical, or general
  conversations. See [context compaction and recovery](docs/22-上下文压缩与恢复.md).
- Attachments: camera / gallery / files, none of which need runtime permission; images, plain text, docx and PDF. Imported images are stored as files in app-private storage, with only the file name kept in the database. Oversized messages use chunked reads; capacity depends on available device storage and memory. Tapping an image opens a full-screen viewer with swipe between images.
- **Images are validated before sending**: the real format is detected from the file header, formats the server does not accept are transcoded to JPEG locally, and unreadable images are skipped with a notice instead of breaking the conversation forever.
- **Model discovery**: reads context and output limits, image input, and reasoning support from the provider’s model list. If a gateway returns only IDs, the app looks up known models. Use Model capabilities in the API profile to override the selected model’s context window, image support, or reasoning settings.
- Data out: JSON backup, **ZIP archive** (backup plus a Markdown document per conversation) and Markdown export; imports this app's backups, ZIP archives, Chatbox backups and DeepSeek official exports (deepseek_data-*.zip), with three import modes: add new only, overwrite same name, or keep both. Import results are reconciled item by item (how many in the archive, how many imported, which ones failed and why), so nothing disappears silently.
- Sharing: select question-and-answer groups and export a long image, paginated images, or Markdown. Copy text or use Android sharing. Image exports retain tables and math.
- Attachments support selecting multiple images or files. Items are imported in order, and a failed item does not remove successful imports.
- **Pinned chats**: tap the Pinned header to collapse or expand the group. The sidebar keeps that state when closed and reopened.
- Search: full-text over titles and bodies, with hit snippets and an option to include inactive branches.
- Long messages: collapsed past 10 lines, whole message stays tappable once expanded.
- Settings: Appearance groups theme, accent color, and font size. Conversation groups thinking-panel behavior and search scope.
- Languages: interface, prompts and export templates in Chinese and English, switchable in settings (follow system / Simplified Chinese / English); typography is tiered per language (justified for Chinese, left-aligned for Western text).

## Not included

- Client-side rewriting of content. Requests go to your configured endpoint as-is.
- Client-side tool calls (server search tools are supported; local functions are not), voice cloning.
- Automatic backup, cloud sync, accounts.
- Navigation, Hilt, Koin, Retrofit, Markwon, material-icons-extended.

## Getting started

### Requirements

- JDK 17 (Android Studio's bundled JBR 21 works)
- Android SDK platform 37, build-tools 37.0.0
- Gradle comes from the wrapper

### Build

```bash
git clone https://github.com/Pokanshuu/Simple-Chat.git
cd Simple-Chat
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

On Windows, point at a JDK explicitly:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## Usage

1. Install and launch.
2. Open Settings → API, pick a provider, fill in the API key, tap Test connection.
3. Go back to a chat and send a message.

## Configuration

### API

| Item | Description | Default | Required |
|---|---|---|---|
| Provider | DeepSeek / OpenCode Go / custom | DeepSeek | yes |
| Base URL | OpenAI-compatible endpoint | per provider | yes |
| Model | Chat model | `deepseek-flash` | yes |
| API Key | Server-side key, stored encrypted locally | none | yes |

### Building releases

Release signing reads `keystore.properties` (never committed):

```properties
storeFile=keystore/xxx.jks
storePassword=...
keyAlias=...
keyPassword=...
```

When the file is missing, `assembleRelease` falls back to the **debug signature** (not an unsigned APK) so that a fresh clone can still run the whole release flow.

## Development

```bash
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # debug build, package suffix .debug
./gradlew assembleRelease      # release build, needs signing config
```

Unit tests cover the pure functions and the protocol layer; no emulator needed.

## Tech stack

| Item | Version |
|---|---|
| Gradle | 9.7.1 |
| AGP | 9.4.1 (Kotlin built in) |
| Kotlin | 2.4.10 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| JVM | 17 |
| Room | 2.8.5 |
| OkHttp | 5.5.0 |
| kotlinx.serialization | 1.11.0 |
| DataStore | 1.2.1 |

Full dependency list: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project layout

```text
app/src/main/java/com/simplechat/app/
├─ data/          repository layer: conversations, backup import/export, attachments, Keystore crypto, token estimate
│  └─ import/     Chatbox / DeepSeek backups, docx text extraction, image size
├─ db/            Room: entities, DAOs, explicit migrations, message-tree pure functions
├─ net/           DTOs, SSE client and line parser, providers and model capability table
└─ ui/
   ├─ chat/       chat page: message list, input bar, panels, edit dialog, plus pure functions (text chunks / request building / ordering)
   ├─ history/    conversation drawer and full-text search
   ├─ settings/   settings (API, data management, appearance, about)
   ├─ markdown/   Markdown rendering and soft-break fixes
   ├─ common/     the one and only widget/visual-token layer (buttons, menus, dialogs, icons, transitions)
   └─ theme/      colours, type, shapes
```

## Privacy & security

- The API key is stored as AES-256-GCM ciphertext under Android Keystore, with keys isolated per UID. The repository contains no keys.
- Chat requests go directly to your configured endpoint. Voice input sends audio to DeepSeek ASR or hands it to Android’s speech recognizer, whose network use depends on the device configuration.
- Database migrations are explicit; `fallbackToDestructiveMigration` is not used.

## Documentation

Design documents, implementation records and the changelog are indexed in [`docs/README.md`](docs/README.md). The `§` references in code comments point to these documents. Historical records describe each version and what was verified.

- [`TODO.md`](TODO.md): current status and to-dos.

## Contributing

Issues and pull requests are welcome. Please keep the change focused and run `./gradlew testDebugUnitTest` before sending.

## License

Released under the [MIT](LICENSE) license.




