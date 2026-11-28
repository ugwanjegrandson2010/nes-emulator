package emu.gba;

import emu.nds.NdsCpu;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Game Boy Advance machine: ARM7TDMI bus, I/O, timers, DMA, interrupts and the frame loop.
 *
 * <p>The CPU core is shared with the Nintendo DS implementation (both are ARM7TDMI-class cores),
 * which is the whole reason a third system was cheap to add to this hub.
 */
public final class GbaMachine implements NdsCpu.Bus {

    static final int WIDTH = 240;
    static final int HEIGHT = 160;
    static final int LINES = 228;
    static final int CYCLES_PER_LINE = 1232;

    final byte[] bios = new byte[0x4000];
    final byte[] ewram = new byte[0x40000];
    final byte[] iwram = new byte[0x8000];
    final byte[] sram = new byte[0x10000];
    byte[] rom = new byte[0x100];
    final GbaPpu ppu = new GbaPpu();

    final NdsCpu cpu = new NdsCpu(this, false);

    private final byte[] io = new byte[0x400];

    private int ie;
    private int iflag;
    private int ime;
    private int waitcnt;
    private int keyinput = 0x03FF;
    private int keycnt;

    private final int[] timerCounter = new int[4];
    private final int[] timerReload = new int[4];
    private final int[] timerControl = new int[4];
    private final long[] timerAccumulator = new long[4];

    private final int[] dmaSource = new int[4];
    private final int[] dmaDest = new int[4];
    private final int[] dmaCount = new int[4];
    private final int[] dmaControl = new int[4];

    private int frameCount;
    private int haltedUntilIrq;

    GbaMachine() {
        installIrqHandler();
        reset();
    }

    public NdsCpu cpu() {
        return cpu;
    }

    /** What the real BIOS leaves behind before it jumps into the cartridge. */
    private void boot() {
        cpu.reset(0x08000000);
        cpu.writeCpsr(0x000000D3);   // SVC, IRQ and FIQ masked
        cpu.r[13] = 0x03007FE0;
        cpu.writeCpsr(0x000000D2);   // IRQ
        cpu.r[13] = 0x03007FA0;
        cpu.writeCpsr(0x000000DF);   // System mode, which is where cartridges start
        cpu.r[13] = 0x03007F00;
        cpu.r[15] = 0x08000000;
    }

    void reset() {
        java.util.Arrays.fill(ewram, (byte) 0);
        java.util.Arrays.fill(iwram, (byte) 0);
        java.util.Arrays.fill(io, (byte) 0);
        java.util.Arrays.fill(timerCounter, 0);
        java.util.Arrays.fill(timerReload, 0);
        java.util.Arrays.fill(timerControl, 0);
        java.util.Arrays.fill(timerAccumulator, 0);
        java.util.Arrays.fill(dmaSource, 0);
        java.util.Arrays.fill(dmaDest, 0);
        java.util.Arrays.fill(dmaCount, 0);
        java.util.Arrays.fill(dmaControl, 0);
        ie = 0;
        iflag = 0;
        ime = 0;
        waitcnt = 0;
        keyinput = 0x03FF;
        keycnt = 0;
        frameCount = 0;
        haltedUntilIrq = 0;
        ppu.reset();
        cpu.reset(0x08000000);
    }

    void restart() {
        reset();
        boot();
    }

    void load(byte[] image) {
        rom = new byte[Math.max(0x200, image.length)];
        System.arraycopy(image, 0, rom, 0, Math.min(image.length, rom.length));
        reset();
        boot();
    }

    private int readRomInt(int offset) {
        if (offset + 3 >= rom.length) {
            return 0;
        }
        return (rom[offset] & 0xFF) | ((rom[offset + 1] & 0xFF) << 8)
                | ((rom[offset + 2] & 0xFF) << 16) | ((rom[offset + 3] & 0xFF) << 24);
    }

    // ---------------------------------------------------------------- frame loop

    void stepFrame() {
        ppu.beginFrame();
        for (int line = 0; line < LINES; line++) {
            ppu.vcount = line;
            if (line < HEIGHT) {
                ppu.renderLine(line);
            }
            if (line == HEIGHT) {
                ppu.startVBlank();
                iflag |= 1;
                if ((io16(0x04) & 0x0008) != 0) {
                    iflag |= 1;
                }
                startDma(1);
            } else if (line < HEIGHT && (io16(0x04) & 0x0010) != 0) {
                iflag |= 2;
            }
            if (line == (io16(0x04) >>> 8)) {
                iflag |= 4;
            }
            runCycles(CYCLES_PER_LINE);
        }
        frameCount++;
    }

