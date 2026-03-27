# Android — Claude Code guidance

Platform-specific guidance for the `Android/` repository. Cross-project context lives in `deposplit.com/CLAUDE.md` (loaded automatically when launching Claude from the workspace root via the `@`-import in `Deposplit/CLAUDE.md`).

## Toolchain

### AGP 9.x — do not apply `org.jetbrains.kotlin.android`

AGP 9.x registers the `kotlin` Gradle extension itself. Applying `org.jetbrains.kotlin.android` alongside it causes an "extension already registered" error. The project deliberately omits it.

Consequences:
- `kotlinOptions { }` is unavailable — set the JVM target via `compileOptions { sourceCompatibility / targetCompatibility }` only; AGP propagates it to the Kotlin compiler automatically.
- `kotlin.plugin.compose` is still required for Compose compiler support and does not conflict.

### Java / Kotlin versions

- Kotlin 2.2.10, JVM 17 bytecode target
- The host JDK is Java 25 (Temurin). Use `compileOptions` for the target — `jvmToolchain(N)` requires an installed JDK of version N and will fail if only 25 is present.
- Gradle wrapper version: see `gradle/wrapper/gradle-wrapper.properties`
- AGP version: see `gradle/libs.versions.toml` (`agp`)

## Module structure

The project currently has a **single `:app` module**. The `deposplit.com/CLAUDE.md` architecture calls for extracting the domain into a pure Kotlin Gradle module; that extraction is deferred until there is enough domain logic to justify the overhead. Do not introduce that split prematurely.

All packages currently live under `com.deposplit` inside `app/src/main/kotlin/`.

## Package layout

```
com.deposplit/
├── DeposplitApp.kt              Application subclass; owns the auth adapter
├── MainActivity.kt              Single activity; NavHost root
├── auth/
│   ├── AuthPort.kt              Domain port interface + RegistrationFlow sealed type
│   ├── MatrixAuthAdapter.kt     OBSOLETE — being replaced by DeposplitAuthAdapter
│   ├── DeposplitAuthAdapter.kt  Infrastructure adapter (deposplit.com API + libsodium keypair)
│   └── SignInViewModel.kt       UI logic for the registration flow
└── ui/
    ├── signin/
    │   └── SignInScreen.kt      Compose screen — pseudonym input + Register button
    ├── home/
    │   └── HomeScreen.kt        Placeholder post-registration screen
    └── theme/                   Material 3 colour, type, and theme definitions
```

## Build & test commands

```bash
# from Android/
./gradlew assembleDebug          # build debug APK
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (requires a device or emulator)
./gradlew test --tests "com.deposplit.shamir.ShamirTest"  # single test class
```

Deploying to a device or emulator requires Android Studio (or `adb install`).

## Key decisions to preserve

- `minSdk = 29` — do not lower; see `deposplit.com/CLAUDE.md` for rationale.
- All UI in Jetpack Compose (no XML layouts).
- Registration is keypair-first (libsodium X25519) — no OIDC, no password, no email. See `deposplit.com/CLAUDE.md` for rationale.
- The X25519 private key is stored in the Android Keystore and never handled as raw key material by app code.
- Session persistence uses plain `SharedPreferences` (just an "is registered" flag). Do not add `EncryptedSharedPreferences` without a concrete reason; `security-crypto` is not a dependency.
- `MatrixAuthAdapter.kt` is obsolete and will be deleted once `DeposplitAuthAdapter` is complete. Do not extend or fix it.
