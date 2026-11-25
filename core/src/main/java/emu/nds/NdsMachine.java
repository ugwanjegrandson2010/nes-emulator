package emu.nds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A Nintendo DS: two ARM cores, shared RAM, the picture unit, timers, DMA, IPC and the BIOS calls
 * homebrew actually uses. Runs on a fixed clock: 59.826 fields per second, 355 dots per scanline and
 * 12 ARM9 cycles per dot.
 */
public final class NdsMachine {

    public static final int LINES = 263;
    public static final int VISIBLE_LINES = 192;
    public static final int DOTS_PER_LINE = 355;
    public static final int ARM9_CYCLES_PER_DOT = 12;

    public final NdsPpu ppu = new NdsPpu();
    public final byte[] mainRam = new byte[4 * 1024 * 1024];
    public final byte[] sharedWram = new byte[0x8000];
    public final byte[] arm7Wram = new byte[0x10000];

    public final NdsCpu cpu9;
    public final NdsCpu cpu7;
    public final NdsBus bus9;
    public final NdsBus bus7;

    public NdsCartridge cart;

    private final byte[] io = new byte[0x2000];

    private int iflags;
    private int ie9;
    private int ime9;
    private int ie7;
    private int ime7;
    private int keyInput = 0x03FF;
    private int ipcSync9;        // last value the ARM9 wrote to IPCSYNC
    private int ipcSync7;        // last value the ARM7 wrote to IPCSYNC
    private int fifoCnt9;        // IPCFIFOCNT as the ARM9 sees it
    private int fifoCnt7;        // IPCFIFOCNT as the ARM7 sees it
    private int fifoError9;      // read-from-empty error latches (bit 14)
    private int fifoError7;
    private int lastRecv9;       // last word the ARM9 read from its FIFO
    private int lastRecv7;

    private final int[] fifoIn9 = new int[16];   // words queued for the ARM9
    private final int[] fifoIn7 = new int[16];   // words queued for the ARM7
    private int fifoIn9Count;
    private int fifoIn7Count;

    private final int[] timerValue = new int[8];
    private final int[] timerReload = new int[8];
    private final int[] timerControl = new int[8];
    private final long[] timerAccumulator = new long[8];

    private final int[] dmaSource = new int[8];
    private final int[] dmaDest = new int[8];
    private final int[] dmaCount = new int[8];
    private final int[] dmaControl = new int[8];

    public long frameCount;
    private boolean vblankFlag;
    private boolean pendingRender;

    public NdsMachine(NdsCartridge cart) {
        this.cart = cart;
        this.bus9 = new NdsBus(this, true);
        this.bus7 = new NdsBus(this, false);
        this.cpu9 = new NdsCpu(bus9, true);
        this.cpu7 = new NdsCpu(bus7, false);
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(mainRam, (byte) 0);
        java.util.Arrays.fill(sharedWram, (byte) 0);
        java.util.Arrays.fill(arm7Wram, (byte) 0);
        java.util.Arrays.fill(io, (byte) 0);
        java.util.Arrays.fill(fifoIn9, 0);
        java.util.Arrays.fill(fifoIn7, 0);
        java.util.Arrays.fill(timerValue, 0);
        java.util.Arrays.fill(timerReload, 0);
        java.util.Arrays.fill(timerControl, 0);
        java.util.Arrays.fill(timerAccumulator, 0);
        java.util.Arrays.fill(dmaControl, 0);
        fifoIn9Count = 0;
        fifoIn7Count = 0;
        iflags = 0;
        ie9 = 0;
        ime9 = 0;
        ie7 = 0;
        ime7 = 0;
        keyInput = 0x03FF;
        ipcSync9 = 0;
        ipcSync7 = 0;
        fifoCnt9 = 0;
        fifoCnt7 = 0;
        fifoError9 = 0;
        fifoError7 = 0;
        lastRecv9 = 0;
        lastRecv7 = 0;
        frameCount = 0;
        vblankFlag = false;
        pendingRender = false;
        ppu.reset();

        // the BIOS leaves a copy of the cartridge header at the top of RAM for both cores
        for (int i = 0; i < NdsCartridge.HEADER_SIZE && i < cart.rom.length; i++) {
            mainRam[(0x027FFE00 - 0x02000000 + i) & 0x3FFFFF] = cart.rom[i];
            sharedWram[(0x037FFE00 - 0x03000000 + i) & 0x7FFF] = cart.rom[i];
        }

        byte[] arm9 = cart.arm9Image();
        byte[] arm7 = cart.arm7Image();
        copyInto(arm9, cart.arm9RamAddress, cart.arm9Size);
        copyInto(arm7, cart.arm7RamAddress, cart.arm7Size);

        cpu9.reset(cart.arm9Entry);
        cpu7.reset(cart.arm7Size > 0 ? cart.arm7Entry : 0);
        cpu7.halted = cart.arm7Size <= 0;
    }