    private void runCycles(int cycles) {
        int budget = cycles;
        int guard = 0;
        while (budget > 0 && guard++ < 200000) {
            if (haltedUntilIrq > 0) {
                if ((ie & iflag & haltedUntilIrq) != 0) {
                    haltedUntilIrq = 0;
                } else {
                    tick(4);
                    budget -= 4;
                    continue;
                }
            }
            int used = cpu.step();
            budget -= used;
            tick(used);
        }
    }

    private void tick(int cycles) {
        for (int i = 0; i < 4; i++) {
            int control = timerControl[i];
            if ((control & 0x0080) == 0) {
                continue;
            }
            boolean cascade = (control & 0x0004) != 0 && i > 0;
            if (cascade) {
                continue;
            }
            int prescaler = new int[]{1, 64, 256, 1024}[control & 3];
            timerAccumulator[i] += cycles;
            while (timerAccumulator[i] >= prescaler) {
                timerAccumulator[i] -= prescaler;
                timerCounter[i] = (timerCounter[i] + 1) & 0xFFFF;
                if (timerCounter[i] == 0) {
                    timerCounter[i] = timerReload[i] & 0xFFFF;
                    onTimerOverflow(i);
                }
            }
        }
    }

    private void onTimerOverflow(int index) {
        iflag |= 1 << (3 + index);
        for (int i = index + 1; i < 4; i++) {
            if ((timerControl[i] & 0x0084) == 0x0084) {
                timerCounter[i] = (timerCounter[i] + 1) & 0xFFFF;
                if (timerCounter[i] == 0) {
                    timerCounter[i] = timerReload[i] & 0xFFFF;
                    onTimerOverflow(i);
                }
            }
        }
    }

    // ---------------------------------------------------------------- interrupts

    private void installIrqHandler() {
        // ARM trampoline at the vector: save, call the user handler, restore and return.
        int[] code = {
                0xE92D400F,   // stmfd sp!, {r0-r3,lr}
                0xE59F000C,   // ldr   r0,[pc,#12]
                0xE5900000,   // ldr   r0,[r0]
                0xE12FFF30,   // blx   r0
                0xE8BD400F,   // ldmfd sp!, {r0-r3,lr}
                0xE25EF004,   // subs  pc, lr, #4
                0xE1A00000,   // nop
                0x03007FFC,   // user handler pointer
        };
        for (int i = 0; i < code.length; i++) {
            int word = code[i];
            int base = 0x18 + i * 4;
            bios[base] = (byte) word;
            bios[base + 1] = (byte) (word >> 8);
            bios[base + 2] = (byte) (word >> 16);
            bios[base + 3] = (byte) (word >> 24);
        }
    }

    @Override
    public boolean irqAsserted() {
        if (ime == 0 || (ie & iflag & 0x3FFF) == 0) {
            return false;
        }
        // No handler installed yet: nothing to call, so keep the interrupt pending instead of
        // jumping into the vector and running off into unmapped memory.
        return read32(0x03007FFC) != 0;
    }

    // ---------------------------------------------------------------- CPU bus

    @Override
    public int read32(int address) {
        if (address >= 0x00000000 && address < 0x00004000) {
            return readBios(address);
        }
        if (address >= 0x02000000 && address < 0x02040000) {
            return readWord(ewram, address & 0x3FFFF);
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            return readWord(iwram, address & 0x7FFF);
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            return readIo(address & 0x3FF, 4);
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            int index = (address - 0x05000000) >> 1;
            return ppu.paletteHalf(index) | (ppu.paletteHalf(index + 1) << 16);
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            return readWord(ppu.vram, address & 0x1FFFF);
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            int index = (address - 0x07000000) >> 1;
            return ppu.oamHalf(index) | (ppu.oamHalf(index + 1) << 16);
        }
        if (address >= 0x08000000 && address < 0x0E000000) {
            return readWord(rom, address & 0x01FFFFFF);
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            return readWord(sram, address & 0xFFFF);
        }
        return 0;
    }

