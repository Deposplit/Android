# Android — Claude Code guidance

Platform-specific guidance for the `Android/` repository. Cross-project context lives in `deposplit.com/CLAUDE.md` (loaded automatically when launching Claude from the workspace root via the `@`-import in `Deposplit/CLAUDE.md`).

## Toolchain

### AGP 9.x — do not apply `org.jetbrains.kotlin.android`

AGP 9.x registers the `kotlin` Gradle extension itself. Applying `org.jetbrains.kotlin.android` alongside it causes an "extension already registered" error. The project deliberately omits it.

Consequences:
- `kotlinOptions { }` is unavailable — set the JVM target via `compileOptions { sourceCompatibility / targetCompatibility }` only; AGP propagates it to the Kotlin compiler automatically.
- `kotlin.plugin.compose` is still required for Compose compiler support and does not conflict.

### Java / Kotlin versions

- Kotlin 2.4.0, JVM 21 bytecode target
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
├── Identity.kt                    isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign
├── ContactManagement.kt           listContacts, addManually, addFromQr, deleteContact
└── ShareManagement.kt             deposit, listDistributed, listSentRequests, requestAll, openRequest, reconstruct,
                                   syncInbox, listHeld, listPendingRequests, respond, deleteHeldShare,
                                   deleteAllHeldFromSender
driven_ports/
├── IdentityStore.kt               isRegistered, save, pseudonym, edPublicKey, edPrivateKey, xPublicKey, xPrivateKey
├── ContactRepository.kt           getAll, getById, getByEdKey, save, delete
├── ShareRepository.kt             getAll, getPlaintextShare, save, delete (local held-share storage)
├── ShareMetadataRepository.kt     getAll, save, delete (local store of distributed ShareMetadata)
├── ShareRelay.kt                  openShareRequest(..., senderSignature), listShareRequests, getShareRequest,
│                                  respondToShareRequest(..., recipientSignature), deleteShareRequest, deleteShareRequests
├── ShareRelayResolver.kt          resolve(relayBaseUrl: String?): ShareRelay — BYOR factory/cache, not a fan-out
│                                  mechanism; null resolves to the device's default relay (RelaySettings)
└── RelaySettings.kt               getDefaultRelayBaseUrl, setDefaultRelayBaseUrl — device's runtime-configurable
                                   default relay, used by ShareRelayResolver and embedded in outgoing QR codes
services/
├── IdentityService.kt             Implements Identity, ShareEncryption — BouncyCastle keypair generation,
│                                  Ed25519 signing, X25519+HKDF+ChaCha20-Poly1305 encrypt/decrypt; delegates persistence
│                                  to IdentityStore
├── ContactService.kt              Implements ContactManagement — key-size validation, VerificationLevel assignment,
│                                  UUID/timestamp generation; delegates persistence to ContactRepository
├── ShareEncryption.kt             Intra-hexagon interface: encrypt(plaintext, recipientXPublicKey),
│                                  decrypt(noncePlusCiphertext, recipientXPublicKey) — consumed by ShareService,
│                                  implemented by IdentityService
└── ShareService.kt                Implements ShareManagement — Shamir.split/combine + ShareEncryption.encrypt/decrypt +
                                   ShareRelay + ShareRepository + ShareMetadataRepository + ContactRepository;
                                   deposit() opens a PickUp request + writes metadata to local store;
                                   listDistributed() reads from local store; syncDistributed() syncs field updates from relay (upserts, never deletes);
                                   syncInbox() auto-approves pending PickUp requests; reconstruct() deletes PickUp rows (cascades)
