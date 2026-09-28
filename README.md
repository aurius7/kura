<div align="center">

# Kura

**An offline encrypted media vault for Android.**

No network permission. No ads. No tracking. No accounts.

</div>

---

Kura is a local-first vault for photos, videos, and GIFs. Media is encrypted
at rest with AES-256-GCM using Android Keystore-managed keys, and the app
declares no `INTERNET` permission, so it cannot open a network connection even
if it wanted to.

## Features

**Encryption and hardening**

- **AES-256-GCM at rest** — authenticated encryption with keys held in the
  Android Keystore, never in app storage. Hardware backing (TEE or StrongBox)
  depends on what the device provides; it is not guaranteed everywhere.
- **Credential-encrypted sandbox** — app files are stored with `0600`
  permissions inside a CE-protected sandbox.
- **Buffer zeroization** — application-owned plaintext byte buffers and
  passphrases are overwritten with zeros after use. This is best-effort: once
  data reaches `Bitmap`, `MediaPlayer`, `MediaMetadataRetriever` or Skia,
  copies outside the app's control may exist and are not zeroed.
- **Best-effort file shredding** — deleted and temporary files are overwritten
  with a single pass of zeros before being unlinked. Flash storage wears
  blocks out and remaps them, so this is *not* a guarantee of irrecoverability
  on eMMC/UFS. Treat it as defence in depth, not erasure.
- **Brute-force lockout** — a mandatory 24-hour lockout after 3 consecutive
  failed PIN attempts. Biometric unlock is unavailable while a lockout is
  active; it does reset the failure counter when it succeeds.
- **Intruder attempt log** — every failed PIN entry is timestamped and retained
  (last 50), with an unread counter on the lock screen and a clear-history
  action.
- **Flip-to-panic lock** — the accelerometer locks the vault the moment the
  screen is turned face down.
- **Auto-lock timeout** — configurable delay after leaving the app, from
  immediate to never.
- **Biometric unlock** — optional, via `androidx.biometric`.
- **Screen privacy** — optional `FLAG_SECURE` to block screenshots and
  app-switcher previews.

**Coercion defense**

- **Decoy vault** — a second, independent PIN opens a completely separate empty
  vault. The real vault's database handle, file paths, and decrypted buffers are
  dropped on every lock and on every decoy unlock, so a decoy session cannot
  read or modify real media even in memory. Implemented in `VaultLock.kt` and
  `Prefs.kt`.
- **Keypad scrambling** — the PIN keypad randomizes its digit layout on every
  entry to defeat shoulder surfing and smudge attacks.
- **Independent lockout** — unlocking with the decoy PIN does not reset the real
  vault's brute-force counter, so an attacker cannot clear it by switching PINs.

**Zero permissions**

The only permission Kura declares is `USE_BIOMETRIC`, and it is used solely to
unlock the vault. There is no `INTERNET` permission, no analytics, and no
third-party SDKs. You can verify this yourself:

```bash
aapt dump permissions kura.apk
```

**Organization**

- **Booru-style tagging** — category prefixes (`c:` character, `a:` artist,
  `s:` series, `m:` meta) with per-category colors. Prefixes are hidden in the
  UI but drive sorting, filtering, and autocomplete.
- **Smart search** — searching `miku` also matches `c:miku`. Exclude with
  `-tag`. Multiple tags are ANDed.
- **Categories** — separate flows for photos, videos, and GIFs, each
  independently hideable.
- **Interactive guide** — an in-app walkthrough of the tag system, plus a
  coachmark tour of the main and detail views.
- **Customizable launcher icon** — pick from a set of alternate icons.
- **Themes** — dark, AMOLED, and light, with accent colors and a monochrome
  mode.
- **Per-media settings** — autoplay and start-muted for videos, grid badges,
  and media detail visibility.

**Backup and portability**

- **Encrypted `.kura` archives** — password-protected (PBKDF2-HMAC-SHA256 +
  AES-256-GCM) with tag manifests, written anywhere via the system file picker.
- **Auto-detect restore** — recognizes `.kura`, legacy `.kuro` / `.vbooru`, and
  plaintext `.zip` archives.
- **Folder export / import** — bulk-move media between the vault and any folder.
- **Delete original on import** — optionally remove the unencrypted source file
  once the encrypted copy is verified.
- **Skip duplicates** — hash-based deduplication during import.
- **Broken media cleaner** — scan the vault and permanently remove zero-byte or
  missing records left by interrupted operations.

## Threat model

Encryption at rest only helps in specific situations. These are the ones Kura
addresses, and the ones it does not.

**Covered**

- **Someone with the unlocked phone** — imported media is ciphertext on disk.
  The system gallery, media scanner, and file browsers see opaque files. App
  storage is additionally a credential-encrypted sandbox at mode 0600.
- **Device loss or theft** — the AES key is Keystore-managed and, where the
  device provides it, hardware-bound, so extracting files yields ciphertext.
  That binding is a property of the device, not of this app.
- **Casual snooping** — scrambled keypad, optional `FLAG_SECURE`, configurable
  auto-lock, 24-hour lockout after 3 failed attempts, and a timestamped intruder
  log.
- **Being asked to unlock the phone** — a second independent PIN opens a real,
  separate decoy vault. The real vault's database handle and decrypted buffers
  are dropped on every lock, and a decoy unlock does not clear the real vault's
  failure counter.
- **Accidental cloud backup** — vault directories, both databases, and the
  secure preferences are excluded from both cloud backup and device-to-device
  transfer.

**Not covered**