    @Override
    public int read16(int address) {
        if (address >= 0x00000000 && address < 0x00004000) {
            int word = readBios(address & ~1);
            return (address & 1) == 0 ? word & 0xFFFF : (word >>> 16) & 0xFFFF;
        }
        if (address >= 0x02000000 && address < 0x02040000) {
            return readHalf(ewram, address & 0x3FFFF);
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            return readHalf(iwram, address & 0x7FFF);
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            return readIo(address & 0x3FF, 2);
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            return ppu.paletteHalf((address - 0x05000000) >> 1);
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            return readHalf(ppu.vram, address & 0x1FFFF);
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            return ppu.oamHalf((address - 0x07000000) >> 1);
        }
        if (address >= 0x08000000 && address < 0x0E000000) {
            return readHalf(rom, address & 0x01FFFFFF);
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            return readHalf(sram, address & 0xFFFF);
        }
        return 0;
    }

    @Override
    public int read8(int address) {
        if (address >= 0x00000000 && address < 0x00004000) {
            return readBios(address) & 0xFF;
        }
        if (address >= 0x02000000 && address < 0x02040000) {
            return ewram[address & 0x3FFFF] & 0xFF;
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            return iwram[address & 0x7FFF] & 0xFF;
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            return readIo(address & 0x3FF, 1);
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            int index = (address - 0x05000000) >> 1;
            int half = ppu.paletteHalf(index);
            return (address & 1) == 0 ? half & 0xFF : (half >> 8) & 0xFF;
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            return ppu.vram[address & 0x1FFFF] & 0xFF;
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            int index = (address - 0x07000000) >> 1;
            int half = ppu.oamHalf(index);
            return (address & 1) == 0 ? half & 0xFF : (half >> 8) & 0xFF;
        }
        if (address >= 0x08000000 && address < 0x0E000000) {
            int offset = address & 0x01FFFFFF;
            return offset < rom.length ? rom[offset] & 0xFF : 0;
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            return sram[address & 0xFFFF] & 0xFF;
        }
        return 0;
    }

    private int readBios(int address) {
        int offset = address & 0x3FFF;
        return (bios[offset] & 0xFF) | ((bios[(offset + 1) & 0x3FFF] & 0xFF) << 8)
                | ((bios[(offset + 2) & 0x3FFF] & 0xFF) << 16)
                | ((bios[(offset + 3) & 0x3FFF] & 0xFF) << 24);
    }

    @Override
    public void write32(int address, int value) {
        if (address >= 0x02000000 && address < 0x02040000) {
            writeWord(ewram, address & 0x3FFFF, value);
            return;
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            writeWord(iwram, address & 0x7FFF, value);
            return;
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            writeIo(address & 0x3FF, value, 4);
            return;
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            int index = (address - 0x05000000) >> 1;
            ppu.writePalette(index, value & 0xFFFF, true);
            ppu.writePalette(index + 1, (value >>> 16) & 0xFFFF, true);
            return;
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            writeWord(ppu.vram, address & 0x1FFFF, value);
            return;
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            int index = (address - 0x07000000) >> 1;
            ppu.writeOam(index, value & 0xFFFF);
            ppu.writeOam(index + 1, (value >>> 16) & 0xFFFF);
            return;
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            writeWord(sram, address & 0xFFFF, value);
        }
    }

    @Override
    public void write16(int address, int value) {
        if (address >= 0x02000000 && address < 0x02040000) {
            writeHalf(ewram, address & 0x3FFFF, value);
            return;
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            writeHalf(iwram, address & 0x7FFF, value);
            return;
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            writeIo(address & 0x3FF, value, 2);
            return;
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            ppu.writePalette((address - 0x05000000) >> 1, value, false);
            return;
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            writeHalf(ppu.vram, address & 0x1FFFF, value);
            return;
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            ppu.writeOam((address - 0x07000000) >> 1, value);
            return;
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            writeHalf(sram, address & 0xFFFF, value);
        }
    }

