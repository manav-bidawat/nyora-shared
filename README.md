# nyora-shared

The shared Nyora engine — the Kotatsu parser runtime (GraalVM JS + parsers),
SQLDelight database, `NyoraRestServer` (loopback REST API), and Supabase sync —
as JVM/KMP **source** (`commonMain` + `jvmMain`).

Consumed as a **git submodule** by `nyora-mac`, `nyora-linux`, and
`nyora-windows`. Each app's thin `:shared` Gradle module compiles `src/` via
`srcDirs`, so there is one source of truth for the engine across all three
desktop apps.
