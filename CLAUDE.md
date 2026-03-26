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

### matrix-rust-sdk

`org.matrix.rustcomponents:sdk-android` ships pre-compiled native `.so` files via UniFFI. No Rust toolchain is needed on the development machine — it is a regular Gradle dependency. After syncing, the generated Kotlin bindings are visible in the IDE under the `org.matrix.rustcomponents.sdk` package. If any constructor parameter names or method signatures in `MatrixAuthAdapter.kt` don't resolve, check the generated bindings — the UniFFI API surface can shift between SDK releases.

## Module structure

The project currently has a **single `:app` module**. The `deposplit.com/CLAUDE.md` architecture calls for extracting the domain into a pure Kotlin Gradle module; that extraction is deferred until there is enough domain logic to justify the overhead. Do not introduce that split prematurely.

All packages currently live under `com.deposplit` inside `app/src/main/kotlin/`.

## Package layout

```
com.deposplit/
├── DeposplitApp.kt          Application subclass; owns the auth adapter and OIDC callback relay
├── MainActivity.kt          Single activity; NavHost root; forwards OIDC deep-link intents
├── auth/
│   ├── AuthPort.kt          Domain port interface + LoginFlow sealed type
│   ├── MatrixAuthAdapter.kt Infrastructure adapter (matrix-rust-sdk)
│   └── SignInViewModel.kt   UI logic for the sign-in flow
└── ui/
    ├── signin/
    │   └── SignInScreen.kt  Compose screen — homeserver input + Continue button
    ├── home/
    │   └── HomeScreen.kt    Placeholder post-login screen
    └── theme/               Material 3 colour, type, and theme definitions
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
- `singleTask` launch mode on `MainActivity` — required so the OIDC browser redirect returns to the existing instance rather than creating a new one.
- OIDC redirect URI is `deposplit://auth/callback` — changing it requires updating both the manifest intent filter and any registered OIDC client metadata on homeservers.
- Session persistence uses plain `SharedPreferences` (just an "is logged in" flag). The sensitive data — access tokens, E2EE keys — lives in the matrix-rust-sdk's own encrypted SQLite store under `context.filesDir/matrix/session`. Do not add `EncryptedSharedPreferences` back without a concrete reason; `security-crypto` is not a dependency.
