# MilliEmu 1.2 — now three systems: NES + Game Boy + Nintendo DS

The hub gained its third core. MilliEmu runs `.nds` images through a from-scratch dual-core ARM
emulator: a real ARM9 *and* ARM7 running side by side, with the DS memory map, both 2D engines and
the inter-processor FIFOs. As with the previous two cores, the UI needed no new code — the
library and the picker just learned one more extension.

- **Package:** `emu.android` · **Android 5.0+ (API 21)** · no permissions, no network
- **Size:** 80,264 bytes
- **SHA-256:** `7df2569daa4d76e3acfd55b89c1598c68a8e6de0e709c61240fe7b43fab58052`
- **Signed:** v1 + v2 schemes, self-signed key `CN=MilliEmu` (committed in `keystore/`)

## New in 1.2 — Nintendo DS core

| Area | Status |
| --- | --- |
| ARM9 (ARMv5TE) + ARM7 (ARMv4T), ARM *and* Thumb, condition codes, banked registers | Complete |
| Exception entry/return, IRQ via the BIOS vector to the user handler at `0x03FFFFFC` | Complete |
| 4 MiB main RAM, shared/ARM7 WRAM, ITCM/DTCM, BIOS stubs, VRAM windows, palette, OAM | Complete |
| IPC sync register + both 16-word FIFOs with the status bits homebrew polls | Complete |
| DMA (4 channels per core), timers (4 per core), keypad | Complete |
| 2D main + sub engines: text and affine backgrounds, OAM sprites, 15-bit bitmap mode 5 | Complete |
| BIOS calls: Div, CpuSet/CpuFastSet, BitUnPack, LZ77, Run-Length, Halt/VBlankIntrWait | Complete |
| Save states | Complete, single slot per game |
| 3D engine · DS sound · KEY1-encrypted retail ROMs | Not implemented |

The two screens are rendered stacked, so the picture keeps the DS's real 256×384 layout and the
app letterboxes it to the physical screen.

### What is *not* there (unchanged honesty)

- **No 3D engine and no sound.** The 3D rasteriser and the 16-channel audio block are not
  modelled; `readAudio` returns silence instead of noise.
- **Unencrypted images only.** Retail cartridges encrypt the start of the ARM9 program with
  Nintendo's KEY1 cipher, whose Blowfish tables are not in this repository. Those images are
  detected and refused with a clear message instead of hanging. Homebrew `.nds` files load.
- **No BIOS firmware**: the pieces homebrew actually calls are emulated in place.
- **Verified against one public test ROM.** Rockwrestler loads, runs its ARM9/ARM7 init and its
  CPU suite for hundreds of millions of instructions, and idles in its per-frame loop; it does
  not yet complete that ROM's full suite, so no DS game is claimed as fully playable. No
  commercial DS game can be run at all without KEY1 decryption.
- **Speed:** ~55–65 fps in the desktop JVM harness, so a mid-range phone sits near full speed.

## Verification

74 checks pass (up from 38), including 26 new DS checks that assemble ARM and Thumb programs and
boot complete synthetic DS ROM images through the real cartridge loader, memory map and both
picture engines. NES and Game Boy results are unchanged: Klaus Dormann's 6502 test, nestest
(8991 instructions), blargg's instr_test-v5, cpu_instrs, instr_timing, halt_bug, and
dmg-acid2 (23040/23040 pixels identical).

As before, the APK is built and signature-verified but was **not launched on a phone** in the
build environment (no KVM, no USB device); everything below the UI is exercised by the suite.

## Install

Copy `MilliEmu-1.2.apk` to the phone, tap it, allow "install unknown apps" for the file manager,
open it, then **Open ROM…** and pick a `.nes`, `.gb`, `.gbc` or `.nds` file. It installs over 1.0
and 1.1 as an update: same signing key, higher version code.
