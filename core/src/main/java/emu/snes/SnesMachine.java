package emu.snes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Super Nintendo bus, I/O, DMA/HDMA, timers, joypad and the per-frame scheduler.
 * The S-SMP audio CPU is not emulated, so the core is silent.
 */
public final class SnesMachine implements SnesCpu.Bus {

    static final int WIDTH = SnesPpu.WIDTH;
    static final int HEIGHT = SnesPpu.HEIGHT;

    private static final int CYCLES_PER_LINE = 226;

    final byte[] ram = new byte[0x20000];
    final byte[] sram = new byte[0x8000];
    private byte[] rom = new byte[0];

    private boolean hiRom;
    private boolean fastRom;

    SnesCpu cpu;
    final SnesPpu ppu = new SnesPpu();
    private final int[] framebuffer = new int[WIDTH * HEIGHT];

    private final int[] regs = new int[0x6000];   // $2000-$5FFF mirrored I/O

    // PPU write latches
    private int vramAddress;
    private int vramIncrement = 1;
    private int vramMapping;
    private int cgramAddress;
    private int cgramLatch = -1;
    private int oamAddress;
    private int oamLatch = -1;
    private final int[] scrollLatch = new int[8];
    private final boolean[] scrollToggle = new boolean[4];
    private int m7Latch = -1;
    private int m7Index;

    // video timing
    private int line;
    private int frame;

    // joypad
    private int joyStrobe;
    private int joyShift;
    private final int[] joypad = new int[4];
    private final int[] joypadLatched = new int[4];

    // APU boot stub: a tiny SPC that reports "ready" so games stop waiting
    private int apuPort0 = 0xAA;
    private int apuPort1 = 0xBB;

    private long totalCycles;

    public SnesMachine() {
        cpu = new SnesCpu(this);
    }

    // ------------------------------------------------------------- loading

    void load(byte[] data) throws IOException {
        int offset = 0;
        if (data.length % 0x8000 == 0x200) {
            offset = 0x200;   // strip the copier header
        }
        int size = data.length - offset;
        if (size < 0x8000) {
            throw new IOException("not a Super Nintendo ROM: file is too small");
        }
        rom = new byte[size];
        System.arraycopy(data, offset, rom, 0, size);

        int loScore = score(0x7FC0);
        int hiScore = score(0xFFC0);
        hiRom = hiScore > loScore;
        fastRom = false;
        restart();
    }

    private int score(int headerOffset) {
        if (rom.length < headerOffset + 0x20) {
            return -1;
        }
        int score = 0;
        int mapMode = rom[headerOffset + 0x15] & 0xFF;
        if ((mapMode & 0x01) != 0) {
            score += 2;
        }
        for (int i = 0; i < 21; i++) {
            int c = rom[headerOffset + i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                score++;
            } else if (c == 0) {
                score--;
            }
        }
        return score;
    }

    void restart() {
        java.util.Arrays.fill(ram, (byte) 0);
        ppu.reset();
        java.util.Arrays.fill(regs, 0);
        vramAddress = 0;
        vramIncrement = 1;
        cgramLatch = -1;
        oamLatch = -1;
        java.util.Arrays.fill(scrollToggle, false);
        line = 0;
        frame = 0;
        totalCycles = 0;
        cpu = new SnesCpu(this);
        cpu.reset();
        // BIOS-ish defaults so a ROM that reads the header sees a sane machine
        regs[0x2100] = 0x8F;
        regs[0x4200] = 0x00;
        java.util.Arrays.fill(framebuffer, 0xFF000000);
        apuPort0 = 0xAA;
        apuPort1 = 0xBB;
    }

    public int[] framebuffer() {
        return framebuffer;
    }

    /** Exposed for the headless runner and the tests. */
    public SnesCpu cpu() {
        return cpu;
    }

    public int scanline() {
        return line;
    }

    public int frameCount() {
        return frame;
    }