    private void copyInto(byte[] image, int address, int declaredSize) {
        int length = Math.min(image.length, declaredSize <= 0 ? image.length : declaredSize);
        for (int i = 0; i < length; i++) {
            int target = address + i;
            if (target >= 0x02000000 && target < 0x03000000) {
                mainRam[target & 0x3FFFFF] = image[i];
            } else if (target >= 0x03000000 && target < 0x03800000) {
                sharedWram[target & 0x7FFF] = image[i];
            } else if (target >= 0x03800000 && target < 0x03810000) {
                arm7Wram[target & 0xFFFF] = image[i];
            }
        }
    }

    // ------------------------------------------------------------------ running

    /** Runs one field and leaves the finished picture in {@link NdsPpu#framebuffer}. */
    public void stepFrame() {
        for (int line = 0; line < LINES; line++) {
            ppu.vcount = line;
            if (line == 0) {
                vblankFlag = false;
            }
            if (line == VISIBLE_LINES) {
                onVBlankStart();
            }
            runCycles(DOTS_PER_LINE * ARM9_CYCLES_PER_DOT);
        }
        frameCount++;
    }

    private void onVBlankStart() {
        vblankFlag = true;
        iflags |= 1;
        if ((io16(0x004) & 0x0008) != 0) {
            iflags |= 1;
        }
        startDma(0, 1);
        startDma(1, 1);
        ppu.render();
    }

    /** Writing bit 13 raises IPCSYNC IRQs on the other core, which must have armed bit 14. */
    private void raiseIpcSyncIrq(boolean fromArm9) {
        int remote = fromArm9 ? ipcSync7 : ipcSync9;
        if ((remote & 0x4000) != 0) {
            iflags |= 1 << 16;
        }
    }

    private void runCycles(int arm9Cycles) {
        int budget9 = arm9Cycles;
        int budget7 = arm9Cycles / 2;
        int guard = 0;
        while (budget9 > 0 || budget7 > 0) {
            if (budget9 > 0) {
                int cycles = cpu9.step();
                budget9 -= cycles;
                tickTimers(true, cycles);
            }
            if (budget7 > 0) {
                int cycles = cpu7.step();
                budget7 -= cycles;
                tickTimers(false, cycles);
            }
            if ((++guard & 0x3FFFF) == 0) {
                break;
            }
        }
    }

    public boolean irqAsserted(boolean arm9) {
        if (arm9) {
            return ime9 != 0 && (ie9 & iflags) != 0;
        }
        return ime7 != 0 && (ie7 & iflags) != 0;
    }

    public void setButtons(int mask) {
        int keys = 0x03FF;
        if ((mask & 0x01) != 0) keys &= ~(1 << 0);  // A
        if ((mask & 0x02) != 0) keys &= ~(1 << 1);  // B
        if ((mask & 0x04) != 0) keys &= ~(1 << 2);  // Select
        if ((mask & 0x08) != 0) keys &= ~(1 << 3);  // Start
        if ((mask & 0x80) != 0) keys &= ~(1 << 4);  // Right
        if ((mask & 0x40) != 0) keys &= ~(1 << 5);  // Left
        if ((mask & 0x10) != 0) keys &= ~(1 << 6);  // Up
        if ((mask & 0x20) != 0) keys &= ~(1 << 7);  // Down
        keyInput = keys;
    }

    // ------------------------------------------------------------------ timers

