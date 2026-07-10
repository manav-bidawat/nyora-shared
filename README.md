<div align="center">

<img src="https://nyora.pages.dev/icon.png" width="120" alt="Nyora" />

# Nyora — Shared Engine

### Read like the world can wait.

The open-source, cross-platform Kotlin engine that powers **Translate**, **Sources**, **Sync** and **Download** for every Nyora desktop client. One source of truth, consumed as a git submodule by the macOS, Windows and Linux apps — fix a parser once, ship it everywhere.

<br/>

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)
![SQLDelight](https://img.shields.io/badge/SQLDelight-003B57?style=for-the-badge&logo=sqlite&logoColor=white)

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg?logo=kotlin&logoColor=white)
![Engine](https://img.shields.io/badge/role-shared%20engine-success.svg)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)
![GitHub stars](https://img.shields.io/github/stars/Hasan72341/nyora-shared?style=social)

**[Product site](https://nyora.pages.dev) · [Web app](https://nyoraweb.pages.dev)**

</div>

> [!NOTE]
> **This repository is open source (Apache-2.0) and public.** It is the shared Kotlin engine behind the Nyora desktop apps, vendored by each of them as a git submodule. Anyone can clone it, build it, and contribute — the desktop apps build fully from scratch with the submodule included. Contributions to the engine (parser runtime, loopback REST server, SQLDelight store, Nyora Cloud sync, downloads manager) are welcome via pull request; see [Contributing](#contributing).

---

## Overview

`nyora-shared` is the **engine**, not an app — the common Kotlin Multiplatform / JVM **source** (`commonMain` + `jvmMain`) behind the three Nyora desktop clients. It is consumed as a **git submodule** by [`nyora-mac`](https://github.com/Hasan72341/nyora-mac), [`nyora-linux`](https://github.com/Hasan72341/nyora-linux) and [`nyora-windows`](https://github.com/Hasan72341/nyora-windows): each app's thin `:shared` Gradle module compiles `src/` via `srcDirs`, so there is **one source of truth** for the engine across all three platforms. Fix a parser bug once; bump the submodule everywhere.

It is original code, built from scratch — **source-compatible** with Kotatsu / Tachiyomi-style sources, not a fork. Nyora itself is a fast, free, ad-free, open-source manga reader with whole-page AI translation, 1000+ sources, offline downloads and cloud sync across every device; this repository is the shared foundation that keeps the desktop experience consistent.

### Why this engine exists

The point of `nyora-shared` is to stop three desktop apps from solving the same hard problems three different ways:

- **One catalogue, one parsing behaviour.** macOS, Windows and Linux see an identical source list and identical results, because they run the *same* parser runtime and OTA bundle.
- **One place to fix things.** A broken source, a sync edge case or a schema migration is fixed once here and bumped into every client — no per-platform drift.
- **A stable contract.** The native UIs (SwiftUI / Compose) talk to the engine over a small loopback REST surface, so each front end stays thin and the runtime stays isolated from the UI process model.

## How It Maps to the Nyora Pillars

The engine exists so the macOS, Windows and Linux front ends never reimplement the hard parts. Four of the five consumer-facing Nyora pillars are delivered, in whole or in part, from this single codebase:

| Pillar | Delivered here | What the desktop app adds |
|---|---|---|
| **Sources** | Parser runtime, OTA bundle updates, REST catalogue API | Native browse / search / reader UI |
| **Sync** | Nyora Cloud client (self-hosted, email + password, JWT), per-row sync | Account UI and sign-in trigger |
| **Download** | Offline chapter downloads manager | Download UI, storage location |
| **Translate** | Shared source + runtime the per-platform engine sits on | The actual whole-page AI translation engine |

> The **Translate** pillar (whole-page AI translation typeset over the art) is delivered by each desktop app's own per-platform engine running on top of this shared source and runtime. The remaining consumer pillar, **Open Source**, is a project-wide property rather than a single module.

## Table of Contents

- [Overview](#overview)
- [How It Maps to the Nyora Pillars](#how-it-maps-to-the-nyora-pillars)
- [What This Repo Is](#what-this-repo-is)
- [Engine Components](#engine-components)
  - [Sources — Parser Runtime](#sources--parser-runtime)
  - [NyoraRestServer — Loopback API](#nyorarestserver--loopback-api)
  - [Library & History Store](#library--history-store)
  - [Sync — Nyora Cloud Sync](#sync--nyora-cloud-sync)
  - [Download — Downloads Manager](#download--downloads-manager)
  - [Bootstrap](#bootstrap)
- [Capability Matrix](#capability-matrix)
- [Architecture](#architecture)
- [Where Things Live](#where-things-live)
- [Contributing](#contributing)
- [Submodule Workflow](#submodule-workflow)
- [Tech Stack](#tech-stack)
- [Nyora on Every Platform](#nyora-on-every-platform)
- [Roadmap](#roadmap)
- [FAQ](#faq)
- [Configuration](#configuration)
- [License](#license)
- [Maintainer](#maintainer)

## What This Repo Is

`nyora-shared` is a **library of source files**, not a buildable application. There is no `main()` you run to launch Nyora; instead the consuming desktop repositories embed this directory as a git submodule and point their own `:shared` Gradle module at it.

Concretely:

- Each consumer app (`nyora-mac`, `nyora-windows`, `nyora-linux`) declares a thin `:shared` Gradle module.
- That module adds this repository's `src/` to its `srcDirs`, so the engine sources compile **as part of the app's build** rather than shipping as a binary artefact.
- All three apps reference the same submodule commit, so the engine stays in lockstep across platforms.

Each platform owns only its native UI layer — Compose Desktop on Windows/Linux, SwiftUI on macOS — and its translation engine. Everything else (parser runtime, REST surface, local database, sync client) lives here, once.

The code is original and built from scratch. It is **source-compatible** with Kotatsu / Tachiyomi-style sources — the same parser model and source contract — but it is not a fork of any of those projects.

## Engine Components

These modules light up the Nyora pillars on desktop. Each is shared verbatim by all three desktop clients.

### Sources — Parser Runtime

The Kotatsu parser engine running on a **GraalVM JavaScript** runtime, with **over-the-air parser bundle updates**, so the desktop apps share the same hundreds of online sources.

- **What it does.** Drives the full source lifecycle — browse, search, filter, details and pages. Each source describes how to enumerate a catalogue, search it, read manga details, and resolve chapter page image URLs.
- **GraalVM JS runtime.** Sources containing JavaScript logic execute on an embedded GraalVM JavaScript engine rather than a native Android WebView. That is how a parser model originally designed for Android runs unchanged on the JVM desktop.
- **Over-the-air bundles.** Parser definitions ship as **OTA bundles** (`parsers.bundle.js`) with a companion sources manifest, fetched from a versioned manifest and updated independently of any app release. `bootstrap()` checks for an update in the background and applies it on next launch, so new and fixed sources reach users without shipping a new desktop build.
- **Curated by default.** The bundled catalogue ships the iOS-curated source set plus the native defaults (e.g. MangaDex, MangaPlus, MangaReader, Asura, MangaFire, ComicK) **installed**; every other parser is available-but-not-installed so users opt in. This also keeps global search fast, since it only fans out over installed sources.
- **Self-healing catalogue.** Seeding prunes orphaned `parser:` rows the current bundle no longer ships, and drops Mihon-engine rows that the desktop JVM cannot open — guarded so a missing or corrupt resource can never wipe the catalogue. Library, history and favourites are never touched.
- **Why it's shared.** Putting the runtime here means macOS, Windows and Linux see an identical catalogue and identical parsing behaviour — exactly one place to fix a broken source.

### NyoraRestServer — Loopback API

An in-process **loopback REST API** that the Compose / SwiftUI front ends talk to over localhost.

- **Surface.** Exposes catalogue, search, details and an image proxy, plus endpoints for the library (favourites, categories, history, bookmarks, updates), manga prefs, downloads, local (offline) chapters, OTA status, Nyora Cloud sync, backup import/export, and `/health`.
- **Loopback bind.** The server binds to the loopback address on an ephemeral port (`0`) by default, exactly as the desktop clients expect; the resolved port is written to a per-OS port file the native launcher reads. The web build can instead pin a fixed port.
- **Why loopback.** The native UI layers (SwiftUI on macOS, Compose on Windows/Linux) talk to the engine over `localhost` HTTP rather than via in-language FFI. Every front end gets the same stable contract regardless of UI toolkit or language, and the parser/runtime stays isolated from the UI process model.
- **Image proxy.** Page and cover images are fetched through the engine's proxy, so source-specific headers, referrers and cookies (including Cloudflare clearance) apply consistently and the UI only ever requests a local URL.

### Library & History Store

A **SQLDelight** database holding the user's reading state, shared across apps.

- **Schema.** Type-safe `.sq` definitions cover manga, favourites and favourite categories, reading history, bookmarks, per-title prefs, source registry, chapter pages and update tracking.
- **Logic.** Includes the dedup / migration logic shared across apps, plus a one-time JSON → SQL migration, so the same schema and upgrade paths apply on every desktop platform.
- **Type-safe access.** SQLDelight generates type-safe Kotlin from SQL, giving the engine compile-time-checked queries and a single canonical schema the sync layer maps onto.

### Sync — Nyora Cloud Sync

A sync client (legacy class name `SupabaseConfig`) providing free cloud sync across devices via Nyora Cloud.

- **Sign-in.** **Email + password** accounts on Nyora Cloud — a self-hosted FastAPI backend (OAuth2 password flow, JWT).
- **What syncs.** **Per-row sync** of library, favourites, categories and history, so reading state follows the user across devices and platforms.
- **Lifecycle.** When configured and authenticated, `bootstrap()` refreshes the token and runs an initial pull on a background daemon thread; thereafter the client reconciles changes per row. `restore-from-cloud` can re-seed a fresh install.
- **Bundled config.** Ships with the Nyora Cloud backend URL as a bundled default, so the desktop apps sync out of the box. It is overridable by a local `.env.sync`, letting contributors point sync at their own self-hosted Nyora Cloud backend (see [Configuration](#configuration)).

### Download — Downloads Manager

A download manager for offline chapters, backing the desktop **Download** pillar. It fetches and stores chapter pages locally so manga reads without a connection, exposes enqueue / start / cancel / settings over the loopback API, and serves saved chapters back through the local image endpoints — so downloaded chapters behave exactly like online ones in the reader.

### Bootstrap

`HelperMain.bootstrap()` is the single entry point the desktop helper JARs run. It returns a `Bootstrap` holding the repository, the `NyoraFacade`, the `DownloadManager` and the network config, and is reused by both the loopback desktop helper and the deployable web server so seeding stays in one place. It wires together:

- the SQLDelight database and its migrations,
- the built-in source catalogue (preferring an OTA-downloaded sources manifest, else the bundled one),
- a background OTA parser update check,
- network configuration and the GraalVM parser runtime,
- the downloads manager,
- and the Nyora Cloud sync client.

After `bootstrap()` completes the engine is fully initialised. `HelperMain.main()` then starts `NyoraRestServer`, writes the port file, and arms a parent-PID watchdog so the helper exits cleanly if its launcher dies.

## Capability Matrix

What this engine delivers, and where the work actually lives. Cells marked **Engine** are owned by `nyora-shared`; **App** means the consuming desktop client owns it on top of the engine.

| Capability | Owner | Notes |
|---|---|---|
| Source parsing & catalogue | Engine | GraalVM JS runtime, OTA bundles |
| OTA parser updates | Engine | Background check, applied on next launch |
| Loopback REST API | Engine | Catalogue, search, details, image proxy, library, downloads, sync |
| Image proxy | Engine | Applies source headers / referrers / Cloudflare clearance |
| Local store (SQLDelight) | Engine | Favourites, categories, history, bookmarks, prefs |
| Cloud sync (Nyora Cloud) | Engine | Email + password, per-row sync, cloud restore |
| Offline downloads | Engine | Enqueue / start / cancel, local serving |
| Backup import / export | Engine | Loopback endpoints |
| Whole-page AI translation | App | Per-platform engine consumes pages from this runtime |
| Native UI | App | SwiftUI (macOS) · Compose (Windows/Linux) |
| Bundled JVM runtime | App | Each app ships a JRE so the helper runs without system Java |

## Architecture

At a high level, every Nyora desktop app is a thin native UI shell over this shared engine:

```
┌───────────────────────────────────────────────────────────────┐
│  Native front end  (SwiftUI on macOS · Compose on Win/Linux)   │
│  + per-platform whole-page AI translation engine               │
└───────────────────────────┬───────────────────────────────────┘
                            │  localhost HTTP (REST)
┌───────────────────────────▼───────────────────────────────────┐
│  nyora-shared  (this repo · commonMain + jvmMain, JVM)         │
│                                                                │
│   HelperMain.bootstrap()                                       │
│        │                                                       │
│        ├── NyoraRestServer ── catalog · search · details ·     │
│        │                      image proxy · downloads          │
│        ├── Parser runtime (GraalVM JS) ── OTA parser bundles   │
│        ├── SQLDelight store ── library · categories · history  │
│        ├── Downloads manager ── offline chapters               │
│        └── Nyora Cloud sync ── email+password · per-row        │
└───────────────────────────────────────────────────────────────┘
```

Flow of control:

1. The desktop helper JAR calls `HelperMain.bootstrap()`, which opens the database, runs migrations, seeds the catalogue, kicks off a background OTA check, configures networking and prepares the sync client.
2. `NyoraRestServer` starts on a loopback address (ephemeral port) and exposes the catalogue, search, details, image-proxy, library, downloads and sync endpoints.
3. The native UI talks to that loopback API. A source listing or page request is served by the GraalVM-backed parser runtime; images return through the engine's proxy.
4. Reading state (favourites, categories, history, progress) is persisted to the SQLDelight store and reconciled with Nyora Cloud per row once the user is signed in (email + password).
5. The desktop app's own translation engine consumes pages from the engine and renders the whole-page AI translation typeset over the art.

Because all three desktop apps embed the **same submodule commit**, this entire layer behaves identically across macOS, Windows and Linux.

## Where Things Live

A quick orientation map for landing in the tree for the first time. Source is split across `commonMain` (platform-agnostic Kotlin), `jvmMain` (the JVM/desktop runtime) and `macosMain`, with SQLDelight schema under `commonMain/sqldelight`:

| Area | Path | What's there |
|---|---|---|
| Shared models | `src/commonMain/kotlin/com/nyora/hasan72341/shared/model` | Cross-platform data models the API and store share |
| Repository / store | `src/commonMain/kotlin/com/nyora/hasan72341/shared/repository` · `…/data` | Library, history and catalogue access |
| Reader & proxy | `src/commonMain/kotlin/com/nyora/hasan72341/shared/reader` · `…/proxy` | Page resolution and image proxy logic |
| Database schema | `src/commonMain/sqldelight/com/nyora/hasan72341/shared/db` | SQLDelight `.sq` definitions and migrations |
| Parser runtime | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/parser` · `…/extension` | GraalVM JS runtime, OTA bundles, source extensions |
| REST server | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/proxy` | `NyoraRestServer`, endpoints, image proxy |
| Network config | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/net` | `HelperNetworkSettings` and network configuration |
| Downloads | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/download` | Offline chapter download manager |
| Sync | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/sync` | Nyora Cloud client (`SupabaseConfig`, legacy name), email + password, per-row sync |
| Engine entry point | `src/jvmMain/kotlin/com/nyora/hasan72341/shared/HelperMain.kt` | `bootstrap()` + `main()`, the wiring root |
| Bundled parsers | `src/commonMain/resources` | `parsers.bundle.js`, `parsers_sources.json` |

When tracing a feature end-to-end, start at the relevant `NyoraRestServer` endpoint, follow it into the owning module above, and check the SQLDelight schema for any state it touches. `HelperMain.bootstrap()` is the wiring root that connects them all.

## Contributing

This is the open-source engine that powers the Nyora desktop apps, and contributions are welcome. Whether you are fixing a parser, extending the loopback API, improving sync, or tightening a migration, pull requests are the way changes land.

**Where to make a change.**

- A broken or new **source** is almost always a parser-bundle / catalogue concern — see the parser runtime and the bundled `resources`. New sources ride in via OTA bundles, so most source work does not require an engine release at all.
- A **sync** or **auth** change touches `sync/` and `SupabaseConfig` (the Nyora Cloud client); point your own environment at a local `.env.sync` rather than editing bundled defaults (see [Configuration](#configuration)).
- A **schema** change means a `.sq` edit plus a migration; the store is shared by every client, so treat migrations as one-way and forward-compatible.
- An **API** change ripples into all three native UIs over the loopback contract; keep endpoint shapes stable and additive where you can.

**How to build.** This source compiles inside a consuming app, not on its own — it has no standalone `main()`. The realistic loop is:

1. Clone a consumer app (e.g. `nyora-linux`) with submodules, so `nyora-shared` is populated — see [Submodule Workflow](#submodule-workflow). Because the engine is public, this gives you a complete, from-scratch build with the engine included.
2. Edit engine sources here, inside the submodule.
3. Build / run the consuming app with `./gradlew` to exercise your change against a real front end and a live loopback server, and run the test suite for the modules you touched.
4. Hit `/health` and the relevant endpoints to sanity-check the running engine.

**Good first contributions.** Fixing or adding a source parser, tidying catalogue-seeding edge cases, extending an existing loopback endpoint, improving error handling around the GraalVM runtime, or sharpening documentation are all approachable starting points.

**PR etiquette.** Keep commits focused and describe the engine-visible effect (a fixed source, an endpoint, a migration). Never commit real per-developer secrets — use `.env.sync` locally. Once a change lands here, it ships by **bumping the submodule** into each desktop app; because all three clients pin the same commit, an unbumped change is invisible to users (full steps under [Submodule Workflow](#submodule-workflow)).

> A great parallel opportunity is **source porting in [`NyoraEngine`](https://github.com/Hasan72341/nyora-ios) (iOS)**: roughly **1,300 sources** still to port as mostly-mechanical template subclasses — highly parallelisable, friendly to first-timers, and entirely in a public repo. The framework and one template are done; the rest is largely filling in per-source specifics. It widens the catalogue everyone benefits from, on every platform.

## Submodule Workflow

This repository is meant to be checked out **inside** a consuming app, not on its own. Use the commands below verbatim.

```bash
# in a consuming app (e.g. nyora-linux)
git clone --recurse-submodules https://github.com/Hasan72341/nyora-linux.git

# bump the engine after changes land here
cd nyora-shared && git pull origin main && cd ..
git add nyora-shared && git commit -m "Bump nyora-shared"
```

Notes:

- `--recurse-submodules` ensures the public `nyora-shared` submodule is populated at clone time, so the app builds fully from scratch — engine included. If you cloned without it, run `git submodule update --init --recursive` from the app root.
- "Bumping the engine" means updating the recorded submodule commit in the consuming app. After landing changes here, pull `main` inside the submodule, then `git add` and commit the new pointer in the app repo so its `:shared` module picks up the new sources.
- Apply the same bump to each of `nyora-mac`, `nyora-windows` and `nyora-linux` to keep all three platforms on the same engine revision.

## Tech Stack

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)
![SQLDelight](https://img.shields.io/badge/SQLDelight-003B57?style=for-the-badge&logo=sqlite&logoColor=white)

- **Kotlin (Multiplatform / JVM)** — the engine is written as `commonMain` + `jvmMain` source, compiled into each desktop app's build.
- **Gradle** — each consuming app's thin `:shared` module compiles this repository's `src/` via `srcDirs`.
- **SQLDelight** — type-safe local store for favourites, categories, reading history and progress, with shared migration logic.
- **Nyora Cloud** — a self-hosted FastAPI backend that backs free cloud sync: email + password accounts (OAuth2 + JWT) and per-row sync of library state.
- **GraalVM JavaScript** — the runtime that executes Kotatsu-style source parsers on the JVM desktop.

## Nyora on Every Platform

This engine powers the desktop trio (macOS, Windows, Linux). The consumer apps live here:

| Platform | Repo | Get it |
|---|---|---|
| Android | [nyora-android](https://github.com/Hasan72341/nyora-android) | [APK](https://github.com/Hasan72341/nyora-android/releases/latest) |
| macOS | [nyora-mac](https://github.com/Hasan72341/nyora-mac) | [.dmg / brew](https://github.com/Hasan72341/nyora-mac/releases/latest) |
| Windows | [nyora-windows](https://github.com/Hasan72341/nyora-windows) | [.exe (x64/ARM64)](https://github.com/Hasan72341/nyora-windows/releases/latest) |
| Linux | [nyora-linux](https://github.com/Hasan72341/nyora-linux) | [deb · rpm · curl](https://github.com/Hasan72341/nyora-linux/releases/latest) |
| iOS / iPadOS | [nyora-ios](https://github.com/Hasan72341/nyora-ios) | [sideload IPA](https://github.com/Hasan72341/nyora-ios/releases/latest) |
| Web | [nyora-web](https://github.com/Hasan72341/nyora-web) | [nyoraweb.pages.dev](https://nyoraweb.pages.dev) |
| Shared engine | **nyora-shared (you are here)** | public submodule |

## Roadmap

Honest, already-implied directions for the engine — no dates, no promises:

- **Broader source parity.** Continue widening OTA-delivered parser coverage and keep the desktop catalogue aligned with the iOS-curated set as new sources are ported.
- **Catalogue hygiene.** Keep pruning orphaned and unopenable source rows as bundles evolve, so the visible catalogue always matches what the JS bundle can actually open.

## FAQ

**Is this an app I can run?**
No. `nyora-shared` is a Kotlin source library consumed as a git submodule by the desktop apps. There is no standalone download or `main()` you launch to "get Nyora" — install a desktop client from [Nyora on Every Platform](#nyora-on-every-platform) instead.

**Can I contribute to this repo?**
Yes. The engine is open source (Apache-2.0) and public, and pull requests are welcome — parser fixes, REST endpoints, sync, store and downloads work all land here. Build it through a consuming desktop app's submodule, point sync at your own Nyora Cloud backend via `.env.sync`, and open a PR. See [Contributing](#contributing). Source porting in the public [`nyora-ios`](https://github.com/Hasan72341/nyora-ios) repo is another high-leverage place to help.

**Is Nyora free, with ads or tracking?**
Nyora is free, ad-free and has no trackers, and you never need an account just to read. This repository is the engine that keeps that true on desktop.

**Where do the sources and content come from?**
The engine connects to hundreds of online sources via Kotatsu/Tachiyomi-style parsers. Nyora is a reader, not a host, and is **not affiliated** with any of the sources it can access.

**What does cloud sync store, and is it private?**
Sync is optional and free. When a user signs in (email + password), the engine reconciles their library, favourites, categories and history per row through Nyora Cloud. It is reading state, not page content; signing in is never required to read.

**Does the engine work offline?**
Yes. The downloads manager stores chapter pages locally and serves them back through the loopback API, so saved chapters read with no connection.

**How are sources updated without an app release?**
Via OTA parser bundles. `bootstrap()` checks a versioned manifest in the background and applies any new bundle on next launch, so fixes and new sources land without shipping a desktop build.

**How is translation handled here?**
The engine does **not** translate. It serves pages and images; each desktop app's own per-platform engine performs the whole-page AI translation on top of this runtime.

**Is the engine open-source?**
Yes. `nyora-shared` is licensed under the **Apache License 2.0** and is a public repository — clone it, build it, and send pull requests. Sync configuration is handled via local `.env.sync` overrides; the bundled default is just the Nyora Cloud backend URL, and accounts use email + password (no third-party OAuth client). See [Configuration](#configuration).

**How do the desktop apps run JVM code?**
Each consuming app bundles a JRE and runs the engine as a loopback helper, so users do not need a separate Java install.

## Configuration

The engine ships with **bundled production defaults** so the desktop apps sync out of the box, and lets contributors override everything locally.

- **Bundled defaults.** `SupabaseConfig` (a legacy class name) carries the bundled Nyora Cloud backend URL. Accounts are email + password against the self-hosted FastAPI backend, so there is no bundled third-party OAuth client secret to worry about.
- **Local overrides.** A local `.env.sync` overrides the bundled values, so a contributor can point sync at **their own self-hosted Nyora Cloud backend** during development without touching committed defaults.
- **Where it lives.** Sync configuration is in `src/jvmMain/kotlin/com/nyora/hasan72341/shared/sync` (`SupabaseConfig`, the Nyora Cloud client; email + password; per-row sync).
- **Hygiene.** If a bundled value is ever misused or you suspect exposure, rotate the Nyora Cloud credentials and update the `SupabaseConfig` defaults accordingly. Never commit per-developer secrets — keep those in your local `.env.sync`.

## License

The code in this repository is licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE). The engine is open source and public; contributions are accepted under the same licence.

## Maintainer

**Md Hasan Raza** — [GitHub](https://github.com/Hasan72341) · [Instagram](https://instagram.com/md_hasan_raza____) · [LinkedIn](https://www.linkedin.com/in/md-hasan-raza) · hasanraza96@outlook.com

---

> Nyora is not affiliated with any of the manga sources it can access.