- **A compromised device.** Root or a kernel exploit can read process memory
  while the vault is open. No app-level encryption defends against that.
- **Screen capture.** `FLAG_SECURE` is optional and off by default, and it does
  nothing about a second camera pointed at the screen.
- **A weak PIN.** PBKDF2 at 120,000 iterations and a 24-hour lockout raise the
  cost of guessing through the UI. Neither makes a 4-digit PIN strong against
  an attacker holding the encrypted file. Use a longer PIN.
- **A decoy that is not maintained.** It only helps if it is set up and posted
  to regularly enough to look real.
- **Exported archives you protect weakly.** A `.kura` file is only as strong as
  the passphrase you chose for it, and Kura cannot recover that passphrase.
- **Unreviewed code.** This is a one-person project with no external audit. The
  cryptography is standard primitives used conventionally, but the surrounding
  code has not been reviewed by a second party.

## Known gaps

Honest list of things that are weaker than they could be.

- **Backup key derivation is PBKDF2-HMAC-SHA256 at 100,000 iterations.** Fine
  against casual attack, below current guidance for offline passphrase
  cracking, where a memory-hard KDF is the usual recommendation. New archives
  can move to Argon2id while old ones stay readable.
- **Restore rolls back, but per-entry failures are tolerated.** An archive is
  fully decrypted and authenticated before anything is committed, and a restore
  that is cancelled, cut short by a lock, or turns out to be truncated partway
  through is rolled back in full rather than leaving half a backup behind. A
  single entry that fails to import is still skipped and the rest of the
  archive continues, so a mostly-good archive restores mostly.
- **Video playback writes plaintext to the cache.** This is the largest place
  plaintext touches disk. `VideoView` can only open a file path, so playback
  decrypts the whole video into the credential-encrypted cache, plays it from
  there, and shreds it afterwards; the cache is also wiped on lock. Locking the
  vault mid-playback is therefore what clears it. A decrypting
  `MediaDataSource` fed to `MediaPlayer` would avoid the file, but that is not
  implemented. Video thumbnails and metadata probing are separate: those try
  `RamMediaDataSource` in memory first and only fall back to a temp file when
  the video is too large to hold in memory.
- **Crash traces are written to disk.** A sanitised stack trace is saved to
  `filesDir/crash.txt` so it survives the process death that caused it, and is
  deleted when the crash screen is closed.
- **Deleted media is overwritten, not provably erased.** See the shredding note
  above.

## Building

Kura targets `compileSdk 34`, `minSdk 26`, and builds with **JDK 17**, Kotlin
1.9.22, and the Gradle version pinned in the wrapper (**8.5**). AGP 8.3 will not
run on an older JDK.

The Gradle wrapper is checked in, so the build uses a known Gradle rather than
whatever is on your `PATH`. `gradle-wrapper.properties` also pins the
distribution's SHA-256, so a tampered or truncated download fails the build
instead of executing.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

### Release signing

`app/build.gradle.kts` reads signing credentials from the environment first and
only falls back to a local `keystore.properties` if those variables are absent:

| Variable | Required | Purpose |
| --- | --- | --- |
| `KURA_KEYSTORE_PATH` | yes | Path to the release keystore |
| `KURA_STORE_PASSWORD` | yes | Keystore password |
| `KURA_KEY_ALIAS` | yes | Key alias, e.g. `kura_rotated` |
| `KURA_KEY_PASSWORD` | yes | Key password |

All four are needed. If either password is missing the build fails at signing
time rather than producing an unsigned APK.

**Use an absolute path for `KURA_KEYSTORE_PATH`.** Relative paths are resolved
against the `app/` module directory, not the repository root.

`keystore.properties`, `*.keystore`, `*.jks`, and `*.apk` are all git-ignored,
so a signing key cannot be committed by accident.

### Verifying a build is genuinely signed

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Release APKs are signed with an RSA 4096-bit key, `CN=Aurius Kura`,
SHA-256 `ad9059713b4d8d998c025f231023f18875699c7ef34b767bba6e5e6f114a1f3f`.
Compare that digest before trusting a build you did not produce yourself.
Android pins that certificate on first install, so it has to stay the same for
the life of the app — a build printing a different digest is not an update.

Signing uses APK Signature Scheme **v2 and v3**. `enableV1Signing` is set in the
build script but is a no-op at `minSdk 26`, since JAR signing is only required
below API 24.

Each release also ships a `SHA256SUMS.txt`, so a download can be checked
against the file the maintainer published:

```bash
sha256sum -c SHA256SUMS.txt
```

That hash covers the file as published, not a rebuild of it. Gradle does not
produce bit-identical APKs across machines, so if you build from source your
APK will differ and only the signing certificate above will match.

## Tests

```bash
gradle testReleaseUnitTest
```

Covers tag normalization and search-variant generation, plus authenticated
encryption round-trips and wrong-password rejection.

## Architecture

Plain Android Views built in Kotlin — no Compose, no data-binding, no DI
framework. Storage is a raw `SQLiteOpenHelper` (`BooruDb`) for metadata and
`CryptoVault` for file encryption.

| Area | Files |
| --- | --- |
| Crypto | `CryptoVault.kt`, `BackupCrypto.kt` |
| Storage | `BooruDb.kt`, `Prefs.kt` |
| Lock | `LockActivity.kt`, `VaultLock.kt`, `BaseVaultActivity.kt` |
| UI | `MainActivity.kt`, `DetailActivity.kt`, `SettingsActivity.kt` |
| Tagging | `Tags.kt` |
| Theme | `ThemeUtils.kt` |

## License

MIT — see [LICENSE](LICENSE).