    private void tickTimers(boolean arm9, int cycles) {
        int base = arm9 ? 0 : 4;
        int scale = arm9 ? 2 : 1;  // the ARM9 clock is twice the timer clock
        for (int ch = 0; ch < 4; ch++) {
            int index = base + ch;
            int control = timerControl[index];
            if ((control & 0x80) == 0) {
                continue;
            }
            int prescaler = (control & 0x04) != 0 ? 1024 : 1;
            timerAccumulator[index] += (long) cycles * scale;
            long ticks = timerAccumulator[index] / prescaler;
            if (ticks == 0) {
                continue;
            }
            timerAccumulator[index] -= ticks * prescaler;
            long value = timerValue[index] + ticks;
            while (value > 0xFFFF) {
                value -= 0x10000;
                timerValue[index] = timerReload[index] & 0xFFFF;
                if ((control & 0x40) != 0) {
                    iflags |= 1 << (3 + ch);
                }
                value = timerValue[index];
            }
            timerValue[index] = (int) value;
        }
    }

    // ------------------------------------------------------------------ DMA

    private void startDma(int cpu, int timing) {
        int base = cpu * 4;
        for (int ch = 0; ch < 4; ch++) {
            int control = dmaControl[base + ch];
            if ((control & 0x8000) == 0) {
                continue;
            }
            int channelTiming = (control >> 13) & 3;
            if (channelTiming != timing) {
                continue;
            }
            transferDma(cpu, ch);
        }
    }

    private void transferDma(int cpu, int channel) {
        int index = cpu * 4 + channel;
        int control = dmaControl[index];
        boolean words = (control & 0x0100) != 0;
        int unit = words ? 4 : 2;
        int count = dmaCount[index];
        if (count == 0) {
            count = words ? 0x2000 : 0x4000;
        }
        int destControl = (control >> 5) & 3;
        int sourceControl = (control >> 7) & 3;
        int source = dmaSource[index];
        int dest = dmaDest[index];
        NdsBus sourceBus = cpu == 0 ? bus9 : bus7;
        NdsBus destBus = cpu == 0 ? bus9 : bus7;
        for (int i = 0; i < count; i++) {
            if (words) {
                destBus.write32(dest & ~3, sourceBus.read32(source & ~3));
            } else {
                destBus.write16(dest & ~1, sourceBus.read16(source & ~1));
            }
            if (sourceControl == 1) {
                source -= unit;
            } else if (sourceControl != 2) {
                source += unit;
            }
            if (destControl == 1) {
                dest -= unit;
            } else if (destControl != 2) {
                dest += unit;
            }
        }
        dmaSource[index] = source;
        dmaDest[index] = dest;
        if ((control & 0x0200) == 0) {
            dmaControl[index] = control & ~0x8000;
        }
    }

    // ------------------------------------------------------------------ IPC

    public int readIpcReceive(boolean arm9, int address) {
        int[] queue = arm9 ? fifoIn9 : fifoIn7;
        int count = arm9 ? fifoIn9Count : fifoIn7Count;
        if (count == 0) {
            // Reading an empty queue returns the previously read word and latches the error bit.
            if (arm9) {
                fifoError9 = 0x4000;
            } else {
                fifoError7 = 0x4000;
            }
            return arm9 ? lastRecv9 : lastRecv7;
        }
        int value = queue[0];
        System.arraycopy(queue, 1, queue, 0, count - 1);
        if (arm9) {
            fifoIn9Count--;
            lastRecv9 = value;
            if ((fifoCnt7 & 0x0200) != 0) {
                // the sender's "receive not empty" IRQ (it is not empty for the ARM9 to fill)
                iflags |= 1 << 18;
            }
        } else {
            fifoIn7Count--;
            lastRecv7 = value;
            if ((fifoCnt9 & 0x0200) != 0) {
                iflags |= 1 << 18;
            }
        }
        return value;
    }

