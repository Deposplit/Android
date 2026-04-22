# Deposplit — Android

Kotlin Android app for [Deposplit](https://github.com/Deposplit/deposplit.com): a secret-sharing app built on Shamir's Secret Sharing (SSS). Secrets are split into *n* shares and distributed to contacts via the deposplit.com backend; reconstruction requires at least *k* holders to cooperate.

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
│   │   │   ├── auth/
│   │   │   │   └── AuthPort.kt          Port: isRegistered, register, pseudonym, keys, sign, encrypt, decrypt
│   │   │   ├── api/
│   │   │   │   └── ShareTransport.kt    Port + value types (Role, ShareRequest{Type,State}, ShareMetadata, ShareRequest)
│   │   │   └── contacts/
│   │   │       └── Contact.kt           Contact + VerificationLevel + ContactRepository port
│   │   └── test/kotlin/com/deposplit/shamir/
│   │       └── ShamirTest.kt            SSS unit tests
│   └── build.gradle.kts
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/deposplit/
│   │   │   │   ├── DeposplitApp.kt          Application subclass
│   │   │   │   ├── MainActivity.kt          Single FragmentActivity; NavHost root
│   │   │   │   ├── auth/
│   │   │   │   │   ├── DeposplitAuthAdapter.kt  libsodium keypair generation + Android Keystore
│   │   │   │   │   └── SignInViewModel.kt       Registration UI logic
│   │   │   │   ├── api/
│   │   │   │   │   └── DeposplitApiAdapter.kt   HTTP adapter — all 7 API operations + request signing
│   │   │   │   ├── contacts/
│   │   │   │   │   └── LocalContactRepository.kt JSON file in filesDir
│   │   │   │   └── ui/
│   │   │   │       ├── signin/       SignInScreen
│   │   │   │       ├── home/         HomeViewModel + HomeScreen (Distributed / Held / Requests tabs)
│   │   │   │       ├── contacts/     Contacts{ViewModel,Screen}, AddContact{ViewModel,Screen}
│   │   │   │       ├── deposit/      Deposit{ViewModel,Screen} — split + encrypt + depositShare
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
│  AuthPort  ◄──── DeposplitAuthAdapter (Adapter)      │
└──────────────────────────────────────────────────────┘
```

**Port (`AuthPort`)** — a Kotlin interface defined by the domain. It expresses what the app needs ("register with a pseudonym") without knowing anything about keypair generation or key storage.

**Adapter (`DeposplitAuthAdapter`)** — implements the port using libsodium keypair generation and the Android Keystore. Changing the storage strategy only requires changing this class.

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
        │  → Pseudonym stored in SharedPreferences (local only, never sent to the backend)
        │
3. App calls AuthPort.register(pseudonym)
        │  → DeposplitAuthAdapter persists the "is registered" flag
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

### Emulator vs real device

Any AVD running API 29+ is sufficient. The registration flow is purely local (keypair generation + SharedPreferences) — no browser, no network call, no Google Play requirement.

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

On first launch the app shows the sign-in screen. Enter a pseudonym (display name only — stored locally, never sent to the backend). Tapping **Register** generates Ed25519 and X25519 keypairs, stores the private keys in the Android Keystore, and navigates to the (currently placeholder) home screen.

---

## Testing against a local backend

### Setup

**Start the backend** (from `deposplit.com/`):

```bash
sbt run -Dconfig.file=conf/localhost.conf
```

It listens on port 9000. The emulator reaches your host machine via the special alias `10.0.2.2`, which is what the debug build uses automatically (`BuildConfig.DEBUG` switches the base URL; the production URL `https://api.deposplit.com/v1` is unaffected).

**Run the app** in Android Studio: open `Android/`, create an AVD (API 29+), then **Run ▶**. The debug variant is wired up automatically — no manual configuration needed.

### Two-AVD setup

You need **two AVD instances** (or two physical devices on the same WiFi, using your machine's LAN IP instead of `10.0.2.2`) to exercise the full social flow. Create a second AVD in the Device Manager and launch it alongside the first.

### Flow 1 — Happy path (2-of-2 threshold)

| Step | Device | What to do |
|---|---|---|
| 1 | AVD-A | Launch → register as "Alice" |
| 2 | AVD-B | Launch → register as "Bob" |
| 3 | AVD-A | TopAppBar QR icon → screenshot the QR code |
| 4 | AVD-B | Contacts → scan QR (or use **Add contact** and paste Alice's keys manually if camera emulation cannot scan the screenshot) |
| 5 | AVD-B | TopAppBar QR icon → screenshot Bob's QR |
| 6 | AVD-A | Add Bob as a contact |
| 7 | AVD-A | FAB (＋) → enter a label (e.g. "test secret"), a secret text, select Bob, choose threshold 2-of-2 (add a second contact for a true 2-of-2 split) |
| 8 | AVD-B | **Requests** tab → a Retrieve request from Alice appears |
| 9 | AVD-B | Tap **Approve** |
| 10 | AVD-A | **Distributed** tab → tap the share → tap **Request Retrieval** → after Bob approves, tap **Reconstruct** → biometric prompt → secret appears |

For a proper 2-of-3 split you need a third AVD. The threshold logic (`Shamir.combine`) is already fully tested in the hexagon unit tests; the manual test above validates the full end-to-end path including encryption and transport.

### Flow 2 — Deny and re-request

After step 8 above: Bob taps **Deny** → on Alice's side the Retrieve section shows "Denied" and a **Retry** button → Alice re-requests → Bob approves.

### Flow 3 — Sender-initiated deletion

Alice taps **Request Deletion** on a share → Bob's Requests tab shows a Delete request → Bob approves → the share disappears from Bob's Held tab.

### Flow 4 — Recipient-initiated deletion

On AVD-B, Bob swipes/deletes Alice's share from his Held tab without any request. The share disappears locally; verify what Alice's Distributed tab shows on refresh.

### Flow 5 — Error states

Kill the backend → refresh on either device → error banner + **Retry** button appears. Restart the backend → Retry recovers successfully.

### Flow 6 — Locale

On the emulator: **Settings → General management → Language** → add German, make it primary → relaunch Deposplit → all strings should appear in German and dates in `dd.MM.yyyy` format.

### Key edge cases to verify

- Re-registering (clear app data, launch again) generates fresh keypairs — existing contacts cannot decrypt new shares with the old keys.
- The **Reconstruct** button is hidden until ≥ 2 approved retrieve shares exist for the same `secretId`.
- The biometric prompt on API 30+ offers "or use PIN"; on API 29 it shows biometric only (the combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator is not supported on API 29).
- 2-of-3 threshold: splitting across three contacts and having only 2 approve should still reconstruct the secret successfully.

---

## What is next

The Android app is feature-complete for v0.1. Next Android-specific work depends on cross-platform priorities — see `deposplit.com/CLAUDE.md` for the current roadmap.