    /** One-line PPU/CPU snapshot for debugging a stalled or blank screen. */
    public String debugState() {
        int nonZero = 0;
        for (byte value : ppu.vram) {
            if (value != 0) {
                nonZero++;
            }
        }
        int nonZeroCgram = 0;
        for (int i = 1; i < 256; i++) {
            if (ppu.cgram[i] != 0) {
                nonZeroCgram++;
            }
        }
        return "pc=" + Integer.toHexString(cpu.pbr) + ":" + Integer.toHexString(cpu.pc)
                + " mode=" + ppu.bgMode + " tm=" + Integer.toHexString(ppu.mainScreen)
                + " inidisp=" + Integer.toHexString(ppu.inidisp)
                + " bgsc=" + Integer.toHexString(ppu.bgsc[0]) + "," + Integer.toHexString(ppu.bgsc[1])
                + " nba=" + Integer.toHexString(ppu.bgNba[0]) + "," + Integer.toHexString(ppu.bgNba[1])
                + " cgram0=" + Integer.toHexString(ppu.cgram[0])
                + " vramUsed=" + nonZero + " cgramUsed=" + nonZeroCgram
                + " m7=" + Integer.toHexString(ppu.m7[0]) + "," + Integer.toHexString(ppu.m7[1])
                + " map=" + mapSnippet();
    }

    // ------------------------------------------------------------ memory

    private int romOffset(int bank, int offset) {
        if (rom.length == 0) {
            return -1;
        }
        int address;
        if (hiRom) {
            address = ((bank & 0x3F) << 16) | offset;
        } else if ((bank & 0x7F) >= 0x40) {
            address = ((bank & 0x7F) << 16) | offset;
        } else {
            if (offset < 0x8000) {
                return -1;
            }
            address = ((bank & 0x7F) << 15) | (offset & 0x7FFF);
        }
        if (address >= rom.length) {
            int mask = rom.length - 1;
            if ((rom.length & mask) == 0) {
                address &= mask;
            } else {
                address %= rom.length;
            }
        }
        return address;
    }

