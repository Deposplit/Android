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
                                 ⚠️ Needs relay protocol update — see "Pending work" below
contacts/
└── Contact.kt                   Contact + VerificationLevel + ContactRepository port
```

Tests: `:hexagon/src/test/kotlin/com/deposplit/shamir/ShamirTest.kt` — round-trip, cross-platform vectors, input validation. Uses `kotlin.test` (JUnit 4 backend via `kotlin-test-junit`).

### `:app/src/main/kotlin/com/deposplit/`

```
DeposplitApp.kt              Application subclass; owns authAdapter + shareTransport + contactRepository
MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact / deposit / share_detail / qr_display / qr_scan)
auth/
├── DeposplitAuthAdapter.kt  Adapter: BouncyCastle keypair generation + Ed25519 signing + X25519+HKDF+ChaCha20-Poly1305 encrypt/decrypt; Android Keystore AES-GCM wrapping
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
- Registration is keypair-first (BouncyCastle X25519 + Ed25519) — no OIDC, no password, no email. See `deposplit.com/CLAUDE.md` for rationale.
- **No native crypto libraries.** Use `org.bouncycastle:bcprov-jdk18on` for all crypto — no lazysodium-android, no JNA, no `.so` files beyond what AndroidX already packages. BouncyCastle is a pure-JVM library; it works on all emulator ABIs without any extra configuration.
- Share encryption uses **X25519 + HKDF-SHA-256 + ChaCha20-Poly1305**. Wire format: `nonce(12 bytes) || ciphertext+tag`. See `deposplit.com/CLAUDE.md` → *Transport Encryption* for the full construction.
- The X25519 and Ed25519 private keys are stored in the Android Keystore (wrapped with AES-256-GCM under the `deposplit_master` alias) and never leave the device as raw key material.
- Session persistence uses plain `SharedPreferences` (just an "is registered" flag). Do not add `EncryptedSharedPreferences` without a concrete reason; `security-crypto` is not a dependency.

## Pending work — relay protocol

The backend (Apr 2026) implements a **pure relay model**: ciphertext is ephemeral on the server and must be managed by the client. The Android app has not yet been updated. The following changes are required:

### 1. `ShareTransport.kt` — add `pickUpShare`

```kotlin
/** Fetches the ciphertext of a deposited share and signals the relay to clear it.
 *  Must be called before the share can be used in a retrieve approval.
 *  Throws if the share has already been picked up or does not exist.
 */
suspend fun pickUpShare(shareId: String): ByteArray
```

Also update `respondToShareRequest` — the backend now requires the ciphertext in the body when approving a retrieve request:
```kotlin
suspend fun respondToShareRequest(requestId: String, approved: Boolean, ciphertext: ByteArray? = null): ShareRequest
```

### 2. `DeposplitApiAdapter.kt` — implement pickup and ciphertext-on-approve

- `pickUpShare`: `GET /shares/{shareId}`, parse `ciphertext` (base64) from the JSON response.
- `respondToShareRequest`: when `approved == true` and `requestType == RETRIEVE`, include `"ciphertext": base64(ciphertext)` in the PATCH body. The backend returns `400` if it is absent.

### 3. Local share storage

After pickup, store each share's ciphertext on the device (e.g., an AES-GCM encrypted JSON file in `filesDir`, keyed by share ID). This store is the source of truth for the recipient's held shares — the relay listing only shows shares not yet picked up (inbox items awaiting delivery).

Suggested adapter: `LocalShareRepository` (analogous to `LocalContactRepository`) — implements a new port interface `ShareRepository` in `:hexagon`.

### 4. `HomeViewModel` — update Held tab

Switch the Held tab from `listShares(role = RECIPIENT)` (relay listing) to reading from `LocalShareRepository`. On `ON_RESUME`, also poll the relay inbox (`listShares(role = RECIPIENT)`) and auto-pick up any new shares (call `pickUpShare`, store locally, relay clears them).

### 5. `RequestsViewModel` / `RecipientRequestsTab` — ciphertext on approve

When Bob taps **Approve** on a retrieve request, read the share's ciphertext from `LocalShareRepository` and pass it to `respondToShareRequest`. If the share is not found locally (e.g., it was never picked up — should not happen in normal flow), show an error.

### 6. `ShareDetailViewModel` — sender cleanup after reconstruct

After a successful reconstruction, call `DELETE /shares/:shareId` (as sender) for each fully-retrieved share to remove the relay row. The backend allows this only after pickup (`pickedUpAt` set); it returns `409` otherwise.
