# SecFiles for Android

A VeraCrypt-style encrypted-container app for Android. You create a container file, unlock it with a
password (plus optional keyfiles and/or biometrics), and while it is unlocked it shows up as a **drive in
the system Files app and in every file picker**, like an SD card or a network share.

> Status: v0.1. The crypto core and filesystem are covered by 28 JVM tests. The Android layer compiles,
> passes lint and builds a release APK, but **has not been run on a device or emulator yet**. Treat it as a
> beta and don't put your only copy of anything in it.

## What it does

| | |
|---|---|
| **Cipher cascade** | AES-256 → Twofish-256 → Serpent-256, each in XTS mode with its own independent data and tweak key (6 keys, 192 random bytes). Breaking one or two ciphers still leaves the third. |
| **Key derivation** | Argon2id v1.3 (memory-hard). Presets: Standard 128 MiB, Strong 256 MiB, Maximum 512 MiB; the app only offers what the device's heap can hold. |
| **Header** | Random master keys are wrapped under the password-derived keys and authenticated with HMAC-SHA-512. A backup header is stored at the end of the file. Changing the password rewrites only the headers. |
| **Keyfiles** | Any number of files; each is SHA-512'd and mixed into the secret (order-independent). |
| **Biometrics** | Optional. The volume secret is wrapped with an AES-256-GCM Android Keystore key (StrongBox if present) that needs a fresh *strong* biometric match for every use and is invalidated by Android if biometrics change. The password always remains the master key. |
| **Drive** | A `DocumentsProvider` exposes each unlocked volume as a storage root. Other apps read and write through it; data is decrypted on the fly and never written to disk in the clear. |
| **Auto-lock** | Inactivity timeout, lock on screen-off, optional lock on leaving the app, "Lock now" in the notification. Keys are zeroed on lock; process death also locks. |
| **Hardening** | No `INTERNET` permission. `FLAG_SECURE` (no screenshots / recents thumbnail) by default. Backups disabled. |

## Container format

```
[ header 4096 ][ data area: N × 4096-byte XTS data units ][ backup header 4096 ]
```
Inside the data area is `SecFs`, a small ext2-style filesystem (inodes, bitmap, direct / indirect /
double-indirect blocks, directories as files). Free space is filled with random bytes at creation. The
full header layout is documented in `core/.../Volume.kt`.

## Project layout

- `core/` – pure Kotlin/JVM: `Xts`, `Cascade`, `Kdf`, `Volume`, `SecFs`. All unit-tested (`./gradlew :core:test`).
  AES-XTS is checked against an independent reference vector (Python `cryptography`/OpenSSL).
- `app/` – Android: Compose UI, `VaultDocumentsProvider`, `MountManager` + foreground `MountService`,
  `BiometricVault`, `FdRandomAccess` (positional I/O on SAF / SD-card files).

## Build

```
./gradlew :core:test          # crypto + filesystem tests
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```
Needs JDK 17+ and the Android SDK (platform 35). CI is in `.github/workflows/build.yml`.

## Honest limitations

- **Not VeraCrypt-compatible.** It borrows the design (cascade, XTS, memory-hard KDF, backup header) but
  uses its own format, so containers can't be opened by VeraCrypt or vice versa.
- **No hidden volumes / plausible deniability.** The header has a cleartext magic and KDF parameters.
- **"Drive" means a Storage Access Framework root, not a kernel mount.** It appears in Files and file
  pickers and works with any SAF-aware app. Apps that use raw paths (`/sdcard/...`) can't see it; a true
  mount point needs root.
- **Speed.** Serpent and Twofish run in Java. On a desktop JVM the cascade does ~23 MB/s; expect a good
  deal less on a phone. Argon2id at *Maximum* takes ~5 s on a desktop and longer on a phone.
- **No journal.** Allocation is ordered so a crash can leak space but not cross-link files; there is no
  fsck. Keep backups of the container file.
- **Anything you open or share leaves the volume.** The receiving app gets a decrypted copy. A rooted or
  compromised phone can read keys from RAM while a volume is unlocked. Typed passwords briefly exist as
  immutable `String`s inside the Compose text field.
- **No password recovery.** Lose the password and keyfiles and the data is unrecoverable.
- Containers must live on storage that supports seeking (device storage, SD card). Some cloud-backed SAF
  providers don't, and the app will say so.
- Fixed-size containers only (no dynamic growth), files up to ~4 GiB, ~1 inode per 32 KiB of volume.
- Not independently audited. The crypto is composed from BouncyCastle/JCE primitives, but the composition
  is new code.