    public void writeIpcSend(boolean arm9, int value) {
        if (System.getenv("NDS_IPC") != null) {
            System.err.println("FIFO send " + (arm9 ? "9" : "7") + " = " + Integer.toHexString(value)
                    + " enabled=" + ((arm9 ? fifoCnt9 : fifoCnt7) & 0x8000));
        }
        boolean enabled = ((arm9 ? fifoCnt9 : fifoCnt7) & 0x8000) != 0;
        if (!enabled) {
            return;
        }
        int[] queue = arm9 ? fifoIn7 : fifoIn9;
        int count = arm9 ? fifoIn7Count : fifoIn9Count;
        if (count >= queue.length) {
            return;
        }
        queue[count] = value;
        if (arm9) {
            fifoIn7Count++;
            if ((fifoCnt7 & 0x0400) != 0) {
                iflags |= 1 << 17;
            }
        } else {
            fifoIn9Count++;
            if ((fifoCnt9 & 0x0400) != 0) {
                iflags |= 1 << 17;
            }
        }
    }

    // ------------------------------------------------------------------ I/O registers

    public byte[] oam(boolean arm9) {
        return arm9 ? ppu.oamMain : ppu.oamSub;
    }

    public int readIo(boolean arm9, int address, int size) {
        int offset = address & 0x1FFF;
        boolean mainEngine = offset < 0x1000;
        int engineOffset = mainEngine ? offset : offset - 0x1000;
        if (engineOffset < 0x04 || (engineOffset >= 0x08 && engineOffset < 0x70)) {
            NdsPpu.Engine engine = mainEngine ? ppu.main : ppu.sub;
            if (size == 4) {
                return engine.read32(engineOffset & ~3);
            }
            if (size == 2) {
                return engine.read16(engineOffset & ~1);
            }
            return (engine.read16(engineOffset & ~1) >> ((offset & 1) * 8)) & 0xFF;
        }
        if (!mainEngine) {
            return readGeneric(offset, size);
        }
        switch (offset) {
            case 0x004:
                return readDispStat(size, offset);
            case 0x006:
                return readVCount(size, offset);
            case 0x130:
                return readKeyInput(size, offset);
            case 0x180:
                return readIpcSync(arm9, size, offset);
            case 0x184:
                return readFifoControl(arm9, size, offset);
            case 0x208:
                return read16Bit(arm9 ? ime9 : ime7, size, offset);
            case 0x210:
                return read32Bit(arm9 ? ie9 : ie7, size, offset);
            case 0x214:
                return read32Bit(iflags, size, offset);
            default:
                break;
        }
        if (offset >= 0x100 && offset < 0x110) {
            int index = (arm9 ? 0 : 4) + ((offset - 0x100) / 4);
            int field = (offset - 0x100) & 3;
            if (field == 0) {
                return read16Bit(timerValue[index], size, offset);
            }
            if (field == 2) {
                return read16Bit(timerControl[index] | 0xF800, size, offset);
            }
            return readGeneric(offset, size);
        }
        if (offset >= 0x0B0 && offset < 0x0E0) {
            int channel = (offset - 0x0B0) / 12;
            int index = (arm9 ? 0 : 4) + channel;
            int field = (offset - 0x0B0) % 12;
            if (field == 0) {
                return read32Bit(dmaSource[index], size, offset);
            }
            if (field == 4) {
                return read32Bit(dmaDest[index], size, offset);
            }
            if (field == 8) {
                return read16Bit(dmaCount[index], size, offset);
            }
            if (field == 10) {
                return read16Bit(dmaControl[index], size, offset);
            }
        }
        return readGeneric(offset, size);
    }

    private int readDispStat(int size, int offset) {
        int value = (vblankFlag ? 1 : 0) | ((ppu.vcount == ((io16(0x004) >> 8) & 0xFF)) ? 4 : 0);
        value |= io16(0x004) & 0xFFF8;
        return read16Bit(value, size, offset);
    }

    private int readVCount(int size, int offset) {
        return read16Bit(ppu.vcount, size, offset);
    }

    private int readKeyInput(int size, int offset) {
        return read16Bit(keyInput, size, offset);
    }

    private int readIpcSync(boolean arm9, int size, int offset) {
        // bits 0-3 are the data the *other* core sent, 8-11 are ours
        int own = arm9 ? ipcSync9 : ipcSync7;
        int other = arm9 ? ipcSync7 : ipcSync9;
        int value = (own & 0x2F00) | ((other >> 8) & 0x000F) | (((other >> 14) & 1) << 14);
        return read16Bit(value, size, offset);
    }

