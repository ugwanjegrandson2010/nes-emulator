# MilliEmu — a multi-system emulator for Android

MilliEmu is an Android emulator **hub**: a shell that plays games through pluggable emulation
cores, written in plain Java with no third-party code. It ships with two complete, from-scratch
cores:

| Core | Systems | Files |
| --- | --- | --- |
| `nes` | NES / Famicom | `.nes` |
| `gb` | Game Boy (DMG) | `.gb`, `.gbc` (DMG-compatible games) |

![Nova the Squirrel running](artifacts/nova-f1200.png)
![dmg-acid2 running on the Game Boy core](artifacts/gb-acid2.png)

## Install it without building anything

Download the signed APK from the [Releases page](https://github.com/ugwanjegrandson2010/nes-emulator/releases/latest) —
`MilliEmu-1.1.apk`, ~59 KB, Android 5.0+ — copy it to your phone, tap it, allow
"install unknown apps" for your file manager, and open it. No JDK, SDK, Gradle or Android Studio
required. The APK is signed with the key committed in `keystore/`, so future releases install
over it as updates.

---

## First, the honest part: there is no "indie game emulator"

"Indie game" is not a platform. An indie game is an ordinary program compiled for a specific
machine (Windows, Linux, a console, or Android itself). Nothing can emulate "every indie game"
the way a single program cannot run every Windows `.exe` on a phone — the binaries, APIs and
hardware targets are all different.

What *does* exist, and what RetroArch, Lemuroid and Pizza Boy are built on, is exactly this
project's shape: **one hub + one core per platform**. Games distributed as console ROMs (the
overwhelming majority of classic-style indie/homebrew titles) run through the core for that
console. This repo now has two of them, and the hub itself needed no changes to gain the second.

## What is implemented

### NES / Famicom core

| Area | Status |
| --- | --- |
| 6502 CPU (official + undocumented opcodes) | Complete, cycle counted |
| NES 2A03 quirk (no decimal arithmetic) | Implemented |
| PPU (background, sprites, scrolling, NMI, sprite-0 hit, overflow) | Complete (scanline granularity) |
| APU (2× pulse, triangle, noise, envelopes, sweeps, frame counter) | Complete except DMC |
| iNES + NES 2.0 header parsing | Complete |
| Mappers | 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), 4 (MMC3+IRQ), 7 (AxROM) |
| Save states | Full machine state, single slot per game |

### Game Boy (DMG) core

| Area | Status |
| --- | --- |
| Sharp LR35902 CPU (all opcodes + CB prefix) | Complete, hardware cycle counts, EI delay, HALT bug |
| PPU (background, window, 8×8 and 8×16 sprites, 10-per-line, DMG priority) | Complete, line renderer |
| APU (2× pulse incl. sweep, wave, noise, envelopes, frame sequencer) | Complete |
| Timer, DIV, joypad, serial, VBlank/STAT/LYC interrupts | Complete |
| Cartridges | ROM-only, MBC1, MBC2, MBC3, MBC5 |
| Save states | Full machine state, single slot per game |

Both cores are plain Java 8 with zero dependencies, so the exact sources compile for Android and
for the JVM test harness.

## Prerequisites

- **JDK 17** (the build and the cores target Java 8 bytecode; the JDK itself must be 17+).
- **Android SDK**: `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`.
  Point to it with `ANDROID_HOME` or a `local.properties` containing `sdk.dir=/path/to/sdk`,
  then accept licenses:
  ```bash
  yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
  sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
  ```
- **Gradle 8.7** — or just use the included wrapper (`./gradlew`), which downloads it.
- No NDK, no native code, no third-party libraries and no network permissions.

## Build and install

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A **signed, installable** release build (what the Releases page ships):

```bash
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
```

Signing is configured from `keystore.properties` (committed on purpose, see the comment inside).
If that file is absent the release APK is built unsigned, which Android will not install — use
the debug build in that case.

On the device: **Open ROM…**, pick a `.nes`, `.gb` or `.gbc` file, play. The core is chosen from
the file extension; the gamepad appears over the video and `Save` / `Load` write a state file
next to the app's private data.

## Run the verification suite

No test framework, no network — one `javac` and one `java`:

```bash
export PATH=/path/to/jdk-17/bin:$PATH
mkdir -p build/classes build/test-classes
javac -encoding UTF-8 -d build/classes $(find core/src/main/java -name '*.java')
javac -encoding UTF-8 -cp build/classes -d build/test-classes $(find core/src/test/java -name '*.java')
java -cp build/classes:build/test-classes emu.test.TestRunner
```

Result on this machine:

```
38 passed, 0 failed  (5.0 s)
```

**NES (29 checks)**

- **Klaus Dormann's 6502 functional test** — every official opcode, all addressing modes,
  all flags including decimal mode, run to its success trap.
- **nestest.nes + its reference log** — 8991 instructions compared one by one, including
  undocumented opcodes and exact cycle counts.
- **blargg's instr_test-v5** — all 16 sub-tests, end to end through CPU + PPU + cartridge.
- **PPU rendering**, **mappers**, **APU** and a byte-identical **save-state** round trip.

**Game Boy (9 checks)**

- **cpu_instrs** — all 11 sub-tests, each verified by comparing the rendered result screen to a
  verified reference frame (they all print `Passed`).
