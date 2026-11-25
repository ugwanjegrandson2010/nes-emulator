package emu.gb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * One Game Boy: CPU, picture unit, audio, cartridge, work RAM, timer, joypad and interrupts.
 * Everything runs off a single 4.19 MHz cycle counter, exactly as on hardware.
 */
public final class GbMachine {

    public static final int INT_VBLANK = 0x01;
    public static final int INT_STAT = 0x02;
    public static final int INT_TIMER = 0x04;
    public static final int INT_SERIAL = 0x08;
    public static final int INT_JOYPAD = 0x10;

    public final GbCartridge cart;
    public final GbPpu ppu;
    public final GbApu apu;
    public final GbCpu cpu;

    final byte[] wram = new byte[0x2000];
    final byte[] hram = new byte[0x7F];
    int ie;
    int ifRegister;

    private int divCounter;
    private int tima, tma, tac;
    private int serialData, serialControl, serialTimer;
    private boolean serialPending;

    private int joypadSelect = 0x30;
    private int buttons;
    private int joypadLines = 0x0F;

    private long frameCount;

    public GbMachine(GbCartridge cart) {
        this.cart = cart;
        this.ppu = new GbPpu(this);
        this.apu = new GbApu();
        this.cpu = new GbCpu(this);
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(wram, (byte) 0);
        java.util.Arrays.fill(hram, (byte) 0);
        ifRegister = 0xE1;
        ie = 0x00;
        divCounter = 0xABCC;
        tima = 0;
        tma = 0;
        tac = 0xF8;
        serialData = 0;
        serialControl = 0x7E;
        serialTimer = 0;
        serialPending = false;
        joypadSelect = 0x30;
        buttons = 0;
        joypadLines = 0x0F;
        frameCount = 0;
        ppu.reset();
        apu.reset();
        cpu.reset();
    }

    public long frameCount() {
        return frameCount;
    }

    /** Runs instructions until the picture unit has produced one full frame. */
    public void stepFrame() {
        ppu.frameReady = false;
        long start = frameCount;
        int guard = 0;
        while (!ppu.frameReady) {
            cpu.step();
            if ((++guard & 0xFFFF) == 0 && frameCount == start) {
                // safety valve: a ROM stuck in a loop with the LCD held off still yields frames
                tick(4);
            }
        }
        frameCount++;
    }

    void requestInterrupt(int bit) {
        ifRegister |= bit & 0x1F;
    }

    void tick(int cycles) {
        timerTick(cycles);
        apu.tick(cycles);
        for (int i = 0; i < cycles; i++) {
            ppu.step();
        }
        if (serialPending) {
            serialTimer -= cycles;
            if (serialTimer <= 0) {
                serialPending = false;
                serialData = 0xFF;
                serialControl &= 0x7F;
                requestInterrupt(INT_SERIAL);
            }
        }
    }

    // ------------------------------------------------------------------ timer

    private void timerTick(int cycles) {
        int oldCounter = divCounter;
        int newCounter = (divCounter + cycles) & 0xFFFF;
        divCounter = newCounter;

        if ((tac & 0x04) == 0) {
            return;
        }
        int bit;
        switch (tac & 0x03) {
            case 0: bit = 9; break;
            case 1: bit = 3; break;
            case 2: bit = 5; break;
            default: bit = 7; break;
        }
        int period = 2 << bit;
        int edges = newCounter / period - oldCounter / period;
        if (newCounter < oldCounter) {
            edges += 0x10000 / period;
        }
        for (int i = 0; i < edges; i++) {
            tima++;
            if (tima > 0xFF) {
                tima = tma & 0xFF;
                requestInterrupt(INT_TIMER);
            }
        }
    }

    // ------------------------------------------------------------------ joypad