    private int readFifoControl(boolean arm9, int size, int offset) {
        // bit 0: our send FIFO is empty, bit 8: the receive FIFO is empty
        int outgoing = arm9 ? fifoIn7Count : fifoIn9Count;
        int incoming = arm9 ? fifoIn9Count : fifoIn7Count;
        int value = (arm9 ? fifoCnt9 : fifoCnt7) & 0xE400;
        value |= outgoing == 0 ? 0x0001 : 0;
        value |= outgoing >= 16 ? 0x0002 : 0;
        value |= incoming == 0 ? 0x0100 : 0;
        value |= incoming >= 16 ? 0x0200 : 0;
        value |= arm9 ? fifoError9 : fifoError7;
        return read16Bit(value, size, offset);
    }

    private static int read16Bit(int value, int size, int offset) {
        if (size == 2 || size == 4) {
            return value & 0xFFFF;
        }
        return (value >> ((offset & 1) * 8)) & 0xFF;
    }

    private static int read32Bit(int value, int size, int offset) {
        if (size == 4) {
            return value;
        }
        if (size == 2) {
            return (value >> ((offset & 2) * 8)) & 0xFFFF;
        }
        return (value >> ((offset & 3) * 8)) & 0xFF;
    }

    private int readGeneric(int offset, int size) {
        int result = 0;
        for (int i = 0; i < size; i++) {
            result |= (io[offset + i] & 0xFF) << (8 * i);
        }
        return result;
    }

    private int io16(int offset) {
        return (io[offset] & 0xFF) | ((io[offset + 1] & 0xFF) << 8);
    }

    private void setIo16(int offset, int value) {
        io[offset] = (byte) value;
        io[offset + 1] = (byte) (value >> 8);
    }

    public void writeIo(boolean arm9, int address, int value, int size) {
        int offset = address & 0x1FFF;
        boolean mainEngine = offset < 0x1000;
        int engineOffset = mainEngine ? offset : offset - 0x1000;
        if (engineOffset < 0x04 || (engineOffset >= 0x08 && engineOffset < 0x70)) {
            NdsPpu.Engine engine = mainEngine ? ppu.main : ppu.sub;
            if (size == 4) {
                engine.write32(engineOffset & ~3, value);
            } else if (size == 2) {
                engine.write16(engineOffset & ~1, value);
            }
            return;
        }
        if (!mainEngine) {
            writeGeneric(offset, value, size);
            return;
        }
        switch (offset) {
            case 0x004:
                setIo16(0x004, (io16(0x004) & 0x0007) | (value & 0xFFF8));
                return;
            case 0x180:
                if (System.getenv("NDS_IPC") != null) {
                    System.err.println("IPCSYNC " + (arm9 ? "9" : "7") + " <- " + Integer.toHexString(value));
                }
                if (arm9) {
                    ipcSync9 = value & 0xFFFF;
                } else {
                    ipcSync7 = value & 0xFFFF;
                }
                setIo16(0x180, value & 0xFFFF);
                if ((value & 0x2000) != 0) {
                    raiseIpcSyncIrq(arm9);
                }
                return;
            case 0x184:
                if (arm9) {
                    fifoCnt9 = value & 0xFFFF;
                } else {
                    fifoCnt7 = value & 0xFFFF;
                }
                setIo16(0x184, value & 0xFFFF);
                if ((value & 0x0008) != 0) {  // clear our send FIFO
                    if (arm9) {
                        fifoIn7Count = 0;
                    } else {
                        fifoIn9Count = 0;
                    }
                }
                if ((value & 0x4000) != 0) {  // acknowledge the error flag
                    if (arm9) {
                        fifoError9 = 0;
                    } else {
                        fifoError7 = 0;
                    }
                }
                return;
            case 0x188:
                writeIpcSend(arm9, value);
                return;
            case 0x208:
                if (arm9) {
                    ime9 = value & 1;
                } else {
                    ime7 = value & 1;
                }
                return;
            case 0x210:
                if (arm9) {
                    ie9 = value;
                } else {
                    ie7 = value;
                }
                return;
            case 0x214:
                iflags &= ~value;
                return;
            case 0x240:
            case 0x242:
            case 0x244:
            case 0x246:
            case 0x248:
                writeGeneric(offset, value, size);
                return;
            case 0x247:
                writeGeneric(offset, value, size);
                return;
            default:
                break;
        }
        if (offset >= 0x100 && offset < 0x110) {
            writeTimer(arm9, offset, value, size);
            return;
        }
        if (offset >= 0x0B0 && offset < 0x0E0) {
            writeDma(arm9, offset, value, size);
            return;
        }
        writeGeneric(offset, value, size);
    }

