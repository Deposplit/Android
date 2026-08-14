# Deposplit — Android

Kotlin Android app for [Deposplit](https://github.com/Deposplit/deposplit.com): a secret-sharing app built on Shamir's Secret Sharing (SSS). Secrets are split into *n* shares and distributed to contacts via the deposplit.com Web app/service; reconstruction requires at least *k* holders to cooperate.

This document is written for a developer who knows Kotlin well but has limited Android experience.

---

## Table of contents

1. [Android concepts you need](#android-concepts-you-need)
2. [Project structure](#project-structure)
3. [Architecture](#architecture)
4. [The registration flow](#the-registration-flow)
5. [Building and running](#building-and-running)
6. [What is next](#what-is-next)

---

## Android concepts you need

### Activities and the Application class

An Android app does not have a `main()` function. Instead, the OS launches a designated **Activity** — a class that owns a window. Deposplit has one: `MainActivity`.

The **Application** class (`DeposplitApp`) is instantiated before any Activity and lives for the lifetime of the process. It is the right place for app-wide singletons (e.g., the auth adapter).

### Jetpack Compose

Deposplit uses **Jetpack Compose** for all UI — there are no XML layout files. Compose is a declarative UI toolkit: you write `@Composable` functions that describe what the screen should look like given the current state, and Compose re-runs ("recomposes") them when state changes. If you have used Kotlin's standard library extensively, you will recognise the lambda-heavy style immediately.

Key primitives:
- `@Composable fun MyScreen() { ... }` — a UI function
- `remember { }` / `rememberSaveable { }` — survive recomposition (and screen rotation, for `rememberSaveable`)
- `LaunchedEffect(key) { ... }` — launches a coroutine scoped to the composable's lifetime; re-launched when `key` changes
- `Scaffold` — provides the standard Material 3 chrome (top bar, FAB slot, padding)

### ViewModel

A `ViewModel` survives Android configuration changes (e.g., screen rotation), unlike an Activity or composable. It holds UI state and business logic, and is obtained via `viewModel()` inside a composable. ViewModels use `viewModelScope` for coroutines — the scope is cancelled automatically when the ViewModel is cleared (i.e., when the user navigates away permanently).

### StateFlow and effects channels

UI state in Deposplit follows a standard pattern:

```kotlin
// In ViewModel:
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()

// In Composable:
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

One-shot events (navigate, open browser) that should not be replayed on recomposition are sent via a `Channel` and exposed as a `Flow`:

```kotlin
private val _effects = Channel<Effect>(Channel.BUFFERED)
val effects = _effects.receiveAsFlow()
```

The composable collects effects inside a `LaunchedEffect(Unit)` block.

### Navigation Compose

Navigation between screens is handled by `NavHost` in `MainActivity`. Each screen is registered as a `composable("route_name") { ... }` block. Navigation is triggered by calling `navController.navigate("route_name")`, typically in response to an effect from a ViewModel.

### Context

`Context` is Android's handle to the OS. It is required for reading files, accessing shared preferences, starting activities, and much more. Inside a composable, `LocalContext.current` provides it. In `DeposplitApp` and adapters, the Application itself is a `Context`.

### Manifest and permissions

`AndroidManifest.xml` declares:
- The `Application` subclass (`android:name=".DeposplitApp"`)
- Each `Activity`, its launch mode, and any intent filters
- Permissions the app requires (e.g., `INTERNET`)

Deposplit's manifest currently has only the launcher intent filter. Deep-link intent filters will be added when the app needs to handle incoming URIs (e.g., share links for key exchange).

---

## Project structure

The project has two Gradle modules, enforcing the Ports & Adapters boundary at the build level:

| Module | Role | Plugins |
|---|---|---|
| `:hexagon` | Pure Kotlin/JVM — domain model, value types, ports, framework-free tests. No Android or infrastructure imports. | `org.jetbrains.kotlin.jvm` |
| `:app` | Android application — adapters (HTTP, Android Keystore, local JSON) + UI (Compose + navigation). Depends on `:hexagon`. | AGP (registers `kotlin` itself) + `kotlin.plugin.compose` + `kotlin.plugin.serialization` |

`:hexagon` must not depend on `:app`, AGP, or any Android library. This mirrors the sbt `hexagon` subproject / root Play app split in `deposplit.com`.

```
Android/
├── hexagon/
│   ├── src/
│   │   ├── main/kotlin/com/deposplit/
│   │   │   ├── shamir/
│   │   │   │   └── Shamir.kt            SSS library (split / combine)
│   │   │   ├── driving_ports/
│   │   │   │   ├── Identity.kt          Port: isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign
│   │   │   │   ├── ContactManagement.kt Port: listContacts, addManually, addFromQr, deleteContact
│   │   │   │   └── ShareManagement.kt   Port: deposit, syncDistributed, listDistributed, listSentRequests, requestAll,
│   │   │   │                            openRequest, reconstruct, syncInbox, listHeld, listPendingRequests,
│   │   │   │                            respond, deleteHeldShare, deleteAllHeldFromSender
│   │   │   ├── driven_ports/
│   │   │   │   ├── IdentityStore.kt     Credential store interface (save/load keys + pseudonym)
│   │   │   │   ├── ContactRepository.kt Contact persistence interface
│   │   │   │   ├── ShareRepository.kt   Local share storage interface
│   │   │   │   ├── ShareMetadataRepository.kt  Local store interface for distributed ShareMetadata
│   │   │   │   └── ShareRelay.kt        Raw relay API interface (openShareRequest, listShareRequests, getShareRequest, respondToShareRequest, deleteShareRequest, deleteShareRequests)
│   │   │   ├── services/
│   │   │   │   ├── IdentityService.kt   Implements Identity, ShareEncryption — BouncyCastle keypair generation,
│   │   │   │   │                        Ed25519 signing, X25519+HKDF+ChaCha20-Poly1305 encrypt/decrypt; delegates persistence to IdentityStore
│   │   │   │   ├── ContactService.kt    Implements ContactManagement — key-size validation, VerificationLevel, UUID/timestamp
│   │   │   │   ├── ShareEncryption.kt   Intra-hexagon interface: encrypt(plaintext, recipientXPublicKey),
│   │   │   │   │                        decrypt(noncePlusCiphertext, recipientXPublicKey) — consumed by ShareService
│   │   │   │   └── ShareService.kt      Implements ShareManagement — SSS split/combine + ShareEncryption.encrypt/decrypt + relay + ShareMetadataRepository;
│   │   │   │                            deposit() writes to local store; listDistributed() reads from local store; syncDistributed() syncs field updates from relay (upserts, never deletes)
│   │   │   └── value_objects/
│   │   │       ├── Contact.kt           Contact + VerificationLevel
│   │   │       ├── HeldShare.kt         HeldShare (ciphertext + metadata, held by the recipient)
│   │   │       └── Share.kt             Role, ShareRequestType, ShareRequestState, ShareMetadata, ShareRequest
│   │   └── test/kotlin/com/deposplit/shamir/
│   │       └── ShamirTest.kt            SSS unit tests
│   └── build.gradle.kts
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/deposplit/
│   │   │   │   ├── DeposplitApp.kt          Application subclass; wires adapters into ContactService + ShareService; exposes contactManagement + shareManagement
│   │   │   │   ├── MainActivity.kt          Single FragmentActivity; NavHost root
│   │   │   │   ├── auth/
│   │   │   │   │   └── AndroidIdentityStore.kt  Android Keystore AES-256-GCM wrapping of private keys; public keys + pseudonym in SharedPreferences
│   │   │   │   ├── api/
│   │   │   │   │   └── DeposplitApiAdapter.kt   HTTP adapter — implements ShareRelay; all /share-requests operations + request signing
│   │   │   │   ├── contacts/
│   │   │   │   │   └── LocalContactRepository.kt JSON file in filesDir
│   │   │   │   ├── shares/
│   │   │   │   │   ├── LocalShareRepository.kt         JSON file in filesDir (shares.json); ciphertext + metadata for held shares
│   │   │   │   │   └── LocalShareMetadataRepository.kt JSON file in filesDir (distributed_shares.json); local store of distributed ShareMetadata
│   │   │   │   └── ui/
│   │   │   │       ├── signin/       SignInViewModel + SignInScreen
│   │   │   │       ├── home/         HomeViewModel + HomeScreen (My Shared Secrets / Their Secret Shares / Requests tabs)
│   │   │   │       ├── contacts/     Contacts{ViewModel,Screen}, AddContact{ViewModel,Screen}
│   │   │   │       ├── deposit/      Deposit{ViewModel,Screen} — contactManagement.listContacts + shareManagement.deposit
│   │   │   │       ├── requests/     RequestsViewModel + RecipientRequestsTab
│   │   │   │       ├── sharedetail/  ShareDetail{ViewModel,Screen} — open requests + reconstruct
│   │   │   │       ├── qr/           QrPayload, QrDisplay{ViewModel,Screen}, QrScan{ViewModel,Screen}
│   │   │   │       ├── biometric/    BiometricGate — availability probe + suspend authenticate(...)
│   │   │   │       └── theme/        Material 3 colour/type/theme
│   │   │   ├── res/                         App icons, string resources
│   │   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml                   Dependency version catalog
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md                                Claude Code guidance (Android-specific)
└── README.md                                This file
```

### Dependency catalog (`gradle/libs.versions.toml`)

Gradle version catalogs centralise dependency coordinates and versions. Instead of writing `"androidx.core:core-ktx:1.17.0"` in a build file, you write `libs.androidx.core.ktx` — the catalog entry resolves the coordinates. All versions are in one place, making upgrades straightforward.

---

## Architecture

Deposplit follows **Ports & Adapters (Hexagonal Architecture)** for the domain and infrastructure layers. The UI layer uses standard Android MVVM.

```
┌──────────────────────────────────────────────────────┐
│  UI Layer (Compose)                                  │
│  SignInScreen ──► SignInViewModel                    │
└─────────────────────────┬────────────────────────────┘
                          │ calls port interface
┌─────────────────────────▼────────────────────────────┐
│  Domain (Port)                                       │
│  Identity  ◄──── IdentityService (Service)           │
└──────────────────────────────────────────────────────┘
```

**Port (`Identity`)** — a Kotlin interface defined by the domain. It expresses what the app needs ("register with a pseudonym") without knowing anything about keypair generation or key storage.

**Service (`IdentityService`)** — implements the port using BouncyCastle keypair generation, Ed25519 signing, and X25519+HKDF+ChaCha20-Poly1305 encryption. Delegates key persistence to the `IdentityStore` driven port.

**Adapter (`AndroidIdentityStore`)** — implements `IdentityStore` using Android Keystore AES-256-GCM wrapping for private keys. Changing the storage strategy only requires changing this class.

**ViewModel (`SignInViewModel`)** — sits at the UI/domain boundary. It calls the port, holds `UiState`, and emits one-shot `Effect`s (navigate). It does not know anything about Compose.

**Application (`DeposplitApp`)** — creates the adapter and exposes it to ViewModels.

The domain hexagon lives in its own pure Kotlin module (`:hexagon`) that `:app` depends on. Gradle enforces the boundary: `:hexagon` has no Android or infrastructure dependencies and cannot accidentally import adapter code.

---

## The registration flow

Deposplit does not use OIDC, passwords, or email. Registration is keypair-first.

```
1. User enters a pseudonym (display name only — no personal information required)
        │
2. App generates an Ed25519 keypair (API auth) and an X25519 keypair (share encryption)
        │  → Both private keys stored in Android Keystore (never leave the device)
        │  → Pseudonym stored in SharedPreferences (local only, never sent to the Web app/service)
        │
3. App calls Identity.register(pseudonym)
        │  → IdentityService persists the "is registered" flag via AndroidIdentityStore
        │  → No server call — the keypair IS the identity; no registration endpoint exists
        │
4. ViewModel emits Effect.NavigateToHome
        │  → NavController pops sign-in, pushes home
```

Identity *is* the keypair pair. If Alice loses her device, she generates new keypairs on a new device and initiates a k-of-n social recovery request that her existing contacts approve.

---

## Building and running

### Prerequisites

- **Android Studio** (latest stable) — required for the Android emulator and device deployment
- **JDK 21+** on `JAVA_HOME` (the Gradle wrapper handles the rest)
- An Android device or AVD (Android Virtual Device) running API 29+

### Common commands

```bash
# from Android/

# Build a debug APK
./gradlew assembleDebug

# Run JVM unit tests (no device needed)
./gradlew test

# Run only the hexagon (domain) tests
./gradlew :hexagon:test

# Run a single test class
./gradlew :hexagon:test --tests "com.deposplit.shamir.ShamirTest"

# Run instrumented tests (requires a connected device or running emulator)
./gradlew connectedAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install app/build/outputs/apk/debug/app-debug.apk` or use Android Studio's Run button.

### First run

On first launch the app shows the sign-in screen. Enter a pseudonym (display name only — stored locally, never sent to the Web app/service). Tapping **Register** generates Ed25519 and X25519 keypairs via BouncyCastle, stores the private keys in the Android Keystore, and navigates to the home screen.

### Continuous Integration

A GitHub Actions workflow (`.github/workflows/test.yml`) runs `./gradlew test` on every push and on pull requests targeting `main`. Dependabot (`.github/dependabot.yml`) keeps GitHub Actions and Gradle dependencies (including the `gradle/libs.versions.toml` version catalog) current on a weekly schedule.

---

## Testing against a local Web app/service

### Setup

**Start the Web app/service** (from `deposplit.com/`):

```bash
sbt run -Dconfig.file=conf/localhost.conf
```

It listens on port 9000.

**Run on an emulator** — the emulator reaches your host machine via the special alias `10.0.2.2`, which is the default debug `BASE_URL`. Open `Android/` in Android Studio, create an AVD (API 29+), then **Run ▶**. No extra configuration needed.

**Run on a physical device** — the `10.0.2.2` alias is emulator-only. Add your machine's LAN IP to `local.properties` (already gitignored):

```
BASE_URL=http://192.168.x.x:9000
```

`local.properties` is read at Gradle sync time; rebuild the app after editing it. Remove or comment out the line to revert to the emulator default. Android Studio regenerates `local.properties` when you change the SDK path, but it only rewrites the `sdk.dir` line — custom properties like `BASE_URL` survive.

### Three-AVD setup

You need **three AVD instances** (or three physical devices on the same WiFi, using your machine's LAN IP instead of `10.0.2.2`) to exercise the full social flow with a 2-of-2 threshold split across two holders. Create two additional AVDs in the Device Manager and launch them alongside the first.

> The **My Shared Secrets** tab groups shares by `secretId`: a deposit to Bob and Carol shows as a single expandable card. Tap it to see delivery status and retrieve-request state per holder, and use **Request Retrieval** to open requests for all holders at once.

### Flow 1 — Happy path (2-of-2 threshold, 2 holders)

| Step | Device | What to do |
|---|---|---|
| 1 | AVD-A | Launch → register as "Alice" |
| 2 | AVD-B | Launch → register as "Bob" |
| 3 | AVD-C | Launch → register as "Carol" |
| 4 | AVD-A | TopAppBar QR icon → screenshot Alice's QR code |
| 5 | AVD-B | Contacts → **Add contact** → paste Alice's keys manually (or scan the screenshot if camera emulation supports it); then TopAppBar QR icon → screenshot Bob's QR |
| 6 | AVD-C | Contacts → **Add contact** → paste Alice's keys; then TopAppBar QR icon → screenshot Carol's QR |
| 7 | AVD-A | Add Bob as a contact; add Carol as a contact |
| 8 | AVD-A | FAB (＋) → enter a label (e.g. "test secret"), a secret text, select Bob and Carol, choose threshold 2-of-2 → **Deposit** |
| 9 | AVD-A | **My Shared Secrets** tab → one grouped card for the secret; expand it to see Bob and Carol as holders |
| 10 | AVD-B | **Their Secret Shares** tab → Bob's inbox shows Alice's PickUp request → app automatically approves it, stores ciphertext locally, relay clears ciphertext |
| 11 | AVD-C | **Their Secret Shares** tab → Carol's inbox shows Alice's PickUp request → app approves the same way |
| 12 | AVD-A | Expand the card → tap **Request Retrieval** (opens Retrieve requests for Bob and Carol at once) |
| 13 | AVD-B | **Requests** tab → a Retrieve request from Alice appears → app reads ciphertext from local storage → tap **Approve** (ciphertext sent in response body) |
| 14 | AVD-C | **Requests** tab → a Retrieve request from Alice appears → tap **Approve** |
| 15 | AVD-A | Expand the card → both holders show "Approved" → tap **Reconstruct** in `ShareDetailScreen` → biometric prompt → secret appears |

The threshold logic (`Shamir.combine`) is already fully tested in the hexagon unit tests; the manual test above validates the full end-to-end path including encryption and transport.

### Flow 2 — Deny and re-request

After step 12 above: Bob taps **Deny** → on Alice's side the Retrieve section shows "Denied" and a **Retry** button → Alice re-requests → Bob approves.

### Flow 3 — Sender-initiated deletion

Alice taps **Request Deletion** on a share (via `ShareDetailScreen`) → Bob's Requests tab shows a Delete request → Bob approves → Bob's PickUp row is deleted (cascade-deleting any related Retrieve/Delete rows) → the share disappears from Bob's **Their Secret Shares** tab.

### Flow 4 — Recipient-initiated deletion

On AVD-B, Bob taps the delete icon on Alice's share in the **Their Secret Shares** tab → confirmation dialog → **Delete**. The share disappears locally. If Bob has multiple shares from Alice, the dialog also offers **Delete all shares from Alice**. Verify what Alice's **My Shared Secrets** tab shows on refresh.

### Flow 5 — Offline / error states

Kill the Web app/service → open or refresh the app:
- **My Shared Secrets** and **Their Secret Shares** tabs render from device storage; a small warning banner ("Relay not reachable") replaces the blocking error.
- **Requests** tab queries the relay for pending events, which aren't stored locally, and will show an error.

Restart the Web app/service → navigate away and back (or re-open the app) → warning clears, data refreshes from relay.

### Flow 6 — Locale

On the emulator: **Settings → General management → Language** → add German, make it primary → relaunch Deposplit → all strings should appear in German and dates in `dd.MM.yyyy` format.

### Key edge cases to verify

- Re-registering (clear app data, launch again) generates fresh keypairs — existing contacts cannot decrypt new shares with the old keys.
- The **Reconstruct** button is hidden until ≥ 2 approved retrieve shares exist for the same `secretId`.
- The biometric prompt on API 30+ offers "or use PIN"; on API 29 it shows biometric only (the combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator is not supported on API 29).
- 2-of-3 threshold: splitting across three contacts and having only 2 approve should still reconstruct the secret successfully.
- Contacts added by manual key entry default to `VerificationLevel.VERY_LOW` and can be raised to `LOW`/`HIGH` via the level picker (`VERY_HIGH` is not offered — it requires physical co-presence); contacts added by QR scan default to `VERY_HIGH` (shown with a colored level label; no label at `VERY_LOW`).

---

## What is next

The Android app is feature-complete for v0.1. Next Android-specific work depends on cross-platform priorities — see `deposplit.com/CLAUDE.md` for the current roadmap.
