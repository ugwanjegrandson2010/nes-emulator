# MilliEmu 1.1 — now two systems: NES + Game Boy

The hub gained its second core. MilliEmu is no longer a NES-only emulator: it now runs Game Boy
(DMG) ROMs through a from-scratch LR35902 core, and the UI picks the core from the file
extension. No build tools needed — download, tap, play.

- **Package:** `emu.android` · **Android 5.0+ (API 21)** · no permissions, no network
- **Size:** 58,897 bytes
- **SHA-256:** `3bb84a636803e5537162058ba6f75f69d962e8adbf66a9a86676d2397703e586`
- **Signed:** v1 + v2 schemes, self-signed key `CN=MilliEmu` (committed in `keystore/`)

## New in 1.1 — Game Boy core

| Area | Status |
| --- | --- |
| LR35902 CPU, all opcodes incl. CB prefix | Complete, hardware cycle counts, EI delay, HALT bug |
| PPU: background, window, 8×8/8×16 sprites, 10-per-line, DMG priority | Complete |
| APU: 2× pulse (with sweep), wave, noise, envelopes, frame sequencer | Complete |
| Timer/DIV, joypad, serial, VBlank/STAT/LYC interrupts | Complete |
| Cartridges | ROM-only, MBC1, MBC2, MBC3, MBC5 |
| Save states | Full machine state, one slot per game |

Supported files: `.nes`, `.gb`, and `.gbc` when the game is DMG-compatible. Game Boy Color
exclusive games are rejected with a clear message instead of booting to garbage.

The video is now letterboxed per core (4:3 for the NES, 10:9 for the Game Boy), and the on-screen
gamepad is unchanged — the same eight buttons map to the Game Boy's D-pad, A, B, Start, Select.

## Verification

`38 passed, 0 failed` in the headless suite (29 NES + 9 Game Boy):

- Game Boy **cpu_instrs**: all 11 sub-tests pass.
- Game Boy **instr_timing**: instruction cycle counts match hardware.
- Game Boy **halt_bug**: the HALT bug behaves like real hardware.
- Game Boy **dmg-acid2**: Matt Currie's PPU conformance test, **23040/23040 pixels identical**
  to the official reference image.
- NES: Klaus Dormann's 6502 functional test, `nestest` (8991/8991 with exact cycles), blargg
  `instr_test-v5` (16/16), PPU/mapper/APU checks and a byte-identical save-state round trip.

## Known limitations

- Game Boy is monochrome DMG only: no CGB colour palettes, no double-speed mode, no boot ROM
  animation (registers start post-boot, as games expect).
- blargg's `mem_timing` still fails its 3 sub-tests: instruction totals are exact, but the
  individual bus accesses inside an instruction are not split across their exact T-cycles.
- No link cable, no RTC, no battery-backed `.sav` files (cartridge RAM is kept in save states).
- NES: no DMC channel, scanline-accurate PPU, mappers 0/1/2/3/4/7 only.

## Install note

This APK is signed with the same key as 1.0, so it installs straight over it as an update.
