// Standalone build of the Nyora parser helper (the :shared JVM module).
// Source comes from the nyora-shared checkout at ./nyora-shared.
plugins {
    kotlin("multiplatform")        version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("app.cash.sqldelight")      version "2.1.0"  apply false
}

group = "com.nyora"
version = "2.0.6"