    @Override
    public void write8(int address, int value) {
        if (address >= 0x02000000 && address < 0x02040000) {
            ewram[address & 0x3FFFF] = (byte) value;
            return;
        }
        if (address >= 0x03000000 && address < 0x03008000) {
            iwram[address & 0x7FFF] = (byte) value;
            return;
        }
        if (address >= 0x04000000 && address < 0x04000400) {
            writeIo(address & 0x3FF, value, 1);
            return;
        }
        if (address >= 0x05000000 && address < 0x05000400) {
            int index = (address - 0x05000000) >> 1;
            int half = ppu.paletteHalf(index);
            if ((address & 1) == 0) {
                half = (half & 0xFF00) | (value & 0xFF);
            } else {
                half = (half & 0x00FF) | ((value & 0xFF) << 8);
            }
            ppu.writePalette(index, half, false);
            return;
        }
        if (address >= 0x06000000 && address < 0x06018000) {
            ppu.vram[address & 0x1FFFF] = (byte) value;
            return;
        }
        if (address >= 0x07000000 && address < 0x07000400) {
            int index = (address - 0x07000000) >> 1;
            int half = ppu.oamHalf(index);
            if ((address & 1) == 0) {
                half = (half & 0xFF00) | (value & 0xFF);
            } else {
                half = (half & 0x00FF) | ((value & 0xFF) << 8);
            }
            ppu.writeOam(index, half);
            return;
        }
        if (address >= 0x0E000000 && address < 0x0E010000) {
            sram[address & 0xFFFF] = (byte) value;
        }
    }

    @Override
    public int cp15Read(int cn, int cm, int opcode2) {
        return 0;   // ARM7TDMI has no CP15
    }

    @Override
    public void cp15Write(int cn, int cm, int opcode2, int value) {
    }

    private static int readHalf(byte[] memory, int offset) {
        if (offset < 0 || offset + 1 >= memory.length) {
            return 0;
        }
        return (memory[offset] & 0xFF) | ((memory[offset + 1] & 0xFF) << 8);
    }

    private static int readWord(byte[] memory, int offset) {
        if (offset < 0 || offset + 3 >= memory.length) {
            return 0;
        }
        return (memory[offset] & 0xFF) | ((memory[offset + 1] & 0xFF) << 8)
                | ((memory[offset + 2] & 0xFF) << 16) | ((memory[offset + 3] & 0xFF) << 24);
    }

    private static void writeHalf(byte[] memory, int offset, int value) {
        if (offset < 0 || offset + 1 >= memory.length) {
            return;
        }
        memory[offset] = (byte) value;
        memory[offset + 1] = (byte) (value >> 8);
    }

    private static void writeWord(byte[] memory, int offset, int value) {
        if (offset < 0 || offset + 3 >= memory.length) {
            return;
        }
        memory[offset] = (byte) value;
        memory[offset + 1] = (byte) (value >> 8);
        memory[offset + 2] = (byte) (value >> 16);
        memory[offset + 3] = (byte) (value >> 24);
    }

    // ---------------------------------------------------------------- I/O

    private int io16(int offset) {
        if (offset < 0 || offset + 1 >= io.length) {
            return 0;
        }
        return (io[offset] & 0xFF) | ((io[offset + 1] & 0xFF) << 8);
    }

    private void setIo16(int offset, int value) {
        if (offset < 0 || offset + 1 >= io.length) {
            return;
        }
        io[offset] = (byte) value;
        io[offset + 1] = (byte) (value >> 8);
    }

    private int readIo(int offset, int size) {
        if (offset < 0 || offset >= io.length) {
            return 0;
        }
        if (size == 1) {
            int half = readIo(offset & ~1, 2);
            return (offset & 1) == 0 ? half & 0xFF : (half >> 8) & 0xFF;
        }
        switch (offset) {
            case 0x130:
                return keyinput & 0x03FF;
            case 0x132:
                return keycnt;
            case 0x200:
                return ie;
            case 0x202:
                return iflag & 0x3FFF;
            case 0x204:
                return waitcnt;
            case 0x208:
                return ime;
            default:
                break;
        }
        if (offset >= 0x100 && offset < 0x110) {
            int index = (offset - 0x100) >> 2;
            int which = offset & 3;
            if (which == 0) {
                return timerCounter[index] & 0xFFFF;
            }
            if (which == 2) {
                return timerControl[index] & 0xFFFF;
            }
            return 0;
        }
        if (offset >= 0xB0 && offset < 0xE0) {
            int index = (offset - 0xB0) / 12;
            int which = (offset - 0xB0) % 12;
            switch (which) {
                case 0: return dmaSource[index] & 0xFFFF;
                case 2: return (dmaSource[index] >>> 16) & 0xFFFF;
                case 4: return dmaDest[index] & 0xFFFF;
                case 6: return (dmaDest[index] >>> 16) & 0xFFFF;
                case 8: return dmaCount[index] & 0xFFFF;
                case 10: return dmaControl[index] & 0xFFFF;
                default: return 0;
            }
        }
        if (offset < 0x60) {
            return ppu.read16(offset);
        }
        return io16(offset);
    }