    private void writeGeneric(int offset, int value, int size) {
        for (int i = 0; i < size; i++) {
            int target = offset + i;
            if (target >= 0 && target < io.length) {
                io[target] = (byte) (value >> (8 * i));
            }
        }
    }

    private void writeTimer(boolean arm9, int offset, int value, int size) {
        int base = arm9 ? 0 : 4;
        int channel = (offset - 0x100) / 4;
        int field = (offset - 0x100) & 3;
        int index = base + channel;
        if (size == 4) {
            timerReload[index] = value & 0xFFFF;
            int control = (value >>> 16) & 0xFFFF;
            applyTimerControl(index, control);
            return;
        }
        if (size == 2 && field == 0) {
            timerReload[index] = value & 0xFFFF;
            return;
        }
        if (size == 2 && field == 2) {
            applyTimerControl(index, value);
        }
    }

    private void applyTimerControl(int index, int control) {
        boolean wasEnabled = (timerControl[index] & 0x80) != 0;
        timerControl[index] = control;
        if ((control & 0x80) != 0 && !wasEnabled) {
            timerValue[index] = timerReload[index] & 0xFFFF;
            timerAccumulator[index] = 0;
        }
    }

    private void writeDma(boolean arm9, int offset, int value, int size) {
        int channel = (offset - 0x0B0) / 12;
        int field = (offset - 0x0B0) % 12;
        int index = (arm9 ? 0 : 4) + channel;
        if (field == 0) {
            if (size == 4) {
                dmaSource[index] = value;
            } else if ((offset & 2) == 0) {
                dmaSource[index] = (dmaSource[index] & 0xFFFF0000) | (value & 0xFFFF);
            } else {
                dmaSource[index] = (dmaSource[index] & 0xFFFF) | (value << 16);
            }
            return;
        }
        if (field == 4) {
            if (size == 4) {
                dmaDest[index] = value;
            } else if ((offset & 2) == 0) {
                dmaDest[index] = (dmaDest[index] & 0xFFFF0000) | (value & 0xFFFF);
            } else {
                dmaDest[index] = (dmaDest[index] & 0xFFFF) | (value << 16);
            }
            return;
        }
        if (field == 8) {
            dmaCount[index] = value & 0xFFFF;
            if (size == 4) {
                applyDmaControl(arm9, channel, index, (value >>> 16) & 0xFFFF);
            }
            return;
        }
        if (field == 10) {
            applyDmaControl(arm9, channel, index, value & 0xFFFF);
        }
    }

    private void applyDmaControl(boolean arm9, int channel, int index, int control) {
        dmaControl[index] = control;
        if ((control & 0x8000) != 0 && ((control >> 13) & 3) == 0) {
            transferDma(arm9 ? 0 : 1, channel);
        }
    }

    // ------------------------------------------------------------------ BIOS calls

