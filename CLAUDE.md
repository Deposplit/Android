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
└── Shamir.kt                      split(...) / combine(...) — SSS implementation; combineWithIntegrity(...) + IntegrityCombineResult +
                                   ReconstructionIntegrityException (item 13 — bounded-exhaustive maximum-agreement decoding to
                                   detect/exclude a bad share among a surplus beyond threshold, via the Reed–Solomon
                                   unique-decoding-radius bound)
driving_ports/
├── Identity.kt                    isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign;
│                                  generateNewKeyPair/activateKeyPair (item 9 — regenerate-identity trigger;
│                                  generateNewKeyPair is pure key generation, not persisted, so a caller can push
│                                  a rotation notice signed by the old identity before activateKeyPair persists
│                                  the new one via the existing IdentityStore.save)
├── ContactManagement.kt           listContacts, addManually, addFromQr, updateContact (contact-update-in-place,
│                                  item 8 — key change forces a fresh verificationLevel), deleteContact,
│                                  markKeyCompromised (item 10 — flags an Ed25519 key into the contact's
│                                  revokedEdKeys history; defaults to the contact's current key when none is given;
│                                  idempotent)
├── ShareManagement.kt             deposit, listSecrets, listDistributed, listSentRequests, requestAll, openRequest,
│                                  reconstruct (pure read, enforces real k, returns ReconstructionResult — item 13's
│                                  integrity cross-check on any surplus beyond k), discardSecret, forceForgetSecret,
│                                  syncInbox, listHeld, listPendingRequests, respond, deleteHeldShare,
│                                  deleteAllHeldFromSender, pushRecoveryMetadata (item 8 — holder side);
│                                  pushRotation (item 9, client primitive, reused unchanged by regenerateIdentity);
│                                  listKeyConflicts, dismissKeyConflict (item 10, local-only, no relay involvement);
│                                  regenerateIdentity (item 9 — the "regenerate my own identity" trigger: best-effort
│                                  drains the inbox/distributed state under the old identity, generates new keys,
│                                  pushes a signed rotation to every contact via pushRotation while still signing
│                                  as the old identity, then activates the new keys — returns RegenerateIdentityResult)
└── CatalogManagement.kt           exportCatalog, importCatalog — optional non-secret catalog backup (item 8)
driven_ports/
├── IdentityStore.kt               isRegistered, save, pseudonym, edPublicKey, edPrivateKey, xPublicKey, xPrivateKey
├── ContactRepository.kt           getAll, getById, getByEdKey, save, delete
├── ShareRepository.kt             getAll, getPlaintextShare (keyed on secretId, not the pickup relay-row id —
│                                  item 8), save, delete (local held-share storage)
├── SecretRepository.kt            getAll, save, delete (local store of sender-side Secret aggregates)
├── ShareMetadataRepository.kt     getAll, save, delete (local store of distributed ShareMetadata)
├── KeyConflictRepository.kt       getAll, save, delete — local store of item 10's KeyConflict records; the
│                                  durable copy a detected conflict is captured into before its relay notice is
│                                  deleted, since the relay may lose its state at any time and must never be
│                                  relied on to keep the alert alive
├── ShareRelay.kt                  openShareRequest(..., k, n, senderSignature), listShareRequests, getShareRequest,
│                                  respondToShareRequest(..., recipientSignature), deleteShareRequest, deleteShareRequests,
│                                  withdrawShareRequests (item 9 — best-effort tombstone, not a hard delete),
│                                  pushRotation/listRotations/deleteRotation (item 9's signed rotate(K_old→K_new)
│                                  push — grouped onto this interface rather than a separate one since it's the
│                                  same physical relay + BYOR routing; see KeyRotation.kt)
├── ShareRelayResolver.kt          resolve(relayBaseUrl: String?): ShareRelay — BYOR factory/cache, not a fan-out
│                                  mechanism; null resolves to the device's default relay (RelaySettings)
└── RelaySettings.kt               getDefaultRelayBaseUrl, setDefaultRelayBaseUrl — device's runtime-configurable
                                   default relay, used by ShareRelayResolver and embedded in outgoing QR codes
