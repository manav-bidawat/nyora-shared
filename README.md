# nyora-shared

The shared **Nyora engine** — the common Kotlin Multiplatform / JVM **source**
(`commonMain` + `jvmMain`) that powers the three desktop apps. It is consumed as a
**git submodule** by [`nyora-mac`](https://github.com/Hasan72341/nyora-mac),
[`nyora-linux`](https://github.com/Hasan72341/nyora-linux), and
[`nyora-windows`](https://github.com/Hasan72341/nyora-windows): each app's thin
`:shared` Gradle module compiles `src/` via `srcDirs`, so there is **one source of
truth** for the engine across all three.

## What it provides

- **Source/parser runtime** — the Kotatsu parser engine on a GraalVM JavaScript runtime, with over-the-air parser bundle updates, so the desktop apps share the same hundreds of online sources (browse, search, filter, details, pages).
- **`NyoraRestServer`** — an in-process loopback REST API (catalog, search, details, image proxy, downloads) that the Compose/SwiftUI front ends talk to over localhost.
- **Library & history store** — a **SQLDelight** database with favourites, user-defined categories, reading history and progress, plus the dedup/migration logic shared across apps.
- **Supabase cloud sync** — `SupabaseConfig` + sync client: Google sign-in (loopback OAuth → Supabase `signInWithIdToken`) and per-row sync of library, favourites, categories and history across devices, with baked production config defaults.
- **Downloads** — a download manager for offline chapters.
- **Bootstrap** — `HelperMain.bootstrap()` wires the DB, migrations, network config and sync, and is the entry point the desktop helper JARs run.

> This repo stays **private**: it carries the full desktop sync configuration, including the Google desktop OAuth client secret, as baked defaults in `SupabaseConfig`.

## Submodule workflow

```bash
# in a consuming app (e.g. nyora-linux)
git clone --recurse-submodules https://github.com/Hasan72341/nyora-linux.git

# bump the engine after changes land here
cd nyora-shared && git pull origin main && cd ..
git add nyora-shared && git commit -m "Bump nyora-shared"
```

Licensed under the **GNU General Public License v3.0**. Built on [Kotatsu](https://github.com/KotatsuApp/Kotatsu).