    private void writeIo(int offset, int value, int size) {
        if (offset < 0 || offset >= io.length) {
            return;
        }
        if (size == 1) {
            int half = readIo(offset & ~1, 2);
            if ((offset & 1) == 0) {
                half = (half & 0xFF00) | (value & 0xFF);
            } else {
                half = (half & 0x00FF) | ((value & 0xFF) << 8);
            }
            writeIo(offset & ~1, half, 2);
            return;
        }
        if (size == 4) {
            writeIo(offset, value & 0xFFFF, 2);
            writeIo(offset + 2, (value >>> 16) & 0xFFFF, 2);
            return;
        }
        switch (offset) {
            case 0x200:
                ie = value & 0x3FFF;
                return;
            case 0x202:
                // writing a 1 clears the flag
                iflag &= ~(value & 0x3FFF);
                return;
            case 0x204:
                waitcnt = value & 0xFFFF;
                return;
            case 0x208:
                ime = value & 1;
                return;
            case 0x130:
                keycnt = value & 0xFFFF;
                return;
            default:
                break;
        }
        if (offset >= 0x100 && offset < 0x110) {
            int index = (offset - 0x100) >> 2;
            int which = offset & 3;
            if (which == 0) {
                timerReload[index] = value & 0xFFFF;
                timerCounter[index] = value & 0xFFFF;
                return;
            }
            if (which == 2) {
                boolean wasEnabled = (timerControl[index] & 0x0080) != 0;
                timerControl[index] = value & 0xFFFF;
                timerAccumulator[index] = 0;
                if (!wasEnabled && (value & 0x0080) != 0) {
                    timerCounter[index] = timerReload[index] & 0xFFFF;
                }
                return;
            }
            return;
        }
        if (offset >= 0xB0 && offset < 0xE0) {
            int index = (offset - 0xB0) / 12;
            int which = (offset - 0xB0) % 12;
            switch (which) {
                case 0: dmaSource[index] = (dmaSource[index] & 0xFFFF0000) | (value & 0xFFFF); return;
                case 2: dmaSource[index] = (dmaSource[index] & 0xFFFF) | ((value & 0xFFFF) << 16); return;
                case 4: dmaDest[index] = (dmaDest[index] & 0xFFFF0000) | (value & 0xFFFF); return;
                case 6: dmaDest[index] = (dmaDest[index] & 0xFFFF) | ((value & 0xFFFF) << 16); return;
                case 8: dmaCount[index] = value & 0xFFFF; return;
                case 10:
                    dmaControl[index] = value & 0xFFFF;
                    if ((value & 0x8000) != 0) {
                        int timing = (value >> 12) & 3;
                        if (timing == 0) {
                            runDma(index);
                        }
                    }
                    return;
                default:
                    return;
            }
        }
        if (offset < 0x60) {
            ppu.write16(offset, value);
            return;
        }
        setIo16(offset, value);
    }

    private void startDma(int timing) {
        for (int i = 0; i < 4; i++) {
            int control = dmaControl[i];
            if ((control & 0x8000) == 0 || ((control >> 12) & 3) != timing) {
                continue;
            }
            runDma(i);
        }
    }

    private void runDma(int index) {
        int control = dmaControl[index];
        if ((control & 0x8000) == 0) {
            return;
        }
        int count = dmaCount[index];
        if (count == 0) {
            count = index == 3 ? 0x10000 : 0x4000;
        }
        boolean word = (control & 0x0400) != 0;
        int unit = word ? 4 : 2;
        int source = dmaSource[index];
        int dest = dmaDest[index];
        int destMode = (control >> 5) & 3;
        for (int i = 0; i < count; i++) {
            if (word) {
                write16(dest, read16(source));
                write16(dest + 2, read16(source + 2));
            } else {
                write16(dest, read16(source));
            }
            source += unit;
            switch (destMode) {
                case 0:
                    dest += unit;
                    break;
                case 1:
                    break;
                case 2:
                    dest -= unit;
                    break;
                default:
                    dest += unit;
                    break;
            }
        }
        dmaSource[index] = source;
        dmaDest[index] = dest;
        dmaCount[index] = (control & 0x0200) != 0 ? dmaCount[index] : 0;
        if ((control & 0x4000) != 0) {
            iflag |= 1 << (8 + index);
        }
        if ((control & 0x0200) == 0) {
            dmaControl[index] &= ~0x8000;
        }
    }

    // ---------------------------------------------------------------- BIOS calls