services/
├── IdentityService.kt             Implements Identity, ShareEncryption — BouncyCastle keypair generation,
│                                  Ed25519 signing, X25519+HKDF+ChaCha20-Poly1305 encrypt/decrypt; delegates persistence
│                                  to IdentityStore; generateNewKeyPair/activateKeyPair (item 9) share the same
│                                  key-gen helper register() uses, factored out for reuse
├── ContactService.kt              Implements ContactManagement — key-size validation, VerificationLevel assignment,
│                                  UUID/timestamp generation; delegates persistence to ContactRepository;
│                                  updateContact requires a fresh verificationLevel whenever either key changes and
│                                  now also carries revokedEdKeys forward and stamps keyChangedAt when a key
│                                  actually changes (item 10); markKeyCompromised (item 10) is idempotent
├── ShareEncryption.kt             Intra-hexagon interface: encrypt(plaintext, recipientXPublicKey),
│                                  decrypt(noncePlusCiphertext, recipientXPublicKey) — consumed by ShareService,
│                                  implemented by IdentityService
├── ShareService.kt                Implements ShareManagement — Shamir.split/combine + ShareEncryption.encrypt/decrypt +
│                                  ShareRelay + ShareRepository + ShareMetadataRepository + SecretRepository + ContactRepository;
│                                  deposit() opens a Deposit request (incl. k/n) + writes ShareMetadata + a Secret to local
│                                  store; listDistributed() reads from local store; syncDistributed() syncs field updates
│                                  from relay (upserts, never deletes), then reconcileDiscarding() cleans up DISCARDING
│                                  secrets whose holder removals have been approved; syncInbox() auto-approves pending
│                                  Deposit requests then calls processRecoveryMetadata() (item 8, private — consumes
│                                  approved inventory pushes, verified against a known contact, rebuilding
│                                  Secret/ShareMetadata); respond()'s retrieval/removal paths match the holder's HeldShare
│                                  by secretId, not the sender's local shareId (item 8); reconstruct() is a pure read
│                                  (enforces Secret.k, no teardown — see item 11) that now calls combineWithIntegrity
│                                  and maps its result to ReconstructionResult (item 13); requestAll() targets item 12's
│                                  Confirmed freshness bucket first via a private isConfirmed helper, widening to every
│                                  holder only when fewer than k are confirmed (item 13); discardSecret() fans out delete
│                                  requests + flips Secret to DISCARDING; forceForgetSecret() is the local-only escape
│                                  hatch; pushRecoveryMetadata(contactId) opens a recoveryMetadata push for every
│                                  HeldShare held from that contact (item 8); takes a new ContactManagement dependency
│                                  (item 9) so processRotations() (private, called from syncInbox) can call updateContact
│                                  after auto-verifying an incoming rotation notice against a known contact's trusted
│                                  old key, downgrading the verification level to min(old, LOW) per item 10's unifying
│                                  rule; deleteHeldShare/deleteAllHeldFromSender best-effort withdraw via the sender's
│                                  relay before deleting locally (item 9); syncDistributed() drops the local
│                                  ShareMetadata pointer and deletes the relay row when it observes a WITHDRAWN deposit;
│                                  takes a new KeyConflictRepository dependency (item 10) so processRotations() checks
│                                  the notice's oldEd25519Key against the contact's revokedEdKeys *before* the
│                                  downgrade/auto-accept branch — on a match it saves a KeyConflict, deletes the relay
│                                  notice, and skips updateContact entirely (never auto-resolved); listKeyConflicts/
│                                  dismissKeyConflict (item 10) delegate directly to KeyConflictRepository;
│                                  regenerateIdentity() (item 9) best-effort drains (syncInbox/syncDistributed) under
│                                  the old identity, generates new keys via identity.generateNewKeyPair(), pushes a
│                                  rotation to every contact via the unchanged pushRotation (order matters — this
│                                  must happen before the swap, since pushRotation signs with whatever identity is
│                                  currently persisted), then calls identity.activateKeyPair()
└── CatalogService.kt              Implements CatalogManagement — exportCatalog reads Contact/Secret/ShareMetadata
                                   repositories; importCatalog upserts-if-absent-by-id, never overwriting a newer
                                   local record (item 8)
