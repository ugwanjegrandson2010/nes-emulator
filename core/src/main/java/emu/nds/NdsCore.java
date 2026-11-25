package emu.nds;

import emu.EmulatorCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * {@link EmulatorCore} implementation for the Nintendo DS. Both screens are shown, stacked the way
 * they sit on the console: the top 256x192 half is the main engine, the bottom half the sub engine.
 */
public final class NdsCore implements EmulatorCore {

    private static final int STATE_MAGIC = 0x4E445330;  // "NDS0"
    private static final int STATE_VERSION = 1;

    private NdsMachine machine;
    private NdsCartridge cartridge;
    private int sampleRate = 44100;
    private final int[] stacked = new int[NdsPpu.WIDTH * NdsPpu.HEIGHT * 2];

    @Override
    public String name() {
        return "Nintendo DS";
    }

    @Override
    public String[] extensions() {
        return new String[]{"nds"};
    }

    @Override
    public void loadRom(byte[] rom) throws IOException {
        NdsCartridge parsed = NdsCartridge.load(rom);
        cartridge = parsed;
        machine = new NdsMachine(parsed);
        java.util.Arrays.fill(stacked, 0xFF000000);
    }

    public String cartridgeInfo() {
        return cartridge == null ? "no cartridge" : cartridge.summary();
    }

    public NdsCartridge cartridge() {
        return cartridge;
    }

    public NdsMachine machine() {
        return machine;
    }

    @Override
    public void reset() {
        if (machine != null) {
            machine.reset();
        }
    }

    @Override
    public void stepFrame() {
        if (machine == null) {
            return;
        }
        machine.stepFrame();
        int screenSize = NdsPpu.WIDTH * NdsPpu.HEIGHT;
        for (int i = 0; i < screenSize; i++) {
            int top = machine.ppu.main.pixels[i];
            stacked[i] = (top & 0xFF000000) != 0 ? top : 0xFF000000;
            int bottom = machine.ppu.sub.pixels[i];
            stacked[screenSize + i] = (bottom & 0xFF000000) != 0 ? bottom : 0xFF000000;
        }
    }

    @Override
    public int width() {
        return NdsPpu.WIDTH;
    }

    @Override
    public int height() {
        return NdsPpu.HEIGHT * 2;
    }

    @Override
    public float aspectRatio() {
        // two 4:3 screens stacked
        return NdsPpu.WIDTH / (float) (NdsPpu.HEIGHT * 2);
    }

    @Override
    public int[] framebuffer() {
        return stacked;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int rate) {
        sampleRate = rate;
    }

    @Override
    public int readAudio(short[] out, int offset, int max) {
        // the DS sound hardware is not modelled yet: silence rather than noise
        return 0;
    }

    @Override
    public void setButtons(int port, int mask) {
        if (machine != null) {
            machine.setButtons(mask);
        }
    }

    @Override
    public byte[] saveState() {
        if (machine == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(6 * 1024 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(STATE_MAGIC);
            out.writeInt(STATE_VERSION);
            machine.saveState(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write save state", e);
        }
    }

    @Override
    public void loadState(byte[] state) throws IOException {
        if (machine == null) {
            throw new IOException("no game loaded");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        if (in.readInt() != STATE_MAGIC) {
            throw new IOException("not a save state for this core");
        }
        int version = in.readInt();
        if (version != STATE_VERSION) {
            throw new IOException("unsupported save state version " + version);
        }
        machine.loadState(in);
    }
}
