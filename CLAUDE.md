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

The project currently has a **single `:app` module**. The `deposplit.com/CLAUDE.md` architecture calls for extracting the hexagon into a pure Kotlin Gradle module (`:hexagon`); that extraction is deferred until there is enough domain logic to justify the overhead. Do not introduce that split prematurely.

All packages currently live under `com.deposplit` inside `app/src/main/kotlin/`.

## Package layout

```
com.deposplit/
├── DeposplitApp.kt              Application subclass; owns authAdapter + shareTransport + contactRepository
├── MainActivity.kt              Single activity; NavHost root (sign_in / home / contacts / add_contact)
├── auth/
│   ├── AuthPort.kt              Domain port interface (isRegistered, register, pseudonym, edPublicKey, xPublicKey, sign)
│   ├── DeposplitAuthAdapter.kt  Infrastructure adapter: libsodium keypair generation, Android Keystore AES-GCM wrapping
│   └── SignInViewModel.kt       UI logic for the registration flow
├── api/
│   ├── ShareTransport.kt        Port interface + domain model (ShareMetadata, ShareRequest, enums)
│   └── DeposplitApiAdapter.kt   HTTP adapter: HttpURLConnection, Ed25519 request signing, JSON via kotlinx.serialization
├── contacts/
│   ├── Contact.kt               Domain model (Contact, VerificationLevel) + ContactRepository port interface
│   └── LocalContactRepository.kt  JSON file in filesDir; @Synchronized; kotlinx.serialization wire types
└── ui/
    ├── signin/
    │   └── SignInScreen.kt      Compose screen — pseudonym input + Register button
    ├── home/
    │   ├── HomeViewModel.kt     Loads distributed + held shares via ShareTransport
    │   └── HomeScreen.kt        Two-tab screen (Distributed / Held) with contacts icon in TopAppBar
    ├── contacts/
    │   ├── ContactsViewModel.kt Load + delete contacts via ContactRepository
    │   ├── ContactsScreen.kt    List with FAB (add) and delete per item; loading/empty/error states
    │   ├── AddContactViewModel.kt Pseudonym + two base64url key fields, validation, save
    │   └── AddContactScreen.kt  Manual-entry form for adding a contact
    ├── deposit/
    │   ├── DepositViewModel.kt  Label/secret/contact-selection/threshold; calls Shamir.split + auth.encrypt + transport.depositShare
    │   └── DepositScreen.kt     Label + secret fields, contact checkboxes, threshold stepper, Split & Share button
    ├── requests/
    │   ├── RequestsViewModel.kt Loads pending RECIPIENT requests + contacts; handles approve/deny via respondToShareRequest
    │   └── RecipientRequestsTab.kt Per-request card with type badge, sender name, Deny/Approve buttons
    ├── sharedetail/
    │   ├── ShareDetailViewModel.kt Loads share + all SENDER requests; opens RETRIEVE/DELETE requests; reconstructs secret via Shamir.combine + auth.decrypt
    │   └── ShareDetailScreen.kt    Recipient info, request state per type, Reconstruct button + secret display
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
- Private keys are wrapped by an AES-256-GCM master key in the Android Keystore (`deposplit_master` alias); only the ciphertext and IV are stored in `SharedPreferences`.
