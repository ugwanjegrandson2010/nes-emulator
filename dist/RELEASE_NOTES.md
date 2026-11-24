# MilliEmu 1.0 — first signed APK

The NES/Famicom core plus the Android host, packaged as a signed, installable APK. No build
tools needed: download `MilliEmu-1.0.apk`, copy it to the phone, tap it, and allow
"install unknown apps" for the app you tapped from.

- **Package:** `emu.android` · **Android 5.0+ (API 21)** · no permissions, no network
- **Size:** 43,890 bytes · **SHA-256:** `59876e07088d33d06384419805747308bea63012513ca67510bd9bb64f21ba5d`
- **Signed:** v1 + v2 schemes, self-signed key `CN=MilliEmu` (the key is committed in `keystore/`)

## What works

- Complete 6502 CPU (official + undocumented opcodes, exact cycle counts)
- PPU: background, sprites, scrolling, NMI, sprite-0 hit and overflow
- APU: 2× pulse, triangle, noise (no DMC)
- Mappers 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), 4 (MMC3 + IRQ), 7 (AxROM)
- iNES and NES 2.0 ROM loading, one save-state slot per game, reset
- On-screen gamepad in landscape, 60 Hz pacing, AudioTrack output
- ROM library with the system file picker, no storage permission

## Verified

29/29 checks pass in the headless suite: Klaus Dormann's 6502 functional test, `nestest`
(8991/8991 instructions with exact cycles), blargg `instr_test-v5` (16/16), PPU
background/scroll/sprite-0 tests, mapper banking, APU output, and a byte-identical save-state
round trip. The APK itself builds and is signature-verified, but was not launched on physical
hardware in this environment (no device or KVM available).

## Known limitations

- NES only; other platforms need their own core.
- PPU is scanline-accurate, not dot-accurate.
- Mappers 5, 9, 10, 69, 71 etc. are unsupported and fall back to NROM (they will not boot).
- No DMC channel, no rewind, no netplay, no turbo.

## Install note

The release APK is signed with a different key than a locally built debug APK. If you installed
`app-debug.apk` before, uninstall it first (`adb uninstall emu.android`), otherwise Android
reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
