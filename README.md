# Deposplit — Android

Kotlin Android app for [Deposplit](https://github.com/Deposplit/deposplit.com): a secret-sharing app built on Shamir's Secret Sharing (SSS) and Matrix. Secrets are split into *n* shares and distributed to contacts via Matrix; reconstruction requires at least *k* holders to cooperate.

This document is written for a developer who knows Kotlin well but has limited Android experience.

---

## Table of contents

1. [Android concepts you need](#android-concepts-you-need)
2. [Project structure](#project-structure)
3. [Architecture](#architecture)
4. [The sign-in flow](#the-sign-in-flow)
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

Deposplit's manifest registers an **Android App Link** intent filter (`android:autoVerify="true"`, `https://` scheme) — the URI the OIDC browser flow redirects to after login. The current URI is `https://www.squeng.com/deposplit/auth/callback` (a temporary stand-in); the production URI will be `https://deposplit.com/auth/callback`.

---

## Project structure

```
Android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/deposplit/
│   │   │   │   ├── DeposplitApp.kt          Application subclass
│   │   │   │   ├── MainActivity.kt          Single activity; NavHost root
│   │   │   │   ├── auth/
│   │   │   │   │   ├── AuthPort.kt              Domain port interface
│   │   │   │   │   ├── MatrixAuthAdapter.kt     OBSOLETE — being replaced
│   │   │   │   │   ├── DeposplitAuthAdapter.kt  deposplit.com API + libsodium keypair
│   │   │   │   │   └── SignInViewModel.kt       Registration UI logic
│   │   │   │   ├── shamir/
│   │   │   │   │   └── Shamir.kt            SSS library (split / combine)
│   │   │   │   └── ui/
│   │   │   │       ├── signin/
│   │   │   │       │   └── SignInScreen.kt  Sign-in composable
│   │   │   │       ├── home/
│   │   │   │       │   └── HomeScreen.kt    Placeholder post-login screen
│   │   │   │       └── theme/               Material 3 colour/type/theme
│   │   │   ├── res/                         App icons, string resources
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── kotlin/com/deposplit/shamir/
│   │           └── ShamirTest.kt            SSS unit tests
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
┌─────────────────────────────────────────────────┐
│  UI Layer (Compose)                             │
│  SignInScreen ──► SignInViewModel               │
└──────────────────────┬──────────────────────────┘
                       │ calls port interface
┌──────────────────────▼──────────────────────────┐
│  Domain (Port)                                  │
│  AuthPort  ◄──── MatrixAuthAdapter (Adapter)    │
└──────────────────────────────────────────────────┘
```

**Port (`AuthPort`)** — a Kotlin interface defined by the domain. It expresses what the app needs ("discover the login flow for this homeserver", "complete an OIDC login") without knowing anything about Matrix SDKs.

**Adapter (`MatrixAuthAdapter`)** — implements the port using a specific technology (here, `matrix-rust-sdk`). Swapping the Matrix SDK only requires changing this class.

**ViewModel (`SignInViewModel`)** — sits at the UI/domain boundary. It calls the port, holds `UiState`, and emits one-shot `Effect`s (open browser, navigate). It does not know anything about Compose.

**Application (`DeposplitApp`)** — creates the adapter and holds a `SharedFlow` that relays OIDC callbacks from `MainActivity` to whichever ViewModel is currently listening.

> **Current simplification:** everything lives in a single `:app` Gradle module. The architecture calls for the hexagon to move to a separate pure Kotlin module (`:hexagon`) once there is enough logic to justify it.

---

## The registration flow

Deposplit does not use OIDC, passwords, or email. Registration is keypair-first.

```
1. User enters a pseudonym (display name only — no personal information required)
        │
2. App generates an X25519 keypair via libsodium
        │  → Private key stored in Android Keystore (never leaves the device)
        │  → Public key held in memory for registration
        │
3. App calls AuthPort.register(pseudonym)
        │  → DeposplitAuthAdapter sends pseudonym + public key to deposplit.com
        │  → Backend stores the pseudonym/public key mapping
        │
4. Adapter persists the "is registered" flag via SharedPreferences
        │
5. ViewModel emits Effect.NavigateToHome
        │  → NavController pops sign-in, pushes home
```

Identity *is* the keypair. If Alice loses her device, she generates a new keypair on a new device and initiates a k-of-n social recovery request that her existing contacts approve.

> **Note:** `MatrixAuthAdapter.kt` (the previous OIDC-based adapter) is present in the codebase but obsolete. It will be deleted once `DeposplitAuthAdapter` is complete. Do not extend or fix it.

---

## Building and running

### Prerequisites

- **Android Studio** (latest stable) — required for the Android emulator and device deployment
- **JDK 17+** on `JAVA_HOME` (the Gradle wrapper handles the rest)
- An Android device or AVD (Android Virtual Device) running API 29+

### Emulator vs real device

The emulator is sufficient for developing and testing the sign-in flow, with one requirement: use an AVD that includes **Google Play** (look for the Play Store icon next to the device name in AVD Manager). Google Play AVDs ship with Chrome, which is required for Chrome Custom Tabs. Plain AOSP images (no Play Store icon) don't have Chrome pre-installed and the Custom Tab won't open.

With a Google Play AVD:
- The emulator routes internet traffic through your host machine's network, so it can reach matrix.org and other homeservers normally
- Chrome Custom Tabs open the homeserver's OIDC login page as expected
- The App Link redirect (`https://www.squeng.com/deposplit/auth/callback`) is routed back to the app once App Links verification completes (the AVD must be able to reach squeng.com at install time)

The only cosmetic difference: Chrome may prompt you to sign in to a Google account the first time it opens — you can skip that.

### Common commands

```bash
# from Android/

# Build a debug APK
./gradlew assembleDebug

# Run JVM unit tests (no device needed)
./gradlew test

# Run a single test class
./gradlew test --tests "com.deposplit.shamir.ShamirTest"

# Run instrumented tests (requires a connected device or running emulator)
./gradlew connectedAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install app/build/outputs/apk/debug/app-debug.apk` or use Android Studio's Run button.

### First run

On first launch the app shows the sign-in screen. Enter `matrix.org` (or your own homeserver URL, or your full Matrix ID like `@alice:matrix.org`). Tapping **Continue** opens a Chrome Custom Tab to the homeserver's login page. After logging in, the browser redirects back to the app and you land on the (currently placeholder) home screen.

---

## What is next

In rough priority order:

1. **Replace auth layer** — implement `DeposplitAuthAdapter` (libsodium keypair generation + pseudonym registration against deposplit.com API); remove `matrix-rust-sdk` dependency
2. **Home screen** — list of secrets the user has distributed; list of shares held for others
3. **Backend protocol message types** — implement the four messages (deposit, list, retrieve, delete)
4. **Shamir integration** — wire `Shamir.split()` / `Shamir.combine()` into the secret distribution flow
5. **Contact management** — add/list contacts backed by the deposplit.com contacts API
6. **Hexagon module extraction** — split `:app` into a pure Kotlin `:hexagon` module and an `:app` module that depends on it
7. **Biometric unlock** — gate secret reconstruction behind `BiometricPrompt`