    /** Executes a BIOS software interrupt in place of the (absent) BIOS. */
    public void biosCall(NdsCpu cpu, int comment) {
        switch (comment) {
            case 0x00:
                reset();
                break;
            case 0x04:
                cpu.halted = true;
                break;
            case 0x05:
                cpu.r[0] = 1;
                cpu.halted = true;
                break;
            case 0x06:
                cpu.halted = true;
                break;
            case 0x09: {
                int numerator = cpu.r[0];
                int denominator = cpu.r[1];
                if (denominator == 0) {
                    cpu.r[0] = numerator < 0 ? -1 : (numerator == 0 ? 0 : 1);
                    cpu.r[1] = numerator;
                    cpu.r[3] = Math.abs(cpu.r[0]);
                } else {
                    int quotient = numerator / denominator;
                    cpu.r[0] = quotient;
                    cpu.r[1] = numerator % denominator;
                    cpu.r[3] = Math.abs(quotient);
                }
                break;
            }
            case 0x0B:
                cpuSet(cpu, false);
                break;
            case 0x0C:
                cpuSet(cpu, true);
                break;
            case 0x0F:
                bitUnpack(cpu);
                break;
            case 0x10:
            case 0x11:
                lz77(cpu);
                break;
            case 0x13:
            case 0x14:
                runLength(cpu);
                break;
            default:
                break;
        }
    }

    private void cpuSet(NdsCpu cpu, boolean fast) {
        int source = cpu.r[0];
        int dest = cpu.r[1];
        int control = cpu.r[2];
        boolean words = (control & (1 << 26)) != 0;
        boolean fill = (control & (1 << 24)) != 0;
        int count = control & 0x1FFFFF;
        NdsBus bus = cpu == cpu9 ? bus9 : bus7;
        int unit = words ? 4 : 2;
        if (fast) {
            count = (count + 7) & ~7;
            words = true;
            unit = 4;
        }
        for (int i = 0; i < count; i++) {
            if (words) {
                int value = bus.read32(source);
                bus.write32(dest, value);
            } else {
                int value = bus.read16(source);
                bus.write16(dest, value);
            }
            if (!fill) {
                source += unit;
            }
            dest += unit;
        }
        cpu.r[0] = source;
        cpu.r[1] = dest;
    }

    private void bitUnpack(NdsCpu cpu) {
        int source = cpu.r[0];
        int dest = cpu.r[1];
        int info = cpu.r[2];
        boolean noOffset = (info & 0x80000000) != 0;
        int sourceWidth = info & 0x1F;
        int destWidth = (info >> 8) & 0x1F;
        int offset = (info >> 16) & 0x3FFF;
        int length = readBus(cpu, source);
        length |= readBus(cpu, source + 1) << 8;
        source += 4;
        int mask = (1 << destWidth) - 1;
        int bitPosition = 0;
        for (int i = 0; i < length; i++) {
            int bits = 0;
            for (int bit = 0; bit < sourceWidth; bit++) {
                int byteIndex = (bitPosition + bit) >> 3;
                int bitIndex = (bitPosition + bit) & 7;
                bits |= ((readBus(cpu, source + byteIndex) >> bitIndex) & 1) << bit;
            }
            int value = bits | (noOffset ? 0 : offset);
            writeBus16(cpu, dest + i * 2, value & mask);
            bitPosition += sourceWidth;
        }
    }

    private void lz77(NdsCpu cpu) {
        int source = cpu.r[0];
        int dest = cpu.r[1];
        if (readBus(cpu, source) != 0x10) {
            return;
        }
        int length = readBus(cpu, source + 1) | (readBus(cpu, source + 2) << 8)
                | (readBus(cpu, source + 3) << 16);
        int input = source + 4;
        int output = dest;
        int end = dest + length;
        while (output < end) {
            int flags = readBus(cpu, input++);
            for (int bit = 7; bit >= 0 && output < end; bit--) {
                if ((flags & (1 << bit)) == 0) {
                    writeBus8(cpu, output++, readBus(cpu, input++));
                } else {
                    int first = readBus(cpu, input++);
                    int second = readBus(cpu, input++);
                    int count = (first >> 4) + 3;
                    int displacement = ((first & 0xF) << 8) | second;
                    int from = output - displacement - 1;
                    for (int i = 0; i < count && output < end; i++) {
                        writeBus8(cpu, output++, readBus(cpu, from++));
                    }
                }
            }
        }
    }

