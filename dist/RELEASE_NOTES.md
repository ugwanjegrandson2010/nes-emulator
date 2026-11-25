# MilliEmu 1.3 — five systems, a real DS fix, and a proper look

MilliEmu now runs **NES, Game Boy, Game Boy Advance, Nintendo DS and Super Nintendo** through the
same hub. Two of those cores are new in this release, the DS core got the two bugs that made
Rockwrestler look frozen, and the app finally has its own icon and a coloured interface.

- **Package:** `emu.android` · **Android 5.0+ (API 21)** · no permissions, no network
- **Size:** 194,539 bytes
- **SHA-256:** `9275692668957cf01f456e3dbad7921943192888a1e4e2d201c98de0bff054be`
- **Signed:** v1 + v2 schemes, self-signed key `CN=MilliEmu` (committed in `keystore/`)

## New: Game Boy Advance core (`gba`, `.gba`)

A complete second ARM7TDMI machine on top of the DS core's CPU: BIOS-free boot state, modes 0-5
PPU (text, affine, bitmap) with 128 sprites, four DMA channels, timers and the standard IRQs, and
in-place BIOS calls for everything games actually use (Div, Sqrt, ArcTan, CpuSet, BgAffineSet,
LZ77, Run-Length, BitUnPack, Halt, IntrWait).

What it runs today:

- `jsmolka/gba-tests` `arm.gba` — the condition-code, branch, flag, shift and data-processing
  suites all pass (200+ numbered checks, each one a real ARM instruction); the ROM draws its
  progress through the GBA PPU.
- `shades.gba` renders its gradient through bitmap mode 3.
- Fixing those tests also fixed the DS: three genuine decoder bugs came out of it —
  `RRX` (ROR #0) now feeds the old carry in, `LSL` computes the carry from the right bit, and a
  register-specified shift leaves the carry alone when the shift count is zero.

## New: Super Nintendo core (`sfc`, `.smc`)

A full 65C816 interpreter (both accumulator/index widths, decimal mode, block moves, every
addressing mode), LoROM and HiROM mapping, PPU background modes 0-7 with 128 sprites and Mode 7,
all eight DMA channels plus HDMA, NMI/VBlank/VCount IRQs and joypad auto-read.

Verified with a hand-written test ROM (palette → two tiles → a filled map → BG1 on) that renders
exactly the expected 50 % white stripe pattern, which exercises the whole CPU → VRAM → PPU →
framebuffer path. Public PeterLemon demos boot and run through the same code.

## Fixed: Rockwrestler no longer looks stalled

The DS test ROM was never actually stuck in the CPU — its menu was running the whole time. Two
real bugs hid it:

1. **ARM single data transfer with a register-specified offset** was silently skipped by the
   decoder (the group switch never routed to it), so register-offset loads/stores did nothing.
2. **VRAM display mode (DISPCNT display mode 2)** was not rendered, and `DISPCNT` was read as a
   16-bit register, losing bits 16-17 — the ROM turns the screen on with `0x20000` and the PPU
   ignored it.

Also fixed in the DS core: the ARM `SWI` number is now taken from bits 16-23 (the field the
hardware uses), so `swi 6` no longer runs SoftReset; TCM is honoured for 16- and 32-bit accesses;
the IPC block was rewritten with independent sync/status registers per core and correct
empty/full/error bits. Rockwrestler now shows its menu, enters **ARMv4** and reports **OK** for its
CONDITION CODES suite (`artifacts/nds-rockwrestler.png`).

## New: the app has an icon, and colour

- A designed launcher icon (controller over a pixel grid, indigo → magenta, cyan rim) replaces the
  default Android robot, in all five densities.
- The library is rebuilt: gradient background, per-system colour chips, a gradient "Open ROM…"
  button, and recent games as cards with a colour strip tinted by the system they belong to
  (orange NES, green Game Boy, blue GBA, pink DS).
- The in-game toolbar chips are rounded and violet, and the status line uses the system colour.

## Everything else

- `CoreRegistry` learns `.gba`, `.sfc` and `.smc`; the library hint lists them.
- New headless tools: `tools/GbaRun.java`, `tools/SnesRun.java` (with `GBA_TRACE`/`GBA_STEPS`,
  `SNES_TRACE`/`SNES_DUMP`) and `tools/IconGen.java` (crops the artwork into the mipmaps).
- Verification suite: **74 checks, 0 failures** (`java -cp build/classes:build/test-classes
  emu.test.TestRunner`).

## Not there yet (unchanged honesty)

- **No sound in the GBA, DS or SNES cores.** `readAudio` returns silence for those three; NES and
  Game Boy audio are complete.
- **No 3D** on the DS, no DS BIOS firmware, and KEY1-encrypted retail ROMs are still refused.
- **SNES windows, colour maths, interlace and offset-per-tile** are not applied; Mode 5/6 render
  at 256 pixels wide. GBA windows and alpha blending are likewise not applied.
- **No commercial cartridge has been run** through the GBA or SNES cores — they are verified with
  public test ROMs and hand-written programs, not with retail games.
- **The APK was built and inspected, not launched on hardware here** (no device or KVM in this
  environment). The screenshot-worthy evidence is in `artifacts/`: Rockwrestler's menu, the GBA
  ARM test output, the GBA shade gradient, and the SNES stripe test.
