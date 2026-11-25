package emu.gba;

import emu.EmulatorCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Game Boy Advance core: one ARM7TDMI, four background layers, 128 sprites, DMA and timers. */
public final class GbaCore implements EmulatorCore {

    /** Button bits shared by every core in this app (must match the overlay's layout). */
    public static final int BUTTON_A = 0x01;
    public static final int BUTTON_B = 0x02;
    public static final int BUTTON_SELECT = 0x04;
    public static final int BUTTON_START = 0x08;
    public static final int BUTTON_UP = 0x10;
    public static final int BUTTON_DOWN = 0x20;
    public static final int BUTTON_LEFT = 0x40;
    public static final int BUTTON_RIGHT = 0x80;
    public static final int BUTTON_L = 0x100;
    public static final int BUTTON_R = 0x200;

    private static final byte[] MAGIC = {'G', 'B', 'A', '0'};

    private GbaMachine machine = new GbaMachine();

    @Override
    public String name() {
        return "Game Boy Advance";
    }

    @Override
    public String[] extensions() {
        return new String[]{"gba"};
    }

    @Override
    public void loadRom(byte[] rom) throws IOException {
        if (rom == null || rom.length < 0xC0) {
            throw new IOException("not a Game Boy Advance ROM: file is too small");
        }
        machine = new GbaMachine();
        machine.load(rom);
    }

    @Override
    public void reset() {
        machine.restart();
    }

    @Override
    public void stepFrame() {
        machine.stepFrame();
    }

    @Override
    public int width() {
        return GbaMachine.WIDTH;
    }

    @Override
    public int height() {
        return GbaMachine.HEIGHT;
    }

    @Override
    public float aspectRatio() {
        return 3f / 2f;
    }

    @Override
    public int[] framebuffer() {
        return machine.framebuffer();
    }

    @Override
    public int sampleRate() {
        return 44100;
    }

    @Override
    public int readAudio(short[] out, int offset, int max) {
        return 0;   // the PSG/DMA sound block is not modelled: the core is silent
    }

    @Override
    public void setButtons(int port, int mask) {
        if (port == 0) {
            machine.setButtons(mask);
        }
    }

    @Override
    public byte[] saveState() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(512 * 1024);
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(MAGIC);
            machine.saveState(out);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("save state failed", e);
        }
    }

    @Override
    public void loadState(byte[] state) throws IOException {
        if (state == null || state.length < MAGIC.length + 4) {
            throw new IOException("empty save state");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (state[i] != MAGIC[i]) {
                throw new IOException("this save state belongs to another core");
            }
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state, MAGIC.length,
                state.length - MAGIC.length));
        machine.loadState(in);
    }

    /** Exposed for the headless test/dump tools. */
    public GbaMachine machine() {
        return machine;
    }
}