value_objects/
├── Catalog.kt                     Catalog data class (contacts, secrets, shareMetadata) — item 8's optional backup
├── Contact.kt                     Contact data class (incl. relayBaseUrl BYOR override) + VerificationLevel enum;
│                                  gained revokedEdKeys: List<ByteArray> (item 10 — historical set, not a single
│                                  flag, so a later legitimate relink to a genuinely new key is never blocked) and
│                                  keyChangedAt: Instant? (item 10 — stamped by updateContact on any key change,
│                                  surfaced as "key changed N days ago" on retrieve-approval)
├── KeyConflict.kt                 KeyConflict data class (item 10) — id, contactId, oldEd25519Key, newEd25519Key,
│                                  newX25519Key, detectedAt; captured the instant a rotation notice's old key is
│                                  found in revokedEdKeys, durable and local, never re-derived from the relay
├── HeldShare.kt                   HeldShare data class (incl. k/n, item 8 — reported back to the owner during recovery)
├── Secret.kt                      Secret data class (id, label, k, n, secretCreatedAt, state) + SecretState enum
│                                  (ACTIVE/DISCARDING) — sender-side per-secret aggregate, see CLAUDE.md item 11
├── ReconstructionResult.kt        ReconstructionResult (secret, integrity) + ReconstructionIntegrity sealed class
│                                  (NoMargin/Confirmed/ExcludedSuspects(excludedContactIds), item 13) — reconstruct()'s return type
├── KeyPairMaterial.kt             KeyPairMaterial data class (edPublicKey/edPrivateKey/xPublicKey/xPrivateKey, item 9) —
│                                  a freshly generated keypair not yet persisted as this device's identity
├── RegenerateIdentityResult.kt    RegenerateIdentityResult (notifiedContacts, totalContacts, item 9) — regenerateIdentity()'s return type
├── KeyRotation.kt                 KeyRotation data class (item 9) — a signed rotate(K_old→K_new) notice addressed
│                                  to this device; not a ShareRequest (no secretId, no consent phase)
├── Share.kt                       Role, ShareTransactionType (incl. INVENTORY, item 8 — self-approved, no
│                                  consent phase), ShareRequestState (incl. WITHDRAWN — item 9, deposit-only
│                                  best-effort tombstone), ShareMetadata (id/secretId/contactId only —
│                                  label/secretCreatedAt live on Secret), ShareRequest (incl. k/n,
│                                  senderSignature/recipientSignature)
├── PayloadCanonical.kt            forOpen (incl. k/n, item 8, appended at the end of the signed sequence)/forRespond
│                                  — canonical byte constructions signed for senderSignature/recipientSignature;
│                                  mirrors deposplit.com's PayloadCanonical
└── SignatureVerificationException.kt  Thrown by respond()/reconstruct() on an unverifiable signature
```

Tests: `:hexagon/src/test/kotlin/com/deposplit/shamir/ShamirTest.kt` — round-trip, cross-platform vectors, input validation. Uses `kotlin.test` (JUnit 4 backend via `kotlin-test-junit`).

### `:app/src/main/kotlin/com/deposplit/`

```
DeposplitApp.kt              Application subclass; owns authAdapter + contactManagement + shareManagement + catalogManagement + relaySettings
MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact / relink_contact/{contactId} / deposit / share_detail / repair/{secretId} / qr_display / qr_scan / settings)
auth/
└── AndroidIdentityStore.kt  Adapter implementing IdentityStore — Android Keystore AES-256-GCM wrapping of private keys; public keys + pseudonym in SharedPreferences
api/
├── DeposplitApiAdapter.kt   HTTP adapter implementing ShareRelay: HttpURLConnection, Ed25519 request signing,
│                            JSON via kotlinx.serialization; senderSignature/recipientSignature and k/n (item 8) wired through;
│                            POST /share-requests/withdraw and POST/GET /key-rotations + DELETE /key-rotations/{id} (item 9)
├── DeposplitRelayResolver.kt  Implements ShareRelayResolver — memoizes one DeposplitApiAdapter per resolved base
│                            URL; null resolves via RelaySettings.getDefaultRelayBaseUrl()
└── RelayDefaults.kt         FALLBACK_BASE_URL constant ("https://api.deposplit.com") — plain Kotlin, decoupled
                             from build-variant machinery (replaces the old BuildConfig.BASE_URL)
