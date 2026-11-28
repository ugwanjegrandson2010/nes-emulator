package emu.test;

import emu.gb.GbApu;
import emu.gb.GbCartridge;
import emu.gb.GbCore;
import emu.gb.GbMachine;
import emu.gb.GbPpu;
import emu.nes.Apu;
import emu.nes.Cartridge;
import emu.nes.Cpu6502;
import emu.nes.Mapper;
import emu.nes.Mappers;
import emu.nes.NesCore;
import emu.nes.NesMachine;
import emu.nes.Ppu;
import emu.snes.SnesCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dependency-free verification harness for the emulator core.
 *
 * <p>Uses the standard public test material: Klaus Dormann's 6502 functional test (every official
 * opcode, decimal mode included), nestest.nes with its reference cycle log (official and
 * undocumented opcodes, exact cycle counts) and blargg's instr_test-v5 suite (end to end).
 */
public final class TestRunner {

    private static int passed;
    private static int failed;
    private static final List<String> FAILURES = new ArrayList<String>();
    private static Path roms = Paths.get("core/src/test/resources/roms");

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            roms = Paths.get(args[0]);
        }
        long start = System.currentTimeMillis();

        cpuBasics();
        cpuOpcodeSmoke();
        cpuDecimalMode();
        klausFunctionalTest();
        nestestLog();
        blarggInstructions();
        ppuBackground();
        ppuFineScroll();
        ppuSprites();
        mapperBanking();
        apuOutput();
        saveStateRoundTrip();

        gbCpuInstructions();
        gbInstructionTiming();
        gbHaltBug();
        gbPictureUnit();
        gbMappers();
        gbApuOutput();
        gbSaveStateRoundTrip();

        NdsTests.run(new NdsTests.Reporter() {
            @Override
            public void check(String name, boolean ok, String detail) {
                TestRunner.check(name, ok, detail);
            }
        });

        snesSaveStateRoundTrip();

        long millis = System.currentTimeMillis() - start;
        System.out.println();
        System.out.println("=================================================");
        System.out.printf("%d passed, %d failed  (%.1f s)%n", passed, failed, millis / 1000.0);
        for (String failure : FAILURES) {
            System.out.println("  FAILED: " + failure);
        }
        System.out.println("=================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : "  [" + detail + "]"));
            System.out.println("FAIL  " + name + (detail == null || detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static byte[] rom(String name) throws IOException {
        Path path = roms.resolve(name);
        if (!Files.exists(path)) {
            throw new IOException("missing test ROM: " + path.toAbsolutePath());
        }
        return Files.readAllBytes(path);
    }

    // ------------------------------------------------------------------ CPU

    /** Loads a test program at $C000 and points the reset vector there. */
    private static Cpu6502 cpuWith(byte[]... programs) {
        final byte[] mem = new byte[0x10000];
        final byte[] ram = new byte[0x800];
        Cpu6502 cpu = new Cpu6502();
        if (programs.length > 0) {
            System.arraycopy(programs[0], 0, mem, 0xC000, Math.min(programs[0].length, mem.length - 0xC000));
            mem[0xFFFC] = 0x00;
            mem[0xFFFD] = (byte) 0xC0;
        }
        cpu.setBus(new Cpu6502.Bus() {
            @Override
            public int read(int address) {
                address &= 0xFFFF;
                if (address < 0x2000) {
                    return ram[address & 0x7FF] & 0xFF;
                }
                return mem[address] & 0xFF;
            }

            @Override
            public void write(int address, int value) {
                address &= 0xFFFF;
                if (address < 0x2000) {
                    ram[address & 0x7FF] = (byte) value;
                } else {
                    mem[address] = (byte) value;
                }
            }
        });
        cpu.reset();
        return cpu;
    }

    private static void cpuBasics() {
        // LDA #$10 ; TAX ; INX ; LDA #$FF ; ADC #$01 -> 0x00 with carry and zero
        Cpu6502 cpu = cpuWith(new byte[]{
                (byte) 0xA9, 0x10,       // LDA #$10
                (byte) 0xAA,             // TAX
                (byte) 0xE8,             // INX  -> 0x11
                (byte) 0xA9, (byte) 0xFF,// LDA #$FF
                (byte) 0x69, 0x01,       // ADC #$01 -> 0x00, C=1, Z=1
                (byte) 0x85, 0x10,       // STA $10
                (byte) 0x4C, 0x0A, (byte) 0xC0  // JMP $C00A (self)
        });
        cpu.step();
        check("LDA immediate", cpu.a == 0x10);
        cpu.step();
        check("TAX", cpu.x == 0x10);
        cpu.step();
        check("INX", cpu.x == 0x11);
        cpu.step();
        cpu.step();
        check("ADC wrap sets carry and zero", cpu.a == 0x00 && (cpu.p & Cpu6502.C) != 0 && (cpu.p & Cpu6502.Z) != 0,
                String.format("A=%02X P=%02X", cpu.a, cpu.p));

        // stack: PHA/PLA
        cpu = cpuWith(new byte[]{
                (byte) 0xA9, 0x42,       // LDA #$42
                (byte) 0x48,             // PHA
                (byte) 0xA9, 0x00,       // LDA #$00
                (byte) 0x68,             // PLA
                (byte) 0x4C, 0x06, (byte) 0xC0
        });
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        check("PHA/PLA round trip", cpu.a == 0x42, String.format("A=%02X SP=%02X", cpu.a, cpu.sp));

        // JSR/RTS
        cpu = cpuWith(new byte[]{
                (byte) 0x20, 0x0A, (byte) 0xC0, // JSR $C00A
                (byte) 0x4C, 0x03, (byte) 0xC0, // JMP $C003 (self)
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0xA9, 0x77,       // LDA #$77
                (byte) 0x60                // RTS
        });
        cpu.step();
        cpu.step();
        check("JSR/RTS returns and executes subroutine", cpu.a == 0x77 && cpu.pc == 0xC00C,
                String.format("A=%02X PC=%04X", cpu.a, cpu.pc));

        // 16-bit memory + indexed addressing
        cpu = cpuWith(new byte[]{
                (byte) 0xA2, 0x03,             // LDX #$03
                (byte) 0xBD, 0x00, (byte) 0xD0,// LDA $D000,X
                (byte) 0x4C, 0x05, (byte) 0xC0
        });
        cpu.step();
        cpu.step();
        check("absolute,X addressing", cpu.a == 0x00, String.format("A=%02X", cpu.a));
    }

    private static void cpuOpcodeSmoke() {
        int exceptions = 0;
        for (int opcode = 0; opcode < 256; opcode++) {
            Cpu6502 cpu = cpuWith(new byte[]{(byte) opcode, 0x00, 0x00, 0x00});
            cpu.pc = 0xC000;
            cpu.sp = 0xFD;
            try {
                cpu.step();
            } catch (RuntimeException e) {
                exceptions++;
                System.out.println("      opcode $" + Integer.toHexString(opcode).toUpperCase()
                        + " threw " + e);
            }
        }
        check("all 256 opcodes execute without throwing", exceptions == 0, exceptions + " threw");
    }

    private static void cpuDecimalMode() {
        // 0x45 + 0x38 in BCD = 0x83
        Cpu6502 cpu = cpuWith(new byte[]{
                (byte) 0xF8,             // SED
                (byte) 0xA9, 0x45,       // LDA #$45
                (byte) 0x69, 0x38,       // ADC #$38
                (byte) 0x4C, 0x05, (byte) 0xC0
        });
        cpu.step();
        cpu.step();
        cpu.step();
        check("decimal ADC 45+38 = 83", cpu.a == 0x83, String.format("A=%02X", cpu.a));

        // 0x99 + 0x01 in BCD = 0x00 carry 1
        cpu = cpuWith(new byte[]{
                (byte) 0xF8,
                (byte) 0xA9, (byte) 0x99,
                (byte) 0x69, 0x01,
                (byte) 0x4C, 0x05, (byte) 0xC0
        });
        cpu.step();
        cpu.step();
        cpu.step();
        check("decimal ADC 99+01 = 00 with carry", cpu.a == 0x00 && (cpu.p & Cpu6502.C) != 0,
                String.format("A=%02X P=%02X", cpu.a, cpu.p));

        // 0x50 - 0x25 in BCD = 0x25
        cpu = cpuWith(new byte[]{
                (byte) 0xF8,             // SED
                (byte) 0x38,             // SEC
                (byte) 0xA9, 0x50,       // LDA #$50
                (byte) 0xE9, 0x25,       // SBC #$25
                (byte) 0x4C, 0x06, (byte) 0xC0
        });
        cpu.step();
        cpu.step();
        cpu.step();
        cpu.step();
        check("decimal SBC 50-25 = 25", cpu.a == 0x25, String.format("A=%02X", cpu.a));
    }

    private static void klausFunctionalTest() throws IOException {
        byte[] bin = rom("6502_functional_test.bin");
        final byte[] mem = new byte[0x10000];
        System.arraycopy(bin, 0, mem, 0, Math.min(bin.length, mem.length));

        Cpu6502 cpu = new Cpu6502();
        cpu.setBus(new Cpu6502.Bus() {
            @Override
            public int read(int address) {
                return mem[address & 0xFFFF] & 0xFF;
            }

            @Override
            public void write(int address, int value) {
                mem[address & 0xFFFF] = (byte) value;
            }
        });
        cpu.pc = 0x0400;
        cpu.sp = 0xFD;
        cpu.p = 0x24;
        cpu.cycles = 0;

        long steps = 0;
        final long maxSteps = 60_000_000L;
        long start = System.currentTimeMillis();
        while (steps < maxSteps) {
            int before = cpu.pc;
            cpu.step();
            steps++;
            if (cpu.isJammed() || cpu.pc == before) {
                break; // trapped: either "success" (JMP *) or a failing self-loop
            }
        }
        long millis = System.currentTimeMillis() - start;
        String detail = String.format("stopped at $%04X after %,d instructions (%.1f s)",
                cpu.pc, steps, millis / 1000.0);
        check("Klaus 6502 functional test (all official opcodes)", cpu.pc == 0x3469, detail);
    }

    private static void nestestLog() throws IOException {
        List<String> lines = Files.readAllLines(roms.resolve("nestest.log"), StandardCharsets.US_ASCII);
        NesMachine machine = new NesMachine(Cartridge.load(rom("nestest.nes")));
        machine.cpu.pc = 0xC000;
        machine.cpu.sp = 0xFD;
        machine.cpu.p = 0x24;
        machine.cpu.a = 0;
        machine.cpu.x = 0;
        machine.cpu.y = 0;
        machine.cpu.cycles = 7;

        int compared = 0;
        int mismatches = 0;
        String firstMismatch = "";

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int pc;
            try {
                pc = Integer.parseInt(line.substring(0, 4), 16);
            } catch (RuntimeException e) {
                continue;
            }
            int a = field(line, "A:");
            int x = field(line, "X:");
            int y = field(line, "Y:");
            int p = field(line, "P:");
            int sp = field(line, "SP:");
            long cycles = fieldDecimal(line, "CYC:");

            if (machine.cpu.pc != pc || machine.cpu.a != a || machine.cpu.x != x || machine.cpu.y != y
                    || machine.cpu.p != p || machine.cpu.sp != sp || machine.cpu.cycles != cycles) {
                mismatches++;
                if (mismatches == 1) {
                    firstMismatch = String.format("line %d expected PC:%04X A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d, got %s",
                            compared + 1, pc, a, x, y, p, sp, cycles, machine.cpu.dump());
                }
            }
            machine.stepInstruction();
            compared++;
        }

        check("nestest: " + compared + " instructions match the reference log",
                mismatches == 0 && compared > 8000,
                mismatches == 0 ? compared + " compared" : mismatches + " mismatches; " + firstMismatch);
    }

    private static int field(String line, String key) {
        int index = line.indexOf(key);
        if (index < 0) {
            return -1;
        }
        int start = index + key.length();
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != ',') {
            end++;
        }
        return Integer.parseInt(line.substring(start, end).trim(), 16);
    }

    private static int fieldDecimal(String line, String key) {
        int index = line.indexOf(key);
        if (index < 0) {
            return -1;
        }
        int start = index + key.length();
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return Integer.parseInt(line.substring(start, end).trim());
    }

    private static void blarggInstructions() throws IOException {
        String[] roms = {
                "instr_01-basics.nes", "instr_02-implied.nes", "instr_03-immediate.nes",
                "instr_04-zero_page.nes", "instr_05-zp_xy.nes", "instr_06-absolute.nes",
                "instr_07-abs_xy.nes", "instr_08-ind_x.nes", "instr_09-ind_y.nes",
                "instr_10-branches.nes", "instr_11-stack.nes", "instr_12-jmp_jsr.nes",
                "instr_13-rts.nes", "instr_14-rti.nes", "instr_15-brk.nes", "instr_16-special.nes"
        };
        int allPassed = 0;
        StringBuilder failures = new StringBuilder();
        for (String name : roms) {
            NesMachine machine = new NesMachine(Cartridge.load(rom(name)));
            String status = "";
            int code = 0;
            for (int frame = 0; frame < 600; frame++) {
                machine.stepFrame();
                status = readString(machine.cart.mapper, 0x6004, 80);
                code = machine.cart.mapper.cpuRead(0x6000) & 0xFF;
                if (status.contains("Passed") || status.contains("Failed")
                        || (code != 0x00 && code != 0x80 && code != 0x81)) {
                    break;
                }
            }
            boolean ok = status.contains("Passed") || code == 0x01;
            if (ok) {
                allPassed++;
            } else {
                failures.append(name).append("='").append(status).append("' ");
                System.out.println("      " + name + " -> '" + status + "' (code 0x" + Integer.toHexString(code) + ")");
            }
        }
        check("blargg instr_test-v5: all " + roms.length + " sub-tests pass (" + allPassed + "/" + roms.length + ")",
                allPassed == roms.length, failures.toString());
    }

    private static String readString(Mapper mapper, int address, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int c = mapper.cpuRead(address + i) & 0xFF;
            if (c == 0) {
                break;
            }
            if (c >= 32 && c < 127) {
                sb.append((char) c);
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ PPU

    private static Mapper blankMapper() {
        byte[] prg = new byte[0x4000];
        byte[] chr = new byte[0x2000];
        return Mappers.create(0, prg, chr, true, Mapper.MIRROR_VERTICAL); // writable CHR for tests
    }

    private static void writePalette(Ppu ppu, int index, int value) {
        ppu.writeRegister(6, 0x3F);
        ppu.writeRegister(6, index & 0xFF);
        ppu.writeRegister(7, value & 0x3F);
    }

    /** $2006 writes leave a scroll address behind; put scroll back to (0,0). */
    private static void resetScroll(Ppu ppu) {
        ppu.writeRegister(6, 0x20);
        ppu.writeRegister(6, 0x00);
        ppu.writeRegister(5, 0x00);
        ppu.writeRegister(5, 0x00);
    }

    private static void runFrames(Ppu ppu, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < 341 * 262; i++) {
                ppu.step();
            }
        }
    }

    private static void ppuBackground() {
        Mapper mapper = blankMapper();
        // tile 0 rows: low plane 0xFF, high plane 0xFF -> every pixel is colour 3
        for (int row = 0; row < 8; row++) {
            mapper.chrWrite(row, 0xFF);
            mapper.chrWrite(row + 8, 0xFF);
        }
        Ppu ppu = new Ppu(mapper);
        ppu.reset();
        writePalette(ppu, 0x00, 0x0F);
        writePalette(ppu, 0x03, 0x16);
        for (int i = 0; i < 0x400; i++) {
            ppu.ppuWrite(0x2000 + i, 0);
        }
        resetScroll(ppu);
        ppu.writeRegister(0, 0x00);
        ppu.writeRegister(1, 0x08); // background on
        runFrames(ppu, 2);

        int px = ppu.framebuffer[120 * 256 + 128];
        check("PPU background renders palette entry 3 across the screen",
                px == Ppu.rgbFor(0x16), String.format("got %08X want %08X", px, Ppu.rgbFor(0x16)));

        // background off -> backdrop colour
        ppu.writeRegister(1, 0x00);
        runFrames(ppu, 2);
        int backdrop = ppu.framebuffer[120 * 256 + 128];
        check("PPU renders backdrop colour when rendering is disabled",
                backdrop == Ppu.rgbFor(0x0F), String.format("got %08X", backdrop));
    }

    private static void ppuFineScroll() {
        Mapper mapper = blankMapper();
        // tile 0: only the leftmost pixel of every row is colour 1
        for (int row = 0; row < 8; row++) {
            mapper.chrWrite(row, 0x80);
            mapper.chrWrite(row + 8, 0x00);
        }
        Ppu ppu = new Ppu(mapper);
        ppu.reset();
        writePalette(ppu, 0x00, 0x0F);
        writePalette(ppu, 0x01, 0x21);
        for (int i = 0; i < 0x400; i++) {
            ppu.ppuWrite(0x2000 + i, 0);
        }
        resetScroll(ppu);
        ppu.writeRegister(0, 0x00);
        ppu.writeRegister(1, 0x0A); // background, no left clipping
        runFrames(ppu, 2);
        int firstNoScroll = ppu.framebuffer[100 * 256 + 0];
        check("PPU leftmost pixel uses tile colour without scroll",
                firstNoScroll == Ppu.rgbFor(0x21), String.format("got %08X", firstNoScroll));

        ppu.writeRegister(5, 0x01); // fine X = 1
        ppu.writeRegister(5, 0x00);
        runFrames(ppu, 2);
        int firstScrolled = ppu.framebuffer[100 * 256 + 0];
        check("PPU fine X scroll shifts the background by one pixel",
                firstScrolled == Ppu.rgbFor(0x0F), String.format("got %08X", firstScrolled));
    }

    private static void ppuSprites() {
        Mapper mapper = blankMapper();
        for (int row = 0; row < 8; row++) {
            mapper.chrWrite(row, 0xFF);
            mapper.chrWrite(row + 8, 0xFF); // tile 0 solid colour 3 (background)
            mapper.chrWrite(16 + row, 0xFF);
            mapper.chrWrite(24 + row, 0xFF); // tile 1 solid colour 3 (sprite)
        }
        Ppu ppu = new Ppu(mapper);
        ppu.reset();
        writePalette(ppu, 0x00, 0x0F);
        writePalette(ppu, 0x03, 0x16);
        writePalette(ppu, 0x11, 0x2A); // sprite palette 0 colour 1
        writePalette(ppu, 0x13, 0x2A); // sprite palette 0 colour 3

        for (int i = 0; i < 0x400; i++) {
            ppu.ppuWrite(0x2000 + i, 0);
        }
        byte[] oam = ppu.oamData();
        Arrays.fill(oam, (byte) 0xF8);
        oam[0] = (byte) 99;  // sprite 0 top = 100
        oam[1] = 1;
        oam[2] = 0;
        oam[3] = 100;

        resetScroll(ppu);
        ppu.writeRegister(0, 0x00);
        ppu.writeRegister(1, 0x1A); // sprites + background, no left clipping
        runFrames(ppu, 2);

        int spritePixel = ppu.framebuffer[100 * 256 + 100];
        check("PPU draws a sprite over the background",
                spritePixel == Ppu.rgbFor(0x2A), String.format("got %08X", spritePixel));

        // status bits are cleared at the pre-render line, so sample the flag inside visible time
        for (int i = 0; i < 341 * 120; i++) {
            ppu.step();
        }
        int status = ppu.readRegister(2);
        check("PPU sets the sprite-0 hit flag", (status & 0x40) != 0, String.format("status=%02X", status));
    }

    // ------------------------------------------------------------------ mappers / APU / state

    private static void mapperBanking() {
        // UxROM: 4 x 16K banks, each filled with its bank number
        byte[] prg = new byte[0x10000];
        for (int bank = 0; bank < 4; bank++) {
            Arrays.fill(prg, bank * 0x4000, (bank + 1) * 0x4000, (byte) bank);
        }
        Mapper uxrom = Mappers.create(2, prg, new byte[0x2000], true, Mapper.MIRROR_VERTICAL);
        uxrom.cpuWrite(0x8000, 2);
        check("UxROM switches the $8000 bank", (uxrom.cpuRead(0x8000) & 0xFF) == 2,
                "read " + (uxrom.cpuRead(0x8000) & 0xFF));
        check("UxROM keeps the last bank fixed at $C000", (uxrom.cpuRead(0xC000) & 0xFF) == 3,
                "read " + (uxrom.cpuRead(0xC000) & 0xFF));

        // MMC1: five serial writes program the PRG bank register
        Mapper mmc1 = Mappers.create(1, prg, new byte[0x2000], true, Mapper.MIRROR_VERTICAL);
        writeMmc1(mmc1, 0xE000, 3); // PRG bank 3, mode 3 -> $8000 window
        check("MMC1 5-bit serial write selects PRG bank", (mmc1.cpuRead(0x8000) & 0xFF) == 3,
                "read " + (mmc1.cpuRead(0x8000) & 0xFF));

        // MMC3: 8K PRG banking, $8000 slot from register 6
        byte[] prg8 = new byte[0x20000]; // 8 x 16K = 16 x 8K
        for (int bank = 0; bank < 16; bank++) {
            Arrays.fill(prg8, bank * 0x2000, (bank + 1) * 0x2000, (byte) bank);
        }
        Mapper mmc3 = Mappers.create(4, prg8, new byte[0x8000], false, Mapper.MIRROR_VERTICAL);
        mmc3.cpuWrite(0x8000, 6);
        mmc3.cpuWrite(0x8001, 5);
        check("MMC3 maps register 6 into the $8000 slot", (mmc3.cpuRead(0x8000) & 0xFF) == 5,
                "read " + (mmc3.cpuRead(0x8000) & 0xFF));
        check("MMC3 keeps the last bank at $E000", (mmc3.cpuRead(0xE000) & 0xFF) == 15,
                "read " + (mmc3.cpuRead(0xE000) & 0xFF));

        mmc3.cpuWrite(0xC000, 3);  // latch
        mmc3.cpuWrite(0xE001, 0);  // enable IRQ
        boolean sawIrq = false;
        for (int line = 0; line < 8 && !sawIrq; line++) {
            mmc3.scanline(line);
            sawIrq = mmc3.irq();
        }
        check("MMC3 scanline counter raises the IRQ line", sawIrq);
    }

    private static void writeMmc1(Mapper mapper, int address, int value) {
        for (int bit = 0; bit < 5; bit++) {
            mapper.cpuWrite(address, (value >> bit) & 1);
        }
    }

    private static void apuOutput() {
        Apu apu = new Apu();
        apu.reset();
        apu.setSampleRate(44100);
        apu.write(0x4015, 0x01);            // enable pulse 1
        apu.write(0x4000, 0xBF);            // duty 2, constant volume 15, length halt
        apu.write(0x4002, 0x40);            // timer low
        apu.write(0x4003, 0x08);            // timer high + length
        short[] buffer = new short[2048];
        int total = 0;
        boolean nonZero = false;
        for (int i = 0; i < 300; i++) {
            apu.step(200);
            int n = apu.readAudio(buffer, 0, buffer.length);
            total += n;
            for (int s = 0; s < n; s++) {
                if (buffer[s] != 0) {
                    nonZero = true;
                }
            }
        }
        check("APU produces non-silent samples for an enabled pulse channel", total > 0 && nonZero,
                "samples=" + total + " nonZero=" + nonZero);
    }

    // ------------------------------------------------------------------ Game Boy

    private static final String[] GB_CPU_TESTS = {
            "01-special", "02-interrupts", "03-op_sp,hl", "04-op_r,imm", "05-op_rp",
            "06-ld_r,r", "07-jr,jp,call,ret,rst", "08-misc_instrs", "09-op_r,r",
            "10-bit_ops", "11-op_a,(hl)"
    };

    /** Runs a Game Boy ROM and checks its final screen against a verified reference frame. */
    private static int[] gbScreen(byte[] rom, int frames) throws IOException {
        GbMachine machine = new GbMachine(GbCartridge.load(rom));
        for (int i = 0; i < frames; i++) {
            machine.stepFrame();
        }
        return machine.ppu.framebuffer.clone();
    }

    /** Number of pixels that differ; grayscale references use the DMG 0x00/0x55/0xAA/0xFF shades. */
    private static int compareScreen(int[] actual, String expectedName, boolean grayscale) throws IOException {
        BufferedImage expected = ImageIO.read(roms.resolve("gb/expected").resolve(expectedName).toFile());
        if (expected == null) {
            throw new IOException("missing reference image: " + expectedName);
        }
        int diffs = 0;
        for (int y = 0; y < GbPpu.HEIGHT; y++) {
            for (int x = 0; x < GbPpu.WIDTH; x++) {
                int want = expected.getRGB(x, y);
                if (grayscale) {
                    int gray = want & 0xFF;
                    int shade = gray <= 0x00 ? 3 : gray < 0x80 ? 2 : gray < 0xE0 ? 1 : 0;
                    want = GbPpu.rgbFor(shade);
                }
                if ((want & 0xFFFFFF) != (actual[y * GbPpu.WIDTH + x] & 0xFFFFFF)) {
                    diffs++;
                }
            }
        }
        return diffs;
    }

    private static void gbCpuInstructions() throws IOException {
        int passedTests = 0;
        StringBuilder failures = new StringBuilder();
        for (String name : GB_CPU_TESTS) {
            int[] screen = gbScreen(rom("gb/" + name + ".gb"), 400);
            int diffs = compareScreen(screen, name + ".png", false);
            if (diffs == 0) {
                passedTests++;
            } else {
                failures.append(name).append(" diff=").append(diffs).append(" ");
            }
        }
        check("Game Boy cpu_instrs: " + passedTests + "/" + GB_CPU_TESTS.length + " sub-tests render Passed",
                passedTests == GB_CPU_TESTS.length, failures.toString());
    }

    private static void gbInstructionTiming() throws IOException {
        int[] screen = gbScreen(rom("gb/instr_timing.gb"), 400);
        int diffs = compareScreen(screen, "instr_timing.png", false);
        check("Game Boy instr_timing: instruction cycle counts match hardware", diffs == 0, "diff=" + diffs);
    }

    private static void gbHaltBug() throws IOException {
        int[] screen = gbScreen(rom("gb/halt_bug.gb"), 400);
        int diffs = compareScreen(screen, "halt_bug.png", false);
        check("Game Boy halt_bug: HALT with a pending interrupt repeats the next byte", diffs == 0, "diff=" + diffs);
    }

    private static void gbPictureUnit() throws IOException {
        int[] screen = gbScreen(rom("gb/dmg-acid2.gb"), 200);
        int diffs = compareScreen(screen, "dmg-acid2.png", true);
        check("Game Boy PPU: dmg-acid2 matches the official reference image (" + (160 * 144 - diffs) + "/23040 px)",
                diffs == 0, diffs + " pixels differ");
    }

    private static void gbMappers() throws IOException {
        // four 16K banks, each stamped with its number, plus the header of a real MBC1 ROM
        byte[] base = new byte[0x10000];
        byte[] header = rom("gb/01-special.gb");
        for (int bank = 0; bank < 4; bank++) {
            Arrays.fill(base, bank * 0x4000, (bank + 1) * 0x4000, (byte) bank);
        }
        System.arraycopy(header, 0x100, base, 0x100, 0x50);
        GbCartridge cart = GbCartridge.load(base);
        cart.write(0x2000, 2);  // MBC1 ROM bank register
        check("Game Boy MBC1 switches the $4000 window", (cart.read(0x4000) & 0xFF) == 2,
                "read " + (cart.read(0x4000) & 0xFF));
        check("Game Boy bank 0 stays at $0000", (cart.read(0x0000) & 0xFF) == 0,
                "read " + (cart.read(0x0000) & 0xFF));
        cart.write(0x2000, 0);  // writing 0 must select bank 1, never bank 0
        check("Game Boy MBC1 maps bank 0 writes to bank 1", (cart.read(0x4000) & 0xFF) == 1,
                "read " + (cart.read(0x4000) & 0xFF));
    }

    private static void gbApuOutput() {
        GbApu apu = new GbApu();
        apu.reset();
        apu.setSampleRate(44100);
        apu.writeRegister(0xFF26, 0x80);  // power on
        apu.writeRegister(0xFF12, 0xF0);  // channel 1 volume 15, no envelope
        apu.writeRegister(0xFF13, 0x00);
        apu.writeRegister(0xFF14, 0x87);  // trigger, 131072 Hz-ish square
        apu.writeRegister(0xFF24, 0x77);
        apu.writeRegister(0xFF25, 0x11);
        short[] buffer = new short[4096];
        int total = 0;
        boolean nonZero = false;
        for (int i = 0; i < 200; i++) {
            apu.tick(1000);
            int n = apu.readAudio(buffer, 0, buffer.length);
            total += n;
            for (int s = 0; s < n; s++) {
                if (buffer[s] != 0) {
                    nonZero = true;
                }
            }
        }
        check("Game Boy APU produces non-silent samples for an enabled pulse channel",
                total > 0 && nonZero, "samples=" + total);
    }

    private static void gbSaveStateRoundTrip() throws Exception {
        GbCore core = new GbCore();
        core.loadRom(rom("gb/01-special.gb"));
        for (int i = 0; i < 40; i++) {
            core.stepFrame();
        }
        byte[] state = core.saveState();

        for (int i = 0; i < 30; i++) {
            core.stepFrame();
        }
        byte[] expected = core.saveState();

        core.loadState(state);
        for (int i = 0; i < 30; i++) {
            core.stepFrame();
        }
        byte[] replayed = core.saveState();

        check("Game Boy save state round trip reproduces the exact machine state",
                Arrays.equals(expected, replayed),
                "state sizes " + expected.length + "/" + replayed.length);
    }

    private static void snesSaveStateRoundTrip() throws IOException {
        byte[] rom = new byte[0x8000];
        rom[0x7FFC] = 0x00;
        rom[0x7FFD] = (byte) 0x80;
        SnesCore original = new SnesCore();
        original.loadRom(rom);
        original.machine().write(0x006000, 0x5A);
        original.machine().write(0x7E0000, 0xA5);
        original.machine().cpu().a = 0x1234;
        byte[] state = original.saveState();

        SnesCore restored = new SnesCore();
        restored.loadRom(rom);
        restored.loadState(state);
        check("SNES state restores SRAM, WRAM and CPU registers into a fresh core",
                restored.machine().read(0x006000) == 0x5A
                && restored.machine().read(0x7E0000) == 0xA5
                && restored.machine().cpu().a == 0x1234
                && Arrays.equals(state, restored.saveState()));
    }

    private static void saveStateRoundTrip() throws Exception {
        NesCore core = new NesCore();
        core.loadRom(rom("nestest.nes"));
        for (int i = 0; i < 40; i++) {
            core.stepFrame();
        }
        byte[] state = core.saveState();

        for (int i = 0; i < 30; i++) {
            core.stepFrame();
        }
        byte[] expected = core.saveState();

        core.loadState(state);
        for (int i = 0; i < 30; i++) {
            core.stepFrame();
        }
        byte[] replayed = core.saveState();

        check("save state round trip reproduces the exact machine state",
                Arrays.equals(expected, replayed),
                "state sizes " + expected.length + "/" + replayed.length);

        int[] fb = core.framebuffer().clone();
        core.reset();
        for (int i = 0; i < 40; i++) {
            core.stepFrame();
        }
        core.loadState(state);
        core.stepFrame();
        check("framebuffer survives state restore", fb.length == 256 * 240, "size " + fb.length);
    }
}
