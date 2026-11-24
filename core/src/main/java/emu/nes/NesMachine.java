package emu.nes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Wires CPU, PPU, APU, cartridge and controllers into a running NES. */
public final class NesMachine implements Cpu6502.Bus {

    public static final int BUTTON_A = 0x01;
    public static final int BUTTON_B = 0x02;
    public static final int BUTTON_SELECT = 0x04;
    public static final int BUTTON_START = 0x08;
    public static final int BUTTON_UP = 0x10;
    public static final int BUTTON_DOWN = 0x20;
    public static final int BUTTON_LEFT = 0x40;
    public static final int BUTTON_RIGHT = 0x80;

    public final Cartridge cart;
    public final Cpu6502 cpu = new Cpu6502();
    public final Ppu ppu;
    public final Apu apu = new Apu();
    public long frames;

    private final byte[] ram = new byte[0x800];
    private final int[] padButtons = new int[2];
    private int padShift;
    private boolean strobe;
    private int dmaCycles;

    public NesMachine(Cartridge cart) {
        this.cart = cart;
        this.ppu = new Ppu(cart.mapper);
        cpu.setBus(this);
        cpu.decimalArithmetic = false; // 2A03 quirk: no decimal mode on the NES
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(ram, (byte) 0);
        cpu.reset();
        ppu.reset();
        apu.reset();
        cart.mapper.reset();
        strobe = false;
        padShift = 0;
        dmaCycles = 0;
        frames = 0;
    }

    public void setButtons(int port, int mask) {
        if (port >= 0 && port < 2) {
            padButtons[port] = mask & 0xFF;
        }
    }

    @Override
    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            return ram[address & 0x7FF] & 0xFF;
        }
        if (address < 0x4000) {
            return ppu.readRegister(address & 0x07);
        }
        if (address == 0x4016) {
            if (strobe) {
                return (padButtons[0] & 0x01) | 0x40;
            }
            int bit = padShift & 0x01;
            padShift = (padShift >> 1) | 0x80;
            return bit | 0x40;
        }
        if (address == 0x4017) {
            return 0x40;
        }
        if (address < 0x4020) {
            return apu.read(address);
        }
        return cart.mapper.cpuRead(address);
    }

    @Override
    public void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            ram[address & 0x7FF] = (byte) value;
            return;
        }
        if (address < 0x4000) {
            ppu.writeRegister(address & 0x07, value);
            return;
        }
        if (address == 0x4014) {
            int base = value << 8;
            for (int i = 0; i < 256; i++) {
                ppu.writeOamDma(read(base + i));
            }
            dmaCycles += 513;
            return;
        }
        if (address == 0x4016) {
            strobe = (value & 0x01) != 0;
            if (strobe) {
                padShift = padButtons[0];
            }
            return;
        }
        if (address < 0x4020) {
            apu.write(address, value);
            return;
        }
        cart.mapper.cpuWrite(address, value);
    }

    /**
     * Executes a single CPU instruction and keeps the PPU/APU in lockstep (3 PPU dots per CPU
     * cycle). Exposed for tests that need instruction granularity.
     */
    public int stepInstruction() {
        int cycles = cpu.step();
        if (dmaCycles > 0) {
            cycles += dmaCycles;
            dmaCycles = 0;
        }
        for (int i = 0, n = cycles * 3; i < n; i++) {
            ppu.step();
        }
        apu.step(cycles);
        if (ppu.consumeNmi()) {
            cpu.nmiPending = true;
        }
        cpu.irqLine = cart.mapper.irq() || apu.frameIrq();
        return cycles;
    }

    /** Runs until the PPU signals one completed frame. */
    public void stepFrame() {
        ppu.frameReady = false;
        int guard = 0;
        while (!ppu.frameReady) {
            stepInstruction();
            if (++guard > 500_000) {
                // A wedged CPU (or a bug) must never hang the UI thread.
                ppu.frameReady = true;
            }
        }
        frames++;
    }

    public void saveState(DataOutputStream out) throws IOException {
        out.write(ram);
        cpu.saveState(out);
        ppu.saveState(out);
        apu.saveState(out);
        cart.mapper.saveState(out);
        out.writeInt(padButtons[0]);
        out.writeInt(padButtons[1]);
        out.writeInt(padShift);
        out.writeBoolean(strobe);
        out.writeLong(frames);
    }

    public void loadState(DataInputStream in) throws IOException {
        in.readFully(ram);
        cpu.loadState(in);
        ppu.loadState(in);
        apu.loadState(in);
        cart.mapper.loadState(in);
        padButtons[0] = in.readInt();
        padButtons[1] = in.readInt();
        padShift = in.readInt();
        strobe = in.readBoolean();
        frames = in.readLong();
    }
}