    @Override
    public int read(int address) {
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x7F) >= 0x7E) {
            return ram[((((bank & 1) << 16) | offset) & 0x1FFFF)] & 0xFF;
        }
        if ((bank & 0x7F) < 0x40) {
            if (offset < 0x2000) {
                return ram[offset] & 0xFF;
            }
            if (offset < 0x6000) {
                return readIo(offset);
            }
            if (offset < 0x8000) {
                return sram[offset & 0x7FFF] & 0xFF;
            }
        } else if ((bank & 0x7F) < 0x40) {
            return sram[offset & 0x7FFF] & 0xFF;
        }
        int romAddress = romOffset(bank, offset);
        if (romAddress < 0) {
            // HiROM SRAM window at $30-$3F:$6000-$7FFF
            if (hiRom && (bank & 0x7F) >= 0x30 && (bank & 0x7F) < 0x40
                    && offset >= 0x6000 && offset < 0x8000) {
                return sram[offset & 0x7FFF] & 0xFF;
            }
            return 0;
        }
        return rom[romAddress] & 0xFF;
    }

    @Override
    public void write(int address, int value) {
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        value &= 0xFF;
        if ((bank & 0x7F) >= 0x7E) {
            ram[((((bank & 1) << 16) | offset) & 0x1FFFF)] = (byte) value;
            return;
        }
        if ((bank & 0x7F) < 0x40) {
            if (offset < 0x2000) {
                ram[offset] = (byte) value;
                return;
            }
            if (offset < 0x6000) {
                writeIo(offset, value);
                return;
            }
            if (offset < 0x8000) {
                sram[offset & 0x7FFF] = (byte) value;
            }
        } else if (hiRom && (bank & 0x7F) >= 0x30 && (bank & 0x7F) < 0x40
                && offset >= 0x6000 && offset < 0x8000) {
            sram[offset & 0x7FFF] = (byte) value;
        }
    }

    // ----------------------------------------------------------- registers

    private int readIo(int offset) {
        switch (offset) {
            case 0x2138: {
                int value = ppu.oam[oamAddress & 0x1FF] & 0xFF;
                oamAddress = (oamAddress + 1) & 0x3FF;
                return value;
            }
            case 0x2139: {
                int value = ppu.readVram((vramAddress & 0x7FFF) * 2);
                stepVramAddress();
                return value;
            }
            case 0x213A: {
                int value = ppu.readVram((vramAddress & 0x7FFF) * 2 + 1);
                stepVramAddress();
                return value;
            }
            case 0x213B: {
                int value = ppu.readCgram(cgramAddress & 0xFF);
                cgramAddress = (cgramAddress + 1) & 0xFF;
                return value;
            }
            case 0x2140: case 0x2141: case 0x2142: case 0x2143: {
                int index = offset - 0x2140;
                return index == 0 ? apuPort0 : index == 1 ? apuPort1 : 0;
            }
            case 0x2180: {
                int value = ram[(regs[0x2181] | (regs[0x2182] << 8) | (regs[0x2183] << 16)) & 0x1FFFF] & 0xFF;
                int next = ((regs[0x2181] | (regs[0x2182] << 8) | (regs[0x2183] << 16)) + 1) & 0x1FFFF;
                regs[0x2181] = next & 0xFF;
                regs[0x2182] = (next >>> 8) & 0xFF;
                regs[0x2183] = (next >>> 16) & 0xFF;
                return value;
            }
            case 0x4210: {
                int value = (regs[0x4210] & 0x80) | 0x02;
                regs[0x4210] &= 0x7F;
                return value;
            }
            case 0x4211: {
                int value = regs[0x4211] & 0x80;
                regs[0x4211] &= 0x7F;
                return value;
            }
            case 0x4212: {
                int value = 0;
                if (line >= 225) {
                    value |= 0x80;
                }
                if (line >= 274 || line < 1) {
                    value |= 0x40;
                }
                return value;
            }
            case 0x4218: case 0x4219: case 0x421A: case 0x421B:
                return joypadLatched[offset - 0x4218] & 0xFF;
            case 0x421C: case 0x421D: case 0x421E: case 0x421F:
                return (joypadLatched[offset - 0x421C] >>> 8) & 0xFF;
            case 0x4016: {
                int value = joyShift & 1;
                int strobe = regs[0x4016];
                if ((strobe & 1) == 0) {
                    joyShift = joyShift >>> 1;
                    joyShift |= 0x8000;
                }
                return value;
            }
            case 0x4017: {
                int value = (joyShift >>> 1) & 1;
                if ((regs[0x4016] & 1) == 0) {
                    joyShift = joyShift >>> 1;
                    joyShift |= 0x8000;
                }
                return value;
            }
            default:
                return regs[offset] & 0xFF;
        }
    }

    private void stepVramAddress() {
        int step = vramIncrement;
        if (vramMapping == 1) {
            vramAddress = (vramAddress & 0xFF00) | ((vramAddress + step) & 0xFF);
        } else if (vramMapping == 2) {
            vramAddress = (vramAddress + step * 32) & 0xFFFF;
        } else if (vramMapping == 3) {
            vramAddress = (vramAddress + step * 128) & 0xFFFF;
        } else {
            vramAddress = (vramAddress + step) & 0x7FFF;
        }
    }

    private void writeIo(int offset, int value) {
        switch (offset) {
            case 0x2100:
                regs[offset] = value;
                ppu.inidisp = value;
                return;
            case 0x2101:
                regs[offset] = value;
                ppu.objsel = value;
                return;
            case 0x2102:
                oamAddress = (oamAddress & 0x100) | value;
                return;
            case 0x2103:
                oamAddress = (oamAddress & 0xFF) | ((value & 1) << 8);
                return;
            case 0x2104: {
                oamLatch = oamLatch < 0 ? value : -1;
                if (oamLatch < 0) {
                    ppu.oam[oamAddress & 0x1FF] = (byte) value;
                    oamAddress = (oamAddress + 1) & 0x3FF;
                }
                return;
            }
            case 0x2105:
                regs[offset] = value;
                ppu.bgMode = value & 7;
                return;
            case 0x2106:
                regs[offset] = value;
                ppu.mosaic = value;
                return;
            case 0x2107: case 0x2108: case 0x2109: case 0x210A:
                regs[offset] = value;
                ppu.bgsc[offset - 0x2107] = value;
                return;
            case 0x210B:
                regs[offset] = value;
                ppu.bgNba[0] = value & 0x0F;
                ppu.bgNba[1] = (value >> 4) & 0x0F;
                return;
            case 0x210C:
                regs[offset] = value;
                ppu.bgNba[2] = value & 0x0F;
                ppu.bgNba[3] = (value >> 4) & 0x0F;
                return;
            case 0x210D: case 0x210F: case 0x2111: case 0x2113: {
                int bg = (offset - 0x210D) / 2;
                scrollLatch[bg * 2] = value;
                ppu.bgHofs[bg] = value;
                scrollToggle[bg] = true;
                return;
            }
            case 0x210E: case 0x2110: case 0x2112: case 0x2114: {
                int bg = (offset - 0x210E) / 2;
                if (scrollToggle[bg]) {
                    ppu.bgHofs[bg] = (scrollLatch[bg * 2] | ((value & 1) << 8)) & 0x1FF;
                    scrollToggle[bg] = false;
                } else {
                    ppu.bgHofs[bg] = value & 0xFF;
                    scrollLatch[bg * 2] = value;
                    scrollToggle[bg] = true;
                }
                ppu.bgVofs[bg] = ppu.bgVofs[bg];
                return;
            }
            case 0x2115:
                regs[offset] = value;
                vramIncrement = 1 << (value & 3);
                vramMapping = (value >> 2) & 3;
                return;
            case 0x2116:
                vramAddress = (vramAddress & 0xFF00) | value;
                return;
            case 0x2117:
                vramAddress = (vramAddress & 0x00FF) | (value << 8);
                return;
            case 0x2118:
                ppu.writeVram((vramAddress & 0x7FFF) * 2, value);
                stepVramAddress();
                return;
            case 0x2119:
                ppu.writeVram((vramAddress & 0x7FFF) * 2 + 1, value);
                stepVramAddress();
                return;
            case 0x2121:
                cgramAddress = value;
                cgramLatch = -1;
                return;
            case 0x2122: {
                if (cgramLatch < 0) {
                    cgramLatch = value;
                } else {
                    ppu.writeCgram(cgramAddress, cgramLatch | (value << 8));
                    cgramAddress = (cgramAddress + 1) & 0xFF;
                    cgramLatch = -1;
                }
                return;
            }
            case 0x212C:
                regs[offset] = value;
                ppu.mainScreen = value;
                return;
            case 0x212D:
                regs[offset] = value;
                ppu.subScreen = value;
                return;
            case 0x211A: case 0x211B: case 0x211C: case 0x211D:
            case 0x211E: case 0x211F: case 0x2120: {
                int index = offset - 0x211A;
                if (m7Latch < 0) {
                    m7Latch = value;
                    return;
                }
                int word = m7Latch | (value << 8);
                m7Latch = -1;
                if (index < 4) {
                    ppu.m7[index] = (short) word;                 // A, B, C, D (signed 16-bit)
                } else {
                    ppu.m7[index] = (short) (word & 0x1FFF);      // X, Y (13-bit signed)
                }
                return;
            }
            case 0x2133:
                regs[offset] = value;
                if ((value & 0x80) != 0) {
                    // external sync: ignore
                }
                return;
            case 0x2180:
                ram[(regs[0x2181] | (regs[0x2182] << 8) | (regs[0x2183] << 16)) & 0x1FFFF] = (byte) value;
                {
                    int next = ((regs[0x2181] | (regs[0x2182] << 8) | (regs[0x2183] << 16)) + 1) & 0x1FFFF;
                    regs[0x2181] = next & 0xFF;
                    regs[0x2182] = (next >>> 8) & 0xFF;
                    regs[0x2183] = (next >>> 16) & 0xFF;
                }
                return;
            case 0x2181: case 0x2182: case 0x2183:
                regs[offset] = value;
                return;
            case 0x4200:
                regs[offset] = value;
                return;
            case 0x4016:
                regs[offset] = value;
                if ((value & 1) != 0) {
                    joyShift = joypad[0];
                }
                return;
            case 0x420B:
                regs[offset] = value;
                runDma(value);
                return;
            case 0x420C:
                regs[offset] = value;
                for (int i = 0; i < 8; i++) {
                    if ((value & (1 << i)) != 0) {
                        regs[0x4300 + i * 0x10 + 0x0A] = 0;   // HDMA line counter
                        hdmaIndirect[i] = -1;
                        hdmaActive[i] = true;
                    } else {
                        hdmaActive[i] = false;
                    }
                }
                return;
            case 0x2140: case 0x2141:
                if (offset == 0x2140) {
                    apuPort0 = value;
                } else {
                    apuPort1 = value;
                }
                return;
            default:
                regs[offset] = value;
        }
    }

    // --------------------------------------------------------------- DMA

    private final boolean[] hdmaActive = new boolean[8];
    private final int[] hdmaIndirect = new int[8];

    private void runDma(int mask) {
        for (int channel = 0; channel < 8; channel++) {
            if ((mask & (1 << channel)) == 0) {
                continue;
            }
            int base = 0x4300 + channel * 0x10;
            int control = regs[base];
            int bOffset = regs[base + 1] & 0xFF;
            int aAddress = regs[base + 2] | (regs[base + 3] << 8);
            int aBank = regs[base + 4] & 0xFF;
            int size = regs[base + 5] | (regs[base + 6] << 8);
            if (size == 0) {
                size = 0x10000;
            }
            boolean fromB = (control & 0x80) != 0;
            int mode = control & 0x07;
            int bytesPerUnit = mode == 0 ? 1 : (mode == 1 || mode == 2 || mode == 6) ? 2 : 4;
            boolean bAdvances = mode >= 2;
            int port = bOffset;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < bytesPerUnit; j++) {
                    int b = 0x2100 + ((port + j) & 0xFF);
                    int a = ((aBank << 16) | ((aAddress + i * bytesPerUnit + j) & 0xFFFF)) & 0xFFFFFF;
                    if (fromB) {
                        write(a, readIo(b));
                    } else {
                        writeIo(b, read(a));
                    }
                }
                if (bAdvances) {
                    port = (port + bytesPerUnit) & 0xFF;
                }
            }
            int end = aAddress + size * bytesPerUnit;
            regs[base + 2] = end & 0xFF;
            regs[base + 3] = (end >> 8) & 0xFF;
            regs[base + 5] = 0;
            regs[base + 6] = 0;
        }
    }

    private void runHdma(int scanline) {
        int enabled = regs[0x420C];
        if (enabled == 0) {
            return;
        }
        for (int channel = 0; channel < 8; channel++) {
            if ((enabled & (1 << channel)) == 0) {
                continue;
            }
            int base = 0x4300 + channel * 0x10;
            int control = regs[base];
            int bAddress = regs[base + 1];
            int tableAddress = regs[base + 7] | (regs[base + 8] << 8);
            int tableBank = regs[base + 9];
            int indirectBank = regs[base + 0x0A];
            int count = regs[base + 0x0B];
            if (count == 0) {
                // fetch a new table entry
                int entry = (tableBank << 16) | tableAddress;
                int value = read(entry);
                if (value == 0) {
                    continue;   // end of table
                }
                count = value & 0x7F;
                boolean indirect = (value & 0x80) == 0;
                tableAddress = (tableAddress + 1) & 0xFFFF;
                regs[base + 7] = tableAddress & 0xFF;
                regs[base + 8] = (tableAddress >> 8) & 0xFF;
                if (indirect) {
                    int low = read((tableBank << 16) | tableAddress);
                    int high = read((tableBank << 16) | ((tableAddress + 1) & 0xFFFF));
                    regs[base + 0x0B] = count;
                    regs[0x4300 + channel * 0x10 + 0x0A] = indirectBank;
                    hdmaIndirect[channel] = low | (high << 8) | (indirectBank << 16);
                } else {
                    hdmaIndirect[channel] = -1;
                    regs[0x4300 + channel * 0x10 + 0x0A] = tableBank;
                }
            }
            if (count == 0) {
                continue;
            }
            int mode = control & 0x07;
            int aAddress = hdmaIndirect[channel];
            if (aAddress < 0) {
                aAddress = (tableBank << 16) | (regs[base + 7] | (regs[base + 8] << 8));
                aAddress = ((aAddress + 0) & 0xFFFFFF);
            }
            int bytes = mode == 0 ? 1 : mode == 1 || mode == 2 || mode == 3 ? 2 : mode == 4 ? 4 : 2;
            if (mode == 5 || mode == 6 || mode == 7) {
                bytes = 1;
            }
            for (int i = 0; i < bytes; i++) {
                int b = 0x2100 + ((bAddress + (mode == 3 || mode == 7 ? (i * 2) % 4 : 0)) & 0xFF);
                writeIo(b & 0xFFFF, read((aAddress + i) & 0xFFFFFF));
            }
            if (mode >= 2 && mode <= 6) {
                // extra bytes already covered above
            }
            if (hdmaIndirect[channel] >= 0) {
                hdmaIndirect[channel] = (hdmaIndirect[channel] + bytes) & 0xFFFFFF;
            } else {
                int address = (regs[0x4300 + channel * 0x10 + 0x0A] << 16)
                        | (regs[base + 7] | (regs[base + 8] << 8));
                address += bytes;
                regs[base + 7] = address & 0xFF;
                regs[base + 8] = (address >> 8) & 0xFF;
            }
            regs[0x4300 + channel * 0x10 + 0x0B] = count - 1;
        }
    }

    // ------------------------------------------------------------- running

    void stepFrame() {
        for (line = 0; line < 262; line++) {
            if (line == 225) {
                regs[0x4210] |= 0x80;
                latchJoypad();
                if ((regs[0x4200] & 0x80) != 0) {
                    interrupt(0xFFEA, false);
                }
            }
            if (line == 224) {
                regs[0x4210] |= 0x80;
            }
            if ((regs[0x4200] & 0x20) != 0) {
                int vtime = regs[0x4209] | (regs[0x420A] << 8);
                if (line == vtime) {
                    regs[0x4211] |= 0x80;
                    if ((cpu.p & SnesCpu.I) == 0) {
                        interrupt(0xFFEE, true);
                    }
                }
            }
            runHdma(line);
            int budget = 0;
            while (budget < CYCLES_PER_LINE) {
                budget += cpu.step();
                totalCycles += 1;
            }
            if (line < HEIGHT) {
                ppu.renderLine(line, framebuffer);
            }
        }
        frame++;
    }

    private void latchJoypad() {
        for (int i = 0; i < 4; i++) {
            joypadLatched[i] = joypad[i];
        }
        regs[0x4210] |= 0x80;
    }

    private void interrupt(int vector, boolean irq) {
        if (irq && (cpu.p & SnesCpu.I) != 0) {
            return;
        }
        cpu.pushInstructionStateForInterrupt();
        cpu.pbr = 0;
        cpu.pc = read(vector) | (read(vector + 1) << 8);
        cpu.p |= SnesCpu.I;
    }

    void setButtons(int mask) {
        // SNES auto-joypad layout: B Y Select Start Up Down Left Right A X L R
        int value = 0;
        if ((mask & 0x0001) != 0) value |= 0x8000;   // A
        if ((mask & 0x0002) != 0) value |= 0x0080;   // B
        if ((mask & 0x0004) != 0) value |= 0x2000;   // Select
        if ((mask & 0x0008) != 0) value |= 0x1000;   // Start
        if ((mask & 0x0010) != 0) value |= 0x0800;   // Up
        if ((mask & 0x0020) != 0) value |= 0x0400;   // Down
        if ((mask & 0x0040) != 0) value |= 0x0200;   // Left
        if ((mask & 0x0080) != 0) value |= 0x0100;   // Right
        if ((mask & 0x0100) != 0) value |= 0x4000;   // X
        if ((mask & 0x0200) != 0) value |= 0x0040;   // Y
        if ((mask & 0x0400) != 0) value |= 0x0020;   // L
        if ((mask & 0x0800) != 0) value |= 0x0010;   // R
        joypad[0] = value;
    }

    /** First few tilemap words, so a blank screen can be told from a blank tilemap. */
    private String mapSnippet() {
        int base = (ppu.bgsc[0] & 0xFC) << 8;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            int address = (base + i * 2) & 0xFFFF;
            int value = (ppu.vram[address] & 0xFF) | ((ppu.vram[(address + 1) & 0xFFFF] & 0xFF) << 8);
            sb.append(Integer.toHexString(value)).append(' ');
        }
        return sb.toString();
    }

    // --------------------------------------------------------- save states

    void saveState(DataOutputStream out) throws IOException {
        out.writeInt(ram.length);
        out.write(ram);
        out.writeInt(sram.length);
        out.write(sram);
        out.write(ppu.vram);
        for (int i = 0; i < 256; i++) {
            out.writeShort(ppu.cgram[i]);
        }
        out.write(ppu.oam);
        out.writeInt(regs.length);
        for (int value : regs) {
            out.writeInt(value);
        }
        out.writeInt(cpu.a);
        out.writeInt(cpu.x);
        out.writeInt(cpu.y);
        out.writeInt(cpu.sp);
        out.writeInt(cpu.d);
        out.writeInt(cpu.dbr);
        out.writeInt(cpu.pbr);
        out.writeInt(cpu.pc);
        out.writeInt(cpu.p);
        out.writeBoolean(cpu.e);
        out.writeInt(vramAddress);
        out.writeInt(vramIncrement);
        out.writeInt(cgramAddress);
        out.writeInt(oamAddress);
        out.writeInt(frame);
        out.writeInt(line);
    }

    void loadState(DataInputStream in) throws IOException {
        int ramLength = in.readInt();
        if (ramLength != ram.length) {
            throw new IOException("save state does not match this machine");
        }
        in.readFully(ram);
        if (in.readInt() != sram.length) {
            throw new IOException("save state has an invalid SRAM size");
        }
        in.readFully(sram);
        in.readFully(ppu.vram);
        for (int i = 0; i < 256; i++) {
            ppu.cgram[i] = in.readShort() & 0x7FFF;
        }
        in.readFully(ppu.oam);
        int regCount = in.readInt();
        if (regCount != regs.length) {
            throw new IOException("save state has an invalid register count");
        }
        for (int i = 0; i < regs.length; i++) {
            regs[i] = in.readInt();
        }
        cpu.a = in.readInt();
        cpu.x = in.readInt();
        cpu.y = in.readInt();
        cpu.sp = in.readInt();
        cpu.d = in.readInt();
        cpu.dbr = in.readInt();
        cpu.pbr = in.readInt();
        cpu.pc = in.readInt();
        cpu.p = in.readInt();
        cpu.e = in.readBoolean();
        vramAddress = in.readInt();
        vramIncrement = in.readInt();
        cgramAddress = in.readInt();
        oamAddress = in.readInt();
        frame = in.readInt();
        line = in.readInt();
        ppu.inidisp = regs[0x2100];
        ppu.objsel = regs[0x2101];
        ppu.bgMode = regs[0x2105] & 7;
        ppu.mosaic = regs[0x2106];
        for (int i = 0; i < 4; i++) {
            ppu.bgsc[i] = regs[0x2107 + i];
        }
        ppu.mainScreen = regs[0x212C];
    }
}
