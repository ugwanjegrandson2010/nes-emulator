package emu.nes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Base class for cartridge mappers. */
public abstract class Mapper {

    public static final int MIRROR_HORIZONTAL = 0;
    public static final int MIRROR_VERTICAL = 1;
    public static final int MIRROR_SINGLE_LOW = 2;
    public static final int MIRROR_SINGLE_HIGH = 3;
    public static final int MIRROR_FOUR = 4;

    protected final byte[] prg;
    protected final byte[] chr;
    protected final boolean chrRam;
    protected final byte[] prgRam = new byte[0x2000];
    protected int mirroringMode;

    /** Banking registers, persisted by save states. */
    protected int[] state = new int[8];

    protected Mapper(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
        this.prg = prg;
        this.chr = chr == null || chr.length == 0 ? new byte[0x2000] : chr;
        this.chrRam = chrRam || chr == null || chr.length == 0;
        this.mirroringMode = mirroring;
    }

    public abstract int cpuRead(int address);

    public abstract void cpuWrite(int address, int value);

    public int chrRead(int address) {
        return chr[address % chr.length] & 0xFF;
    }

    public void chrWrite(int address, int value) {
        if (chrRam) {
            chr[address % chr.length] = (byte) value;
        }
    }

    public int mirroring() {
        return mirroringMode;
    }

    /** Called once per visible scanline while rendering (mapper IRQ clocking). */
    public void scanline(int line) {
    }

    public boolean irq() {
        return false;
    }

    public void reset() {
    }

    public abstract String name();

    /** Re-applies banking after {@link #loadState}. */
    protected void applyState() {
    }

    protected int readPrg(int offset) {
        int index = offset % prg.length;
        return prg[index] & 0xFF;
    }

    public void saveState(DataOutputStream out) throws IOException {
        for (int v : state) {
            out.writeInt(v);
        }
        out.write(prgRam);
        if (chrRam) {
            out.write(chr);
        }
    }

    public void loadState(DataInputStream in) throws IOException {
        for (int i = 0; i < state.length; i++) {
            state[i] = in.readInt();
        }
        in.readFully(prgRam);
        if (chrRam) {
            in.readFully(chr);
        }
        applyState();
    }
}