settings/
├── SharedPreferencesRelaySettings.kt  Implements RelaySettings — same "deposplit" SharedPreferences file
│                            AndroidIdentityStore uses
└── CatalogCodec.kt          JSON (de)serialization for Catalog (item 8) — lives in the app layer since the
                             hexagon has no JSON dependency; mirrors the Local*Repository wire-shape conventions
contacts/
├── LocalContactRepository.kt  JSON file in filesDir; @Synchronized; kotlinx.serialization wire types; ContactWire
│                            gained non-optional revokedEdKeys: List<String> (base64url) and keyChangedAt: String?
│                            (item 10 — no optional/fallback decode shim, since Deposplit is pre-launch and local
│                            stores are wiped, not migrated)
└── LocalKeyConflictRepository.kt  JSON file in filesDir (key_conflicts.json) (item 10) — structurally identical
                             to LocalShareMetadataRepository.kt: @Synchronized, kotlinx.serialization wire type,
                             base64url keys, ISO-8601 timestamps
shares/
├── LocalShareRepository.kt         JSON file in filesDir (shares.json); @Synchronized; stores HeldShare (plaintext share + metadata, incl. k/n) keyed by share ID; getPlaintextShare keyed on secretId (item 8)
├── LocalSecretRepository.kt        JSON file in filesDir (secrets.json); @Synchronized; local store of sender-side Secret aggregates
└── LocalShareMetadataRepository.kt JSON file in filesDir (distributed_shares.json); @Synchronized; local store of distributed ShareMetadata keyed by share ID
ui/
├── signin/       SignInViewModel + SignInScreen              — pseudonym input + Register button
├── home/         HomeViewModel + HomeScreen                 — My Shared Secrets / Their Secret Shares / Requests tabs
│                                                              SecretGroup (wraps a Secret) + HolderStatus (sender view); HeldShareDisplay + HeldSortOrder (recipient view)
│                                                              SecretHealth (graduated n_live-vs-k badge, item 11) computed on SecretGroup
│                                                              requestAll, discardSecret, forceForgetSecret, setHeldSortOrder, deleteSingleShare, deleteAllFromSender
├── contacts/     ContactsViewModel + ContactsScreen (contactManagement.listContacts/deleteContact, "Relink" icon per row;
│               a red warning badge when revokedEdKeys is non-empty, a "Mark Key Compromised" IconButton +
│               AlertDialog confirmation, item 10 — viewModel.markKeyCompromised),
│               AddContactViewModel + AddContactScreen (contactManagement.addManually),
│               QrScanViewModel uses contactManagement.addFromQr,
│               RelinkContactViewModel + RelinkContactScreen (item 8) — scans a re-presented QR code, calls
│               contactManagement.updateContact then shareManagement.pushRecoveryMetadata; distinct from
│               QrScanViewModel, which always mints a *new* contact
├── deposit/      DepositViewModel + DepositScreen           — contactManagement.listContacts + shareManagement.deposit(...);
│               SplitTimeWarning sealed interface + onDepositClick/confirmDespiteWarnings (item 11 non-blocking split-time warnings);
│               DepositViewModel gained an optional Prefill constructor param (item 9, seeds UiState) and DepositScreen's form
│               body was extracted into a public DepositForm composable so RepairScreen can embed the identical validated form
│               (including the warning dialog) as its own wizard step instead of duplicating it
├── repair/       RepairViewModel + RepairScreen (item 9 — reconstruct-and-re-split "Repair" flow, deposplit.com/CLAUDE.md
│               "What is next" item 9) — one screen, internal wizard state (RepairPhase: GATHERING/RECONSTRUCTING/REDEPOSIT/
│               CONFIRM_DISCARD/DONE) composing three already-existing primitives: requestAll/reconstruct (ShareManagement),
│               and (via a key-scoped, prefilled DepositViewModel constructed only on entering REDEPOSIT) deposit, then
│               discardSecret. Reconstruct is biometric-gated exactly like ShareDetailScreen (reuses the same
│               ui/biometric/authenticate + biometricAvailability helpers); the reconstructed plaintext lives only in that
│               transient DepositViewModel's UiState, dropped immediately on deposit success. discardSecret is called at most
│               once per flow (confirmed non-idempotent — see ShareService.discardSecret). Entry point is a "Repair" button on
│               HomeScreen's SecretGroupCard, shown only when SecretHealth is CAUTION or CRITICAL, navigating to
│               "repair/{secretId}". Gained reconstructionIntegrity + contacts in UiState (item 13); the redeposit step
│               renders a ReconstructionAdvisory above the embedded DepositForm once reconstruct succeeds
├── reconstruction/  ReconstructionAdvisory composable (item 13) — renders ReconstructionIntegrity's three cases as a
│               one-line badge (info/checkmark/warning), shared by ShareDetailScreen and RepairScreen; takes a
│               contactName: (UUID) -> String lambda to resolve ExcludedSuspects' names
├── requests/     RequestsViewModel + RecipientRequestsTab   — approve/deny incoming requests; RequestsViewModel
│               gained keyConflicts: List<KeyConflict> (loaded in load(), soft-failed), keyChangedDaysAgo(request)
│               (item 10 — gated to RETRIEVAL requests only, per the "key change → quick retrieval" attack
│               signature), contactName(conflict), dismissConflict(id); RecipientRequestsTab renders a
│               KeyConflictItem list ("Possible impersonation attempt," Dismiss only, steers to the existing
│               Relink flow rather than any new "Accept" action, never auto-resolved) above pending requests, and
│               RequestItem shows an orange "key changed N days ago" Label when keyChangedDaysAgo is non-null
├── sharedetail/  ShareDetailViewModel + ShareDetailScreen   — open RETRIEVAL/REMOVAL + shareManagement.reconstruct(...);
│               loads both the ShareMetadata and its parent Secret (for label/k); UiState gained reconstructionIntegrity
│               (item 13), rendered via a ReconstructionAdvisory under the reconstructed secret; a distinct
│               share_detail_error_integrity message is shown when ReconstructionIntegrityException is thrown
├── qr/           QrPayload (v2, incl. relay field), QrDisplay{ViewModel,Screen}, QrScan{ViewModel,Screen}
│               (CameraViewfinder composable shared with RelinkContactScreen)
├── settings/     SettingsViewModel + SettingsScreen         — edit/reset the default relay (RelaySettings);
│               "Catalog Backup" section (item 8) — export via SAF CreateDocument, import via SAF OpenDocument;
│               "Identity" section (item 9) — "Regenerate My Identity" destructive button + AlertDialog
│               confirmation (contact count pre-fetched via ContactManagement.listContacts()) calling
│               shareManagement.regenerateIdentity(); shows a loading state then "Notified X of Y contact(s)."
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
- **Local share storage** (`LocalShareRepository`): held shares (plaintext share + metadata) are stored as JSON in `filesDir/shares.json`, keyed by share ID. On `syncInbox()` the relay is polled for pending Deposit requests; each is approved (which delivers the ciphertext once and clears the relay row), decrypted on-device with the holder's X25519 private key + the sender's current X25519 public key, and the resulting **plaintext** is what's stored locally — see `deposplit.com/CLAUDE.md` item 7 (holder-decrypts-at-pickup). The share is standard base64 in the JSON wire format; the sender is referenced by `contactId` (a stable local UUID), not by key, so the record survives a sender key rotation.

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