- **instr_timing** — instruction cycle counts, verified the same way.
- **halt_bug** — the HALT bug (pending interrupt with `IME=0` repeats the next byte).
- **dmg-acid2** — Matt Currie's PPU conformance test, compared pixel-for-pixel against the
  official reference image: **23040/23040 pixels identical**.
- **MBC1 banking**, **APU output** and a byte-identical **save-state** round trip.

Headless tools in `tools/` render frames to PNG without a phone:

```bash
javac -cp build/classes -d /tmp tools/FrameDump.java tools/GbDump.java
java -cp build/classes:/tmp FrameDump game.nes out.png 180
java -cp build/classes:/tmp GbDump game.gb out.png 300
```

`tools/GbSuite.java` runs several Game Boy ROMs and tiles their final frames into one contact
sheet (`artifacts/gb-suite.png` is that sheet for the test suite).

## Architecture

```
core/   plain Java 8, zero dependencies — this is the emulator
  emu/EmulatorCore.java     the contract the UI uses
  emu/CoreRegistry.java     extension -> core factory (the hub's plug-in point)
  emu/nes/                  6502, 2C02 PPU, 2A03 APU, iNES mappers, machine, save states
  emu/gb/GbCpu.java         LR35902: opcodes, cycle counts, EI delay, HALT bug
  emu/gb/GbPpu.java         160x144 LCD: BG/window/sprites, STAT/LYC, 456-dot lines
  emu/gb/GbApu.java         4 channels + frame sequencer, 44.1 kHz resampling
  emu/gb/GbCartridge.java   header parsing, MBC1/2/3/5 banking, cart RAM
  emu/gb/GbMachine.java     bus, timer/DIV, joypad, serial, interrupts, frame loop
  emu/gb/GbCore.java        EmulatorCore implementation + save states
app/    Android app (plain Java, no AndroidX)
  MainActivity              ROM library
  EmulatorActivity          hosting, overlay controls, state files
  EmulatorView              SurfaceView renderer + touch gamepad + frame thread
  AudioOutput               AudioTrack streaming
```

The emulation thread owns the core exclusively; UI actions are posted to it as commands, so no
two threads ever touch machine state. Frames are paced to 60 Hz, audio writes provide the
back-pressure that keeps sync, and the video is letterboxed to the core's aspect ratio (4:3 for
the NES, 10:9 for the Game Boy).

### Adding a third platform

1. Write `MyCore implements EmulatorCore` (load, step one frame, framebuffer, audio, buttons,
   save state).
2. Register it once:
   ```java
   CoreRegistry.register("snes", "SNES", new CoreRegistry.Factory() {
       public EmulatorCore create() { return new SnesCore(); }
   });
   ```
3. Nothing else changes: the library lists the new extension, the picker accepts it, and the
   activity drives it through the same interface. This is exactly how the Game Boy was added —
   no changes to the UI code beyond using the core's aspect ratio.

## Known limitations (measured, not guessed)

**NES**

- **No DMC channel.** A few games' sampled drums will be silent.
- **PPU is scanline-accurate, not dot-accurate.** Normal games, scrolling, split screens and
  MMC3 IRQ timing work; exotic mid-scanline raster tricks and the strict `ppu_vbl_nmi`
  dot-level tests are not guaranteed.
- **Mappers are limited to 0, 1, 2, 3, 4, 7.** Unknown mappers fall back to NROM and will not
  boot correctly.

**Game Boy**

- **Monochrome DMG only.** Game Boy Color exclusive ROMs are rejected with a clear error;
  CGB-compatible games run in DMG mode (no colour palettes). There is no CGB double-speed mode.
- **No boot ROM**, so the Nintendo logo animation is skipped — registers start in their
  post-boot state, which is what games expect.
- **`mem_timing` fails (3 sub-tests).** Instruction *totals* are exact (`instr_timing` passes),
  but individual memory accesses inside an instruction are not spread across their exact
  T-cycles, so blargg's sub-instruction bus-timing test detects the difference. No known game
  depends on it.
- **No link cable, no RTC, no MBC3 clock.** MBC3 carts work as ROM+RAM; time-based events stay
  at their initial values.
- **No battery saves**: cartridge RAM lives in memory and in save states, not in a `.sav` file.

**App**

- **One save-state slot per game**, no rewind, no netplay, no turbo.
- **The APK was built and inspected, but not launched on hardware in this environment**: the
  sandbox has no access to `/dev/kvm` and no USB device, so the Android UI itself is unverified.
  Everything below the UI (both cores, the exact code path the app calls) is verified by the
  suite above and by the rendered frames in `artifacts/`.

## Test material

`core/src/test/resources/roms` contains freely redistributable test material:

- NES: Klaus Dormann's 6502 functional test, `nestest.nes`/`nestest.log`, blargg's instr_test-v5.
- Game Boy: blargg's cpu_instrs (11 sub-tests), instr_timing and halt_bug, plus Matt Currie's
  dmg-acid2. `gb/expected/` holds the reference screens the suite compares against; the
  dmg-acid2 reference is the upstream `img/reference-dmg.png`.

They are test fixtures, not playable games. The NES screenshot uses *Nova the Squirrel*, an
open-source homebrew game that is not included in this repository.
