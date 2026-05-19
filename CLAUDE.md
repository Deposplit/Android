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

Packages use snake_case to mirror the Scala relay hexagon (`driving_ports`, `driven_ports`, etc.).

```
shamir/
└── Shamir.kt                      split(...) / combine(...) — SSS implementation
driving_ports/
├── Identity.kt                    isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign, encrypt, decrypt
└── ShareTransport.kt              depositShare, listShares, pickUpShare, deleteShare, openShareRequest,
                                   listShareRequests, getShareRequest, respondToShareRequest
                                   + value types: Role, ShareRequestType, ShareRequestState, ShareMetadata{+pickedUpAt}, ShareRequest
driven_ports/
├── IdentityStore.kt               isRegistered, save, pseudonym, edPublicKey, edPrivateKey, xPublicKey, xPrivateKey
├── ContactRepository.kt           getAll, getById, getByEdKey, save, delete
└── ShareRepository.kt             getAll, getCiphertext, save, delete (local share storage)
services/
└── IdentityService.kt             Implements Identity — BouncyCastle keypair generation, Ed25519 signing,
                                   X25519+HKDF+ChaCha20-Poly1305 encrypt/decrypt; delegates persistence to IdentityStore
value_objects/
├── Contact.kt                     Contact data class + VerificationLevel enum (UNVERIFIED, VERIFIED)
├── HeldShare.kt                   HeldShare data class
└── Share.kt                       Role, ShareRequestType, ShareRequestState, ShareMetadata, ShareRequest
```

Tests: `:hexagon/src/test/kotlin/com/deposplit/shamir/ShamirTest.kt` — round-trip, cross-platform vectors, input validation. Uses `kotlin.test` (JUnit 4 backend via `kotlin-test-junit`).

### `:app/src/main/kotlin/com/deposplit/`

```
DeposplitApp.kt              Application subclass; owns authAdapter + shareTransport + contactRepository + shareRepository
MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact / deposit / share_detail / qr_display / qr_scan)
auth/
├── AndroidIdentityStore.kt  Adapter implementing IdentityStore — Android Keystore AES-256-GCM wrapping of private keys; public keys + pseudonym in SharedPreferences
└── SignInViewModel.kt       UI logic for the registration flow
api/
└── DeposplitApiAdapter.kt   HTTP adapter: HttpURLConnection, Ed25519 request signing, JSON via kotlinx.serialization
                             pickUpShare (GET /shares/:shareId) + ciphertext-on-approve (PATCH /share-requests/:id)
contacts/
└── LocalContactRepository.kt  JSON file in filesDir; @Synchronized; kotlinx.serialization wire types
shares/
└── LocalShareRepository.kt  JSON file in filesDir; @Synchronized; stores HeldShare (ciphertext + metadata) keyed by share ID
ui/
├── signin/       SignInScreen                               — pseudonym input + Register button
├── home/         HomeViewModel + HomeScreen                 — My Shared Secrets / Their Secret Shares / Requests tabs
│                                                              SecretGroup + HolderStatus (sender view); HeldShareDisplay + HeldSortOrder (recipient view)
│                                                              requestAll, setHeldSortOrder, deleteSingleShare, deleteAllFromSender
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

## Environment configuration (base URL)

`BuildConfig.BASE_URL` and `BuildConfig.SKIP_BIOMETRIC` are set via `buildConfigField` in `app/build.gradle.kts`. The `release` build type hard-codes safe production values for both; the `debug` build type reads overrides from `local.properties` (already gitignored) and falls back to safe defaults.

| `local.properties` key | Type | Debug default | Release |
|---|---|---|---|
| `BASE_URL` | `String` | `http://10.0.2.2:9000` | `https://api.deposplit.com` (fixed) |
| `SKIP_BIOMETRIC` | `Boolean` | `false` | `false` (fixed) |

Gradle reads `local.properties` at sync time — rebuild the app after editing. Android Studio may regenerate `local.properties` when you change the SDK path, but it only rewrites `sdk.dir`; custom keys survive.

### Changing the debug URL (e.g. physical device on LAN)

Add to `Android/local.properties`:

```
BASE_URL=http://192.168.x.x:9000
```

### Skipping biometric during development

Emulators often have no enrolled biometric, which blocks the Reconstruct flow. Add to `Android/local.properties`:

```
SKIP_BIOMETRIC=true
```

When set, `ShareDetailScreen` shows the Reconstruct button unconditionally and calls `viewModel.reconstruct()` directly, bypassing `BiometricGate`. The release build always enforces biometric regardless of this key.

### Alternative: product flavors with `buildConfigField`

If you ever need a persistent named environment (e.g. `staging` pointing at `https://staging.api.deposplit.com`) rather than a per-developer override, prefer **product flavors** over `local.properties`:

```kotlin
// app/build.gradle.kts
flavorDimensions += "env"
productFlavors {
    create("emulator") {
        dimension = "env"
        buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:9000\"")
    }
    create("device") {
        dimension = "env"
        // developer fills in their LAN IP here and does not commit it
        buildConfigField("String", "BASE_URL", "\"http://192.168.x.x:9000\"")
    }
    create("staging") {
        dimension = "env"
        buildConfigField("String", "BASE_URL", "\"https://staging.api.deposplit.com\"")
    }
}
```

