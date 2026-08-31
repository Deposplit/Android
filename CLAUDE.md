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

**Do not add `gradle/gradle-daemon-jvm.properties`.** The Android Studio scaffold generated
one pinning `toolchainVersion=21`, and it was deleted. It constrains the JVM the *daemon
process* runs on, which has nothing to do with the JVM target — `compileOptions` sets that.
`JAVA_HOME` is JDK 25 on both development machines, so the pin could never be satisfied
directly and Gradle went hunting for some other Java 21 instead. Two things followed:

- **`jlink` disappeared.** VS Code's `redhat.java` extension bundles a jlink'd Java 21 image
  that ships `javac` but no `jlink`, and its Build Server for Gradle launches daemons on it
  because `java.import.gradle.java.home` is unset by default. With a version-only pin, daemon
  reuse is judged on "is this a Java 21 daemon", not on `JAVA_HOME` — so `./gradlew` happily
  adopted VS Code's daemon, and AGP's `JdkImageTransform` shelled out to a `jlink` that was
  not there. `:app:compileDebugJavaWithJavac` failed locally while CI stayed green.
- **CI downloaded a JDK every run.** `setup-java` installs Temurin 25, which cannot satisfy a
  21 pin either, so Gradle auto-provisioned one from `api.foojay.io` on every build.

If a `jlink`, `JdkImageTransform` or "daemon JVM" failure ever appears again, find out which
JVM actually served the build before theorising — the daemon records it:

```bash
grep -m1 "start() called on daemon" ~/.gradle/daemon/*/daemon-*.out.log \
  | sed 's/.*javaHome=/javaHome=/'          # PowerShell: Select-String, same log path
```

It must name `JAVA_HOME`. If it names a `.vscode/extensions` path, VS Code launched that
daemon: set `java.import.gradle.java.home` to a full JDK in VS Code's user settings
(`~/Library/Application Support/Code/User/settings.json`, or `%APPDATA%\Code\User\settings.json`
on Windows, where the path needs doubled backslashes: `"C:\\Users\\..."`). Leave
`java.jdt.ls.java.home` alone — the bundled JRE is meant to run the language server, and that
was never the problem.

`gradle.properties` keeps `org.gradle.java.installations.auto-detect=false`. With no pin and
no `jvmToolchain(N)` nothing requests toolchain resolution, so it is inert today — but it is a
cheap guard if anyone adds one later.

**Android Studio offers to create the file for you.** Its "Daemon toolchain" notification
sells it on detecting an installed toolchain, downloading a compatible one when there is
none, and aligning the JVM the IDE and the CLI use. Decline it. The download is the CI
regression above, and the alignment is criteria-based daemon matching — the very mechanism
that let a `jlink`-less Java 21 image serve this build.

The alignment is available without it: set the IDE's **Gradle JDK** to the `JAVA_HOME` entry
(Settings → Build, Execution, Deployment → Build Tools → Gradle). Studio records that as
`gradleJvm` in `.idea/gradle.xml`, or as `java.home` in `.gradle/config.properties` where it
has migrated the project to `#GRADLE_LOCAL_JAVA_HOME`. Both are gitignored, so this is a
per-machine setting, not a repository one. "Multiple Gradle daemons might be spawned" is the
warning that says it is unset: the IDE otherwise builds on its bundled JetBrains Runtime,
which is a second daemon beside the terminal's.

The **project** JDK is a third setting again. `.idea/misc.xml`'s `jbr-25` runs the IDE's own
indexing and language level, not the build, so a complaint about it says nothing about which
JVM Gradle uses.

A running daemon caches all of this, so after changing anything in this area run
`./gradlew --stop` (`.\gradlew.bat --stop`) once before believing the result.

Versions are pinned in `gradle/libs.versions.toml`: AGP 9.3.2, Kotlin 2.4.10, BouncyCastle
1.85.2, Compose BOM 2026.08.00, biometric 1.1.0. Gradle wrapper 9.7.1. compileSdk 37,
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
  `deposplit_master` alias, and never leave the device as raw key material. `previous_dec_key`
  holds the key-agreement key displaced by the last rotation, kept one generation under the same
  wrapping so a share sealed before the rotation can still be opened at pickup.
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
./gradlew test                    # JVM unit tests (124 in :hexagon, 20 in :app) — what CI runs
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
