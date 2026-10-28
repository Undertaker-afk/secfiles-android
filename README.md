# SecFiles for Android

A VeraCrypt-style encrypted-container app for Android. You create a container file, unlock it with a
password (plus optional keyfiles and/or biometrics), and while it is unlocked it shows up as a **drive in
the system Files app and in every file picker**, like an SD card or a network share.

> Status: v0.2. The crypto core, compression and filesystem are covered by 55 JVM tests (including randomized
> operations checked against a byte-array model on every compression engine, and cross-checks against
> reference implementations). The Android layer compiles, passes lint and builds a release APK, but **has not
> been run on a device or emulator yet** (the build sandbox has no KVM). That includes the biometric prompt, the
> eject overlay and how third-party file managers display the drive. Treat it as a beta and don't put your only
> copy of anything in it.

## What it does

| | |
|---|---|
| **Cipher cascade, 1 to 5 layers** | You choose only the depth. The ciphers and their order are drawn from a pool of eight (AES, Twofish, Serpent, Camellia, Kuznyechik, ARIA, CAST-256, RC6, all 256-bit keys, XTS mode, independent keys per layer) by a hash of the password-derived key material and the container's random salt. The result is sealed in the header and never shown. |
| **Compression, before encryption** | Optional. One of five engines (LZ4, Deflate, LZMA, BZip2, Snappy), picked the same hidden way and applied per 64 KiB chunk, so compressible data shrinks and incompressible data costs nothing extra. |
| **Key derivation** | Argon2id v1.3 (memory-hard). Presets: Standard 128 MiB, Strong 256 MiB, Maximum 512 MiB; the app only offers what the device's heap can hold. |
| **Header** | Master keys sealed with ChaCha20-Poly1305 under a fixed five-cipher XTS cascade, signed with Falcon-512. The signature is checked *before* the slow KDF, so damage/tampering is told apart from a wrong password and a damaged primary header falls back to the backup at the end of the file. Changing the password rewrites only the headers. |
| **Keyfiles** | Any number of files; each is SHA-512'd and mixed into the secret (order-independent). |
| **Biometrics ("remember me")** | After a password unlock you can remember the credential for 3 days. It is wrapped by an AES-GCM Android Keystore key (StrongBox if present) that needs a fresh strong biometric match per use, and held in RAM only, never on disk. A restart/reboot, a biometric change, or 3 days since the last password entry means the password is required again. |
| **Drive** | A `DocumentsProvider` exposes each unlocked volume as a storage root (with search). Other apps read and write through it; data is decrypted on the fly and never written to disk in the clear. |
| **Eject overlay** | Optional grey X on the right edge of the screen, drawn over other apps while a volume is unlocked (needs the "display over other apps" permission). One tap flushes, zeroes keys and plaintext caches, and removes the drive. |
| **Auto-lock** | Inactivity timeout, lock on screen-off, optional lock on leaving the app, "Lock now" in the notification. Process death also locks. |
| **Hardening** | No `INTERNET` permission. `FLAG_SECURE` (no screenshots / recents thumbnail) by default. Backups disabled. Dark theme only. |

## Design notes you should know

- **The hidden cascade structure is obscurity, not extra strength.** It costs nothing and makes a container
  unique, but the design assumes an attacker knows every algorithm. Security comes from Argon2id and from the
  ciphers themselves. The structure is stored in the header rather than recomputed from the password each
  time, because otherwise changing the password would make all data undecryptable.
- **ChaCha20-Poly1305 protects the header only.** It is deliberately not a disk-sector layer: a stream cipher
  keyed per sector would reuse its keystream every time a sector is rewritten, leaking plaintext XORs. Sector
  layers are all XTS.
- **Falcon covers the header, not the data.** The data area has no per-block authentication (XTS is
  malleable), so a modified ciphertext block decrypts to garbage rather than being rejected.
- **Compression and encryption.** Compressing first is the right order for size, but compressed sizes are
  visible as block usage: someone with the container can see roughly how compressible each file is. If that
  matters to you, turn compression off.

## Container format

```
[ header 4096 ][ data area: N × 4096-byte XTS data units ][ backup header 4096 ]
```
Header layout (v2) is documented in `core/.../Volume.kt`. Inside the data area is `SecFs`, a small ext2-style filesystem (inodes, bitmap, direct / indirect /
double-indirect blocks, directories as files). Free space is filled with random bytes at creation. The
full header layout is documented in `core/.../Volume.kt`.

## Project layout

- `core/` – pure Kotlin/JVM: `Xts`, `Cascade`, `Structure`, `Kdf`, `Integrity` (Falcon), `Volume`, `SecFs`, and the
  codecs (`Lz4` and `Snappy` are in-tree and bounds-checked; Deflate is the JDK's; LZMA and BZip2 are pure-Java
  libraries). All unit-tested (`./gradlew :core:test`). AES-XTS is checked against an independent reference
  vector (Python `cryptography`/OpenSSL); LZ4 and Snappy are cross-checked in both directions against the
  reference implementations.
- `app/` – Android: Compose UI, `VaultDocumentsProvider`, `MountManager` + foreground `MountService`,
  `BiometricVault`, `EjectOverlay`, `FdRandomAccess` (positional I/O on SAF / SD-card files).

## Build

```
./gradlew :core:test          # crypto + filesystem tests
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```
Needs JDK 17+ and the Android SDK (platform 35). CI is in `.github/workflows/build.yml`.

## Honest limitations

- **Not VeraCrypt-compatible.** It borrows the design (cascade, XTS, memory-hard KDF, backup header) but
  uses its own format, so containers can't be opened by VeraCrypt or vice versa.
- **No hidden volumes / plausible deniability.** The header has a cleartext magic, KDF parameters and a public key.
- **"Drive" means a Storage Access Framework root, not a kernel mount.** It appears in the system Files app and
  in file pickers and works with SAF-aware apps. Whether a *particular* file manager (including Google Files)
  lists third-party roots in its own browser is up to that app and is not something this app can force; apps
  that use raw paths (`/sdcard/...`) can't see it at all. A true mount point needs root.
- **Speed.** Everything runs in Java. On a desktop JVM a 3-layer cascade does ~23 MB/s and each extra layer
  slows it further; expect a good deal less on a phone. LZMA and BZip2 are much slower than LZ4/Snappy, and
  the engine is not your choice. Argon2id at *Maximum* takes ~5 s on a desktop and longer on a phone.
- **No journal.** Allocation is ordered so a crash can leak space but not cross-link files; there is no fsck.
  Compressed files hold the last chunk in memory until `flush`/lock/eject, so an abrupt kill can lose the most
  recent writes to that file. Keep backups of the container file.
- **Anything you open or share leaves the volume.** The receiving app gets a decrypted copy. A rooted or
  compromised phone can read keys from RAM while a volume is unlocked. Typed passwords briefly exist as
  immutable `String`s inside the Compose text field.
- **The eject overlay is a convenience, not a security boundary.** It draws over other apps with the
  permission you grant; a hostile app with the same permission could draw over or cover it.
- **No password recovery.** Lose the password and keyfiles and the data is unrecoverable.
- Containers must live on storage that supports seeking (device storage, SD card). Some cloud-backed SAF
  providers don't, and the app will say so.
- Fixed-size containers only (no dynamic growth), files up to ~4 GiB, ~1 inode per 32 KiB of volume.
- Not independently audited. The crypto is composed from BouncyCastle/JCE primitives, but the composition
  is new code.