    @Override
    public void swi(NdsCpu core, int comment) {
        int r0 = core.r[0];
        int r1 = core.r[1];
        int r2 = core.r[2];
        switch (comment) {
            case 0x00:  // SoftReset
                reset();
                return;
            case 0x01:  // RegisterRamReset
                if ((r0 & 1) != 0) {
                    java.util.Arrays.fill(ewram, (byte) 0);
                }
                if ((r0 & 2) != 0) {
                    java.util.Arrays.fill(iwram, (byte) 0);
                }
                if ((r0 & 4) != 0) {
                    java.util.Arrays.fill(ppu.palette, (short) 0);
                }
                if ((r0 & 8) != 0) {
                    java.util.Arrays.fill(ppu.vram, (byte) 0);
                }
                return;
            case 0x02:  // Halt
                haltedUntilIrq = 0x3FFF;
                return;
            case 0x03:  // Stop
                haltedUntilIrq = 0x3FFF;
                return;
            case 0x04:  // IntrWait
                iflag &= ~r1;
                haltedUntilIrq = r1 != 0 ? r1 : 0x3FFF;
                return;
            case 0x05:  // VBlankIntrWait
                iflag &= ~1;
                haltedUntilIrq = 1;
                return;
            case 0x06: {  // Div
                int numerator = r0;
                int denominator = r1;
                if (denominator == 0) {
                    core.r[0] = 0;
                    core.r[1] = 0;
                    core.r[3] = 0;
                    return;
                }
                int quotient = numerator / denominator;
                core.r[0] = quotient;
                core.r[1] = numerator % denominator;
                core.r[3] = Math.abs(quotient);
                return;
            }
            case 0x07: {  // DivArm
                int divisor = r0;
                int dividend = r1;
                if (divisor == 0) {
                    core.r[0] = 0;
                    core.r[1] = 0;
                    core.r[3] = 0;
                    return;
                }
                int quotient = dividend / divisor;
                core.r[0] = quotient;
                core.r[1] = dividend % divisor;
                core.r[3] = Math.abs(quotient);
                return;
            }
            case 0x08: {  // Sqrt
                core.r[0] = (int) Math.sqrt(r0 & 0xFFFFFFFFL);
                return;
            }
            case 0x09: {  // ArcTan
                // fixed point tan^-1, angle in 0x10000 = 360 degrees
                double x = (short) r0 / 16384.0;
                core.r[0] = (int) Math.round(Math.atan(x) * 65536.0 / (2 * Math.PI));
                return;
            }
            case 0x0A: {  // ArcTan2
                double x = (short) r0 / 16384.0;
                double y = (short) r1 / 16384.0;
                double angle = Math.atan2(y, x) * 65536.0 / (2 * Math.PI);
                core.r[0] = (int) Math.round(angle) & 0xFFFF;
                return;
            }
            case 0x0B:    // CpuSet
                cpuSet(core);
                return;
            case 0x0C:    // CpuFastSet
                cpuFastSet(core);
                return;
            case 0x0E: {  // BgAffineSet
                int count = r2;
                int source = r0;
                int dest = r1;
                for (int i = 0; i < count; i++) {
                    bgAffineSet(source, dest);
                    source += 20;
                    dest += 16;
                }
                return;
            }
            case 0x0F: {  // ObjAffineSet
                int count = r1;
                int source = r0;
                int dest = r2;
                int stride = (r2 >> 24) & 0xFF;
                int step = stride == 0 ? 16 : stride;
                for (int i = 0; i < count; i++) {
                    int sx = (short) read16(source);
                    int sy = (short) read16(source + 2);
                    int rotation = (read16(source + 4) >> 8) & 0xFF;
                    double angle = rotation * 2 * Math.PI / 256.0;
                    int pa = (int) Math.round(sx * Math.cos(angle) * 0x100);
                    int pb = (int) Math.round(-sx * Math.sin(angle) * 0x100);
                    int pc = (int) Math.round(sy * Math.sin(angle) * 0x100);
                    int pd = (int) Math.round(sy * Math.cos(angle) * 0x100);
                    write16(dest + i * step, pa & 0xFFFF);
                    write16(dest + i * step + 2, pb & 0xFFFF);
                    write16(dest + i * step + 4, pc & 0xFFFF);
                    write16(dest + i * step + 6, pd & 0xFFFF);
                    source += 8;
                }
                return;
            }
            case 0x10:    // BitUnPack
                bitUnpack(r0, r1, r2);
                return;
            case 0x11:    // LZ77UnCompVram
                lz77(r0, r1, true);
                return;
            case 0x12:    // LZ77UnCompWram
                lz77(r0, r1, false);
                return;
            case 0x14:    // RlUnCompVram
                runLength(r0, r1, true);
                return;
            case 0x15:    // RlUnCompWram
                runLength(r0, r1, false);
                return;
            default:
                // unknown SWI: return instead of hanging the game on a vector we do not have
                return;
        }
    }