    public void setButtons(int port, int mask) {
        // same bit order as the NES core so the Android gamepad needs no changes
        int next = 0;
        if ((mask & 0x01) != 0) next |= 0x10;  // A
        if ((mask & 0x02) != 0) next |= 0x20;  // B
        if ((mask & 0x04) != 0) next |= 0x40;  // Select
        if ((mask & 0x08) != 0) next |= 0x80;  // Start
        if ((mask & 0x10) != 0) next |= 0x04;  // Up
        if ((mask & 0x20) != 0) next |= 0x08;  // Down
        if ((mask & 0x40) != 0) next |= 0x02;  // Left
        if ((mask & 0x80) != 0) next |= 0x01;  // Right

        int lineMask = 0;
        if ((joypadSelect & 0x10) == 0) {
            lineMask |= 0x0F;
        }
        if ((joypadSelect & 0x20) == 0) {
            lineMask |= 0xF0;
        }
        int wasLow = (~joypadLines) & lineMask & 0x0F0F;
        buttons = next;
        joypadLines = readJoypad() & 0x0F;
        int nowLow = (~joypadLines) & 0x0F0F;
        if ((nowLow & ~wasLow & lineMask & 0x0F0F) != 0) {
            requestInterrupt(INT_JOYPAD);
        }
    }

    private int readJoypad() {
        int value = 0xC0 | (joypadSelect & 0x30) | 0x0F;
        if ((joypadSelect & 0x10) == 0) {
            value &= ~(buttons & 0x0F);
        }
        if ((joypadSelect & 0x20) == 0) {
            value &= ~((buttons >> 4) & 0x0F);
        }
        return value;
    }

