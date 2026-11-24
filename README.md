# MilliEmu — a retro emulator hub for Android

MilliEmu is an Android emulator **hub**: a shell that plays games through pluggable emulation
cores. The first core is a complete, from-scratch **NES / Famicom** emulator written in plain
Java (CPU, PPU, APU, cartridge mappers, save states).

![Nova the Squirrel running](artifacts/nova-f1200.png)

---

## First, the honest part: there is no "indie game emulator"

"Indie game" is not a platform. An indie game is an ordinary program compiled for a specific
machine (Windows, Linux, a console, or Android itself). Nothing can emulate "every indie game"
the way a single program cannot run every Windows `.exe` on a phone — the binaries, APIs and
hardware targets are all different.

What *does* exist, and what RetroArch, Lemuroid and Pizza Boy are built on, is exactly this
project's shape: **one hub + one core per platform**. Games that are distributed as console
ROMs (the overwhelming majority of classic-style indie/homebrew titles) run through the core for
that console.

So this repo is the thing that actually works: a tested hub with a real core, plus a registry
where the next core drops in with no UI changes.

## What is implemented

| Area | Status |
| --- | --- |
| 6502 CPU (official + undocumented opcodes) | Complete, cycle counted |
| NES 2A03 quirk (no decimal arithmetic) | Implemented |
| PPU (background, sprites, scrolling, NMI, sprite-0 hit, overflow) | Complete (scanline granularity) |
| APU (2× pulse, triangle, noise, envelopes, sweeps, frame counter) | Complete except DMC |
| iNES + NES 2.0 header parsing | Complete |
| Mappers | 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), 4 (MMC3+IRQ), 7 (AxROM) |
| Save states | Full machine state, single slot per game |
| Android app | ROM picker, library, touch gamepad, audio, save/load, reset |

## Prerequisites

- **JDK 17** (the build and the core target Java 8 bytecode; the JDK itself must be 17+).
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

On the device: **Open ROM…**, pick a `.nes` file, play. The gamepad appears over the video;
`Save` / `Load` write a state file next to the app's private data.

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
29 passed, 0 failed  (2.0 s)
```

What those 29 checks cover:

- **Klaus Dormann's 6502 functional test** — every official opcode, all addressing modes,
  all flags including decimal mode, run to its success trap.
- **nestest.nes + its reference log** — 8991 instructions compared one by one, including
  undocumented opcodes and exact cycle counts.
- **blargg's instr_test-v5** — all 16 sub-tests, end to end through CPU + PPU + cartridge.
- **PPU rendering** — background palette/tiles, fine-X scrolling, sprite drawing, sprite-0 hit.
- **Mappers** — UxROM bank switching, MMC1 serial writes, MMC3 banking + scanline IRQ.
- **APU** — a pulse channel produces non-silent samples.
- **Save states** — a state restored 30 frames later reproduces an identical machine state,
  byte for byte.

Headless tools in `tools/` render frames to PNG without a phone:

```bash
javac -cp build/classes -d /tmp tools/FrameDump.java
java -cp build/classes:/tmp FrameDump game.nes out.png 180
```

## Architecture

```
core/   plain Java 8, zero dependencies — this is the emulator
  emu/EmulatorCore.java     the contract the UI uses
  emu/CoreRegistry.java     extension -> core factory (the hub's plug-in point)
  emu/nes/Cpu6502.java      6502 core, packed opcode table
  emu/nes/Ppu.java          2C02: video, scroll registers, NMI, sprite-0
  emu/nes/Apu.java          2A03 audio, cycle counters + 44.1 kHz resampling
  emu/nes/Cartridge.java    iNES / NES 2.0 loader
  emu/nes/Mappers.java      mappers 0,1,2,3,4,7
  emu/nes/NesMachine.java   bus wiring, controllers, frame loop
  emu/nes/NesCore.java      EmulatorCore implementation + save states
app/    Android app (plain Java, no AndroidX)
  MainActivity              ROM library
  EmulatorActivity          hosting, overlay controls, state files
  EmulatorView              SurfaceView renderer + touch gamepad + frame thread
  AudioOutput               AudioTrack streaming
```

The emulation thread owns the core exclusively; UI actions are posted to it as commands, so no
two threads ever touch machine state. Frames are paced to 60 Hz and audio writes provide the
back-pressure that keeps sync.

### Adding another platform

1. Write `MyCore implements EmulatorCore` (load, step one frame, framebuffer, audio, buttons,
   save state).
2. Register it once:
   ```java
   CoreRegistry.register("gb", "Game Boy", new CoreRegistry.Factory() {
       public EmulatorCore create() { return new GbCore(); }
   });
   ```
3. Nothing else changes: the library lists the new extension, the picker accepts it, and the
   activity drives it through the same interface.

## Known limitations (measured, not guessed)

- **Only the NES core exists.** The hub is general; other platforms need their own core.
- **No DMC channel.** A few games' sampled drums will be silent.
- **PPU is scanline-accurate, not dot-accurate.** Normal games, scrolling, split screens and
  MMC3 IRQ timing work; exotic mid-scanline raster tricks and the strict `ppu_vbl_nmi`
  dot-level tests are not guaranteed.
- **Mappers are limited to 0, 1, 2, 3, 4, 7.** Unknown mappers fall back to NROM and will not
  boot correctly.
- **One save-state slot per game**, no rewind, no netplay, no turbo.
- **The APK was built and inspected, but not launched on hardware in this environment**: the
  sandbox has no access to `/dev/kvm` (permission denied) and no USB device, so the Android UI
  itself is unverified. Everything below the UI (the core, the exact code path the app calls)
  is verified by the suite above and by the rendered frames in `artifacts/`.

## Test material

`core/src/test/resources/roms` contains freely redistributable test material:
Klaus Dormann's 6502 functional test, `nestest.nes`/`nestest.log` and blargg's instr_test-v5.
They are test fixtures, not playable games. The screenshots use *Nova the Squirrel*, an
open-source homebrew game that is not included in this repository.