    private void cpuSet(NdsCpu core) {
        int source = core.r[0];
        int dest = core.r[1];
        int control = core.r[2];
        int count = control & 0x1FFFFF;
        boolean word = (control & 0x04000000) != 0;
        boolean fill = (control & 0x01000000) != 0;
        if (count == 0) {
            count = 0x200000;
        }
        int value = word ? this.read32(source) : this.read16(source);
        for (int i = 0; i < count; i++) {
            if (word) {
                this.write32(dest + i * 4, value);
            } else {
                this.write16(dest + i * 2, value);
            }
            if (!fill) {
                value = word ? this.read32(source + i * 4) : this.read16(source + i * 2);
            }
        }
    }

    private void cpuFastSet(NdsCpu core) {
        int source = core.r[0];
        int dest = core.r[1];
        int count = core.r[2];
        int words = count >> 2;
        boolean fill = (count & 0x01000000) != 0;
        int value = this.read32(source);
        for (int i = 0; i < Math.max(words, 1); i++) {
            this.write32(dest + i * 4, value);
            if (!fill) {
                value = this.read32(source + i * 4);
            }
        }
    }

    private void bgAffineSet(int source, int dest) {
        int bgx = this.read32(source);
        int bgy = this.read32(source + 4);
        int sx = (short) this.read16(source + 8);
        int sy = (short) this.read16(source + 10);
        int rotation = (this.read16(source + 12) >> 8) & 0xFF;
        int displacementX = this.read32(source + 16) - bgx;
        int displacementY = this.read32(source + 16) - bgy;
        double angle = rotation * 2 * Math.PI / 256.0;
        int pa = (int) Math.round(sx * Math.cos(angle) * 0x100);
        int pb = (int) Math.round(-sx * Math.sin(angle) * 0x100);
        int pc = (int) Math.round(sy * Math.sin(angle) * 0x100);
        int pd = (int) Math.round(sy * Math.cos(angle) * 0x100);
        this.write16(dest, pa & 0xFFFF);
        this.write16(dest + 2, pb & 0xFFFF);
        this.write16(dest + 4, pc & 0xFFFF);
        this.write16(dest + 6, pd & 0xFFFF);
        this.write32(dest + 8, (int) ((pa * displacementX + pb * displacementY) >> 8));
        this.write32(dest + 12, (int) ((pc * displacementX + pd * displacementY) >> 8));
    }

    private void bitUnpack(int source, int dest, int info) {
        int sourceLength = this.read16(info);
        int sourceBpp = this.read8(info + 2);
        int destBpp = this.read8(info + 3);
        int offset = this.read32(info + 4);
        int data = offset & 0x80000000;
        int writeOffset = offset & 0x7FFFFFFF;
        int inBits = sourceBpp;
        int outBits = destBpp;
        int count = (sourceLength * 8) / inBits;
        int out = 0;
        for (int i = 0; i < count; i++) {
            int value = readBits(source, i * inBits, inBits);
            if (data != 0) {
                value = (value + writeOffset) & ((1 << outBits) - 1);
            }
            writeBits(dest, out, outBits, value);
            out += outBits;
        }
    }

    private int readBits(int address, int bitOffset, int bits) {
        int value = 0;
        for (int i = 0; i < bits; i++) {
            int bit = bitOffset + i;
            int b = read8(address + (bit >> 3));
            value |= ((b >> (bit & 7)) & 1) << i;
        }
        return value;
    }

    private void writeBits(int address, int bitOffset, int bits, int value) {
        for (int i = 0; i < bits; i++) {
            int bit = bitOffset + i;
            int slot = address + (bit >> 3);
            int b = read8(slot);
            if (((value >> i) & 1) != 0) {
                b |= 1 << (bit & 7);
            } else {
                b &= ~(1 << (bit & 7));
            }
            write8(slot, b);
        }
    }