    private void runLength(NdsCpu cpu) {
        int source = cpu.r[0];
        int dest = cpu.r[1];
        if (readBus(cpu, source) != 0x30) {
            return;
        }
        int length = readBus(cpu, source + 1) | (readBus(cpu, source + 2) << 8)
                | (readBus(cpu, source + 3) << 16);
        int input = source + 4;
        int output = dest;
        int end = dest + length;
        while (output < end) {
            int flag = readBus(cpu, input++);
            if ((flag & 0x80) != 0) {
                int count = (flag & 0x7F) + 3;
                int value = readBus(cpu, input++);
                for (int i = 0; i < count && output < end; i++) {
                    writeBus8(cpu, output++, value);
                }
            } else {
                int count = (flag & 0x7F) + 1;
                for (int i = 0; i < count && output < end; i++) {
                    writeBus8(cpu, output++, readBus(cpu, input++));
                }
            }
        }
    }

    private int readBus(NdsCpu cpu, int address) {
        return (cpu == cpu9 ? bus9 : bus7).read8(address);
    }

    private void writeBus8(NdsCpu cpu, int address, int value) {
        (cpu == cpu9 ? bus9 : bus7).write8(address, value);
    }

    private void writeBus16(NdsCpu cpu, int address, int value) {
        (cpu == cpu9 ? bus9 : bus7).write16(address, value);
    }

    // ------------------------------------------------------------------ save states

    public void saveState(DataOutputStream out) throws IOException {
        cpu9.saveState(out);
        cpu7.saveState(out);
        out.write(mainRam);
        out.write(sharedWram);
        out.write(arm7Wram);
        out.write(io);
        out.writeInt(iflags);
        out.writeInt(ie9);
        out.writeInt(ime9);
        out.writeInt(ie7);
        out.writeInt(ime7);
        out.writeInt(keyInput);
        out.writeInt(ipcSync9);
        out.writeInt(ipcSync7);
        out.writeInt(fifoCnt9);
        out.writeInt(fifoCnt7);
        out.writeInt(fifoError9);
        out.writeInt(fifoError7);
        out.writeInt(lastRecv9);
        out.writeInt(lastRecv7);
        out.writeInt(fifoIn9Count);
        out.writeInt(fifoIn7Count);
        for (int value : fifoIn9) {
            out.writeInt(value);
        }
        for (int value : fifoIn7) {
            out.writeInt(value);
        }
        for (int i = 0; i < 8; i++) {
            out.writeInt(timerValue[i]);
            out.writeInt(timerReload[i]);
            out.writeInt(timerControl[i]);
            out.writeLong(timerAccumulator[i]);
            out.writeInt(dmaSource[i]);
            out.writeInt(dmaDest[i]);
            out.writeInt(dmaCount[i]);
            out.writeInt(dmaControl[i]);
        }
        out.writeLong(frameCount);
        ppu.saveState(out);
    }

    public void loadState(DataInputStream in) throws IOException {
        cpu9.loadState(in);
        cpu7.loadState(in);
        in.readFully(mainRam);
        in.readFully(sharedWram);
        in.readFully(arm7Wram);
        in.readFully(io);
        iflags = in.readInt();
        ie9 = in.readInt();
        ime9 = in.readInt();
        ie7 = in.readInt();
        ime7 = in.readInt();
        keyInput = in.readInt();
        ipcSync9 = in.readInt();
        ipcSync7 = in.readInt();
        fifoCnt9 = in.readInt();
        fifoCnt7 = in.readInt();
        fifoError9 = in.readInt();
        fifoError7 = in.readInt();
        lastRecv9 = in.readInt();
        lastRecv7 = in.readInt();
        fifoIn9Count = in.readInt();
        fifoIn7Count = in.readInt();
        for (int i = 0; i < fifoIn9.length; i++) {
            fifoIn9[i] = in.readInt();
        }
        for (int i = 0; i < fifoIn7.length; i++) {
            fifoIn7[i] = in.readInt();
        }
        for (int i = 0; i < 8; i++) {
            timerValue[i] = in.readInt();
            timerReload[i] = in.readInt();
            timerControl[i] = in.readInt();
            timerAccumulator[i] = in.readLong();
            dmaSource[i] = in.readInt();
            dmaDest[i] = in.readInt();
            dmaCount[i] = in.readInt();
            dmaControl[i] = in.readInt();
        }
        frameCount = in.readLong();
        ppu.loadState(in);
    }
}