value_objects/
├── Contact.kt                     Contact data class (incl. relayBaseUrl BYOR override) + VerificationLevel enum
├── HeldShare.kt                   HeldShare data class
├── Share.kt                       Role, ShareRequestType, ShareRequestState, ShareMetadata, ShareRequest
│                                  (incl. senderSignature/recipientSignature)
├── PayloadCanonical.kt            forOpen/forRespond — canonical byte constructions signed for
│                                  senderSignature/recipientSignature; mirrors deposplit.com's PayloadCanonical
└── SignatureVerificationException.kt  Thrown by respond()/reconstruct() on an unverifiable signature
```

Tests: `:hexagon/src/test/kotlin/com/deposplit/shamir/ShamirTest.kt` — round-trip, cross-platform vectors, input validation. Uses `kotlin.test` (JUnit 4 backend via `kotlin-test-junit`).

### `:app/src/main/kotlin/com/deposplit/`

```
DeposplitApp.kt              Application subclass; owns authAdapter + contactManagement + shareManagement + relaySettings
MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact / deposit / share_detail / qr_display / qr_scan / settings)
auth/
└── AndroidIdentityStore.kt  Adapter implementing IdentityStore — Android Keystore AES-256-GCM wrapping of private keys; public keys + pseudonym in SharedPreferences
api/
├── DeposplitApiAdapter.kt   HTTP adapter implementing ShareRelay: HttpURLConnection, Ed25519 request signing,
│                            JSON via kotlinx.serialization; senderSignature/recipientSignature wired through
├── DeposplitRelayResolver.kt  Implements ShareRelayResolver — memoizes one DeposplitApiAdapter per resolved base
│                            URL; null resolves via RelaySettings.getDefaultRelayBaseUrl()
└── RelayDefaults.kt         FALLBACK_BASE_URL constant ("https://api.deposplit.com") — plain Kotlin, decoupled
                             from build-variant machinery (replaces the old BuildConfig.BASE_URL)
settings/
└── SharedPreferencesRelaySettings.kt  Implements RelaySettings — same "deposplit" SharedPreferences file
                             AndroidIdentityStore uses
contacts/
└── LocalContactRepository.kt  JSON file in filesDir; @Synchronized; kotlinx.serialization wire types
shares/
├── LocalShareRepository.kt         JSON file in filesDir (shares.json); @Synchronized; stores HeldShare (plaintext share + metadata) keyed by share ID
└── LocalShareMetadataRepository.kt JSON file in filesDir (distributed_shares.json); @Synchronized; local store of distributed ShareMetadata keyed by share ID
ui/
├── signin/       SignInViewModel + SignInScreen              — pseudonym input + Register button
├── home/         HomeViewModel + HomeScreen                 — My Shared Secrets / Their Secret Shares / Requests tabs
│                                                              SecretGroup + HolderStatus (sender view); HeldShareDisplay + HeldSortOrder (recipient view)
│                                                              requestAll, setHeldSortOrder, deleteSingleShare, deleteAllFromSender
├── contacts/     ContactsViewModel + ContactsScreen (contactManagement.listContacts/deleteContact),
│               AddContactViewModel + AddContactScreen (contactManagement.addManually),
│               QrScanViewModel uses contactManagement.addFromQr
├── deposit/      DepositViewModel + DepositScreen           — contactManagement.listContacts + shareManagement.deposit(...)
├── requests/     RequestsViewModel + RecipientRequestsTab   — approve/deny incoming requests
├── sharedetail/  ShareDetailViewModel + ShareDetailScreen   — open RETRIEVE/DELETE + shareManagement.reconstruct(...)
├── qr/           QrPayload (v2, incl. relay field), QrDisplay{ViewModel,Screen}, QrScan{ViewModel,Screen}
├── settings/     SettingsViewModel + SettingsScreen         — edit/reset the default relay (RelaySettings)
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

## Continuous Integration