This produces named build variants (`emulatorDebug`, `deviceDebug`, `stagingDebug`, `stagingRelease`, …) selectable from the Android Studio Build Variants panel and from `./gradlew assembleEmulatorDebug`. The tradeoff: each new flavor multiplies the variant matrix; for solo development, `local.properties` is simpler.

## Key decisions to preserve

- `minSdk = 29` — do not lower; see `deposplit.com/CLAUDE.md` for rationale.
- All UI in Jetpack Compose (no XML layouts).
- Registration is keypair-first (BouncyCastle X25519 + Ed25519) — no OIDC, no password, no email. See `deposplit.com/CLAUDE.md` for rationale.
- **No native crypto libraries.** Use `org.bouncycastle:bcprov-jdk18on` for all crypto — no lazysodium-android, no JNA, no `.so` files beyond what AndroidX already packages. BouncyCastle is a pure-JVM library; it works on all emulator ABIs without any extra configuration.
- Share encryption uses **X25519 + HKDF-SHA-256 + ChaCha20-Poly1305**. Wire format: `nonce(12 bytes) || ciphertext+tag`. See `deposplit.com/CLAUDE.md` → *Transport Encryption* for the full construction.
- The X25519 and Ed25519 private keys are stored in the Android Keystore (wrapped with AES-256-GCM under the `deposplit_master` alias) and never leave the device as raw key material.
- Session persistence uses plain `SharedPreferences` (just an "is registered" flag). Do not add `EncryptedSharedPreferences` without a concrete reason; `security-crypto` is not a dependency.
- **Local share storage** (`LocalShareRepository`): held shares (ciphertext + metadata) are stored as JSON in `filesDir/shares.json`, keyed by share ID. The relay is polled for new inbox items on each load; each new share is picked up (`GET /shares/:shareId`), stored locally, and the relay clears its ciphertext. Ciphertext is standard base64 in the JSON wire format; sender keys are base64url (consistent with the contact and API conventions).

## what follows was moved from ../deposplit.com/CLAUDE.md to ./CLAUDE.md

### Android App

#### Minimum SDK: API 29 (Android 10)

The Android app targets **`minSdk = 29`**, not the Android Studio default of API 24. This was a deliberate choice for a security-sensitive app:

| API | Feature | Relevance |
|---|---|---|
| 28 | **`BiometricPrompt`** (native) | Gate secret reconstruction behind biometric auth |
| 28 | **StrongBox Keymaster** (`setIsStrongBoxBacked(true)`) | Keys stored in dedicated security chip, not just TEE |
| 28 | Cleartext traffic disabled by default | No accidental plaintext traffic to the Web app/service |
| 29 | **Scoped Storage** | Relevant for the file-upload secret input method |
| 29 | **TLS 1.3** enabled by default | Baseline transport security for Web app/service comms |

API 29 still covers >90% of active Android devices, which is acceptable for a niche security app. Do not lower `minSdk` without revisiting these dependencies.

Note: `BiometricPrompt` and StrongBox require runtime capability checks regardless of `minSdk` — `BiometricManager.canAuthenticate()` and `setIsStrongBoxBacked(true)` can throw `StrongBoxUnavailableException` on devices lacking the hardware.

#### Authentication / Registration

Registration is **keypair-first** — no OIDC, no password, no email.

Flow:
1. On first launch the device generates two keypairs via BouncyCastle (Android) / Swift Crypto (iOS): an X25519 keypair (share encryption) and an Ed25519 keypair (API authentication)
2. The user picks a pseudonym (display name only — stored locally, never sent to the Web app/service)
3. Both private keys are stored in the Android Keystore (wrapped with AES-256-GCM) and never leave the device
4. Both public keys are shared with contacts out-of-band (QR code scan, share link via Signal/Threema, etc.) — the Web app/service never stores or indexes them

Subsequent API requests are authenticated by signing a canonical request representation with the Ed25519 private key; the Web app/service verifies against the Ed25519 public key supplied in the `X-Deposplit-Public-Key` header. No pre-registration is required.

Session state (the "has completed onboarding" flag) is persisted via plain `SharedPreferences`. Private keys are managed by the Android Keystore — the app never handles raw key material directly.

Identity *is* the keypair pair. This integrates directly with the k-of-n social recovery design: if Alice loses her device, she generates new keypairs on a new device and initiates a re-association request that existing contacts approve.

#### UI toolkit: Jetpack Compose + Material 3

Use **Jetpack Compose** (not XML/Views) for all UI. The "Empty Activity" template in Android Studio is the Compose template and is the correct starting point for new app scaffolding.

#### Build toolchain: AGP 9.x + Kotlin 2.x

AGP 9.x integrates Kotlin compilation directly — it registers the `kotlin` Gradle extension itself. Do **not** apply `org.jetbrains.kotlin.android`; doing so causes a "extension already registered" conflict. The Android Studio template deliberately omits it.

Consequences:
- `kotlinOptions { }` is **not available** (it requires `kotlin.android`)
- Set the Kotlin JVM target via `compileOptions` only — AGP 9.x propagates `targetCompatibility` to the Kotlin compiler automatically
- `kotlin.plugin.compose` (the Compose compiler plugin) is still required and does not conflict
