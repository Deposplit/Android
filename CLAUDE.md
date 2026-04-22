# Android — Claude Code guidance

Platform-specific guidance for the `Android/` repository. Cross-project context lives in `deposplit.com/CLAUDE.md` (loaded automatically when launching Claude from the workspace root via the `@`-import in `Deposplit/CLAUDE.md`).

## Toolchain

### AGP 9.x — do not apply `org.jetbrains.kotlin.android`

AGP 9.x registers the `kotlin` Gradle extension itself. Applying `org.jetbrains.kotlin.android` alongside it causes an "extension already registered" error. The project deliberately omits it.

Consequences:
- `kotlinOptions { }` is unavailable — set the JVM target via `compileOptions { sourceCompatibility / targetCompatibility }` only; AGP propagates it to the Kotlin compiler automatically.
- `kotlin.plugin.compose` is still required for Compose compiler support and does not conflict.

### Java / Kotlin versions

- Kotlin 2.3.20, JVM 21 bytecode target
- The host JDK is Java 25 (Temurin). Use `compileOptions` / `java { sourceCompatibility / targetCompatibility }` for the target — `jvmToolchain(N)` requires an installed JDK of version N and will fail if only 25 is present.
- Gradle wrapper version: see `gradle/wrapper/gradle-wrapper.properties`
- AGP version: see `gradle/libs.versions.toml` (`agp`)

## Module structure

The project has two Gradle modules, enforcing the Ports & Adapters boundary at the build level:

| Module | Role | Plugins |
|---|---|---|
| `:hexagon` | Pure Kotlin/JVM — domain model, value types, ports, framework-free tests. No Android or infrastructure imports. | `org.jetbrains.kotlin.jvm` |
| `:app` | Android application — adapters (HTTP, Android Keystore, local JSON) + UI (Compose + navigation). Depends on `:hexagon`. | AGP (registers `kotlin` itself) + `kotlin.plugin.compose` + `kotlin.plugin.serialization` |

`:hexagon` must not depend on `:app`, AGP, or any Android library. This mirrors the sbt `hexagon` subproject / root Play app split in `deposplit.com`.

When adding a new pure domain type (value object, port interface, domain service, framework-free test), place it under `hexagon/src/{main,test}/kotlin/com/deposplit/...`. Infrastructure adapters and everything UI-related live under `app/src/main/kotlin/com/deposplit/...`.

The top-level `build.gradle.kts` declares all plugins `apply false` so subprojects share a single resolved version. Do not declare plugin versions inside `:hexagon/build.gradle.kts` or `:app/build.gradle.kts`.

## Package layout

### `:hexagon/src/main/kotlin/com/deposplit/`

```
shamir/
└── Shamir.kt                    split(...) / combine(...) — SSS implementation
auth/
└── AuthPort.kt                  Port: isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign, encrypt, decrypt
api/
└── ShareTransport.kt            Port + value types (Role, ShareRequestType, ShareRequestState, ShareMetadata, ShareRequest)
contacts/
└── Contact.kt                   Contact + VerificationLevel + ContactRepository port
```

Tests: `:hexagon/src/test/kotlin/com/deposplit/shamir/ShamirTest.kt` — round-trip, cross-platform vectors, input validation. Uses `kotlin.test` (JUnit 4 backend via `kotlin-test-junit`).

### `:app/src/main/kotlin/com/deposplit/`

```
DeposplitApp.kt              Application subclass; owns authAdapter + shareTransport + contactRepository
MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact / deposit / share_detail / qr_display / qr_scan)
auth/
├── DeposplitAuthAdapter.kt  Adapter: libsodium keypair generation, Android Keystore AES-GCM wrapping
└── SignInViewModel.kt       UI logic for the registration flow
api/
└── DeposplitApiAdapter.kt   HTTP adapter: HttpURLConnection, Ed25519 request signing, JSON via kotlinx.serialization
contacts/
└── LocalContactRepository.kt  JSON file in filesDir; @Synchronized; kotlinx.serialization wire types
ui/
├── signin/       SignInScreen                               — pseudonym input + Register button
├── home/         HomeViewModel + HomeScreen                 — Distributed / Held / Requests tabs
├── contacts/     ContactsViewModel + ContactsScreen, AddContactViewModel + AddContactScreen
├── deposit/      DepositViewModel + DepositScreen           — Shamir.split + auth.encrypt + transport.depositShare
├── requests/     RequestsViewModel + RecipientRequestsTab   — approve/deny incoming requests
├── sharedetail/  ShareDetailViewModel + ShareDetailScreen   — open RETRIEVE/DELETE + reconstruct via Shamir.combine + auth.decrypt
├── qr/           QrPayload, QrDisplay{ViewModel,Screen}, QrScan{ViewModel,Screen}
└── theme/        Material 3 colour, type, theme
```

Adapters may only depend on `:hexagon` ports and Android/infrastructure libraries. They must never depend on UI code.

## Build & test commands

```bash
# from Android/
./gradlew assembleDebug          # build debug APK
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (requires a device or emulator)
./gradlew :hexagon:test          # hexagon (domain) tests only
./gradlew :hexagon:test --tests "com.deposplit.shamir.ShamirTest"  # single test class
```

Deploying to a device or emulator requires Android Studio (or `adb install`).

## Key decisions to preserve

- `minSdk = 29` — do not lower; see `deposplit.com/CLAUDE.md` for rationale.
- All UI in Jetpack Compose (no XML layouts).
- Registration is keypair-first (libsodium X25519) — no OIDC, no password, no email. See `deposplit.com/CLAUDE.md` for rationale.
- The X25519 private key is stored in the Android Keystore and never handled as raw key material by app code.
- Session persistence uses plain `SharedPreferences` (just an "is registered" flag). Do not add `EncryptedSharedPreferences` without a concrete reason; `security-crypto` is not a dependency.
- Private keys are wrapped by an AES-256-GCM master key in the Android Keystore (`deposplit_master` alias); only the ciphertext and IV are stored in `SharedPreferences`.