    // ------------------------------------------------------------------ bus

    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0x8000) {
            return cart.read(address);
        }
        if (address < 0xA000) {
            return ppu.vram[address - 0x8000] & 0xFF;
        }
        if (address < 0xC000) {
            return cart.read(address);
        }
        if (address < 0xFE00) {
            return wram[address - 0xC000] & 0xFF;
        }
        if (address < 0xFEA0) {
            return ppu.oam[address - 0xFE00] & 0xFF;
        }
        if (address < 0xFF00) {
            return 0x00;
        }
        if (address < 0xFF80) {
            return readIo(address);
        }
        if (address < 0xFFFF) {
            return hram[address - 0xFF80] & 0xFF;
        }
        return ie;
    }

    public void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x8000) {
            cart.write(address, value);
            return;
        }
        if (address < 0xA000) {
            ppu.vram[address - 0x8000] = (byte) value;
            return;
        }
        if (address < 0xC000) {
            cart.write(address, value);
            return;
        }
        if (address < 0xFE00) {
            wram[address - 0xC000] = (byte) value;
            return;
        }
        if (address < 0xFEA0) {
            ppu.oam[address - 0xFE00] = (byte) value;
            return;
        }
        if (address < 0xFF00) {
            return;
        }
        if (address < 0xFF80) {
            writeIo(address, value);
            return;
        }
        if (address < 0xFFFF) {
            hram[address - 0xFF80] = (byte) value;
            return;
        }
        ie = value;
    }

    private int readIo(int address) {
        switch (address) {
            case 0xFF00: return readJoypad();
            case 0xFF01: return serialData;
            case 0xFF02: return serialControl;
            case 0xFF04: return (divCounter >> 8) & 0xFF;
            case 0xFF05: return tima;
            case 0xFF06: return tma;
            case 0xFF07: return tac | 0xF8;
            case 0xFF0F: return ifRegister | 0xE0;
            case 0xFF46: return 0xFF;
            case 0xFF4D: return 0xFF;
            case 0xFF50: return 0xFF;
            default:
                if (address >= 0xFF10 && address <= 0xFF3F) {
                    return apu.readRegister(address);
                }
                if (address >= 0xFF40 && address <= 0xFF4B) {
                    return readPpuRegister(address);
                }
                return 0xFF;
        }
    }

    private int readPpuRegister(int address) {
        switch (address) {
            case 0xFF40: return ppu.lcdc;
            case 0xFF41: return ppu.statRegister();
            case 0xFF42: return ppu.scy;
            case 0xFF43: return ppu.scx;
            case 0xFF44: return ppu.ly;
            case 0xFF45: return ppu.lyc;
            case 0xFF47: return ppu.bgp;
            case 0xFF48: return ppu.obp0;
            case 0xFF49: return ppu.obp1;
            case 0xFF4A: return ppu.wy;
            case 0xFF4B: return ppu.wx;
            default: return 0xFF;
        }
    }

    private void writeIo(int address, int value) {
        switch (address) {
            case 0xFF00:
                joypadSelect = value & 0x30;
                joypadLines = readJoypad() & 0x0F;
                return;
            case 0xFF01:
                serialData = value;
                return;
            case 0xFF02:
                serialControl = value;
                if ((value & 0x80) != 0) {
                    serialPending = true;
                    serialTimer = 4096;  // one byte at 8192 Hz
                }
                return;
            case 0xFF04:
                divCounter = 0;
                return;
            case 0xFF05:
                tima = value;
                return;
            case 0xFF06:
                tma = value;
                return;
            case 0xFF07:
                tac = value;
                return;
            case 0xFF0F:
                ifRegister = value & 0x1F;
                return;
            case 0xFF46: {
                int source = value << 8;
                for (int i = 0; i < 160; i++) {
                    ppu.oam[i] = (byte) read(source + i);
                }
                return;
            }
            case 0xFF50:
                return;  // boot ROM disable: there is no boot ROM to disable
            default:
                break;
        }
        if (address >= 0xFF10 && address <= 0xFF3F) {
            apu.writeRegister(address, value);
            return;
        }
        if (address >= 0xFF40 && address <= 0xFF4B) {
            switch (address) {
                case 0xFF40: ppu.lcdc = value; return;
                case 0xFF41: ppu.writeStat(value); return;
                case 0xFF42: ppu.scy = value; return;
                case 0xFF43: ppu.scx = value; return;
                case 0xFF44: return;  // LY is read only
                case 0xFF45: ppu.writeLyc(value); return;
                case 0xFF47: ppu.bgp = value; return;
                case 0xFF48: ppu.obp0 = value; return;
                case 0xFF49: ppu.obp1 = value; return;
                case 0xFF4A: ppu.wy = value; return;
                case 0xFF4B: ppu.wx = value; return;
                default: return;
            }
        }
    }

    // ------------------------------------------------------------------ save states

    void saveState(DataOutputStream out) throws IOException {
        out.writeInt(cpu.a);
        out.writeInt(cpu.f);
        out.writeInt(cpu.b);
        out.writeInt(cpu.c);
        out.writeInt(cpu.d);
        out.writeInt(cpu.e);
        out.writeInt(cpu.h);
        out.writeInt(cpu.l);
        out.writeInt(cpu.sp);
        out.writeInt(cpu.pc);
        out.writeBoolean(cpu.halted);
        out.writeBoolean(cpu.haltBug);
        out.writeBoolean(cpu.ime);
        out.writeBoolean(cpu.imePending);
        out.writeInt(ie);
        out.writeInt(ifRegister);
        out.writeInt(divCounter);
        out.writeInt(tima);
        out.writeInt(tma);
        out.writeInt(tac);
        out.writeInt(serialData);
        out.writeInt(serialControl);
        out.writeInt(serialTimer);
        out.writeBoolean(serialPending);
        out.writeInt(joypadSelect);
        out.writeInt(buttons);
        out.write(wram);
        out.write(hram);
        out.writeLong(frameCount);
        out.writeInt(ppu.lineDot);
        ppu.saveState(out);
        apu.saveState(out);
        cart.saveState(out);
    }

    void loadState(DataInputStream in) throws IOException {
        cpu.a = in.readInt();
        cpu.f = in.readInt();
        cpu.b = in.readInt();
        cpu.c = in.readInt();
        cpu.d = in.readInt();
        cpu.e = in.readInt();
        cpu.h = in.readInt();
        cpu.l = in.readInt();
        cpu.sp = in.readInt();
        cpu.pc = in.readInt();
        cpu.halted = in.readBoolean();
        cpu.haltBug = in.readBoolean();
        cpu.ime = in.readBoolean();
        cpu.imePending = in.readBoolean();
        ie = in.readInt();
        ifRegister = in.readInt();
        divCounter = in.readInt();
        tima = in.readInt();
        tma = in.readInt();
        tac = in.readInt();
        serialData = in.readInt();
        serialControl = in.readInt();
        serialTimer = in.readInt();
        serialPending = in.readBoolean();
        joypadSelect = in.readInt();
        buttons = in.readInt();
        in.readFully(wram);
        in.readFully(hram);
        frameCount = in.readLong();
        ppu.lineDot = in.readInt();
        ppu.loadState(in);
        apu.loadState(in);
        cart.loadState(in);
        ppu.frameReady = false;
    }
}
