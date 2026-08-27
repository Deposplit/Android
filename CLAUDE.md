# CLAUDE.md — Android

Guidance for Claude Code working in this repository. Platform-specific only; the shared
design is documented in the hub repository.

## Context, in ten lines

Deposplit splits a secret into *n* shares using Shamir's Secret Sharing, gives each to a
contact, and reconstructs it from any *k*. Fewer than *k* shares reveal nothing.

Identity is a keypair, not an account: an Ed25519 pair for authenticating to the relay and
an X25519 pair for encryption, both generated at first launch. Contacts exchange public keys
out of band — a QR code in person — never through a server. The relay stores and forwards
opaque ciphertext and cannot decrypt anything or learn who anybody is. Holders decrypt their
share at pickup and keep the **plaintext** locally, re-encrypting fresh to the requester's
*current* key at retrieval; that is what lets social recovery work after key loss. Retrieval
needs the holder's consent, which is the real protection — not the keypair.

Read before changing anything non-trivial:
[architecture](https://github.com/Deposplit/deposplit.com/blob/main/docs/architecture.md) ·
[protocol](https://github.com/Deposplit/deposplit.com/blob/main/docs/protocol.md) ·
[security](https://github.com/Deposplit/deposplit.com/blob/main/docs/security.md) ·
[trust model](https://github.com/Deposplit/deposplit.com/blob/main/docs/trust-model.md).
Open work is tracked in the hub's
[TODO.md](https://github.com/Deposplit/deposplit.com/blob/main/TODO.md).

## Toolchain traps

**Do not apply `org.jetbrains.kotlin.android`.** AGP 9.x registers the `kotlin` Gradle
extension itself; applying the Kotlin Android plugin alongside it fails with "extension
already registered". The project deliberately omits it. Two consequences:

- `kotlinOptions { }` is unavailable. Set the JVM target through
  `compileOptions { sourceCompatibility / targetCompatibility }` only — AGP propagates it
  to the Kotlin compiler.
- `kotlin.plugin.compose` **is** still required for Compose support, and does not conflict.

**Do not use `jvmToolchain(N)`.** It requires an installed JDK of exactly version N. The
host JDK here is 25 while the target is JVM 21 bytecode, so `jvmToolchain(21)` fails. Use
`compileOptions` / `java { sourceCompatibility / targetCompatibility }`.

**Plugin versions live only in the root `build.gradle.kts`**, declared `apply false` so both
modules share one resolved version. Never declare a plugin version inside
`app/build.gradle.kts` or `hexagon/build.gradle.kts`.

**`gradle.properties` sets `org.gradle.java.installations.auto-detect=false`. Do not remove
it.** Gradle otherwise scans well-known locations for JDKs, including VS Code extension
directories, and VS Code's `redhat.java` extension ships a trimmed Java 21 image with no
`jlink`. Since the project targets JVM 21 bytecode, Gradle would pick that image for AGP's
`JdkImageTransform`, which shells out to `jlink`, and `:app:compileDebugJavaWithJavac` fails
locally while CI stays green. With auto-detection off, Gradle uses only the JVM `gradlew`
launched — the one on `JAVA_HOME`. Safe because the build uses `compileOptions` rather than
`jvmToolchain(N)`, so no toolchain resolution is needed.

A running daemon caches the old setting, so after changing anything in this area run
`./gradlew --stop` once before believing the result.

Versions are pinned in `gradle/libs.versions.toml`: AGP 9.3.2, Kotlin 2.4.0, BouncyCastle
1.85.2, Compose BOM 2026.06.01, biometric 1.1.0. Gradle wrapper 9.6.1. compileSdk 37,
targetSdk 36, minSdk 29.

## Module boundary

| Module | What | Plugins |
|---|---|---|
| `:hexagon` | The domain. Pure Kotlin/JVM. | `kotlin-jvm` only |
| `:app` | Adapters and UI. | AGP + Compose |

`:hexagon` must not depend on `:app`, on AGP, or on any Android library. Its only dependency
is BouncyCastle. An accidental Android import is a compile error, which is the whole point.

New domain logic goes in `hexagon/src/{main,test}/kotlin/com/deposplit/…`; adapters and
anything UI go in `app/src/main/kotlin/com/deposplit/…`. Adapters may depend on `:hexagon`
ports and on Android libraries, and must **never** depend on UI code.

Packages use `snake_case` (`driving_ports`, `driven_ports`, `driving_adapters`,
`value_objects`) to mirror the Scala relay hexagon, so the two read the same way.

## Why minSdk is 29

Do not lower it. Each level buys something the app relies on:

| API | Feature | Why it matters |
|---|---|---|
| 28 | `BiometricPrompt` (native) | Gates secret reconstruction |
| 28 | StrongBox Keymaster | Keys in a dedicated security chip, not just the TEE |
| 28 | Cleartext traffic off by default | No accidental plaintext to the relay |
| 29 | Scoped Storage | Needed for file-based secret input |
| 29 | TLS 1.3 by default | Baseline transport security |

`BiometricPrompt` and StrongBox still need **runtime** capability checks regardless of
`minSdk`: `BiometricManager.canAuthenticate()`, and `setIsStrongBoxBacked(true)` can throw
`StrongBoxUnavailableException` on hardware that lacks it.

The prompt also behaves differently by level: API 30+ offers "or use PIN", while API 29 is
biometric-only, because the combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator is
not supported there.

## Platform decisions worth preserving

- **No native crypto libraries.** All crypto is `org.bouncycastle:bcprov-jdk18on` — no
  lazysodium, no JNA, no `.so` files beyond what AndroidX already packages. BouncyCastle is
  pure JVM, so it works on every emulator ABI with no extra configuration.
- **Private keys live in the Android Keystore**, wrapped with AES-256-GCM under the
  `deposplit_master` alias, and never leave the device as raw key material.
- **Session state is plain `SharedPreferences`** — just an "is registered" flag. Do **not**
  add `EncryptedSharedPreferences`; `security-crypto` is deliberately not a dependency, and
  adding it needs a concrete reason, because the sensitive material is already in Keystore.
- **Held shares live in `filesDir/shares.json`**, keyed by share id, with the share itself as
  standard base64. The sender is referenced by `contactId` — a stable local UUID — and never
  by key, so a record survives the sender rotating their keys.
- **Ciphertext wire format is `suiteTag(1) || nonce(12) || ciphertext+tag`.** The leading
  suite tag is easy to forget when hand-checking bytes.
- **Catalog backup uses the Storage Access Framework** — `CreateDocument` to export,
  `OpenDocument` to import.
- **All UI is Jetpack Compose.** No XML layouts.

> **`CatalogCodec.kt` is a trap, now guarded.** It hand-writes the catalog's wire DTO rather
> than serialising the domain type, so **every new field on `Contact`, `Secret` or
> `ShareMetadata` must be added there by hand** or it is silently dropped on export/import.
> Kotlin's `copy()` protects the rest of the codebase from this class of bug; this file is the
> exception. Six fields had already been lost this way before `CatalogCodecTest` was written.
>
> That test now derives its expectation from the domain types by reflection, so a forgotten
> field fails by name. Note why it cannot simply compare whole objects: `Contact.equals`
> compares `id` alone, so a naive round-trip assertion passes even when every other field has
> been dropped.

## Navigation

`NavHost` routes: `sign_in`, `home`, `contacts`, `add_contact`,
`relink_contact/{contactId}`, `deposit`, `share_detail`, `repair/{secretId}`, `qr_display`,
`qr_scan`, `settings`.

## Dev conveniences

**`SKIP_BIOMETRIC`** — emulators often have no enrolled biometric, which blocks the
reconstruct flow. Set `SKIP_BIOMETRIC=true` in `local.properties`; the reconstruct button
then appears unconditionally and bypasses `BiometricGate`. **Release always enforces
biometric regardless.** Gradle reads `local.properties` at sync time, so rebuild after
editing.

**Local relay** — not a compile-time constant. `RelayDefaults.FALLBACK_BASE_URL` is a fixed
fallback; the actual default is a runtime setting (`SharedPreferencesRelaySettings`, stored
in the same `"deposplit"` preferences file as the identity flag). Point a debug build at a
local relay from the in-app **Settings** screen: `http://10.0.2.2:9000` on an emulator
(cleartext to that host is allowed by `app/src/debug/res/xml/network_security_config.xml`),
or your LAN IP on a physical device.

Note for whoever implements the freemium gate: putting the Settings relay editor behind
`isPremium()` removes the only way to point a dev build at a local relay, so it needs its
own debug-only fake-Premium `PurchaseRepository` in the same shape as `SKIP_BIOMETRIC`.

## Build and test

```bash
./gradlew test                    # JVM unit tests (115 in :hexagon, 20 in :app) — what CI runs
./gradlew :hexagon:test           # domain only
./gradlew :hexagon:test --tests "com.deposplit.shamir.ShamirTest"
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest    # needs a device or emulator
```

`:hexagon` tests use `kotlin.test` on a JUnit 4 backend. `:app` tests use **plain JUnit 4**
instead — `kotlin("test")` is unavailable there, because `:app` deliberately does not apply
the Kotlin plugin (see the AGP 9 note above).

`:app` tests cover the two files that are pure Kotlin and hand-write a wire format:
`CatalogCodec` and `QrPayload`. Everything else in `:app` needs Context, Compose or a device
and is covered by the manual end-to-end flows instead. Deploying to a device needs Android
Studio or `adb install`.

Manual end-to-end flows are in the hub's
[testing.md](https://github.com/Deposplit/deposplit.com/blob/main/docs/testing.md).

## House style

- Match the surrounding code. Value objects are `data class`es; ports are interfaces named
  for the capability, not the implementation.
- Strings are localised in `values/strings.xml` and `values-de/strings.xml`. Keep both in
  sync — an untranslated string is a bug, not a TODO.
- Line endings are CRLF; `core.autocrlf` handles conversion, so do not hand-convert.
- Do not reference work items by number in comments. Say what the code does and why.
- Changes to shared concepts — ports, value objects, canonical byte constructions — should
  land on iOS and the relay too. `PayloadCanonical` in particular is **append-only**, and its
  cross-platform vector tests must be updated in lockstep.
