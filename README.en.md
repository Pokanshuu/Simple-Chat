# SimpleChat

> A lightweight, natively fast AI chat client for Android. Built with Jetpack Compose; the R8-minified release APK is about 3.8 MB.

**[中文](README.md) | English**

[![Version](https://img.shields.io/badge/version-1.1.5-blue)](https://github.com/Pokanshuu/Simple-Chat/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## Introduction

An Android AI chat client aimed at long-form, continuous creative writing. It talks to any OpenAI-compatible endpoint, stores everything locally, and has no accounts and no cloud sync.

Good for:

- long-form writing, world building, role-play
- long multi-turn conversations that need context usage tracking and compaction
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

- Multiple providers: any OpenAI-compatible endpoint, with built-in presets for DeepSeek and OpenCode Go.
- **Two protocol channels**: the official DeepSeek endpoint can use the **Anthropic-compatible** extended channel (web search / link reading / citations / server-side titles / time-and-place reminder), while every other endpoint stays on OpenAI-compatible. Both coexist and can be pinned manually.
- **Smart search**: `deepseek_web_search`. The process renders as a timeline (search / browse pages / find in page / read links), the answer carries `[ⁿ]` citation marks, and the source strip and source sheet share one numbering.
- Streaming output: SSE token by token, live Markdown rendering, pixel-identical before and after finalisation.
- Thinking panel: `reasoning_content` shown collapsed, timing counts real thinking time only, folds as soon as the answer starts (can be turned off).
- **Continue generating**: a reply you stopped, or one cut off by the output limit, can be continued in place (trailing-assistant prefix).
- **Math**: `$…$` / `$$…$$` / `\[…\]` rendered natively by Compose (no WebView + KaTeX).
- **Mermaid diagrams**: ```mermaid fences render as diagrams with a switch back to source; the script ships in the APK, so it works offline.
- **Read aloud**: replies are spoken by the system TTS engine — no backend, no permission. Pick a voice (with preview) in Settings; code blocks and tables are stripped before reading.
- **Automatic reading**: the speaker switch next to New chat reads newly completed replies using the selected voice. Turning it off stops playback immediately.
- **API profiles**: save independent URLs, models, protocols and encrypted keys; create, copy, rename, delete or switch profiles. Changes save automatically and existing settings are preserved.
- Message tree organised by `parentId`. Regenerating creates an in-conversation branch; switching versions carries the downstream along instead of deleting history; long-press forks into a new conversation.
- Context management: CJK-weighted token estimate, usage ring on the input bar, incremental compaction (keeps the last 6 messages in full).
- Attachments: camera / gallery / files, none of which need runtime permission; images, plain text, docx and PDF. Imported images are stored as files in app-private storage, with only the file name kept in the database. Oversized messages use chunked reads; capacity depends on available device storage and memory. Tapping an image opens a full-screen viewer with swipe between images.
- **Images are validated before sending**: the real format is detected from the file header, formats the server does not accept are transcoded to JPEG locally, and unreadable images are skipped with a notice instead of breaking the conversation forever.
- Model capability aware: when the selected model does not accept images, they are skipped with a notice instead of being rejected by the server.
- Data out: JSON backup, **ZIP archive** (backup plus a Markdown document per conversation) and Markdown export; imports this app's backups, ZIP archives, Chatbox backups and DeepSeek official exports (deepseek_data-*.zip), with three import modes: add new only, overwrite same name, or keep both. Import results are reconciled item by item (how many in the archive, how many imported, which ones failed and why), so nothing disappears silently.
- Search: full-text over titles and bodies, with hit snippets in the results.
- Long messages: collapsed past 10 lines, whole message stays tappable once expanded.
- Appearance: dark mode, accent colours, font scale, follow the system.
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
- Requests go straight from the device to your endpoint; no third party in between.
- Database migrations are explicit; `fallbackToDestructiveMigration` is not used.

## Documentation

Design documents, implementation records and the changelog are indexed in [`docs/README.md`](docs/README.md). The `§` references in code comments point to these documents. Historical records describe the version and verification scope at the time; current release notes are in [`RELEASE_NOTES_v1.1.5.md`](RELEASE_NOTES_v1.1.5.md).

- [`TODO.md`](TODO.md): current status and to-dos.

## Contributing

Issues and pull requests are welcome. Please keep the change focused and run `./gradlew testDebugUnitTest` before sending.

## License

Released under the [MIT](LICENSE) license.




