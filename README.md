# Deposplit — Android

The Android app, plus the Kotlin port of Shamir's Secret Sharing.

Deposplit splits a secret into *n* shares, gives each to a person you choose, and
reconstructs it from any *k*. Fewer than *k* shares reveal nothing. All cryptography happens
on the device; the relay only stores and forwards bytes it cannot read.

**Design documentation lives in the hub repository** and covers all three platforms:

- [Architecture](https://github.com/Deposplit/deposplit.com/blob/main/docs/architecture.md)
- [Protocol](https://github.com/Deposplit/deposplit.com/blob/main/docs/protocol.md)
- [Security](https://github.com/Deposplit/deposplit.com/blob/main/docs/security.md)
- [Trust model](https://github.com/Deposplit/deposplit.com/blob/main/docs/trust-model.md)
- [Manual testing](https://github.com/Deposplit/deposplit.com/blob/main/docs/testing.md)

This README covers only what is specific to building and running the Android app.
Android-specific guidance for Claude Code is in [CLAUDE.md](CLAUDE.md).

## Requirements

- **JDK 25** (Temurin). The project targets JVM 21 bytecode but builds on 25.
- **Android Studio** for running on a device or emulator. Command-line builds work without
  it, but deployment does not.
- An AVD on **API 29 or later**, or a physical device.

Versions are pinned in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`:
AGP 9.3.2, Kotlin 2.4.10, Compose BOM 2026.08.00, BouncyCastle 1.85.2, Gradle 9.7.1,
compileSdk 37, targetSdk 36, minSdk 29.

## Build and test

```bash
./gradlew test                    # JVM unit tests — no device needed (124 :hexagon, 20 :app)
./gradlew :hexagon:test           # the domain only
./gradlew :hexagon:test --tests "com.deposplit.shamir.ShamirTest"
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest    # instrumented tests — needs a device or emulator
```

`./gradlew test` is what CI runs. Most coverage is in `:hexagon`; `:app` tests cover the two
files that are pure Kotlin and hand-write a wire format — `CatalogCodec` (catalog backup) and
`QrPayload` (contact exchange). The rest of `:app` needs Context, Compose or a device, and is
covered by the [manual end-to-end
flows](https://github.com/Deposplit/deposplit.com/blob/main/docs/testing.md) instead.

The Gradle daemon runs on whichever JDK `JAVA_HOME` names, because nothing pins it to a
version. Android Studio has to be told separately — its Gradle JDK defaults to the bundled
JetBrains Runtime, which builds in a second daemon of its own — so point it at the `JAVA_HOME`
entry under Settings → Build, Execution, Deployment → Build Tools → Gradle. Do not reintroduce
`gradle/gradle-daemon-jvm.properties`, whatever the IDE suggests: [CLAUDE.md](CLAUDE.md)
explains what pinning the daemon broke, and how to find out which JVM actually served a build
before theorising about one.

## Modules

| Module | What | Depends on |
|---|---|---|
| `:hexagon` | The domain — pure Kotlin/JVM, no Android APIs | BouncyCastle only |
| `:app` | Adapters and UI — AGP, Compose, Keystore, HTTP | `:hexagon` |

`:hexagon` must never depend on `:app`, on AGP, or on any Android library. It is a plain
Kotlin module precisely so that an accidental Android import fails to compile rather than
quietly eroding the boundary. New domain logic goes in `hexagon/src/main/kotlin/com/deposplit/…`;
adapters and anything UI go in `app/src/main/kotlin/com/deposplit/…`.

Packages use `snake_case` to mirror the Scala relay hexagon.

## Where things live

**`:hexagon`** — `driving_ports/` (`Identity`, `ContactManagement`, `ShareManagement`,
`CatalogManagement`), `driving_adapters/` (the services implementing them, plus
`ShareEncryption`), `driven_ports/` (ten interfaces the domain needs from the world:
`IdentityStore`, `ContactRepository`, `ShareRepository`, `ShareMetadataRepository`,
`SecretRepository`, `RetainedDepositRepository`, `KeyConflictRepository`, `ShareRelay`,
`ShareRelayResolver`, `RelaySettings`), `value_objects/`, and `shamir/Shamir.kt`.

**`:app`** — `api/` (relay client, resolver, defaults), `auth/` (Keystore-backed identity
store), `contacts/` and `shares/` (JSON-file repositories), `settings/`, and `ui/` split by
screen: `home`, `contacts`, `deposit`, `sharedetail`, `repair`, `requests`, `qr`,
`settings`, `signin`, `biometric`, `reconstruction`, `theme`.

Adapters may depend on `:hexagon` ports and on Android libraries. They must never depend on
UI code.

## Pointing at a local relay

The relay URL is **not** a build-time property. `RelayDefaults.FALLBACK_BASE_URL` supplies a
single fixed fallback (`https://api.deposplit.com`), and the app resolves its actual default
at runtime through `RelaySettings`, backed by `SharedPreferences`.

Start a relay from `deposplit.com/`:

```bash
sbt run -Dconfig.file=conf/localhost.conf
```

Then, in the app's **Settings** screen (gear icon on Home), set the default relay to:

- `http://10.0.2.2:9000` on an emulator — the alias the emulator uses to reach your host.
  Cleartext to that host is already permitted by
  `app/src/debug/res/xml/network_security_config.xml`.
- `http://<your-LAN-IP>:9000` on a physical device, on the same Wi-Fi. The `10.0.2.2` alias
  is emulator-only.

One-time per fresh install; the setting persists across restarts. No rebuild needed.

A contact may additionally carry its own `relayBaseUrl` override, which takes precedence for
that contact's traffic — see the architecture doc on Bring Your Own Relay.

## Skipping biometric during development

Emulators often have no enrolled biometric, which blocks the reconstruct flow. Add to
`local.properties`:

```
SKIP_BIOMETRIC=true
```

The reconstruct button then appears unconditionally and bypasses the biometric gate. **The
release build always enforces biometric regardless of this key.** Gradle reads
`local.properties` at sync time, so rebuild after editing.

## Localisation

English and German, in `app/src/main/res/values/strings.xml` and `values-de/`. Both must be
kept in sync.

## Continuous integration

`.github/workflows/test.yml` runs `./gradlew test` on every push and on pull requests
targeting `main`. No emulator or device is involved. Dependabot updates Gradle dependencies
and pinned action SHAs weekly.

## Licence

MIT. Copyright © 2026 [Squeng AG](https://www.squeng.com).
