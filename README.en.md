# SimpleChat

> A lightweight, natively fast AI chat client for Android. Built with Jetpack Compose; the R8-minified release APK is about 2.02 MB.

**[中文](README.md) | English**

[![Version](https://img.shields.io/badge/version-0.9.0--beta.7-blue)](https://github.com/Pokanshuu/Simple-Chat/releases)
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
- Streaming output: SSE token by token, live Markdown rendering, pixel-identical before and after finalisation.
- Thinking panel: `reasoning_content` shown collapsed, timing counts real thinking time only, folds as soon as the answer starts.
- Message tree organised by `parentId`. Regenerating creates an in-conversation branch; switching versions carries the downstream along instead of deleting history; long-press forks into a new conversation.
- Context management: CJK-weighted token estimate, usage ring on the input bar, incremental compaction (keeps the last 6 messages in full).
- Attachments: camera / gallery / files, none of which need runtime permission; images, plain text, docx and PDF.
- Data out: JSON backup and Markdown export; imports Chatbox backups.
- Search: full-text over titles and bodies, with hit snippets in the results.
- Long messages: collapsed past 10 lines, whole message stays tappable once expanded.
- Appearance: dark mode, accent colours, font scale, follow the system.
- Languages: interface, prompts and export templates in Chinese and English, switchable in settings (follow system / Simplified Chinese / English); typography is tiered per language (justified for Chinese, left-aligned for Western text).

## Not included

- Client-side rewriting of content. Requests go to your configured endpoint as-is.
- Tool calls, math formula rendering, voice input, TTS.
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
./gradlew testDebugUnitTest    # unit tests (271)
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
│  └─ import/     Chatbox backup, docx text extraction, image size
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

The design documents, implementation records and changelog are **internal and not part of this repository** (the `§` references in code comments point at them). What is public lives in the repo:

- [`TODO.md`](TODO.md): current status and to-dos.

## Contributing

Issues and pull requests are welcome. Please keep the change focused and run `./gradlew testDebugUnitTest` before sending.

## License

Released under the [MIT](LICENSE) license.