`.github/workflows/test.yml` runs `./gradlew test` (both `:hexagon` and `:app` JVM unit tests) on `ubuntu-latest` for every push and for pull requests targeting `main` — JDK 25/Temurin via `actions/setup-java`, no Android SDK setup step needed (GitHub's `ubuntu-latest` images ship one). `.github/dependabot.yml` covers the `github-actions` and `gradle` ecosystems on a weekly schedule; the latter picks up `build.gradle.kts` in the root, `app`, and `hexagon` modules, plus `gradle/libs.versions.toml`.

## Environment configuration (default relay, BYOR)

The relay endpoint is no longer a compile-time `BuildConfig` field — BYOR (see `deposplit.com/CLAUDE.md`) needs a per-contact relay override anyway, so the "default relay" (used for any contact without one) is a **runtime-configurable setting** instead, resolved through the same mechanism:

- `RelaySettings` (`:hexagon` driven port) — `getDefaultRelayBaseUrl()` / `setDefaultRelayBaseUrl(url: String?)`.
- `SharedPreferencesRelaySettings` (`app/.../settings/`) — persists to the same `"deposplit"` SharedPreferences file `AndroidIdentityStore` already uses; falls back to `RelayDefaults.FALLBACK_BASE_URL` (`app/.../api/RelayDefaults.kt`, hardcoded `https://api.deposplit.com`) when unset.
- `ShareRelayResolver` / `DeposplitRelayResolver` (`app/.../api/`) — resolves a `Contact.relayBaseUrl` override to its own `DeposplitApiAdapter`, or falls back to `RelaySettings` when the override is `null`; memoizes one adapter per distinct base URL.
- **Settings screen** (`ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`, gear icon on `HomeScreen`) — the user-facing way to change the default relay. For local dev (emulator/physical device), point it at `http://10.0.2.2:9000` (emulator) or your LAN IP — no rebuild needed, unlike the old `local.properties`-based `BASE_URL`.

`BuildConfig.SKIP_BIOMETRIC` is unrelated and still configured via `local.properties` — see below.

### Skipping biometric during development

Emulators often have no enrolled biometric, which blocks the Reconstruct flow. Add to `Android/local.properties`:

```
SKIP_BIOMETRIC=true
```

When set, `ShareDetailScreen` shows the Reconstruct button unconditionally and calls `viewModel.reconstruct()` directly, bypassing `BiometricGate`. The release build always enforces biometric regardless of this key. Gradle reads `local.properties` at sync time — rebuild the app after editing.

## Key decisions to preserve

- `minSdk = 29` — do not lower; see `deposplit.com/CLAUDE.md` for rationale.
- All UI in Jetpack Compose (no XML layouts).
- Registration is keypair-first (BouncyCastle X25519 + Ed25519) — no OIDC, no password, no email. See `deposplit.com/CLAUDE.md` for rationale.
- **No native crypto libraries.** Use `org.bouncycastle:bcprov-jdk18on` for all crypto — no lazysodium-android, no JNA, no `.so` files beyond what AndroidX already packages. BouncyCastle is a pure-JVM library; it works on all emulator ABIs without any extra configuration.
- Share encryption uses **X25519 + HKDF-SHA-256 + ChaCha20-Poly1305**. Wire format: `nonce(12 bytes) || ciphertext+tag`. See `deposplit.com/CLAUDE.md` → *Transport Encryption* for the full construction.
- The X25519 and Ed25519 private keys are stored in the Android Keystore (wrapped with AES-256-GCM under the `deposplit_master` alias) and never leave the device as raw key material.
- Session persistence uses plain `SharedPreferences` (just an "is registered" flag). Do not add `EncryptedSharedPreferences` without a concrete reason; `security-crypto` is not a dependency.
- **Local share storage** (`LocalShareRepository`): held shares (plaintext share + metadata) are stored as JSON in `filesDir/shares.json`, keyed by share ID. On `syncInbox()` the relay is polled for pending PickUp requests; each is approved (which delivers the ciphertext once and clears the relay row), decrypted on-device with the holder's X25519 private key + the sender's current X25519 public key, and the resulting **plaintext** is what's stored locally — see `deposplit.com/CLAUDE.md` item 7 (holder-decrypts-at-pickup). The share is standard base64 in the JSON wire format; the sender is referenced by `contactId` (a stable local UUID), not by key, so the record survives a sender key rotation.

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