    private void lz77(int source, int dest, boolean vram) {
        int header = this.read32(source);
        if ((header & 0xFF) != 0x10) {
            return;
        }
        int length = (header >>> 8) & 0xFFFFFF;
        int in = source + 4;
        int out = dest;
        int written = 0;
        while (written < length) {
            int flags = read8(in++);
            for (int bit = 7; bit >= 0 && written < length; bit--) {
                if (((flags >> bit) & 1) != 0) {
                    int b0 = read8(in++);
                    int b1 = read8(in++);
                    int count = (b0 >>> 4) + 3;
                    int distance = (((b0 & 0xF) << 8) | b1) + 1;
                    for (int i = 0; i < count && written < length; i++) {
                        write8(out, read8(out - distance));
                        out++;
                        written++;
                    }
                } else {
                    write8(out, read8(in++));
                    out++;
                    written++;
                }
            }
        }
    }

    private void runLength(int source, int dest, boolean vram) {
        int header = this.read32(source);
        if ((header & 0xFF) != 0x30) {
            return;
        }
        int length = (header >>> 8) & 0xFFFFFF;
        int in = source + 4;
        int out = dest;
        int written = 0;
        while (written < length) {
            int flag = read8(in++);
            if ((flag & 0x80) != 0) {
                int count = (flag & 0x7F) + 3;
                int value = read8(in++);
                for (int i = 0; i < count && written < length; i++) {
                    write8(out++, value);
                    written++;
                }
            } else {
                int count = (flag & 0x7F) + 1;
                for (int i = 0; i < count && written < length; i++) {
                    write8(out++, read8(in++));
                    written++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- input

    void setButtons(int mask) {
        int keys = 0x03FF;
        if ((mask & 0x01) != 0) keys &= ~(1 << 0);  // A
        if ((mask & 0x02) != 0) keys &= ~(1 << 1);  // B
        if ((mask & 0x04) != 0) keys &= ~(1 << 2);  // Select
        if ((mask & 0x08) != 0) keys &= ~(1 << 3);  // Start
        if ((mask & 0x80) != 0) keys &= ~(1 << 4);  // Right
        if ((mask & 0x40) != 0) keys &= ~(1 << 5);  // Left
        if ((mask & 0x10) != 0) keys &= ~(1 << 6);  // Up
        if ((mask & 0x20) != 0) keys &= ~(1 << 7);  // Down
        if ((mask & 0x200) != 0) keys &= ~(1 << 8); // R
        if ((mask & 0x100) != 0) keys &= ~(1 << 9); // L
        keyinput = keys;
    }

    // ---------------------------------------------------------------- save states

    void saveState(DataOutputStream out) throws IOException {
        cpu.saveState(out);
        out.write(ewram);
        out.write(iwram);
        out.write(io);
        out.writeInt(ie);
        out.writeInt(iflag);
        out.writeInt(ime);
        out.writeInt(waitcnt);
        out.writeInt(keyinput);
        out.writeInt(keycnt);
        for (int i = 0; i < 4; i++) {
            out.writeInt(timerCounter[i]);
            out.writeInt(timerReload[i]);
            out.writeInt(timerControl[i]);
            out.writeLong(timerAccumulator[i]);
            out.writeInt(dmaSource[i]);
            out.writeInt(dmaDest[i]);
            out.writeInt(dmaCount[i]);
            out.writeInt(dmaControl[i]);
        }
        out.writeInt(frameCount);
        out.writeInt(haltedUntilIrq);
        ppu.saveState(out);
        out.write(sram);
    }

    void loadState(DataInputStream in, boolean hasSram) throws IOException {
        cpu.loadState(in);
        in.readFully(ewram);
        in.readFully(iwram);
        in.readFully(io);
        ie = in.readInt();
        iflag = in.readInt();
        ime = in.readInt();
        waitcnt = in.readInt();
        keyinput = in.readInt();
        keycnt = in.readInt();
        for (int i = 0; i < 4; i++) {
            timerCounter[i] = in.readInt();
            timerReload[i] = in.readInt();
            timerControl[i] = in.readInt();
            timerAccumulator[i] = in.readLong();
            dmaSource[i] = in.readInt();
            dmaDest[i] = in.readInt();
            dmaCount[i] = in.readInt();
            dmaControl[i] = in.readInt();
        }
        frameCount = in.readInt();
        haltedUntilIrq = in.readInt();
        ppu.loadState(in);
        if (hasSram) {
            in.readFully(sram);
        }
    }

    int[] framebuffer() {
        return ppu.framebuffer;
    }

    int frameCount() {
        return frameCount;
    }
}
